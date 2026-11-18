package org.mudlet.android.ui.scripts

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType
import org.mudlet.android.ui.ansiColor

class ColorPatternTest {
    @Test
    fun aColourPatternIsWrittenAsMudletWritesIt() {
        assertEquals("ANSI_COLORS_F{1}_B{IGNORE}", ColorPattern(1, ColorPattern.ANY_COLOR).text)
        assertEquals("ANSI_COLORS_F{DEFAULT}_B{255}", ColorPattern(ColorPattern.DEFAULT_COLOR, 255).text)
        assertEquals("ANSI_COLORS_F{IGNORE}_B{0}", ColorPattern(ColorPattern.ANY_COLOR, 0).text)
        for (text in listOf("ANSI_COLORS_F{12}_B{DEFAULT}", "ANSI_COLORS_F{IGNORE}_B{232}")) {
            assertEquals(text, ColorPattern.parse(text).text)
        }
    }

    @Test
    fun bothSidesAnyIsNoPatternMudletRuns() {
        val none = ColorPattern(ColorPattern.ANY_COLOR, ColorPattern.ANY_COLOR)
        assertTrue(none.matchesNothing)
        assertEquals("", none.text)
        assertFalse(ColorPattern(ColorPattern.ANY_COLOR, ColorPattern.DEFAULT_COLOR).matchesNothing)
    }

    @Test
    fun aPatternTurnedIntoAColourPatternKeepsOnlyColoursAndLosesThemTurnedBack() {
        val words = TriggerPattern("You hit", TriggerPatternType.SUBSTRING)
        assertEquals(TriggerPattern("", TriggerPatternType.COLOR), words.withType(TriggerPatternType.COLOR))
        assertEquals(TriggerPattern("You hit", TriggerPatternType.REGEX), words.withType(TriggerPatternType.REGEX))
        // Typed by hand as a substring, it is a colour pattern's text already
        val typed = TriggerPattern("ANSI_COLORS_F{1}_B{IGNORE}", TriggerPatternType.SUBSTRING)
        assertEquals(TriggerPattern("ANSI_COLORS_F{1}_B{IGNORE}", TriggerPatternType.COLOR), typed.withType(TriggerPatternType.COLOR))

        val red = TriggerPattern("ANSI_COLORS_F{1}_B{IGNORE}", TriggerPatternType.COLOR)
        assertEquals(TriggerPattern("", TriggerPatternType.SUBSTRING), red.withType(TriggerPatternType.SUBSTRING))
        assertEquals(TriggerPattern("1", TriggerPatternType.SPACER), red.withType(TriggerPatternType.SPACER))
        assertEquals(red, red.withType(TriggerPatternType.COLOR))
        assertEquals(TriggerPattern("", TriggerPatternType.COLOR), TriggerPattern("3", TriggerPatternType.SPACER).withType(TriggerPatternType.COLOR))
    }

    @Test
    fun theColoursPastTheSixteenAreXtermsAsMudletMakesThem() {
        assertEquals(Color(0, 0, 0), ansiColor(16))
        assertEquals(Color(255, 255, 255), ansiColor(231))
        // 16 + 36 * 5 + 6 * 2 + 1: red at its top step, green at its second, blue at its first
        assertEquals(Color(255, 135, 95), ansiColor(209))
        assertEquals(Color(8, 8, 8), ansiColor(232))
        assertEquals(Color(238, 238, 238), ansiColor(255))
    }
}
