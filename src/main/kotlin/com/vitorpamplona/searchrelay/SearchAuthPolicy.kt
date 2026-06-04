package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult

/**
 * NIP-42 authentication for a connection. Quartz's [FullAuthPolicy] runs the whole handshake
 * (challenge on connect, signature/challenge/relay verification) and tracks the proven pubkey,
 * which the engine surfaces to the search source as `RequestContext.authenticatedUsers`. That
 * pubkey is all we need as the Vespa observer — there is no token to mint.
 *
 * The only customization: [FullAuthPolicy] gates REQ behind authentication; this relay allows
 * anonymous search (it just ranks from the default observer), so REQ is always accepted.
 */
class SearchAuthPolicy(relay: NormalizedRelayUrl) : FullAuthPolicy(relay) {
    override fun accept(command: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Accepted(command)
}
