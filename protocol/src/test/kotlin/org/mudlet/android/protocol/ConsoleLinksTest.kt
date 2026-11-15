package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLinksTest {
    private fun runs(vararg runs: List<Int>) = runs.toList().flatten().toIntArray()

    @Test
    fun eachLinkIsASpanOfItsCharacters() {
        // "go: north or south", the two directions links 3 and 4
        val line = runs(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, -1, -1, 4, 3), listOf(9, 4, -1, -1, 0, 0), listOf(13, 5, -1, -1, 4, 4))
        assertEquals(listOf(LinkSpan(3, 4, 9), LinkSpan(4, 13, 18)), linkSpans(line, 18))
    }

    @Test
    fun runsOfOneLinkNextToEachOtherAreOneSpanButApartAreTwo() {
        // A link whose colour changes half way, then the same id again after a gap
        val line = runs(listOf(0, 2, 1, -1, 0, 7), listOf(2, 3, 2, -1, 0, 7), listOf(5, 1, -1, -1, 0, 0), listOf(6, 2, 1, -1, 0, 7))
        assertEquals(listOf(LinkSpan(7, 0, 5), LinkSpan(7, 6, 8)), linkSpans(line, 8))
    }

    @Test
    fun runsWithoutALinkOrOutsideTheLineMakeNoSpan() {
        // Link 0 is none, and a run cut short is ignored; an empty run, and one starting past the end, make no span
        val short = intArrayOf(0, 3, 1, 0, 3, 0, 1, 2, -1, -1, 0)
        assertEquals(emptyList<LinkSpan>(), linkSpans(short, 3))
        val line = runs(listOf(0, 0, -1, -1, 0, 2), listOf(2, 10, -1, -1, 0, 5), listOf(20, 2, -1, -1, 0, 6))
        assertEquals(listOf(LinkSpan(5, 2, 4)), linkSpans(line, 4))
    }

    @Test
    fun aLinkIsCalledByItsToolTipElseByItsText() {
        val withTip = EngineMessage.Link(1, "chat", " the stew ", true, "", emptyList(), false, false, false, false, false, false, null, null, null, null)
        assertEquals("the stew", linkLabel(withTip, "stew"))
        assertEquals("stew", linkLabel(withTip.copy(tooltip = "  "), " stew "))
        assertEquals("stew", linkLabel(null, "stew"))
    }
}
