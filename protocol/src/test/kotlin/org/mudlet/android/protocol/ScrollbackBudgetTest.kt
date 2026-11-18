package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The most lines a console keeps, from the device's memory. */
class ScrollbackBudgetTest {
    private val gib = 1L shl 30

    @Test
    fun aSixteenthOfTheDeviceHoldsFourGamesInWholeThousands() {
        // What Android reports for phones sold as 4, 8 and 12 GB: the kernel keeps some
        assertEquals(36_000, ScrollbackBudget.maxLines(3_700_000_000L, lowRamDevice = false))
        assertEquals(73_000, ScrollbackBudget.maxLines(7_500_000_000L, lowRamDevice = false))
        assertEquals(109_000, ScrollbackBudget.maxLines(11_200_000_000L, lowRamDevice = false))
        // Four games at their most hold a sixteenth of the device
        val lines = ScrollbackBudget.maxLines(8 * gib, lowRamDevice = false)
        assertTrue(lines * ScrollbackBudget.BYTES_PER_LINE * ScrollbackBudget.GAMES <= 8 * gib / 16)
    }

    @Test
    fun aLowRamDeviceGivesHalf() {
        assertEquals(10_000, ScrollbackBudget.maxLines(2 * gib, lowRamDevice = true))
        assertEquals(20_000, ScrollbackBudget.maxLines(2 * gib, lowRamDevice = false))
    }

    @Test
    fun neverBelowWhatTheScreenKeeps() {
        assertEquals(ScrollbackBudget.LEAST, ScrollbackBudget.maxLines(512L shl 20, lowRamDevice = true))
        assertEquals(ScrollbackBudget.LEAST, ScrollbackBudget.maxLines(0, lowRamDevice = false))
        assertEquals(ScrollbackBudget.LEAST, ScrollbackBudget.maxLines(-1, lowRamDevice = false))
    }

    @Test
    fun aHugeDeviceStaysAWholeNumber() {
        assertEquals(2_147_483_000, ScrollbackBudget.maxLines(Long.MAX_VALUE, lowRamDevice = false))
    }
}
