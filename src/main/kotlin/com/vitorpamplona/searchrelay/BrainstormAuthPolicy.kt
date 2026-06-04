package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import java.time.Instant

/**
 * Per-connection NIP-42 authentication. Quartz's [FullAuthPolicy] runs the handshake (challenge
 * on connect, signature/challenge/relay verification); a fresh instance is created per connection
 * by the server's policy factory. We add:
 *
 *  - [authorize]: once the pubkey is proven, exchange the event for a backend JWT and hold it
 *    (with its expiry) on this policy. The search source reads it back via `RequestContext.policy`.
 *  - [accept]: [FullAuthPolicy] gates REQ behind auth; this relay allows anonymous search, so REQ
 *    is always accepted.
 */
class BrainstormAuthPolicy(
    relay: NormalizedRelayUrl,
    private val backend: BrainstormClient,
) : FullAuthPolicy(relay) {

    @Volatile
    private var token: Token? = null

    /** The still-valid JWT minted for this connection, or null if absent or expired. */
    fun validJwt(now: Instant = Instant.now()): String? =
        token?.takeIf { it.expiresAt?.isAfter(now) ?: true }?.jwt

    override suspend fun authorize(event: RelayAuthEvent) {
        backend.login(event)?.let { jwt -> token = Token(jwt, Jwt.parseExpiry(jwt)) }
    }

    override fun accept(command: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Accepted(command)

    private data class Token(val jwt: String, val expiresAt: Instant?)
}
