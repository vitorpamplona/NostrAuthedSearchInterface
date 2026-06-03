package com.vitorpamplona.searchrelay.backend

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.searchrelay.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
 * Thin async client over the Brainstorm search backend. One instance is shared by every
 * WebSocket connection so the underlying connection pool is reused across the whole relay.
 */
class BrainstormClient(private val config: Config) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BrainstormClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        engine {
            requestTimeout = config.backendRequestTimeoutMs
            maxConnectionsCount = config.backendMaxConnectionsCount
            endpoint.maxConnectionsPerRoute = config.backendMaxConnectionsPerRoute
            endpoint.pipelineMaxSize = 20
            endpoint.keepAliveTime = 30_000
            endpoint.connectTimeout = config.backendRequestTimeoutMs
        }
    }

    /**
     * Calls GET /search/byText and converts each profile hit into a synthesized kind-0
     * metadata Event. The events are UNSIGNED (sig = "") because the search index stores
     * indexed profile fields, not the original signed events.
     */
    suspend fun searchProfiles(text: String, ownPubkey: Boolean, jwt: String?): List<Event> {
        val url = buildString {
            append(config.backendBaseUrl)
            append("/search/byText?text=")
            append(java.net.URLEncoder.encode(text, "UTF-8"))
            append("&onlyRanked=").append(config.onlyRanked)
            append("&ownPubkey=").append(ownPubkey)
        }
        val response: HttpResponse = http.get(url) {
            if (ownPubkey && jwt != null) header(config.backendTokenHeader, jwt)
        }
        if (!response.status.isSuccess()) {
            log.warn("search backend returned {} for '{}'", response.status, text)
            return emptyList()
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val results = body["data"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map(::toMetadataEvent)
    }

    /**
     * Exchanges a verified NIP-42 AUTH event for a backend JWT. The backend validates the
     * event statelessly (signature + freshness), mirroring its NIP-98 interface, and returns
     * `{ data: { token } }`. Returns null if the backend rejects the event.
     */
    suspend fun login(authEvent: Event): String? {
        val url = config.backendLoginUrl(authEvent.pubKey)
        // Quartz produces the canonical NIP-01 JSON; wrap it as { "signed_event": <event> }.
        val payload = """{"signed_event":${authEvent.toJson()}}"""
        return try {
            val response = http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (!response.status.isSuccess()) {
                log.warn("backend login rejected pubkey {} with {}", authEvent.pubKey, response.status)
                return null
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["data"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            log.warn("backend login call failed for {}: {}", authEvent.pubKey, e.message)
            null
        }
    }

    /** Builds an UNSIGNED kind-0 metadata Event from a single search result. */
    private fun toMetadataEvent(result: JsonObject): Event {
        val pubkey = result["pubkey"]?.jsonPrimitive?.contentOrNull ?: ""
        val createdAt = System.currentTimeMillis() / 1000

        // NIP-01 kind-0 content carries the profile metadata as a JSON-encoded string.
        val metadata = buildJsonObject {
            copyString(result, "name")
            copyString(result, "display_name")
            copyString(result, "picture")
            copyString(result, "banner")
            copyString(result, "nip05")
            copyString(result, "lud16")
            copyString(result, "about")
        }
        val content = metadata.toString()

        // Carry the search-only signals as tags so clients can sort/inspect them.
        val tags = ArrayList<Array<String>>(3)
        result["documentid"]?.jsonPrimitive?.contentOrNull?.let { tags.add(arrayOf("documentid", it)) }
        result["_relevance"]?.jsonPrimitive?.doubleOrNull?.let { tags.add(arrayOf("relevance", it.toString())) }
        result["_quality_score"]?.jsonPrimitive?.doubleOrNull?.let { tags.add(arrayOf("quality_score", it.toString())) }
        val tagArray = tags.toTypedArray()

        val id = EventHasher.hashId(pubkey, createdAt, 0, tagArray, content)
        return Event(id, pubkey, createdAt, 0, tagArray, content, "")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.copyString(src: JsonObject, key: String) {
        src[key]?.jsonPrimitive?.contentOrNull?.let { put(key, it) }
    }

    override fun close() = http.close()
}
