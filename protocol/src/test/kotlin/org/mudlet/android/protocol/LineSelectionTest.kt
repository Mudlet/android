package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSelectionTest {
    // Lines 100 to 104 of a console that dropped its first hundred; 102 a script deleted
    private val lines = listOf("You hit the rat.", "", "deleted", "  The rat bites you.  ", "It dies.")
    private fun line(i: Int) = lines[i].takeUnless { i == 2 }

    @Test
    fun aSelectionRunsFromEitherEndToTheOther() {
        val down = LineSelection(101, 103)
        val up = LineSelection(103, 101)
        for (selection in listOf(down, up)) {
            assertEquals(101L, selection.first)
            assertEquals(103L, selection.last)
            assertTrue(102L in selection)
            assertFalse(100L in selection)
            assertFalse(104L in selection)
        }
        assertEquals(LineSelection(103, 100), up.extendTo(100))
        assertEquals(LineSelection(103, 103), up.extendTo(103))
    }

    @Test
    fun linesAreJoinedAsDesktopCopiesThemWithALineFeedBetweenAndNoneAfterTheLast() {
        // A blank line stays, as desktop copies it; the spaces of a line are its own
        assertEquals("You hit the rat.\n\n  The rat bites you.  ", copiedLines(LineSelection(100, 103), 100, lines.size, line = ::line))
        assertEquals("It dies.", copiedLines(LineSelection(104, 104), 100, lines.size, line = ::line))
    }

    @Test
    fun aDeletedLineIsLeftOutAndTheOrderIsTheConsolesWhicheverWayTheSelectionWent() {
        assertEquals("  The rat bites you.  \nIt dies.", copiedLines(LineSelection(104, 102), 100, lines.size, line = ::line))
        assertEquals("", copiedLines(LineSelection(102, 102), 100, lines.size, line = ::line))
    }

    @Test
    fun onlyTheLinesStillKeptAreSelectedAndNoneWhenAllAreGone() {
        assertEquals(0..1, LineSelection(90, 101).indicesIn(100, lines.size))
        assertEquals(3..4, LineSelection(103, 200).indicesIn(100, lines.size))
        assertEquals("You hit the rat.\n", copiedLines(LineSelection(5, 101), 100, lines.size, line = ::line))
        assertTrue(LineSelection(10, 99).indicesIn(100, lines.size).isEmpty())
        assertTrue(LineSelection(105, 110).indicesIn(100, lines.size).isEmpty())
        assertTrue(LineSelection(100, 100).indicesIn(100, 0).isEmpty())
    }

    @Test
    fun textLongerThanTheMostIsNotGivenAtAll() {
        // "You hit the rat.\n\n  The rat bites you.  " is 40 characters
        assertEquals(40, copiedLines(LineSelection(100, 103), 100, lines.size, most = 40, line = ::line)?.length)
        assertNull(copiedLines(LineSelection(100, 103), 100, lines.size, most = 39, line = ::line))
        assertNull("a cut short copy is never given", copiedLines(LineSelection(100, 104), 100, lines.size, most = 40, line = ::line))
    }
}
