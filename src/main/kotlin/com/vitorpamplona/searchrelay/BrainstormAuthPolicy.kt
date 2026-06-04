package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.searchrelay.backend.BrainstormClient

/**
 * NIP-42 authentication for a connection. Quartz's [FullAuthPolicy] does the handshake — it
 * issues the challenge on connect and verifies the AUTH event's signature, challenge and relay
 * tag. We add two things:
 *
 *  - [authorize]: once the pubkey is proven, exchange the event for a backend JWT and record it
 *    on the connection (search then runs with `ownPubkey=true`).
 *  - [accept]: [FullAuthPolicy] gates REQ behind authentication; this relay instead allows
 *    anonymous search, so REQ is always accepted.
 */
class BrainstormAuthPolicy(
    relay: NormalizedRelayUrl,
    private val backend: BrainstormClient,
    private val auth: ConnectionAuth,
) : FullAuthPolicy(relay) {

    override suspend fun authorize(pubkey: String, event: RelayAuthEvent) {
        backend.login(event)?.let(auth::authenticate)
    }

    override fun accept(command: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Accepted(command)
}
