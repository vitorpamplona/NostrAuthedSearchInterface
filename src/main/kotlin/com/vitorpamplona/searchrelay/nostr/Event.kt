package com.vitorpamplona.searchrelay.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A minimal Nostr event. We only need enough of the model to receive and verify
 * AUTH events (NIP-42) and to forward them to the backend — this is intentionally
 * lean rather than depending on the full Quartz/Amethyst KMP library, which carries
 * Android, OkHttp and SQLite baggage that would hurt a high-throughput server.
 *
 * The crypto primitive (secp256k1 / BIP-340 schnorr) is the very same native library
 * Quartz uses, so verification behaviour matches.
 */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag whose name matches, or null. */
    fun firstTag(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /**
     * Recomputes the event id per NIP-01 and verifies it matches `id`, then verifies the
     * schnorr signature. Returns true only if both the id and the signature are valid.
     */
    fun hasValidSignature(): Boolean {
        if (id.length != 64 || pubkey.length != 64 || sig.length != 128) return false
        val computedId = computeId(pubkey, created_at, kind, tags, content)
        if (!computedId.equals(id, ignoreCase = true)) return false
        return try {
            val idBytes = id.hexToByteArray()
            val sigBytes = sig.hexToByteArray()
            val pubkeyBytes = pubkey.hexToByteArray()
            Secp256k1.get().verifySchnorr(sigBytes, idBytes, pubkeyBytes)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * NIP-01 id: sha256 of the JSON array
         * [0, pubkey, created_at, kind, tags, content] with no extra whitespace.
         */
        fun computeId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String {
            val sb = StringBuilder(content.length + 128)
            sb.append("[0,\"").append(pubkey).append("\",")
            sb.append(createdAt).append(',')
            sb.append(kind).append(',')
            sb.append('[')
            tags.forEachIndexed { i, tag ->
                if (i > 0) sb.append(',')
                sb.append('[')
                tag.forEachIndexed { j, value ->
                    if (j > 0) sb.append(',')
                    sb.append('"').append(escape(value)).append('"')
                }
                sb.append(']')
            }
            sb.append("],\"").append(escape(content)).append("\"]")
            val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
            return digest.toHexString()
        }

        /** JSON string escaping per the NIP-01 serialization rules. */
        private fun escape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHexString(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex character" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
