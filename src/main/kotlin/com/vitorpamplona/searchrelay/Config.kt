package com.vitorpamplona.searchrelay

/**
 * Runtime configuration, sourced from environment variables with sensible defaults so the
 * relay runs out of the box against the staging backend.
 */
data class Config(
    val host: String = env("RELAY_HOST", "0.0.0.0"),
    val port: Int = env("RELAY_PORT", "8080").toInt(),

    val backendBaseUrl: String = env(
        "BACKEND_BASE_URL",
        "https://brainstormserver-staging.nosfabrica.com",
    ).trimEnd('/'),

    /** Header carrying the JWT on backend search calls (the backend uses `access_token`). */
    val backendTokenHeader: String = env("BACKEND_TOKEN_HEADER", "access_token"),

    /** Pass-through of the backend's onlyRanked search flag. */
    val onlyRanked: Boolean = env("BACKEND_ONLY_RANKED", "true").toBoolean(),

    val backendRequestTimeoutMs: Long = env("BACKEND_REQUEST_TIMEOUT_MS", "15000").toLong(),
    val backendMaxConnectionsCount: Int = env("BACKEND_MAX_CONNECTIONS", "2000").toInt(),
    val backendMaxConnectionsPerRoute: Int = env("BACKEND_MAX_CONNECTIONS_PER_ROUTE", "1000").toInt(),

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
    /** Backend endpoint that validates the NIP-42 event and returns a JWT. */
    fun backendLoginUrl(pubkey: String): String = "$backendBaseUrl/authChallenge/$pubkey/verify"

    companion object {
        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
