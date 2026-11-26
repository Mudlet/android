package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The game's text size: from 6 to 32, 14 at first, kept under the name it has always had. */
class TextSizeSettingTest {
    // Settings is one object for the whole run, so a size set here would outlast the test
    @After
    fun resetTextSize() = Settings.textSize.reset()

    @Test
    fun theTextGoesDownToSixAndNoFurther() {
        val size = Settings.textSize
        size.set(6)
        assertEquals(6, size.value)
        size.set(5)
        assertEquals("a pinch past the smallest size stops there", 6, size.value)
        assertEquals(6, size.range.first)
    }

    @Test
    fun theLargestAndTheFirstSizeStand() {
        val size = Settings.textSize
        assertEquals(14, size.default)
        size.set(33)
        assertEquals(32, size.value)
        // A size a player already has, from 8 on, is kept as it is
        for (kept in 8..32) {
            size.set(kept)
            assertEquals(kept, size.value)
        }
    }

    @Test
    fun theStoredNameIsUnchanged() = assertEquals("textSizeSp", Settings.textSize.key)
}
