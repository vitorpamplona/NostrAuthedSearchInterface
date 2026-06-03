package com.vitorpamplona.searchrelay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/** Minimal JWT inspection — just enough to know when a connection's token expires. */
object Jwt {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Best-effort expiry extraction from a JWT payload. Honours the standard numeric `exp`
     * claim (seconds since epoch) and the backend's `expires_date` (ISO local date-time,
     * treated as UTC). Returns null if neither is present or parseable, in which case the
     * caller treats the token as non-expiring for the connection's lifetime.
     */
    fun parseExpiry(jwt: String): Instant? {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payload = String(Base64.getUrlDecoder().decode(pad(parts[1])))
            val obj = json.parseToJsonElement(payload).jsonObject

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
