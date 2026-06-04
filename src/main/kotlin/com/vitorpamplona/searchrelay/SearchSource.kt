package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSource
import com.vitorpamplona.quartz.nip50Search.SearchQuery
import com.vitorpamplona.searchrelay.backend.BrainstormClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Answers a REQ by redirecting its NIP-50 `search` filter to the backend. A connection with a
 * valid JWT searches with `ownPubkey=true`; anonymous connections use `ownPubkey=false`.
 *
 * Quartz's engine streams an `EVENT` for each emitted event and an `EOSE` once the flow ends,
 * and enforces subscription/message limits — so this only has to produce events.
 */
class SearchSource(
    private val backend: BrainstormClient,
    private val auth: ConnectionAuth,
    private val maxResults: Int,
) : EventSource {
    override fun events(filters: List<Filter>): Flow<Event> = flow {
        for (filter in filters) {
            val query = filter.search?.let { SearchQuery.parse(it) } ?: continue
            if (query.isTermsEmpty()) continue

            val jwt = auth.validJwt()
            val limit = filter.limit?.coerceIn(1, maxResults) ?: maxResults
            backend.searchProfiles(query.terms, ownPubkey = jwt != null, jwt = jwt)
                .take(limit)
                .forEach { emit(it) }
        }
    }
}
