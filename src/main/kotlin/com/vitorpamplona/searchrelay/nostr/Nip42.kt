package com.vitorpamplona.searchrelay.nostr

import java.security.SecureRandom

/**
 * NIP-42 client authentication helpers.
 *
 * The relay issues a random challenge on connect. The client replies with a signed
 * kind:22242 event carrying `relay` and `challenge` tags. We verify that event locally;
 * the proven pubkey is then exchanged for a backend JWT (see BrainstormClient).
 */
object Nip42 {
    const val AUTH_KIND = 22242

    /** Max age (seconds) we accept for the AUTH event's created_at, in either direction. */
    private const val MAX_SKEW_SECONDS = 600L

    private val random = SecureRandom()

    /** A fresh, unguessable challenge string. */
    fun newChallenge(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHexString()
    }

    sealed interface Result {
        data class Ok(val pubkey: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Verifies a NIP-42 AUTH event against the expected challenge and this relay's URL.
     *
     * @param expectedChallenge the challenge this connection issued
     * @param relayUrls acceptable relay URLs (normalized); empty disables the relay-tag check
     */
    fun verify(
        event: Event,
        expectedChallenge: String,
        relayUrls: Set<String>,
        now: Long = System.currentTimeMillis() / 1000,
    ): Result {
        if (event.kind != AUTH_KIND) {
            return Result.Rejected("invalid: expected kind $AUTH_KIND auth event")
        }
        if (kotlin.math.abs(now - event.created_at) > MAX_SKEW_SECONDS) {
            return Result.Rejected("invalid: auth event timestamp out of range")
        }
        val challenge = event.firstTag("challenge")
        if (challenge == null || challenge != expectedChallenge) {
            return Result.Rejected("invalid: challenge does not match")
        }
        if (relayUrls.isNotEmpty()) {
            val relayTag = event.firstTag("relay")?.let { normalizeRelayUrl(it) }
            if (relayTag == null || relayTag !in relayUrls) {
                return Result.Rejected("invalid: relay tag does not match")
            }
        }
        if (!event.hasValidSignature()) {
            return Result.Rejected("invalid: bad signature")
        }
        return Result.Ok(event.pubkey)
    }

    /** Lowercases and strips a trailing slash so "wss://x/" and "wss://x" compare equal. */
    fun normalizeRelayUrl(url: String): String =
        url.trim().lowercase().removeSuffix("/")
}
