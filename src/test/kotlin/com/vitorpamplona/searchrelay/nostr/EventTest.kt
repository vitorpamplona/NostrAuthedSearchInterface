package com.vitorpamplona.searchrelay.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test vectors generated with @noble/curves (the reference JS implementation), exercised
 * through Quartz's Event model + verification — including tricky content (quotes, newline,
 * tab, non-ASCII and emoji) and the NIP-42 challenge/relay/freshness policy.
 */
class EventTest {
    // kind:1 with tricky content
    private val e1 = Event(
        "5828c2489132647d5e6ac4f8ab84e58881eee75009c9ad83f5b9d4c0f4c9ebe0",
        "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7",
        1735700000,
        1,
        arrayOf(arrayOf("t", "brainstorm_login"), arrayOf("challenge", "abc123")),
        "Héllo \"world\"\nنبيذ 🍷\t<tag>",
        "e35701c91ccae708b93545da19ccd435a084df64283d136db09366bffde15f60487276b3547398cc514dbe57a3751c413972f90ed288ca4a55fd56212b0860ab",
    )

    // kind:22242 NIP-42 auth event
    private val e2 = Event(
        "7bda131eeb4e3702fe8558ee3ce4d68b5926a5757257f604fef924fedd7250e3",
        "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7",
        1735700000,
        22242,
        arrayOf(arrayOf("relay", "wss://relay.example.com/"), arrayOf("challenge", "deadbeef")),
        "",
        "3c2cc0271a3a9ad24431ddc32e895c2157677cbc34d25f9a686cff4308f14e9b1495fc91e464b975d638b74d6e1b05016879ef2ac337a19877d85ccf7ec73ec9",
    )

    @Test
    fun `Quartz computes the NIP-01 id with tricky content`() {
        assertEquals(e1.id, EventHasher.hashId(e1.pubKey, e1.createdAt, e1.kind, e1.tags, e1.content))
        assertEquals(e2.id, EventHasher.hashId(e2.pubKey, e2.createdAt, e2.kind, e2.tags, e2.content))
    }

    @Test
    fun `Quartz verifies a valid schnorr signature`() {
        assertTrue(e1.verify())
        assertTrue(e2.verify())
    }

    @Test
    fun `Quartz rejects a tampered event`() {
        val tamperedContent = Event(e1.id, e1.pubKey, e1.createdAt, e1.kind, e1.tags, e1.content + "!", e1.sig)
        assertFalse(tamperedContent.verify())

        val tamperedSig = Event(e2.id, e2.pubKey, e2.createdAt, e2.kind, e2.tags, e2.content, "00" + e2.sig.substring(2))
        assertFalse(tamperedSig.verify())

        val tamperedPubkey = Event(e2.id, "deadbeef" + e2.pubKey.substring(8), e2.createdAt, e2.kind, e2.tags, e2.content, e2.sig)
        assertFalse(tamperedPubkey.verify())
    }

    @Test
    fun `nip42 accepts the matching challenge and relay`() {
        val result = Nip42.verify(e2, "deadbeef", setOf("wss://relay.example.com"), now = 1735700010)
        assertEquals(Nip42.Result.Ok(e2.pubKey), result)
    }

    @Test
    fun `nip42 rejects a mismatched challenge`() {
        assertTrue(Nip42.verify(e2, "other", emptySet(), now = 1735700010) is Nip42.Result.Rejected)
    }

    @Test
    fun `nip42 rejects a stale event`() {
        assertTrue(Nip42.verify(e2, "deadbeef", emptySet(), now = 1735700000 + 10_000) is Nip42.Result.Rejected)
    }
}
