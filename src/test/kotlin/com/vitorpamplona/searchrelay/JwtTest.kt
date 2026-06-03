package com.vitorpamplona.searchrelay

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTest {
    /** Builds a JWT-shaped string (header.payload.sig) with the given payload JSON. */
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `parses the backend expires_date claim as UTC`() {
        val expiry = Jwt.parseExpiry(jwt("""{"nostr_pubkey":"ab","expires_date":"2099-01-01T00:00:00.000000"}"""))
        assertNotNull(expiry)
        assertTrue(expiry.isAfter(Instant.now()))
    }

    @Test
    fun `parses the standard numeric exp claim`() {
        val exp = Instant.now().plus(1, ChronoUnit.HOURS).epochSecond
        assertEquals(exp, Jwt.parseExpiry(jwt("""{"exp":$exp}"""))?.epochSecond)
    }

    @Test
    fun `returns null when no expiry claim is present`() {
        assertNull(Jwt.parseExpiry(jwt("""{"nostr_pubkey":"ab"}""")))
        assertNull(Jwt.parseExpiry("not-a-jwt"))
    }
}
