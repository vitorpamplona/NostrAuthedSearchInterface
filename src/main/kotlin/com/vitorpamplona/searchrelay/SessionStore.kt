package com.vitorpamplona.searchrelay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * An authenticated session: a pubkey, the backend JWT minted for it, and when that JWT
 * expires. Sessions are immutable — refreshing means replacing the entry in the store.
 */
data class Session(
    val pubkey: String,
    val jwt: String,
    val expiresAt: Instant,
) {
    fun isValid(now: Instant = Instant.now()): Boolean = now.isBefore(expiresAt)
}

/**
 * Relay-owned store of authenticated sessions, keyed by pubkey.
 *
 * Holding the session in the relay (rather than only on the WebSocket) means a client that
 * drops and reconnects — or opens a second connection — can resume without the relay having
 * to call the backend again: a still-valid JWT is reused. The pubkey is always re-proven
 * via NIP-42 on every connection, so a cached session can never be claimed by someone else.
 */
class SessionStore(
    private val defaultTtlSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(SessionStore::class.java)
    private val sessions = ConcurrentHashMap<String, Session>()

    /** Returns a still-valid session for the pubkey, evicting and returning null if expired. */
    fun get(pubkey: String): Session? {
        val existing = sessions[pubkey] ?: return null
        if (!existing.isValid()) {
            sessions.remove(pubkey, existing)
            return null
        }
        return existing
    }

    /** Stores (or replaces) the session for a pubkey, deriving expiry from the JWT. */
    fun put(pubkey: String, jwt: String): Session {
        val expiresAt = parseJwtExpiry(jwt) ?: Instant.now().plusSeconds(defaultTtlSeconds)
        val session = Session(pubkey, jwt, expiresAt)
        sessions[pubkey] = session
        return session
    }

    fun remove(pubkey: String) {
        sessions.remove(pubkey)
    }

    fun activeCount(): Int = sessions.size

    /** Drops every expired session; called periodically so disconnected users don't pile up. */
    fun sweepExpired() {
        val now = Instant.now()
        val before = sessions.size
        sessions.values.removeIf { !it.isValid(now) }
        val removed = before - sessions.size
        if (removed > 0) log.debug("swept {} expired sessions ({} remain)", removed, sessions.size)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Best-effort expiry extraction from a JWT payload. Honours the standard numeric `exp`
         * claim (seconds since epoch) and the backend's `expires_date` (ISO local date-time,
         * treated as UTC). Returns null if neither is present or parseable.
         */
        fun parseJwtExpiry(jwt: String): Instant? {
            return try {
                val parts = jwt.split('.')
                if (parts.size < 2) return null
                val payloadJson = String(Base64.getUrlDecoder().decode(pad(parts[1])))
                val obj = json.parseToJsonElement(payloadJson).jsonObject

                obj["exp"]?.jsonPrimitive?.longOrNull?.let { return Instant.ofEpochSecond(it) }

                obj["expires_date"]?.jsonPrimitive?.contentOrNull?.let {
                    return LocalDateTime.parse(it).toInstant(ZoneOffset.UTC)
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        /** Restores base64url padding that JWTs strip. */
        private fun pad(s: String): String = when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
    }
}
