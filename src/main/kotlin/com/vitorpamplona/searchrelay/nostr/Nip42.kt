package com.vitorpamplona.searchrelay.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import java.security.SecureRandom
import java.util.HexFormat

/**
 * NIP-42 client authentication helpers, built on Quartz's event model and crypto.
 *
 * The relay issues a random challenge on connect. The client replies with a signed
 * kind:22242 event carrying `relay` and `challenge` tags. We verify that event with Quartz;
 * the proven pubkey is then exchanged for a backend JWT (see BrainstormClient).
 */
object Nip42 {
    /** Max age (seconds) we accept for the AUTH event's created_at, in either direction. */
    private const val MAX_SKEW_SECONDS = 600L

    private val random = SecureRandom()
    private val hex = HexFormat.of()

    /** A fresh, unguessable challenge string. */
    fun newChallenge(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return hex.formatHex(bytes)
    }

    sealed interface Result {
        data class Ok(val pubkey: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Verifies a NIP-42 AUTH event against the expected challenge and this relay's URL.
     * Signature/id verification is delegated to Quartz; challenge, freshness and relay-tag
     * checks are this relay's policy.
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
        if (event.kind != RelayAuthEvent.KIND) {
            return Result.Rejected("invalid: expected kind ${RelayAuthEvent.KIND} auth event")
        }
        if (kotlin.math.abs(now - event.createdAt) > MAX_SKEW_SECONDS) {
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
        if (!event.verify()) {
            return Result.Rejected("invalid: bad signature")
        }
        return Result.Ok(event.pubKey)
    }

    /** Lowercases and strips a trailing slash so "wss://x/" and "wss://x" compare equal. */
    fun normalizeRelayUrl(url: String): String =
        url.trim().lowercase().removeSuffix("/")

    /** First value of the first tag whose name matches, or null. */
    private fun Event.firstTag(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.getOrNull(1)
}
