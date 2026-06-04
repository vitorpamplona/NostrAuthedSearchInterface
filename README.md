# Nostr Authed Search Interface

A tiny, high-throughput WebSocket **search redirector** that speaks just enough of the
Nostr relay protocol to front the [Brainstorm](https://brainstormserver-staging.nosfabrica.com/docs)
profile-search HTTP API.

It is **not a general relay** — it does not store or relay events. It implements only:

- **[NIP-50](https://github.com/nostr-protocol/nips/blob/master/50.md)** — the `search`
  filter field on `REQ`. Each search is redirected to the backend's
  `GET /search/byText` endpoint and the hits are streamed back as `EVENT` + `EOSE`.
- **[NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md)** — client
  authentication. A connection that authenticates gets a backend JWT, and its subsequent
  searches are run with `ownPubkey=true` (trust scores from the caller's own perspective).

Everything else (`EVENT` publishing, other filters, subscriptions) is intentionally absent.

## Behaviour

```
                        ┌─────────────────────────────────────────────┐
   Nostr client  ──ws──►│  redirector (this service, Kotlin/Ktor)      │
                        │                                              │
   ["REQ",id,{search}]  │   anonymous  ─► GET /search/byText?...&ownPubkey=false
                        │   authed     ─► GET /search/byText?...&ownPubkey=true
                        │                       (access_token: <JWT>)  │
                        │                                              │──http──► Brainstorm API
   ["AUTH",<22242 evt>] │   verify schnorr locally ─► exchange for JWT │
                        └─────────────────────────────────────────────┘
```

1. On connect the relay sends `["AUTH", <challenge>]` (NIP-42).
2. A `["REQ", subId, { "search": "..." }]` is redirected to `GET /search/byText`. Hits come
   back as synthesized **kind-0** metadata events, followed by `["EOSE", subId]`.
   - Not authenticated → `ownPubkey=false`.
   - Authenticated → `ownPubkey=true`, JWT sent in the `access_token` header.
3. To authenticate, the client replies with `["AUTH", <signed kind-22242 event>]` carrying
   the `challenge` (and optionally `relay`) tags. The relay verifies the BIP-340 signature
   locally, then exchanges the event for a JWT and holds it for the connection (see below).

### Per-connection sessions

NIP-42 authentication is bound to the connection. Each socket is greeted with its **own**
fresh challenge, and the AUTH event must be signed against *that* challenge — so:

- The relay holds the JWT server-side **only for the life of the connection**. On disconnect
  it is dropped; a **reconnect gets a new challenge and must AUTH again**. (Resuming auth by
  pubkey across connections would bypass the per-connection challenge, so we deliberately
  don't.)
- The relay tracks the JWT's expiry (standard `exp`, or the backend's `expires_date`). If the
  token expires while the connection is still open, the next search falls back to anonymous
  and the client is told to re-`AUTH`.

> **Synthesized events are unsigned.** The search index stores indexed profile fields, not
> the original signed `kind:0` events, so the events we emit have `sig: ""` and extra
> `relevance` / `quality_score` / `documentid` tags. Treat them as search hits / profile
> cards, not as verifiable relay events.

## Authentication & the backend

NIP-42 proves to *the relay* that the client controls a pubkey. The backend JWT is then
obtained by forwarding the signed event to:

```
POST {BACKEND_BASE_URL}/authChallenge/{pubkey}/verify
{ "signed_event": <the NIP-42 kind-22242 event> }   ->   { "data": { "token": "<JWT>" } }
```

**Backend requirement:** the relay issues the NIP-42 challenge (per spec), so the backend
must validate the event **statelessly — like its NIP-98 interface** — i.e. by signature +
freshness (and, if desired, the `relay` tag), *without* requiring a server-issued challenge
or the `t=brainstorm_login` tag. Until the backend accepts the NIP-42 event, authentication
returns `["OK", id, false, "restricted: ..."]` and clients still get anonymous results.

## Configuration

All via environment variables (defaults target staging and work out of the box):

| Variable | Default | Purpose |
|---|---|---|
| `RELAY_HOST` / `RELAY_PORT` | `0.0.0.0` / `8080` | Bind address |
| `BACKEND_BASE_URL` | `https://brainstormserver-staging.nosfabrica.com` | Search backend |
| `BACKEND_TOKEN_HEADER` | `access_token` | Header carrying the JWT on search calls |
| `BACKEND_ONLY_RANKED` | `true` | Pass-through of the backend `onlyRanked` flag |
| `RELAY_PUBLIC_URLS` | _(empty)_ | Comma-separated URLs accepted in the NIP-42 `relay` tag; empty disables the check |
| `MAX_RESULTS` | `100` | Cap on hits returned per filter |
| `WS_MAX_FRAME_BYTES` | `131072` | Max inbound frame size |
| `BACKEND_MAX_CONNECTIONS` / `_PER_ROUTE` | `2000` / `1000` | Backend HTTP pool sizing |
| `BACKEND_REQUEST_TIMEOUT_MS` | `15000` | Backend call timeout |

## Build, test, run

```bash
./gradlew test          # unit tests (NIP-01 id, BIP-340 verify, NIP-42 logic)
./gradlew run           # run locally on :8080
./gradlew installDist   # produces build/install/.../bin/nostr-authed-search-interface
./gradlew shadowJar     # fat jar in build/libs/

# health & metrics
curl localhost:8080/healthz
curl localhost:8080/metrics      # relay_live_connections <n>
```

Docker:

```bash
docker build -t nostr-search-redirector .
docker run -p 8080:8080 -e BACKEND_BASE_URL=... nostr-search-redirector
```

## Design notes

- **Stack:** Kotlin + Ktor (Netty engine). Connections are coroutine-driven and each search
  is a one-shot non-blocking backend call — there are no long-lived subscriptions to track,
  so idle connections are cheap and the service holds many thousands of sockets.
- **Nostr:** the entire protocol layer is **Quartz**'s relay-server engine — we don't hand-roll
  any of it. Each WebSocket creates a Quartz `RelaySession` that drives the whole protocol
  (NIP-42 challenge on connect, `REQ` → `EVENT…` → `EOSE`, `OK`/`CLOSED`, message/sub limits).
  The app supplies just two small pieces (see `RelayServer.kt`):
  - an `EventSource` (`SearchSource`) whose `events(filters)` parses the filter with
    `SearchQuery` (NIP-50), redirects to the backend, and emits the hits as `Event`s; and
  - a `FullAuthPolicy` subclass (`BrainstormAuthPolicy`) — Quartz does the NIP-42 challenge +
    signature/challenge/relay verification; we override the suspend `authorize` hook to swap the
    verified event for a JWT, and `accept(ReqCmd)` to allow anonymous search.
  So the Ktor handler is essentially `for (frame in incoming) session.receive(frame.text)`.
- Quartz is pulled from **amethyst `main` via JitPack** (`com.github.vitorpamplona.amethyst:quartz`,
  pinned to a commit). It's a Kotlin Multiplatform library whose JVM variant transitively needs
  `androidx.sqlite`, so the build adds Google's Maven repo (`google()`) and the JitPack repo, and
  requires Kotlin 2.3.x (Quartz's metadata is compiled with 2.3.0).
- A shared Ktor CIO HTTP client pools connections to the backend across all sockets.

### Quartz friction (things that would make a relay like this leaner still)

The new engine removed most of the code, but a few rough edges remain for an auth-scoped
redirector:

1. **`EventSource.events(filters)` gets no per-connection/auth context.** A redirector needs the
   connection's authenticated identity/JWT to set `ownPubkey`, but `EventSource` is a single
   shared object. So instead of the one-liner `EventSourceServer.serve()`, we construct a
   `RelaySession` per socket with a per-connection `EventSource` + policy sharing a
   `ConnectionAuth` holder. Passing the session (or its policy/auth set) into `events()` would
   let the simple shared-server path work.
2. **`FullAuthPolicy` requires auth for `REQ`.** For "anonymous allowed, auth optional" relays we
   had to override `accept(ReqCmd)`. A challenge-issuing-but-non-gating policy variant would fit
   search/redirector use cases out of the box.
3. **NIP-77 plumbing is mandatory.** `RelaySession` requires `NegentropySettings` even though a
   redirector stores nothing; an opt-out default would simplify.
4. **`RelaySession` wiring is manual** (CoroutineScope + id + `send`/`onClose`), and the `send`
   sink is a non-suspend `(String) -> Unit` (forcing `trySend`, with backpressure risk). A small
   transport adapter and/or a suspend send hook would remove the boilerplate.
