package com.vitorpamplona.searchrelay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventSource
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip50Search.SearchQuery
import com.vitorpamplona.searchrelay.backend.VespaClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Answers a REQ by searching Vespa from an observer's perspective. The observer is the
 * connection's NIP-42-authenticated pubkey (`ctx.authenticatedUsers`) when present, otherwise
 * the relay's default observer — so authentication changes *which trust scores rank the
 * results*, with no token exchange involved.
 *
 * A query that is itself a hex pubkey is resolved to a direct document lookup, mirroring the
 * brainstorm `/search/byText` endpoint.
 */
class SearchSource(
    private val vespa: VespaClient,
    private val defaultObserver: String,
    private val onlyRanked: Boolean,
    private val maxResults: Int,
) : EventSource {
    override fun events(ctx: RequestContext, filters: List<Filter>): Flow<Event> = flow {
        // Every pubkey this connection authenticated via NIP-42 becomes an observer; their
        // quality scores are combined when ranking. Anonymous → the single default observer.
        val observers = ctx.authenticatedUsers.ifEmpty { setOf(defaultObserver) }

        for (filter in filters) {
            val text = filter.search?.let { sanitize(SearchQuery.parse(it).terms) } ?: continue
            if (text.isEmpty()) continue
            val limit = filter.limit?.coerceIn(1, maxResults) ?: maxResults

            val hex = text.takeIf(HEX_PUBKEY::matches)?.lowercase()
            val results =
                if (hex != null) listOfNotNull(vespa.getDocument(hex))
                else vespa.search(text, observers, hits = limit, includeZeroScore = !onlyRanked)

            results.take(limit).forEach { emit(it) }
        }
    }

    /** Strip control characters and trim, matching the backend's `_sanitize`. */
    private fun sanitize(text: String): String = text.replace(CONTROL_CHARS, "").trim()

    private companion object {
        private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")
        private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
    }
}
