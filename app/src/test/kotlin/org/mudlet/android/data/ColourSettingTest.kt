package org.mudlet.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The colours as Mudlet's getConfig() gives them and setConfig() takes them */
class ColourSettingTest {
    private val red = Settings.ansiColours[1]

    @Test
    fun aColourGoesToMudletAsLowerCaseHex() {
        assertEquals("#800000", red.valueToMudlet())
        assertEquals("#0000ff", Settings.colourName(0xFF))
        assertEquals("#12ab34", Settings.colourName(0x7F12AB34))
    }

    @Test
    fun onlyAWholeHexColourComesBack() {
        assertEquals(0x12AB34, red.fromMudlet("#12AB34"))
        assertEquals(0x12AB34, red.fromMudlet("#12ab34"))
        for (bad in listOf("12ab34", "#12ab3", "#12ab345", "#12ab3g", "red", "")) {
            assertNull(bad, red.fromMudlet(bad))
        }
        assertNull(red.fromMudlet(0x12AB34))
    }

    @Test
    fun theColoursAreDesktopMudletsTwentyByItsKeys() {
        assertEquals(20, Settings.colours.map { it.key }.toSet().size)
        assertEquals("#c0c0c0", Settings.foregroundColour.mudletDefaultToMudlet())
        assertEquals("#717100", Settings.commandForegroundColour.mudletDefaultToMudlet())
        assertEquals("#ffffff", Settings.ansiColours.last().mudletDefaultToMudlet())
    }
}
