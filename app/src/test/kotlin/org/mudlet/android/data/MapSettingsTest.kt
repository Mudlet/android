package org.mudlet.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Map page's settings as Mudlet's getConfig() gives them and setConfig() takes them */
class MapSettingsTest {
    @Test
    fun aLinesThicknessGoesToMudletAsFiftyOverIt() {
        assertEquals(10.0, Settings.mapExitSize.mudletDefaultToMudlet())
        assertEquals(50.0, Settings.mapRoomBorderSize.toMudlet(1))
        assertEquals(50.0 / 3, Settings.mapRoomBorderSize.toMudlet(3))
        assertEquals(50.0 / 11, Settings.mapExitSize.toMudlet(11))
    }

    @Test
    fun aLinesThicknessComesBackAsDesktopsPreferencesRoundIt() {
        val exits = Settings.mapExitSize
        assertEquals(5, exits.fromMudlet(10))
        // The profile keeps 50 / 3 to one decimal place
        assertEquals(3, exits.fromMudlet(16.7))
        assertEquals(4, exits.fromMudlet(12.5))
        assertEquals(11, exits.fromMudlet(1.0))
        assertEquals(1, exits.fromMudlet(100.0))
        for (bad in listOf<Any>(0, -2.0, Double.NaN, Double.POSITIVE_INFINITY, "10")) {
            assertNull("$bad", exits.fromMudlet(bad))
        }
    }

    @Test
    fun theMarkerStylesAreMudletsNamesInItsOrder() {
        assertEquals(listOf("original", "redRing", "blueYellowRing", "customRing"), Settings.PlayerRoomStyle.entries.map { it.mudletValue })
        assertEquals(Settings.PlayerRoomStyle.CUSTOM_RING, Settings.playerRoomStyle.fromMudlet("customRing"))
        assertNull(Settings.playerRoomStyle.fromMudlet("square"))
    }

    @Test
    fun theMapColoursAreDesktopMudletsByTheirKeys() {
        val keys = Settings.mapColours.map { it.key }
        assertEquals(23, keys.toSet().size)
        assertEquals(Settings.ansiColours.map { "map" + it.key.replaceFirstChar(Char::uppercaseChar) }, keys.drop(7))
        assertEquals("#800000", Settings.mapColours.first { it.key == "mapAnsiRed" }.mudletDefaultToMudlet())
        assertEquals("#d3d3d3", Settings.mapGridColour.mudletDefaultToMudlet())
        assertEquals("#c0c0c0", Settings.mapLinkColour.mudletDefaultToMudlet())
    }
}
