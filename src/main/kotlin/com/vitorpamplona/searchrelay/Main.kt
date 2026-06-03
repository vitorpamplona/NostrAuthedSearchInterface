package com.vitorpamplona.searchrelay

import com.vitorpamplona.searchrelay.backend.BrainstormClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("com.vitorpamplona.searchrelay.Main")

fun main() {
    val config = Config()
    val backend = BrainstormClient(config)
    val sessions = SessionStore(config.sessionDefaultTtlSeconds)
    val relay = RelayServer(config, backend, sessions)

    // Periodically evict expired sessions so disconnected users don't accumulate.
    val sweeper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-sweeper").apply { isDaemon = true }
    }
    sweeper.scheduleWithFixedDelay(
        { runCatching { sessions.sweepExpired() } },
        config.sessionSweepIntervalSeconds,
        config.sessionSweepIntervalSeconds,
        TimeUnit.SECONDS,
    )

    log.info("Starting Nostr search relay on {}:{} -> backend {}", config.host, config.port, config.backendBaseUrl)

    val server = embeddedServer(
        Netty,
        host = config.host,
        port = config.port,
    ) {
        module(config, relay, sessions)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down…")
        sweeper.shutdownNow()
        backend.close()
    })

    server.start(wait = true)
}

fun Application.module(config: Config, relay: RelayServer, sessions: SessionStore) {
    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 60_000
        maxFrameSize = config.maxFrameSize
        masking = false
    }

    routing {
        // The relay endpoint. Nostr clients connect with the `wss://host/` root, but we also
        // accept an explicit path for flexibility behind proxies.
        webSocket("/") { relay.handle(this) }

        // Liveness/metrics for orchestrators and load balancers.
        get("/healthz") {
            call.respondText("ok")
        }
        get("/metrics") {
            call.respondText(
                "relay_live_connections ${relay.liveConnectionCount()}\n" +
                    "relay_active_sessions ${sessions.activeCount()}\n"
            )
        }
    }
}
