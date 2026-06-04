package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.server.RelaySession
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSourceBackend
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges Ktor WebSockets to Quartz's relay-server engine.
 *
 * Each connection gets a [RelaySession] that drives the whole Nostr protocol — NIP-42 challenge
 * on connect, REQ → EVENT… → EOSE, OK/CLOSED, and message/subscription limits. The relay-specific
 * behaviour lives in two small collaborators wired in per connection: [SearchSource] (turns a
 * NIP-50 search into a backend call) and [BrainstormAuthPolicy] (NIP-42 + JWT exchange).
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
            ws, // the WebSocket session doubles as the engine's CoroutineScope
            { text -> ws.outgoing.trySend(Frame.Text(text)) }, // the engine's (non-suspend) outbound sink
            {}, // onClose: nothing extra to release
            NO_STORAGE_NEGENTROPY,
            connectionIds.incrementAndGet(),
        )

        liveConnections.incrementAndGet()
        try {
            session.use {
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) it.receive(frame.readText())
                }
            }
        } catch (e: Exception) {
            log.debug("connection {} closed: {}", session.id, e.message)
        } finally {
            liveConnections.decrementAndGet()
        }
    }

    private companion object {
        // This relay stores no events, so NIP-77 set reconciliation is effectively unused;
        // disable its sessions rather than carry meaningful sync limits.
        private val NO_STORAGE_NEGENTROPY = NegentropySettings(0L, 0, 0)
    }
}
