package com.vitorpamplona.searchrelay

/**
 * Runtime configuration, sourced from environment variables with sensible defaults.
 */
data class Config(
    val host: String = env("RELAY_HOST", "0.0.0.0"),
    val port: Int = env("RELAY_PORT", "8080").toInt(),

    /** Base URL of the Vespa container the relay queries directly (e.g. http://vespa:8080). */
    val vespaUrl: String = env("VESPA_URL", "http://localhost:8081").trimEnd('/'),

    /**
     * Observer pubkey (hex) used to rank results for anonymous connections. Defaults to the
     * brainstorm server's hardcoded periodic-graperank perspective.
     */
    val defaultObserver: String = env(
        "DEFAULT_OBSERVER_PUBKEY",
        "be7bf5de068c1d842ed34a7c270507ec940f5ea51671cfd062a95e9d09420d0a",
    ),

    /** If true, drop results with a zero quality_score from the observer's perspective. */
    val onlyRanked: Boolean = env("ONLY_RANKED", "true").toBoolean(),

    val requestTimeoutMs: Long = env("VESPA_REQUEST_TIMEOUT_MS", "15000").toLong(),
    val maxConnectionsCount: Int = env("VESPA_MAX_CONNECTIONS", "2000").toInt(),
    val maxConnectionsPerRoute: Int = env("VESPA_MAX_CONNECTIONS_PER_ROUTE", "1000").toInt(),

    /**
     * This relay's public URL, enforced as the NIP-42 `relay` tag by Quartz's FullAuthPolicy.
     * Clients must sign their AUTH event against this URL.
     */
    val relayUrl: String = env("RELAY_URL", "wss://nostr-search.relay/"),

    /** Max inbound WebSocket frame size in bytes. */
    val maxFrameSize: Long = env("WS_MAX_FRAME_BYTES", "131072").toLong(),

    /** Maximum number of search hits returned per filter, regardless of client `limit`. */
    val maxResults: Int = env("MAX_RESULTS", "100").toInt(),
) {
    companion object {
        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
