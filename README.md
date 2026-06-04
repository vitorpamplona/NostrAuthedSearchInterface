# Nostr Authed Search Interface

A tiny, high-throughput WebSocket **search relay** that speaks just enough of the Nostr
protocol to query a [Brainstorm](https://github.com/NosFabrica/brainstorm_server) **Vespa**
index for profiles — ranked from the caller's own trust perspective.

It is **not a general relay** — it does not store or relay events. It implements only:

- **[NIP-50](https://github.com/nostr-protocol/nips/blob/master/50.md)** — the `search`
  filter field on `REQ`. Each search becomes a Vespa query and the hits stream back as
  `EVENT` + `EOSE`.
- **[NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md)** — client
  authentication. The authenticated pubkey is used as the **observer perspective** for Vespa's
  trust scores. **No JWT, no token exchange** — the NIP-42-verified pubkey *is* the credential.

Everything else (`EVENT` publishing, other filters, subscriptions) is intentionally absent.

## Behaviour

```
                        ┌──────────────────────────────────────────────┐
   Nostr client  ──ws──►│  search relay (this service, Kotlin/Ktor)     │
                        │                                               │
   ["REQ",id,{search}]  │  anonymous ─► query(user_q)={<defaultObs>:1.0}
                        │  authed    ─► query(user_q)={<auth'd pubkey>:1.0}
                        │                                               │──http──► Vespa /search/
   ["AUTH",<22242 evt>] │  Quartz verifies NIP-42 → pubkey is observer  │
                        └──────────────────────────────────────────────┘
```

1. On connect the relay sends `["AUTH", <challenge>]` (NIP-42).
2. A `["REQ", subId, { "search": "..." }]` becomes a Vespa `/search/` query (rank profile
   `name_and_quality_score_only`). Hits come back as synthesized **kind-0** metadata events,
   followed by `["EOSE", subId]`.
3. The **observer** — Vespa's `ranking.features.query(user_q) = {<pubkey>:1.0}`, which selects
   that observer's cell in each doc's `quality_scores` tensor — is:
   - the **default observer** for anonymous connections, or
   - **every pubkey the connection authenticated** via NIP-42. `user_q` is a weighted set, so
     multiple observers (`{p1:1.0,p2:1.0}`) rank by the *combined* trust scores — and NIP-42
     lets one connection AUTH several pubkeys.
4. To authenticate, the client replies with `["AUTH", <signed kind-22242 event>]` carrying the
   `challenge` and `relay` tags. Quartz verifies the BIP-340 signature, challenge and relay tag.

### Why there's no JWT

The observer pubkey is **not an auth credential** to Vespa — it's just a ranking feature.
NIP-42 already proves the client controls the pubkey, so the relay passes that hex pubkey
straight into the Vespa query as `user_q`. This replicates `app/core/vespa.py::search` from the
brainstorm server (which derived the same observer from a JWT); we skip the
`/authChallenge` → `/verify` → JWT dance entirely. A query that *is itself* a hex pubkey is
resolved to a direct `/document/v1` lookup, mirroring `/search/byText`.

NIP-42 is per-connection: each socket gets a fresh challenge and must AUTH against it; the
proven pubkey lives only for that connection (a reconnect must AUTH again).

> **Synthesized events are unsigned.** Vespa stores indexed profile fields, not the original
> signed `kind:0` events, so the events we emit have `sig: ""` and extra
> `relevance` / `quality_score` / `documentid` tags. Treat them as search hits / profile cards,
> not as verifiable relay events.

## Configuration

All via environment variables (defaults target staging and work out of the box):

| Variable | Default | Purpose |
|---|---|---|
| `RELAY_HOST` / `RELAY_PORT` | `0.0.0.0` / `8080` | Bind address |
| `VESPA_URL` | `http://localhost:8081` | Base URL of the Vespa container to query |
| `DEFAULT_OBSERVER_PUBKEY` | `be7bf5…420d0a` | Observer (hex) used to rank results for anonymous connections |
| `ONLY_RANKED` | `true` | Drop results with a zero quality_score from the observer's perspective |
| `RELAY_URL` | `wss://nostr-search.relay/` | This relay's URL, enforced as the NIP-42 `relay` tag |
| `MAX_RESULTS` | `100` | Cap on hits returned per filter |
| `WS_MAX_FRAME_BYTES` | `131072` | Max inbound frame size |
| `VESPA_MAX_CONNECTIONS` / `_PER_ROUTE` | `2000` / `1000` | Vespa HTTP pool sizing |
| `VESPA_REQUEST_TIMEOUT_MS` | `15000` | Vespa call timeout |

> The relay needs network reachability to the Vespa container (same as the brainstorm server).

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
docker build -t nostr-search-relay .
docker run -p 8080:8080 -e VESPA_URL=http://vespa:8081 -e RELAY_URL=wss://your.relay/ nostr-search-relay
```

## Design notes

- **Stack:** Kotlin + Ktor (Netty engine). Connections are coroutine-driven and each search
  is a one-shot non-blocking backend call — there are no long-lived subscriptions to track,
  so idle connections are cheap and the service holds many thousands of sockets.
- **Nostr:** the entire protocol layer is **Quartz**'s relay-server engine — we don't hand-roll
  any of it. A single `EventSourceServer` (shared `SearchSource` + a per-connection policy
  factory) drives the whole protocol via `serve()`: NIP-42 challenge on connect, `REQ` →
  `EVENT…` → `EOSE`, `OK`/`CLOSED`, and message/subscription limits. The app supplies just two
  small pieces (see `RelayServer.kt`):
  - an `EventSource` (`SearchSource`) whose `events(ctx, filters)` parses the filter with
    `SearchQuery` (NIP-50) and queries Vespa. It reads the observer pubkey from
    `ctx.authenticatedUsers` (the NIP-42 pubkey) or falls back to the default observer; and
  - a `FullAuthPolicy` subclass (`SearchAuthPolicy`) — Quartz does the NIP-42 handshake and
    tracks the authenticated pubkey; we override `accept(ReqCmd)` to allow anonymous search.
    It is composed as **`VerifyAuthOnlyPolicy + SearchAuthPolicy`**: `FullAuthPolicy` checks the
    challenge/relay/freshness but **not the signature**, so the verify policy must be stacked in
    to reject a forged AUTH before its pubkey can become the observer (see `AuthSignatureTest`).
  So the whole Ktor handler is `server.serve(send) { s -> for (f in incoming) s.receive(f.text) }`.
- **Vespa query** (`VespaQuery` / `VespaClient`) is a faithful port of the brainstorm server's
  `app/core/vespa.py` — same YQL, rank profile and `user_q` observer feature — verified
  byte-for-byte against the Python in `VespaQueryTest`.
- Quartz is pulled from **amethyst `main` via JitPack** (`com.github.vitorpamplona.amethyst:quartz`,
  pinned to a commit). It's a Kotlin Multiplatform library whose JVM variant transitively needs
  `androidx.sqlite`, so the build adds Google's Maven repo (`google()`) and the JitPack repo, and
  requires Kotlin 2.3.x (Quartz's metadata is compiled with 2.3.0).
- A shared Ktor CIO HTTP client pools connections to Vespa across all sockets.

### Quartz friction

The headline gap — **`EventSource` had no per-connection/auth context** — is now **fixed**
upstream: `events(ctx, filters)` receives a `RequestContext` exposing the connection's
`policy`/`authenticatedUsers`, so the per-connection auth holder and the manual `RelaySession`
wiring are gone, and this uses the plain `EventSourceServer.serve()` path.

Remaining nits: `FullAuthPolicy` gates `REQ` (we override `accept(ReqCmd)` to allow anonymous
search); `NegentropySettings` is required even though this relay stores nothing; and — the sharp
one — **`FullAuthPolicy` does not verify the AUTH signature on its own**. You must compose it with
`VerifyAuthOnlyPolicy` (`+`), or any client can claim any pubkey. That's easy to miss; a verifying
`FullAuthPolicy` by default (or a loud warning) would be safer.
