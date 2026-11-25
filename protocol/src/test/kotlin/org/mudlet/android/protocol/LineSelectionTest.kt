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

    @Test
    fun selectAllTakesEveryLineKeptAndIsOfferedOnlyWhileSomeAreLeftOut() {
        assertEquals(LineSelection(100, 104), allLines(100, lines.size))
        assertNull(allLines(100, 0))
        val all = listOf(SelectionItem.COPY, SelectionItem.SHARE, SelectionItem.SELECT_ALL, SelectionItem.TO_COMMAND_LINE)
        assertEquals(all, selectionItems(LineSelection(101, 104), 100, lines.size, oneTriggerLine = false))
        assertEquals(all, selectionItems(LineSelection(100, 103), 100, lines.size, oneTriggerLine = false))
        assertEquals(all - SelectionItem.SELECT_ALL, selectionItems(LineSelection(104, 100), 100, lines.size, oneTriggerLine = false))
        // Lines the scrollback dropped since are not left out
        assertEquals(all - SelectionItem.SELECT_ALL, selectionItems(LineSelection(90, 200), 100, lines.size, oneTriggerLine = false))
        assertEquals(all + SelectionItem.MAKE_TRIGGER, selectionItems(LineSelection(102, 102), 100, lines.size, oneTriggerLine = true))
    }

    @Test
    fun theCommandLineGetsTheLinesOnOneLine() {
        assertEquals("You hit the rat.    The rat bites you.  ", commandLineText(copiedLines(LineSelection(100, 103), 100, lines.size, line = ::line)!!))
        assertEquals("It dies.", commandLineText("It dies."))
    }

    @Test
    fun theSpanOfTheSelectionIsItsDrawnLinesAndAnEdgeOfTheListForAnEndScrolledOut() {
        // Lines 3 to 7 drawn in a list 100 high, the first partly above its top and 5 deleted (no height)
        val drawn = listOf(DrawnLine(3, -10, 20), DrawnLine(4, 20, 50), DrawnLine(5, 50, 50), DrawnLine(6, 50, 80), DrawnLine(7, 80, 110))
        assertEquals(20..80, selectedSpan(4..6, drawn, 100))
        assertEquals(0..20, selectedSpan(3..3, drawn, 100))
        assertEquals(80..100, selectedSpan(7..7, drawn, 100))
        assertEquals(0..50, selectedSpan(0..4, drawn, 100))
        assertEquals(50..100, selectedSpan(6..20, drawn, 100))
        assertEquals(0..100, selectedSpan(0..20, drawn, 100))
        assertEquals("all above: the top", 0..0, selectedSpan(0..2, drawn, 100))
        assertEquals("all below: the bottom", 100..100, selectedSpan(8..9, drawn, 100))
        assertNull(selectedSpan(IntRange.EMPTY, drawn, 100))
        assertNull(selectedSpan(4..6, emptyList(), 100))
    }

    @Test
    fun aHandleDraggedTakesTheLineUnderItOrTheNearestPastEitherEndButNeverADeletedOne() {
        val drawn = listOf(DrawnLine(3, -10, 20), DrawnLine(4, 20, 50), DrawnLine(5, 50, 50), DrawnLine(6, 50, 80))
        assertEquals(3, lineUnder(-50, drawn))
        assertEquals(3, lineUnder(19, drawn))
        assertEquals(4, lineUnder(20, drawn))
        assertEquals(6, lineUnder(50, drawn))
        assertEquals(6, lineUnder(500, drawn))
        assertNull(lineUnder(10, listOf(DrawnLine(5, 50, 50))))
        assertNull(lineUnder(10, emptyList()))
    }

    @Test
    fun aHandleNearAnEdgeScrollsThatWayFasterNearerTheEdge() {
        assertEquals(0f, edgeScroll(50f, 100f, 10f, 8f), 0f)
        assertEquals(0f, edgeScroll(10f, 100f, 10f, 8f), 0f)
        assertEquals(-4f, edgeScroll(5f, 100f, 10f, 8f), 0.001f)
        assertEquals(-8f, edgeScroll(-30f, 100f, 10f, 8f), 0f)
        assertEquals(4f, edgeScroll(95f, 100f, 10f, 8f), 0.001f)
        assertEquals(8f, edgeScroll(130f, 100f, 10f, 8f), 0f)
        assertEquals(0f, edgeScroll(5f, 100f, 0f, 8f), 0f)
    }
}
