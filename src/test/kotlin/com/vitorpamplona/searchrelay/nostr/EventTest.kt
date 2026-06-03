package com.vitorpamplona.searchrelay.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test vectors generated with @noble/curves (the reference JS implementation) to confirm
 * our hand-rolled NIP-01 serialization, id computation and BIP-340 schnorr verification
 * match real Nostr clients byte-for-byte — including tricky content (quotes, newline, tab,
 * non-ASCII and emoji).
 */
class EventTest {
    // kind:1 with tricky content
    private val e1 = Event(
        id = "5828c2489132647d5e6ac4f8ab84e58881eee75009c9ad83f5b9d4c0f4c9ebe0",
        pubkey = "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7",
        created_at = 1735700000,
        kind = 1,
        tags = listOf(listOf("t", "brainstorm_login"), listOf("challenge", "abc123")),
        content = "Héllo \"world\"\nنبيذ 🍷\t<tag>",
        sig = "e35701c91ccae708b93545da19ccd435a084df64283d136db09366bffde15f60487276b3547398cc514dbe57a3751c413972f90ed288ca4a55fd56212b0860ab",
    )

    // kind:22242 NIP-42 auth event
    private val e2 = Event(
        id = "7bda131eeb4e3702fe8558ee3ce4d68b5926a5757257f604fef924fedd7250e3",
        pubkey = "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7",
        created_at = 1735700000,
        kind = 22242,
        tags = listOf(listOf("relay", "wss://relay.example.com/"), listOf("challenge", "deadbeef")),
        content = "",
        sig = "3c2cc0271a3a9ad24431ddc32e895c2157677cbc34d25f9a686cff4308f14e9b1495fc91e464b975d638b74d6e1b05016879ef2ac337a19877d85ccf7ec73ec9",
    )

    @Test
    fun `computes the NIP-01 id with tricky content`() {
        assertEquals(e1.id, Event.computeId(e1.pubkey, e1.created_at, e1.kind, e1.tags, e1.content))
        assertEquals(e2.id, Event.computeId(e2.pubkey, e2.created_at, e2.kind, e2.tags, e2.content))
    }

    @Test
    fun `verifies a valid schnorr signature`() {
        assertTrue(e1.hasValidSignature())
        assertTrue(e2.hasValidSignature())
    }

    @Test
    fun `rejects a tampered event`() {
        assertFalse(e1.copy(content = e1.content + "!").hasValidSignature())
        assertFalse(e2.copy(sig = "00" + e2.sig.substring(2)).hasValidSignature())
        // a different (but well-formed) pubkey must invalidate both id and signature
        assertFalse(e2.copy(pubkey = "deadbeef" + e2.pubkey.substring(8)).hasValidSignature())
    }

    @Test
    fun `nip42 accepts the matching challenge and relay`() {
        val result = Nip42.verify(
            event = e2,
            expectedChallenge = "deadbeef",
            relayUrls = setOf("wss://relay.example.com"),
            now = 1735700010,
        )
        assertEquals(Nip42.Result.Ok(e2.pubkey), result)
    }

    @Test
    fun `nip42 rejects a mismatched challenge`() {
        val result = Nip42.verify(e2, expectedChallenge = "other", relayUrls = emptySet(), now = 1735700010)
        assertTrue(result is Nip42.Result.Rejected)
    }

    @Test
    fun `nip42 rejects a stale event`() {
        val result = Nip42.verify(e2, expectedChallenge = "deadbeef", relayUrls = emptySet(), now = 1735700000 + 10_000)
        assertTrue(result is Nip42.Result.Rejected)
    }
}
