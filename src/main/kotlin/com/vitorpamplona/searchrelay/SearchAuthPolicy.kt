package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult

/**
 * NIP-42 authentication for a connection. Quartz's [FullAuthPolicy] runs the handshake (challenge
 * on connect, challenge/relay/freshness checks) and tracks the proven pubkey, surfaced to the
 * search source as `RequestContext.authenticatedUsers` and used as the Vespa observer.
 *
 * **It must be composed with a verifying policy** (`VerifyAuthOnlyPolicy + SearchAuthPolicy`) —
 * `FullAuthPolicy` does not check the BIP-340 signature itself, so on its own it would let a
 * client claim any pubkey. The verify policy in the stack rejects an AUTH event with a bad
 * signature before the pubkey can be marked authenticated (see `RelayServer`).
 *
 * Our only customization: allow anonymous search ([FullAuthPolicy] otherwise gates REQ).
 */
class SearchAuthPolicy(relay: NormalizedRelayUrl) : FullAuthPolicy(relay) {
    override fun accept(command: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Accepted(command)
}
