package com.vitorpamplona.searchrelay

import com.vitorpamplona.searchrelay.backend.VespaClient
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

private val log = LoggerFactory.getLogger("com.vitorpamplona.searchrelay.Main")

fun main() {
    val config = Config()
    val vespa = VespaClient(config)
    val relay = RelayServer(config, vespa)

    log.info("Starting Nostr search relay on {}:{} -> vespa {}", config.host, config.port, config.vespaUrl)

    val server = embeddedServer(
        Netty,
        host = config.host,
        port = config.port,
    ) {
        module(config, relay)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down…")
        relay.close()
        vespa.close()
    })

    server.start(wait = true)
}

fun Application.module(config: Config, relay: RelayServer) {
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
            call.respondText("relay_live_connections ${relay.liveConnectionCount()}\n")
        }
    }
}
