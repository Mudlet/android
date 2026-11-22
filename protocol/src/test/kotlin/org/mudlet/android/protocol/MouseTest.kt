package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseTest {
    @Test
    fun aNotchScrollsTheConsoleThreeRowsAsDesktopsWheelDoes() {
        val wheel = ConsoleWheel()
        assertEquals(3, wheel.rows(1f, shift = false, control = false, screenRows = ROWS))
        assertEquals(-3, wheel.rows(-1f, shift = false, control = false, screenRows = ROWS))
        assertEquals(6, wheel.rows(2f, shift = false, control = false, screenRows = ROWS))
    }

    @Test
    fun shiftScrollsARowANotchAndControlHalfTheScreen() {
        val wheel = ConsoleWheel()
        assertEquals(1, wheel.rows(1f, shift = true, control = false, screenRows = ROWS))
        // Half of 41 rows, rounded as Qt rounds
        assertEquals(-21, wheel.rows(-1f, shift = false, control = true, screenRows = ROWS))
        // Shift slows the scroll even with Control held, as on desktop
        assertEquals(1, wheel.rows(1f, shift = true, control = true, screenRows = ROWS))
    }

    @Test
    fun aSmoothWheelsSmallTurnsAddUpToTheRowsOfANotch() {
        val wheel = ConsoleWheel()
        val tenths = (1..10).sumOf { wheel.rows(0.1f, shift = false, control = false, screenRows = ROWS) }
        val upAgain = (1..10).sumOf { wheel.rows(-0.1f, shift = false, control = false, screenRows = ROWS) }
        assertEquals("ten tenths of a notch, as desktop keeps what a turn leaves", 3, tenths)
        assertEquals(-3, upAgain)
    }

    @Test
    fun aNotchAwayZoomsTheMapInBySevenPercentAndControlFiveNotches() {
        assertEquals(1.07, MapWheel.zoom(-1f, control = false), 1e-9)
        assertEquals(1 / 1.07, MapWheel.zoom(1f, control = false), 1e-9)
        assertEquals(Math.pow(1.07, 5.0), MapWheel.zoom(-1f, control = true), 1e-9)
        assertEquals(1.0, MapWheel.zoom(0f, control = false), 1e-9)
    }

    @Test
    fun theEngineIsToldOfTheTurnInEighthsOfADegreeAwayFromThePlayer() {
        assertEquals(120, MapWheel.wheelY(-1f, control = false))
        assertEquals(-240, MapWheel.wheelY(2f, control = false))
        assertEquals(-12, MapWheel.wheelY(0.1f, control = false))
        assertEquals(600, MapWheel.wheelY(-1f, control = true))
    }

    @Test
    fun aRightClickOnALinkRevealsASpoilerFirstThenOpensAMenuAndOffALinkTheLines() {
        assertEquals(RightClick.LINE_MENU, rightClick(onLink = false, link = null))
        assertEquals(RightClick.LINK_MENU, rightClick(onLink = true, link = link(menu = true)))
        // Revealed before anything else, even a disabled one, as desktop's console does
        assertEquals(RightClick.REVEAL, rightClick(onLink = true, link = link(menu = true, spoiler = true, disabled = true)))
        assertEquals(RightClick.LINK_MENU, rightClick(onLink = true, link = link(menu = true, spoiler = true, revealed = true)))
    }

    @Test
    fun aRightClickOnALinkWithoutAMenuOrDisabledOrNotYetAnsweredDoesNothing() {
        // Desktop's console shows neither the link's menu nor its own there
        assertEquals(RightClick.NOTHING, rightClick(onLink = true, link = link(menu = false)))
        assertEquals(RightClick.NOTHING, rightClick(onLink = true, link = link(menu = true, disabled = true)))
        assertEquals(RightClick.NOTHING, rightClick(onLink = true, link = null))
    }

    private fun link(menu: Boolean, spoiler: Boolean = false, revealed: Boolean = false, disabled: Boolean = false) = EngineMessage.Link(
        link = 1, window = null, tooltip = "", menu = menu, menuTitle = "", items = emptyList(), spoiler = spoiler, revealed = revealed,
        disabled = disabled, concealed = false, visited = false, selected = false, fg = null, bg = null, styles = null, replyTo = null,
    )

    private companion object {
        const val ROWS = 41
    }
}
