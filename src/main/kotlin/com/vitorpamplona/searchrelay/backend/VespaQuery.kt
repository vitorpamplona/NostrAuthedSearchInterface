package com.vitorpamplona.searchrelay.backend

/**
 * Pure construction of the Vespa `/search/` request — a faithful port of the brainstorm
 * server's `app/core/vespa.py` (`_build_yql` + the `search()` param dict), kept HTTP-free so it
 * can be unit-tested against the Python output.
 *
 * The observer pubkey is **not** an auth credential here: it is the ranking feature
 * `query(user_q) = {<pubkey>:1.0}`, which selects that observer's cell in the doc's
 * `quality_scores` sparse tensor. So a NIP-42-verified pubkey can be used directly as the
 * observer perspective without any token exchange.
 */
object VespaQuery {
    const val RANK_PROFILE = "name_and_quality_score_only"
    private const val MAX_QUERY_WORDS = 6
    private const val GRAM = 3

    /** Builds the full set of GET query parameters for a free-text search. */
    fun searchParams(text: String, observers: Collection<String>, hits: Int, includeZeroScore: Boolean): List<Pair<String, String>> {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }.take(MAX_QUERY_WORDS)
        val joined = if (words.size >= 2) words.joinToString("") else null
        val shortest = words.minOfOrNull { it.length } ?: text.length
        val wGram = if (shortest <= 3) 20.0 else 5.0

        var vespaHits = maxOf(hits, 20)
        if (!includeZeroScore) vespaHits = maxOf(hits * 5, 100)
        vespaHits = minOf(vespaHits, 400) // Vespa default max-hits

        val params = mutableListOf(
            "yql" to buildYql(words, joined),
            "ranking" to RANK_PROFILE,
            "ranking.features.query(user_q)" to userQ(observers),
            "ranking.features.query(w_gram)" to wGram.toString(),
            "ranking.features.query(w_about)" to "0.5",
            "ranking.features.query(w_about_bonus)" to "0.0",
            "hits" to vespaHits.toString(),
        )
        words.forEachIndexed { i, w -> params.add("w$i" to w) }
        joined?.let { params.add("wj" to it) }
        return params
    }

    /**
     * The `user_q` weighted set selecting one or more observer cells of the `quality_scores`
     * tensor — `{p:1.0}` for a single observer, `{p1:1.0,p2:1.0}` to rank from the combined
     * perspective of several (e.g. every pubkey a connection authenticated via NIP-42). Sorted
     * for a deterministic, deduplicated query.
     */
    fun userQ(observers: Collection<String>): String =
        observers.toSortedSet().joinToString(separator = ",", prefix = "{", postfix = "}") { "$it:1.0" }

    /** Per-word groups OR'd together, plus an optional joined-CamelCase variant. */
    fun buildYql(words: List<String>, joined: String?): String {
        val parts = words.take(MAX_QUERY_WORDS).mapIndexed { i, w -> wordGroup("@w$i", w, withGrams = true) }.toMutableList()
        if (joined != null) parts.add(wordGroup("@wj", joined, withGrams = false))
        return "select * from doc where " + parts.joinToString(" or ")
    }

    private fun wordGroup(variable: String, literal: String, withGrams: Boolean): String {
        val maxEdits = wordMaxEdits(literal)
        val clauses = mutableListOf<String>()
        for (field in FIELDS) clauses += fieldClauses(field, variable, maxEdits)
        if (withGrams) {
            for (gramField in GRAM_FIELDS) gramClause(literal, gramField).takeIf { it.isNotEmpty() }?.let { clauses += it }
            aboutGramClauseForWord(literal).takeIf { it.isNotEmpty() }?.let { clauses += it }
        }
        return "(" + clauses.joinToString(" or ") + ")"
    }

    /** Per-word fuzzy budget — 0 for very short words, up to 2 for longer ones. */
    private fun wordMaxEdits(word: String): Int = if (word.length < 3) 0 else if (word.length < 6) 1 else 2

    private fun fieldClauses(field: String, variable: String, maxEdits: Int): List<String> {
        val parts = mutableListOf(
            "({defaultIndex:\"$field\"}userInput($variable))",
            "({defaultIndex:\"$field\",prefix:true}userInput($variable))",
        )
        if (maxEdits > 0) {
            parts.add("({defaultIndex:\"$field\",fuzzy:{maxEditDistance:$maxEdits,prefixLength:1}}userInput($variable))")
        }
        return parts
    }

    /** OR of every trigram in `text` against `gramField` (sorted, deduplicated). */
    private fun gramClause(text: String, gramField: String): String {
        val grams = sortedSetOf<String>()
        for (word in text.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }) {
            val upper = maxOf(1, word.length - GRAM + 1)
            for (i in 0 until upper) {
                if (i + GRAM <= word.length) {
                    val g = word.substring(i, i + GRAM)
                    if (g.all { it.isLetterOrDigit() }) grams.add(g)
                }
            }
        }
        if (grams.isEmpty()) return ""
        return "(" + grams.joinToString(" or ") { "$gramField contains \"$it\"" } + ")"
    }

    /** AND of one word's trigrams against `about_gram` (discriminative; order preserved). */
    private fun aboutGramClauseForWord(word: String): String {
        val grams = ArrayList<String>()
        for (i in 0..(word.length - GRAM)) {
            val g = word.substring(i, i + GRAM)
            if (g.all { it.isLetterOrDigit() }) grams.add(g)
        }
        if (grams.isEmpty()) return ""
        return "(" + grams.joinToString(" and ") { "about_gram contains \"$it\"" } + ")"
    }

    private val WHITESPACE = Regex("\\s+")
    private val FIELDS = listOf("name", "display_name", "about")
    private val GRAM_FIELDS = listOf("name_gram", "display_name_gram")
}
