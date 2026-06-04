package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.VerifyAuthOnlyPolicy
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the security-critical wiring: the AUTH signature MUST be verified, because the proven
 * pubkey becomes the Vespa observer. `FullAuthPolicy` alone does NOT verify signatures (it only
 * checks challenge/relay/freshness), so the relay stacks `VerifyAuthOnlyPolicy + SearchAuthPolicy`.
 * These tests assert the verify policy rejects a forged AUTH event before it can be accepted.
 *
 * Vector: a real kind-22242 event (id/pubkey/sig generated with @noble/curves).
 */
class AuthSignatureTest {
    private val tags = arrayOf(arrayOf("relay", "wss://relay.example.com/"), arrayOf("challenge", "deadbeef"))
    private val id = "7bda131eeb4e3702fe8558ee3ce4d68b5926a5757257f604fef924fedd7250e3"
    private val pubkey = "4366d9a71e4098a4e937c05633fd39d2ff435c98c8f40f078895e59dd36509b7"
    private val sig = "3c2cc0271a3a9ad24431ddc32e895c2157677cbc34d25f9a686cff4308f14e9b1495fc91e464b975d638b74d6e1b05016879ef2ac337a19877d85ccf7ec73ec9"

    private fun authCmd(pk: String, s: String) =
        AuthCmd(RelayAuthEvent(id, pk, 1735700000, tags, "", s))

    @Test
    fun `rejects a tampered signature`() {
        val result = VerifyAuthOnlyPolicy.accept(authCmd(pubkey, "00" + sig.substring(2)))
        assertTrue(result is PolicyResult.Rejected)
    }

    @Test
    fun `rejects a pubkey-swap impersonation`() {
        // Same (valid) signature, but claiming a different pubkey — the id no longer matches.
        val impostor = "deadbeef" + pubkey.substring(8)
        val result = VerifyAuthOnlyPolicy.accept(authCmd(impostor, sig))
        assertTrue(result is PolicyResult.Rejected)
    }
}
