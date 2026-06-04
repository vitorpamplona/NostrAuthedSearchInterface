package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSource
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip50Search.SearchQuery
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Answers a REQ by redirecting its NIP-50 `search` filter to the backend. A single shared
 * instance serves every connection: the per-connection auth state comes from the
 * [RequestContext] (`ctx.policy`), so a connection with a valid JWT searches with
 * `ownPubkey=true`, while anonymous connections use `ownPubkey=false`.
 *
 * Quartz's engine streams an `EVENT` per emitted event and an `EOSE` once the flow ends.
 */
class SearchSource(
    private val backend: BrainstormClient,
    private val maxResults: Int,
) : EventSource {
    override fun events(ctx: RequestContext, filters: List<Filter>): Flow<Event> = flow {
        val jwt = (ctx.policy as? BrainstormAuthPolicy)?.validJwt()
        for (filter in filters) {
            val query = filter.search?.let { SearchQuery.parse(it) } ?: continue
            if (query.isTermsEmpty()) continue

            val limit = filter.limit?.coerceIn(1, maxResults) ?: maxResults
            backend.searchProfiles(query.terms, ownPubkey = jwt != null, jwt = jwt)
                .take(limit)
                .forEach { emit(it) }
        }
    }
}
