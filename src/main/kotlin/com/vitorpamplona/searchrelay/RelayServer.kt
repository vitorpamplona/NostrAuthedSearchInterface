package com.vitorpamplona.searchrelay

import com.vitorpamplona.searchrelay.backend.BrainstormClient
import com.vitorpamplona.searchrelay.nostr.Nip42
import com.vitorpamplona.searchrelay.nostr.Protocol
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-connection authentication state and the message routing logic.
 *
 * The relay is a stateless search redirector: a REQ with a NIP-50 `search` field is turned
 * into a backend HTTP call and the hits are streamed back as EVENT + EOSE. There are no
 * long-lived subscriptions to track, which keeps each idle connection cheap.
 *
 * NIP-42 authentication is bound to the connection: every socket is greeted with its own
 * fresh challenge and must AUTH against it. The backend JWT is held by the relay only for
 * the life of that connection — a reconnect gets a new challenge and must AUTH again.
 */
class RelayServer(
    private val config: Config,
    private val backend: BrainstormClient,
) {
    private val log = LoggerFactory.getLogger(RelayServer::class.java)
    private val liveConnections = AtomicLong(0)

    fun liveConnectionCount(): Long = liveConnections.get()

    /** A single WebSocket connection and its (per-connection) authenticated session. */
    private inner class Connection(val session: DefaultWebSocketSession) {
        val challenge: String = Nip42.newChallenge()

        // The pubkey proven on this socket and the JWT minted for it, held by the relay for
        // this connection only. Cleared when the JWT expires; gone entirely on disconnect.
        @Volatile var pubkey: String? = null
        @Volatile var jwt: String? = null
        @Volatile var jwtExpiresAt: Instant? = null

        // Serializes writes so concurrent coroutines never interleave frames.
        private val sendLock = Mutex()

        /** True while this connection holds a still-valid JWT. */
        fun authenticated(now: Instant = Instant.now()): Boolean =
            jwt != null && (jwtExpiresAt?.isAfter(now) ?: true)

        suspend fun send(text: String) = sendLock.withLock { session.send(text) }
    }

    suspend fun handle(session: DefaultWebSocketSession) {
        val conn = Connection(session)
        liveConnections.incrementAndGet()
        try {
            // NIP-42: greet the client with an auth challenge straight away. Clients that
            // don't care about authenticated results may simply ignore it.
            conn.send(Protocol.auth(conn.challenge))

            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text.length.toLong() > config.maxFrameSize) {
                    conn.send(Protocol.notice("message too large"))
                    continue
                }
                route(conn, text)
            }
        } catch (e: Exception) {
            log.debug("connection closed: {}", e.message)
        } finally {
            liveConnections.decrementAndGet()
        }
    }

    private suspend fun route(conn: Connection, text: String) {
        when (val msg = Protocol.parse(text)) {
            is Protocol.Inbound.Req -> handleReq(conn, msg)
            is Protocol.Inbound.Auth -> handleAuth(conn, msg)
            is Protocol.Inbound.Close -> { /* no long-lived subscriptions to cancel */ }
            is Protocol.Inbound.Unsupported ->
                conn.send(Protocol.notice("unsupported message type: ${msg.verb}"))
            is Protocol.Inbound.Malformed ->
                conn.send(Protocol.notice("invalid message: ${msg.error}"))
        }
    }

    private suspend fun handleReq(conn: Connection, req: Protocol.Inbound.Req) {
        // If the JWT minted earlier on this connection has since expired, drop it and tell the
        // client to AUTH again; the search then proceeds anonymously.
        if (conn.pubkey != null && !conn.authenticated()) {
            conn.pubkey = null
            conn.jwt = null
            conn.jwtExpiresAt = null
            conn.send(Protocol.notice("auth-required: session expired, please AUTH again"))
        }
        val authed = conn.authenticated()

        // NIP-50: we only serve filters that carry a `search` term. Other filters EOSE empty.
        for (filter in req.filters) {
            val term = filter.search?.trim()
            if (term.isNullOrEmpty()) continue
            val limit = filter.limit?.coerceIn(1, config.maxResults) ?: config.maxResults

            val hits = try {
                backend.searchProfiles(
                    text = term,
                    ownPubkey = authed,
                    jwt = conn.jwt,
                )
            } catch (e: Exception) {
                log.warn("search failed for '{}': {}", term, e.message)
                conn.send(Protocol.closed(req.subscriptionId, "error: search backend unavailable"))
                return
            }

            for (event in hits.take(limit)) {
                conn.send(Protocol.event(req.subscriptionId, event))
            }
        }
        conn.send(Protocol.eose(req.subscriptionId))
    }

    private suspend fun handleAuth(conn: Connection, auth: Protocol.Inbound.Auth) {
        val event = auth.event
        when (val result = Nip42.verify(event, conn.challenge, config.relayUrls)) {
            is Nip42.Result.Rejected -> {
                conn.send(Protocol.ok(event.id, false, result.reason))
            }
            is Nip42.Result.Ok -> {
                // Ownership of the pubkey is proven against this connection's challenge.
                // Exchange the event for a JWT (validated NIP-98-style) and hold it for the
                // life of this connection.
                val token = backend.login(event)
                if (token == null) {
                    conn.send(Protocol.ok(event.id, false, "restricted: backend declined authentication"))
                } else {
                    conn.pubkey = result.pubkey
                    conn.jwt = token
                    conn.jwtExpiresAt = Jwt.parseExpiry(token)
                    conn.send(Protocol.ok(event.id, true, ""))
                    log.debug("authenticated {}", result.pubkey)
                }
            }
        }
    }
}
