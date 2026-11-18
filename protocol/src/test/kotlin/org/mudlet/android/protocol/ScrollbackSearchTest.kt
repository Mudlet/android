package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollbackSearchTest {
    private val lines = listOf("You hit the RAT.", "A bat bites you.", "Nothing here.", "The rat bites you.")

    @Test
    fun matchesIgnoreCaseAndMayOverlapAsDesktopFindsThem() {
        assertEquals(listOf(12), matchStarts(lines[0], "rat"))
        assertEquals(listOf(4), matchStarts(lines[3], "RAT"))
        assertEquals(listOf(0, 1), matchStarts("aaa", "aa"))
        assertEquals(emptyList<Int>(), matchStarts("aaa", ""))
    }

    @Test
    fun theFirstSearchUpStartsAtTheBottomAndEachNextGoesOnFromTheLast() {
        val bottom = nextMatchingLine(lines, "rat", lines.size, up = true)
        assertEquals(3, bottom)
        assertEquals(0, nextMatchingLine(lines, "rat", bottom!!, up = true))
        assertNull("nothing above the top match", nextMatchingLine(lines, "rat", 0, up = true))
        assertEquals(3, nextMatchingLine(lines, "rat", 0, up = false))
        assertNull("nothing below the bottom match", nextMatchingLine(lines, "rat", 3, up = false))
    }

    @Test
    fun aStartPastEitherEndIsClampedAndNoQueryOrNoLinesFindNothing() {
        assertEquals(3, nextMatchingLine(lines, "bites", 99, up = true))
        assertEquals(1, nextMatchingLine(lines, "bites", -5, up = false))
        assertNull(nextMatchingLine(lines, "", lines.size, up = true))
        assertNull(nextMatchingLine(emptyList(), "rat", 0, up = true))
        assertNull("nothing below the bottom", nextMatchingLine(lines, "RAT", lines.size, up = false))
    }
}
