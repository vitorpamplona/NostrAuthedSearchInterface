package com.vitorpamplona.searchrelay

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {
    private val pk = "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7"

    /** Builds a JWT-shaped string (header.payload.sig) with the given payload JSON. */
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `parses backend expires_date claim`() {
        val token = jwt("""{"nostr_pubkey":"$pk","expires_date":"2099-01-01T00:00:00.000000"}""")
        val expiry = SessionStore.parseJwtExpiry(token)
        assertNotNull(expiry)
        assertTrue(expiry.isAfter(Instant.now()))
    }

    @Test
    fun `parses standard numeric exp claim`() {
        val exp = Instant.now().plus(1, ChronoUnit.HOURS).epochSecond
        val expiry = SessionStore.parseJwtExpiry(jwt("""{"exp":$exp}"""))
        assertEquals(exp, expiry?.epochSecond)
    }

    @Test
    fun `stores and resumes a valid session`() {
        val store = SessionStore(defaultTtlSeconds = 3600)
        val token = jwt("""{"exp":${Instant.now().plusSeconds(3600).epochSecond}}""")
        store.put(pk, token)

        val resumed = store.get(pk)
        assertNotNull(resumed)
        assertEquals(token, resumed.jwt)
        assertEquals(1, store.activeCount())
    }

    @Test
    fun `evicts an expired session on access and on sweep`() {
        val store = SessionStore(defaultTtlSeconds = 3600)
        val token = jwt("""{"exp":${Instant.now().minusSeconds(10).epochSecond}}""")
        store.put(pk, token)

        assertNull(store.get(pk)) // lazily evicted on access
        store.put(pk, token)
        store.sweepExpired()
        assertEquals(0, store.activeCount())
    }

    @Test
    fun `falls back to default ttl when no expiry is present`() {
        val store = SessionStore(defaultTtlSeconds = 3600)
        val session = store.put(pk, jwt("""{"nostr_pubkey":"$pk"}"""))
        assertTrue(session.isValid())
        assertTrue(session.expiresAt.isAfter(Instant.now().plusSeconds(3000)))
    }
}
