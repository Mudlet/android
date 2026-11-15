package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {
    private fun score(query: String, candidate: String): Double {
        val score = FuzzyMatch.score(query, candidate)
        assertNotNull("'$query' should match '$candidate'", score)
        return score!!
    }

    @Test
    fun subsequenceMatchesAndOthersDoNot() {
        score("gc", "get coins from corpse")
        score("GCFC", "get coins from corpse")
        assertNull(FuzzyMatch.score("cg", "get coins"))
        assertNull(FuzzyMatch.score("x", "get coins"))
        assertNull(FuzzyMatch.score("get coins!", "get coins"))
        assertEquals(0.0, FuzzyMatch.score("", "anything")!!, 0.0)
    }

    @Test
    fun wordStartsBeatScatteredCharacters() {
        assertTrue(score("gc", "get coins") > score("gc", "magic"))
        assertTrue(score("gc", "get coins") > score("gc", "eggcup"))
        assertTrue(score("ks", "kill snake") > score("ks", "kicks"))
    }

    @Test
    fun aPrefixBeatsEverythingElse() {
        val prefix = score("ge", "get everything")
        assertTrue(prefix >= FuzzyMatch.PREFIX_BONUS)
        assertTrue(prefix > score("ge", "gaze east"))
        assertTrue(prefix > score("ge", "say get"))
        assertTrue(score("Ki", "kill rat") >= FuzzyMatch.PREFIX_BONUS)
    }

    @Test
    fun consecutiveCharactersBeatGaps() {
        assertTrue(score("cor", "get corpse") > score("cor", "get cxoxrpse"))
    }

    @Test
    fun theBestAlignmentIsFoundNotTheFirstGreedyOne() {
        // Greedy takes the "c" of "scan" (5 points in all); the best alignment takes the word start "coins":
        // g at the start 11, a gap of 8 then c at a word start 7 - 8, o right after it 1 + 4
        assertEquals(15.0, score("gco", "get scan coins"), 1e-9)
    }
}
