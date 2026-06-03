package com.vitorpamplona.searchrelay.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing of inbound relay messages and construction of outbound ones, following the
 * Nostr relay protocol (NIP-01) plus NIP-42 (AUTH) and NIP-50 (the `search` filter field).
 */
object Protocol {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A single REQ filter — we only care about the fields relevant to NIP-50 search. */
    data class Filter(
        val search: String?,
        val kinds: List<Int>?,
        val limit: Int?,
    )

    sealed interface Inbound {
        data class Req(val subscriptionId: String, val filters: List<Filter>) : Inbound
        data class Close(val subscriptionId: String) : Inbound
        data class Auth(val event: Event) : Inbound
        data class Unsupported(val verb: String) : Inbound
        data class Malformed(val error: String) : Inbound
    }

    fun parse(text: String): Inbound {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Inbound.Malformed("could not parse JSON: ${e.message}")
        }
        if (root !is JsonArray || root.isEmpty()) {
            return Inbound.Malformed("expected a non-empty JSON array")
        }
        val verb = (root[0] as? JsonPrimitive)?.contentOrNull
            ?: return Inbound.Malformed("missing message verb")

        return when (verb) {
            "REQ" -> parseReq(root)
            "CLOSE" -> {
                val sub = (root.getOrNull(1) as? JsonPrimitive)?.contentOrNull
                    ?: return Inbound.Malformed("CLOSE missing subscription id")
                Inbound.Close(sub)
            }
            "AUTH" -> parseAuth(root)
            else -> Inbound.Unsupported(verb)
        }
    }

    private fun parseReq(root: JsonArray): Inbound {
        val sub = (root.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            ?: return Inbound.Malformed("REQ missing subscription id")
        val filters = ArrayList<Filter>()
        for (i in 2 until root.size) {
            val obj = root[i] as? JsonObject ?: continue
            val search = obj["search"]?.jsonPrimitive?.contentOrNull
            val kinds = obj["kinds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
            val limit = obj["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            filters.add(Filter(search = search, kinds = kinds, limit = limit))
        }
        return Inbound.Req(sub, filters)
    }

    private fun parseAuth(root: JsonArray): Inbound {
        val payload = root.getOrNull(1) as? JsonObject
            ?: return Inbound.Malformed("AUTH must carry a signed event object")
        val event = Event.fromJsonOrNull(payload.toString())
            ?: return Inbound.Malformed("AUTH event malformed")
        return Inbound.Auth(event)
    }

    // ---- Outbound builders ---------------------------------------------------------

    fun auth(challenge: String): String =
        buildJsonArray { add("AUTH"); add(challenge) }.toString()

    fun ok(eventId: String, accepted: Boolean, message: String): String =
        buildJsonArray { add("OK"); add(eventId); add(accepted); add(message) }.toString()

    fun eose(subscriptionId: String): String =
        buildJsonArray { add("EOSE"); add(subscriptionId) }.toString()

    fun closed(subscriptionId: String, message: String): String =
        buildJsonArray { add("CLOSED"); add(subscriptionId); add(message) }.toString()

    fun notice(message: String): String =
        buildJsonArray { add("NOTICE"); add(message) }.toString()

    /** EVENT message wrapping a synthesized kind-0 metadata event for a search hit. */
    fun event(subscriptionId: String, event: JsonObject): String =
        buildJsonArray { add("EVENT"); add(subscriptionId); add(event) }.toString()
}
