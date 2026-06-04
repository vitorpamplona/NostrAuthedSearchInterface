package com.vitorpamplona.searchrelay.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression vectors generated from the brainstorm server's own `_build_yql` (Python), so our
 * Kotlin port stays byte-for-byte identical: fuzzy budgets per word length, trigram OR/AND
 * clauses, and the joined-CamelCase variant.
 */
class VespaQueryTest {
    @Test
    fun `yql for a single 5-letter word (fuzzy=1, grams)`() {
        val expected =
            "select * from doc where (({defaultIndex:\"name\"}userInput(@w0)) or " +
                "({defaultIndex:\"name\",prefix:true}userInput(@w0)) or " +
                "({defaultIndex:\"name\",fuzzy:{maxEditDistance:1,prefixLength:1}}userInput(@w0)) or " +
                "({defaultIndex:\"display_name\"}userInput(@w0)) or " +
                "({defaultIndex:\"display_name\",prefix:true}userInput(@w0)) or " +
                "({defaultIndex:\"display_name\",fuzzy:{maxEditDistance:1,prefixLength:1}}userInput(@w0)) or " +
                "({defaultIndex:\"about\"}userInput(@w0)) or " +
                "({defaultIndex:\"about\",prefix:true}userInput(@w0)) or " +
                "({defaultIndex:\"about\",fuzzy:{maxEditDistance:1,prefixLength:1}}userInput(@w0)) or " +
                "(name_gram contains \"ito\" or name_gram contains \"tor\" or name_gram contains \"vit\") or " +
                "(display_name_gram contains \"ito\" or display_name_gram contains \"tor\" or display_name_gram contains \"vit\") or " +
                "(about_gram contains \"vit\" and about_gram contains \"ito\" and about_gram contains \"tor\"))"
        assertEquals(expected, VespaQuery.buildYql(listOf("vitor"), null))
    }

    @Test
    fun `yql for a 2-letter word omits fuzzy and grams`() {
        val expected =
            "select * from doc where (({defaultIndex:\"name\"}userInput(@w0)) or " +
                "({defaultIndex:\"name\",prefix:true}userInput(@w0)) or " +
                "({defaultIndex:\"display_name\"}userInput(@w0)) or " +
                "({defaultIndex:\"display_name\",prefix:true}userInput(@w0)) or " +
                "({defaultIndex:\"about\"}userInput(@w0)) or " +
                "({defaultIndex:\"about\",prefix:true}userInput(@w0)))"
        assertEquals(expected, VespaQuery.buildYql(listOf("ab"), null))
    }

    @Test
    fun `two-word query adds w1 group and a joined-CamelCase variant`() {
        val yql = VespaQuery.buildYql(listOf("vitor", "pamplona"), "vitorpamplona")
        // 8-letter word gets maxEditDistance:2; the joined variant uses @wj with no grams.
        assertTrue(yql.contains("fuzzy:{maxEditDistance:2,prefixLength:1}}userInput(@w1)"))
        assertTrue(yql.contains("userInput(@wj))"))
        assertTrue(!yql.substringAfter("@wj").contains("gram contains")) // joined variant has no grams
    }

    @Test
    fun `search params carry the observer as the user_q ranking feature`() {
        val params = VespaQuery.searchParams("vitor pamplona", observer = "abc123", hits = 100, includeZeroScore = false)
        val map = params.toMap()
        assertEquals("name_and_quality_score_only", map["ranking"])
        assertEquals("{abc123:1.0}", map["ranking.features.query(user_q)"])
        assertEquals("5.0", map["ranking.features.query(w_gram)"]) // shortest word > 3
        assertEquals("0.5", map["ranking.features.query(w_about)"])
        assertEquals("400", map["hits"]) // onlyRanked over-fetches: max(100*5,100) capped at 400
        assertEquals("vitor", map["w0"])
        assertEquals("pamplona", map["w1"])
        assertEquals("vitorpamplona", map["wj"])
    }

    @Test
    fun `short word raises the w_gram weight`() {
        val map = VespaQuery.searchParams("ab", observer = "x", hits = 50, includeZeroScore = true).toMap()
        assertEquals("20.0", map["ranking.features.query(w_gram)"]) // shortest <= 3
        assertEquals("50", map["hits"]) // include-zero: max(50,20) capped at 400
    }
}
