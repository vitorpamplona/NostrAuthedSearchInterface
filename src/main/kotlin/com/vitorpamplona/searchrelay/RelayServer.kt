package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.server.EventSourceServer
import com.vitorpamplona.quartz.nip01Core.relay.server.RelayServerListener
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers

/**
 * Bridges Ktor WebSockets to Quartz's relay-server engine.
 *
 * A single [EventSourceServer] holds a shared [SearchSource] and a per-connection policy factory.
 * Quartz's `serve` builds a session per connection that drives the whole protocol — NIP-42
 * challenge on connect, REQ → EVENT… → EOSE, OK/CLOSED, limits — so the Ktor handler only has to
 * pump frames in. The per-connection JWT lives on the connection's [BrainstormAuthPolicy] and is
 * read back by the source through `RequestContext.policy`.
 */
class RelayServer(
    config: Config,
    backend: BrainstormClient,
) : AutoCloseable {
    private val relayUrl = RelayUrlNormalizer.normalize(config.relayUrl)

    private val server = EventSourceServer(
        SearchSource(backend, config.maxResults),
        { BrainstormAuthPolicy(relayUrl, backend) }, // a fresh policy per connection
        Dispatchers.Default,
        NO_STORAGE_NEGENTROPY,
        object : RelayServerListener {},
        RelayLimits(),
    )

    fun liveConnectionCount(): Long = server.activeConnections

    suspend fun handle(ws: DefaultWebSocketSession) {
        server.serve({ text -> ws.outgoing.trySend(Frame.Text(text)) }) { session ->
            for (frame in ws.incoming) {
                if (frame is Frame.Text) session.receive(frame.readText())
            }
        }
    }

    override fun close() = server.close()

    private companion object {
        // This relay stores no events, so NIP-77 set reconciliation is unused — disable it.
        private val NO_STORAGE_NEGENTROPY = NegentropySettings(0L, 0, 0)
    }
}
