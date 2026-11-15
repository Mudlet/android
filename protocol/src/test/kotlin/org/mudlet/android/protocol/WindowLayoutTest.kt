package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    // Mudlet's characters 8 px wide (the estimate of (7.9, 8.1]) and the app's 16: 2 screen pixels per Mudlet pixel
    private val font = CharWidth(7.9, 8.1)

    private fun state(area: EngineMessage.MainArea?, vararg windows: ScriptWindow) = ScriptWindows(windows.toList(), mainArea = area, charWidth = font)

    private fun area(w: Float, h: Float, fitted: Boolean, selectedPage: WindowKey? = null) = WindowLayout.Area(w, h, fitted, 16f, HEADER, selectedPage)

    private fun WindowLayout.rectOf(name: String) = windows.single { it.window.name == name }.rect

    @Test
    fun autoIsFittedFrom600dp() {
        assertTrue(WindowLayout.fitted(LayoutMode.AUTO, 600f))
        assertFalse(WindowLayout.fitted(LayoutMode.AUTO, 599f))
        assertTrue(WindowLayout.fitted(LayoutMode.FITTED, 300f))
        assertFalse(WindowLayout.fitted(LayoutMode.STACKED, 900f))
    }

    @Test
    fun fittedDrawsTheMainAreaScaledWithTheConsoleInItsTextRectangle() {
        val layout = WindowLayout.of(
            state(mainArea(800, 500, 75, 25, 100, 50, 600, 400), window("chat", x = 0, y = 0, w = 240, h = 200)),
            area(1600f, 1000f, fitted = true),
        )
        assertEquals(2.0, layout.scale!!, 1e-9)
        assertEquals(MudletSize(800, 500), layout.mainWindowSize)
        assertEquals(PxRect(200f, 100f, 1200f, 800f), layout.console)
        assertEquals(PxRect(0f, 0f, 480f, 400f), layout.rectOf("chat"))
        assertTrue(layout.pages.isEmpty())
    }

    @Test
    fun fittedDocksTakeAQuarterEachUpToHalfAndTheRestArePages() {
        val left = window("left", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "left", false, "Left"))
        val right = window("right", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "right", false, "Right"))
        val more = window("more", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "right", false, "More"))
        val floating = window("floating", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "right", true, "Floating"))
        val inLeft = window("inLeft", x = 0, y = 0, w = 50, h = 50, parent = "left", parentKind = WindowKind.USER_WINDOW)
        // The left dock as the engine has it after the placement below: 200 by 450
        val layout = WindowLayout.of(state(null, left.copy(w = 200, h = 450), right, more, floating, inLeft), area(1600f, 1000f, fitted = true))

        assertEquals(listOf("left", "right"), layout.dockHeaders.map { it.window.name })
        assertEquals(PxRect(0f, 0f, 400f, HEADER), layout.dockHeaders[0].rect)
        assertEquals(PxRect(0f, HEADER, 400f, 1000f - HEADER), layout.rectOf("left"))
        assertEquals(PxRect(1200f, HEADER, 400f, 1000f - HEADER), layout.rectOf("right"))
        // A window in a dock, in the dock's pixels: 2 screen pixels to each here
        assertEquals(PxRect(0f, HEADER, 100f, 100f), layout.rectOf("inLeft"))
        assertEquals(listOf("more", "floating"), layout.pages.map { it.name })
        // The main window has what is left, and without mainArea the console fills it
        assertEquals(PxRect(400f, 0f, 800f, 1000f), layout.console)
        assertEquals(MudletSize(400, 500), layout.mainWindowSize)
        assertEquals(Placement(0, 50, 200, 450, false), layout.placements["left"])
        assertEquals(Placement(600, 50, 200, 450, false), layout.placements["right"])
        // Pages fill the pane, over the main window's area, below its header
        assertEquals(Placement(200, 50, 400, 450, false), layout.placements["more"])
        assertEquals(Placement(200, 50, 400, 450, true), layout.placements["floating"])
    }

    @Test
    fun stackedPutsTheBordersAboveAndBelowTheWindowsOverTheTextOverItAndTheSidesInPages() {
        // A 1000 by 500 main window with borders of 100 left and right and 50 top and bottom
        val main = mainArea(1000, 500, 100, 25, 100, 50, 800, 400)
        val layout = WindowLayout.of(
            state(
                main,
                window("title", x = 0, y = 0, w = 1000, h = 50),
                window("status", x = 0, y = 450, w = 500, h = 50),
                window("map", x = 0, y = 50, w = 100, h = 400),
                window("gauge", WindowKind.LABEL, x = 900, y = 50, w = 100, h = 400),
                window("chat", x = 500, y = 50, w = 400, h = 100),
                window("log", x = 100, y = 350, w = 200, h = 100),
                window("side", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "right", false, "Side")),
            ),
            area(800f, 1600f, fitted = false, selectedPage = WindowKey("map", WindowKind.MINI_CONSOLE)),
        )
        // Mudlet gets the area as it would be in landscape
        assertEquals(MudletSize(800, 400), layout.mainWindowSize)
        // The borders at the width's scale, 0.8; the console fills the width between them
        assertEquals(PxRect(0f, 40f, 800f, 1520f), layout.console)
        assertEquals(PxRect(0f, 0f, 800f, 40f), layout.rectOf("title"))
        assertEquals(PxRect(0f, 1560f, 400f, 40f), layout.rectOf("status"))
        // Over the text at the text's scale, 1 here, from the console's top or bottom, whichever is nearer
        assertEquals(PxRect(400f, 40f, 400f, 100f), layout.rectOf("chat"))
        assertEquals(PxRect(0f, 1460f, 200f, 100f), layout.rectOf("log"))
        // The left border's console and the dock are pages; a label in a side border is not one
        assertEquals(listOf("map", "side"), layout.pages.map { it.name })
        assertTrue(layout.windows.none { it.window.name in setOf("map", "gauge", "side") })
        assertEquals(PxRect(0f, 40f, 800f, 1520f), layout.pane)
        assertEquals(listOf(PlacedWindow(layout.pages[0], PxRect(0f, 140f, 800f, 1420f), PxRect(0f, 140f, 800f, 1420f))), layout.paneWindows)
        assertEquals(Placement(0, 70, 400, 710, false), layout.placements["side"])
    }

    @Test
    fun stackedBordersTakeAtMostHalfTheHeight() {
        // Borders of 200 at the top and the bottom of a 1000 by 500 window, in an area 800 by 400: 0.8 would give them 320
        val layout = WindowLayout.of(state(mainArea(1000, 500, 75, 12, 0, 200, 1000, 100)), area(800f, 400f, fitted = false))
        assertEquals(PxRect(0f, 100f, 800f, 200f), layout.console)
    }

    @Test
    fun withoutMudletsFontNothingIsSentButTheWindowsAreDrawn() {
        val layout = WindowLayout.of(
            ScriptWindows(
                listOf(window("chat", w = 400, h = 250), window("side", WindowKind.USER_WINDOW, dock = ScriptWindow.Dock(true, "left", false, ""))),
                mainArea = mainArea(800, 500, 40, 10, 0, 0, 800, 500),
            ),
            area(1600f, 1000f, fitted = true),
        )
        assertNull(layout.scale)
        assertNull(layout.mainWindowSize)
        assertTrue(layout.placements.isEmpty())
        // The main window has 1200 of the 1600 beside the dock, for Mudlet's 800
        assertEquals(PxRect(400f, 0f, 600f, 500f), layout.rectOf("chat"))
    }

    @Test
    fun windowsAreCutToTheirAreaAndToTheirParentsAndHiddenOnesLeftOut() {
        val layout = WindowLayout.of(
            state(
                mainArea(800, 500, 100, 25, 0, 0, 800, 500),
                window("box", WindowKind.SCROLL_BOX, x = 700, y = 0, w = 200, h = 100),
                window("inside", x = 0, y = 0, w = 200, h = 50, parent = "box", parentKind = WindowKind.SCROLL_BOX),
                window("hidden", x = 0, y = 0, w = 10, h = 10, shown = false),
            ),
            area(1600f, 1000f, fitted = true),
        )
        assertEquals(PxRect(1400f, 0f, 200f, 200f), layout.rectOf("box"))
        val inside = layout.windows.single { it.window.name == "inside" }
        assertEquals(PxRect(1400f, 0f, 200f, 100f), inside.rect)
        assertEquals(400f, inside.full.w)
        assertEquals(PxRect(1400f, 0f, 400f, 100f), inside.full)
        assertTrue(layout.windows.none { it.window.name == "hidden" })
    }

    @Test
    fun parentsComeBeforeTheirChildrenAndSiblingsByStackWithoutConsolesOwnCommandLines() {
        val layout = WindowLayout.of(
            state(
                mainArea(800, 500, 100, 25, 0, 0, 800, 500),
                window("high", stack = 1),
                window("low", stack = 0),
                window("high", WindowKind.COMMAND_LINE, w = 0, h = 0, parent = "high", parentKind = WindowKind.MINI_CONSOLE),
                window("inLow", WindowKind.LABEL, w = 10, h = 10, parent = "low", parentKind = WindowKind.MINI_CONSOLE),
            ),
            area(1600f, 1000f, fitted = true),
        )
        assertEquals(listOf("low", "inLow", "high"), layout.windows.map { it.window.name })
    }

    private companion object {
        const val HEADER = 100f
    }
}
