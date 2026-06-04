package com.vitorpamplona.searchrelay.backend

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.searchrelay.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Talks to Vespa directly, the same way the brainstorm server does (`app/core/vespa.py`):
 * a free-text `/search/` query ranked from an observer's perspective, plus a `/document/v1`
 * lookup by pubkey. One instance is shared by every WebSocket so the connection pool is reused.
 *
 * Hits are converted into synthesized kind-0 metadata events. They are UNSIGNED (sig = "")
 * because the index stores profile fields, not the original signed events.
 */
class VespaClient(private val config: Config) : AutoCloseable {
    private val log = LoggerFactory.getLogger(VespaClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        engine {
            requestTimeout = config.requestTimeoutMs
            maxConnectionsCount = config.maxConnectionsCount
            endpoint.maxConnectionsPerRoute = config.maxConnectionsPerRoute
            endpoint.keepAliveTime = 30_000
            endpoint.connectTimeout = config.requestTimeoutMs
        }
    }

    /**
     * Free-text profile search ranked from one or more observers' perspective (Vespa rank
     * profile [VespaQuery.RANK_PROFILE]). `observers` are hex pubkeys — every pubkey the
     * connection authenticated via NIP-42, or the relay's single default observer when anonymous.
     */
    suspend fun search(text: String, observers: Collection<String>, hits: Int, includeZeroScore: Boolean): List<Event> {
        val response = http.get("${config.vespaUrl}/search/") {
            VespaQuery.searchParams(text, observers, hits, includeZeroScore).forEach { (k, v) ->
                url.parameters.append(k, v)
            }
        }
        if (!response.status.isSuccess()) {
            log.warn("vespa search returned {} for '{}'", response.status, text)
            return emptyList()
        }

        val children = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["root"]?.jsonObject?.get("children")?.jsonArray
            ?: return emptyList()

        var hitObjects = children.mapNotNull { it as? JsonObject }
        if (!includeZeroScore) {
            hitObjects = hitObjects.filter { (userScore(it) ?: 0.0) > 0.0 }
        }
        return hitObjects.take(hits).mapNotNull(::toMetadataEvent)
    }

    /** Direct document lookup by hex pubkey; null if the profile isn't indexed. */
    suspend fun getDocument(pubkey: String): Event? {
        val response = http.get("${config.vespaUrl}/document/v1/doc/doc/docid/$pubkey")
        if (response.status == HttpStatusCode.NotFound || !response.status.isSuccess()) return null
        val fields = json.parseToJsonElement(response.bodyAsText()).jsonObject["fields"]?.jsonObject ?: return null
        return eventFrom(pubkey, fields, documentId = "id:doc:doc::$pubkey", relevance = null, qualityScore = null)
    }

    private fun userScore(hit: JsonObject): Double? =
        hit["fields"]?.jsonObject?.get("matchfeatures")?.jsonObject?.get("user_score")?.jsonPrimitive?.doubleOrNull

    private fun toMetadataEvent(hit: JsonObject): Event? {
        val fields = hit["fields"]?.jsonObject ?: return null
        val pubkey = fields["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null
        return eventFrom(
            pubkey = pubkey,
            fields = fields,
            documentId = hit["id"]?.jsonPrimitive?.contentOrNull,
            relevance = hit["relevance"]?.jsonPrimitive?.doubleOrNull,
            qualityScore = userScore(hit),
        )
    }

    /** Assembles an unsigned kind-0 event from a Vespa document's profile fields. */
    private fun eventFrom(
        pubkey: String,
        fields: JsonObject,
        documentId: String?,
        relevance: Double?,
        qualityScore: Double?,
    ): Event {
        val createdAt = System.currentTimeMillis() / 1000
        val content = buildJsonObject {
            for (key in PROFILE_FIELDS) {
                fields[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }.toString()

        val tags = ArrayList<Array<String>>(3)
        documentId?.let { tags.add(arrayOf("documentid", it)) }
        relevance?.let { tags.add(arrayOf("relevance", it.toString())) }
        qualityScore?.let { tags.add(arrayOf("quality_score", it.toString())) }
        val tagArray = tags.toTypedArray()

        val id = EventHasher.hashId(pubkey, createdAt, 0, tagArray, content)
        return Event(id, pubkey, createdAt, 0, tagArray, content, "")
    }

    override fun close() = http.close()

    private companion object {
        // Standard kind-0 fields the Vespa schema stores (app/core/vespa.py PROFILE_FIELDS).
        private val PROFILE_FIELDS = listOf(
            "name", "display_name", "about", "picture", "banner", "nip05", "lud06", "lud16", "website",
        )
    }
}
