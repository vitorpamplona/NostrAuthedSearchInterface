package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.server.RelaySession
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSource
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSourceBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip50Search.SearchQuery
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires Quartz's relay-server engine to Ktor. Each WebSocket gets its own [RelaySession],
 * which drives the whole protocol (NIP-42 challenge on connect, REQ → EVENT… → EOSE, OK/CLOSED).
 * We supply only two things:
 *
 *  - a [SearchSource] (Quartz's [EventSource] SPI) that turns a NIP-50 search filter into a
 *    backend HTTP call and streams the hits back as events, and
 *  - a [BrainstormAuthPolicy] (a [FullAuthPolicy] subclass) whose suspend `authorize` hook
 *    exchanges the verified NIP-42 event for a per-connection JWT.
 *
 * The two share a per-connection [ConnectionAuth] holder, because Quartz's `EventSource` is not
 * handed any session/auth context — see README "Quartz friction".
 */
class RelayServer(
    private val config: Config,
    private val backend: BrainstormClient,
) {
    private val log = LoggerFactory.getLogger(RelayServer::class.java)
    private val liveConnections = AtomicLong(0)
    private val connectionIds = AtomicLong(0)
    private val relayUrl = RelayUrlNormalizer.normalize(config.relayUrl)

    fun liveConnectionCount(): Long = liveConnections.get()

    suspend fun handle(ws: DefaultWebSocketSession) {
        val auth = ConnectionAuth()
        val session = RelaySession(
            EventSourceBackend(SearchSource(backend, auth, config.maxResults)),
            BrainstormAuthPolicy(relayUrl, backend, auth),
            ws, // the WebSocket session is the CoroutineScope the engine runs in
            { text -> ws.outgoing.trySend(Frame.Text(text)) }, // non-suspend sink the engine writes to
            { },
            NegentropySettings(50_000L, 10_000, 1),
            connectionIds.incrementAndGet(),
        )
        liveConnections.incrementAndGet()
        try {
            session.use { live ->
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) live.receive(frame.readText())
                }
            }
        } catch (e: Exception) {
            log.debug("connection closed: {}", e.message)
        } finally {
            liveConnections.decrementAndGet()
        }
    }

    /** Per-connection authentication state, populated by the auth policy and read by the source. */
    class ConnectionAuth {
        @Volatile var jwt: String? = null
        @Volatile var expiresAt: Instant? = null

        /** The still-valid JWT for this connection, or null. */
        fun token(now: Instant = Instant.now()): String? =
            jwt?.takeIf { expiresAt?.isAfter(now) ?: true }
    }

    /**
     * Quartz [EventSource]: answers a REQ by redirecting its NIP-50 `search` filter to the
     * backend. Authenticated connections (a live JWT) search with ownPubkey=true. The engine
     * sends EVENT for each emitted event and EOSE when the flow completes.
     */
    private class SearchSource(
        private val backend: BrainstormClient,
        private val auth: RelayServer.ConnectionAuth,
        private val maxResults: Int,
    ) : EventSource {
        override fun events(filters: List<Filter>): Flow<Event> = flow {
            for (filter in filters) {
                val query = filter.search?.let { SearchQuery.parse(it) } ?: continue
                if (query.isTermsEmpty()) continue
                val token = auth.token()
                val limit = filter.limit?.coerceIn(1, maxResults) ?: maxResults
                backend.searchProfiles(query.terms, ownPubkey = token != null, jwt = token)
                    .take(limit)
                    .forEach { emit(it) }
            }
        }
    }

    /**
     * Quartz [FullAuthPolicy] handles the NIP-42 handshake (challenge on connect, signature +
     * challenge + relay-tag verification). We only add the app-specific step: exchange the
     * verified event for a backend JWT and stash it on the connection for authenticated search.
     */
    private class BrainstormAuthPolicy(
        relay: com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl,
        private val backend: BrainstormClient,
        private val auth: RelayServer.ConnectionAuth,
    ) : FullAuthPolicy(relay) {
        // FullAuthPolicy gates REQ behind authentication; we instead allow anonymous search
        // (ownPubkey=false) and treat auth as optional, so REQ is always accepted.
        override fun accept(command: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Accepted(command)

        override suspend fun authorize(pubkey: String, event: RelayAuthEvent) {
            backend.login(event)?.let { token ->
                auth.jwt = token
                auth.expiresAt = Jwt.parseExpiry(token)
            }
        }
    }
}
