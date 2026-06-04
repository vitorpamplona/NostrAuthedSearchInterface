package com.vitorpamplona.searchrelay

import java.time.Instant

/**
 * The authenticated state of a single connection: the backend JWT and its expiry.
 *
 * It exists because Quartz's `EventSource` is handed no session context, so the auth policy
 * (which proves the pubkey and mints the JWT) and the search source (which needs the JWT to
 * search with `ownPubkey=true`) communicate through this small per-connection holder.
 */
class ConnectionAuth {
    @Volatile
    private var token: Token? = null

    /** Records the JWT minted for this connection, capturing its expiry. */
    fun authenticate(jwt: String) {
        token = Token(jwt, Jwt.parseExpiry(jwt))
    }

    /** The still-valid JWT for this connection, or null if absent or expired. */
    fun validJwt(now: Instant = Instant.now()): String? =
        token?.takeIf { it.expiresAt?.isAfter(now) ?: true }?.jwt

    private data class Token(val jwt: String, val expiresAt: Instant?)
}
