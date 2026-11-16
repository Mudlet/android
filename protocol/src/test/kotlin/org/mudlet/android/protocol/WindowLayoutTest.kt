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

    @Test
    fun aMapInTheMainAreaIsPlacedAsAnyWindowAndADockedOneIsAPage() {
        val main = mainArea(800, 500, 100, 25, 0, 0, 600, 500)
        val geyser = window("mapper", WindowKind.MAPPER, x = 600, y = 0, w = 200, h = 250)
        val fitted = WindowLayout.of(state(main, geyser), area(1600f, 1000f, fitted = true))
        assertEquals(PxRect(1200f, 0f, 400f, 500f), fitted.rectOf("mapper"))
        assertTrue(fitted.pages.isEmpty())

        // openMapWidget()'s, a dock the size of the main window, in either layout
        val docked = window("mapper", WindowKind.MAPPER, w = 800, h = 500).copy(inDock = true)
        for (isFitted in listOf(true, false)) {
            val layout = WindowLayout.of(state(main, docked), area(1600f, 1000f, isFitted, selectedPage = docked.key))
            assertTrue(layout.windows.isEmpty())
            assertEquals(listOf(docked), layout.pages)
            assertEquals(docked, layout.paneWindows.single().window)
        }
        // Hidden, as by closeMapWidget(), it is no page
        assertTrue(WindowLayout.of(state(main, docked.copy(shown = false)), area(1600f, 1000f, fitted = true)).pages.isEmpty())
    }

    @Test
    fun stackedAMapBesideTheTextIsAPageAsAConsoleThereIs() {
        val layout = WindowLayout.of(
            state(mainArea(1000, 500, 100, 25, 100, 50, 800, 400), window("mapper", WindowKind.MAPPER, x = 900, y = 50, w = 100, h = 400)),
            area(800f, 1600f, fitted = false),
        )
        assertEquals(listOf("mapper"), layout.pages.map { it.name })
        assertTrue(layout.windows.isEmpty())
    }

    // A titled MXP frame at [y] in the main area, 200 by 270 below its tab header, its pages "tabs" (its own console),
    // "chat" and "log", with the one at [current] in front
    private fun frameWithTabs(current: Int, x: Int = 0, y: Int = 30): List<ScriptWindow> {
        val names = listOf("tabs", "chat", "log")
        val tabs = ScriptWindow.FrameTabs(names.map { ScriptWindow.FrameTabs.Page(it, it.replaceFirstChar(Char::uppercase)) }, current)
        return names.mapIndexed { i, name ->
            window(name, WindowKind.MXP_FRAME, x = x, y = y, w = 200, h = 270, shown = i == current).let { if (i == 0) it.copy(frameTabs = tabs) else it }
        }
    }

    @Test
    fun anMxpFramesTabsGoAboveThePageInFrontAtLeastAHeaderTallAndInsideTheArea() {
        val main = mainArea(800, 500, 100, 25, 200, 0, 600, 500)
        val low = WindowLayout.of(state(main, *frameWithTabs(current = 1, y = 230).toTypedArray()), area(1600f, 1000f, fitted = true))
        // Only the page in front is drawn, the header frame not among them
        assertEquals(listOf("chat"), low.windows.map { it.window.name })
        assertEquals(PxRect(0f, 460f, 400f, 540f), low.rectOf("chat"))
        val strip = low.frameTabs.single()
        assertEquals("tabs", strip.frame.name)
        assertEquals(listOf("tabs", "chat", "log"), strip.tabs.pages.map { it.name })
        assertEquals(1, strip.tabs.current)
        // Mudlet's 30 pixels are 60 here, less than a header's 100
        assertEquals(PxRect(0f, 360f, 400f, HEADER), strip.rect)
        // With a header less than them, they are as tall as Mudlet's scaled
        val scaled = WindowLayout.of(state(main, *frameWithTabs(current = 1, y = 230).toTypedArray()), WindowLayout.Area(1600f, 1000f, true, 16f, 40f, null))
        assertEquals(PxRect(0f, 400f, 400f, 60f), scaled.frameTabs.single().rect)
        // At the area's top they go over the page's top rather than above the area
        val high = WindowLayout.of(state(main, *frameWithTabs(current = 0).toTypedArray()), area(1600f, 1000f, fitted = true))
        assertEquals(listOf("tabs"), high.windows.map { it.window.name })
        assertEquals(PxRect(0f, 0f, 400f, HEADER), high.frameTabs.single().rect)
        // A frame with no tabs has none drawn
        assertTrue(WindowLayout.of(state(main, window("side", WindowKind.MXP_FRAME)), area(1600f, 1000f, fitted = true)).frameTabs.isEmpty())
    }

    @Test
    fun stackedAPageInFrontOpensBelowItsTabsAndTheOneBroughtForwardStaysOpen() {
        // The frame in the left border, so a page
        val main = mainArea(1000, 500, 100, 25, 300, 50, 600, 400)
        val chat = WindowKey("chat", WindowKind.MXP_FRAME)
        val layout = WindowLayout.of(state(main, *frameWithTabs(current = 1, y = 80).toTypedArray()), area(800f, 1600f, fitted = false, selectedPage = chat))
        assertEquals(listOf("chat"), layout.pages.map { it.name })
        assertEquals(chat, layout.selectedPage)
        assertTrue(layout.frameTabs.isEmpty())
        assertEquals(PxRect(0f, 40f, 800f, 1520f), layout.pane)
        // The tabs at the top of the pane below its header, the page below them
        val strip = layout.paneFrameTabs.single()
        assertEquals(PxRect(0f, 140f, 800f, HEADER), strip.rect)
        assertEquals("tabs", strip.frame.name)
        assertEquals(PxRect(0f, 240f, 800f, 1320f), layout.paneWindows.single().rect)

        // "log" brought forward: still open, as the page in front
        val forward = WindowLayout.of(state(main, *frameWithTabs(current = 2, y = 80).toTypedArray()), area(800f, 1600f, fitted = false, selectedPage = chat))
        assertEquals(listOf("log"), forward.pages.map { it.name })
        assertEquals(WindowKey("log", WindowKind.MXP_FRAME), forward.selectedPage)
        assertEquals("log", forward.paneWindows.single().window.name)
        assertEquals(2, forward.paneFrameTabs.single().tabs.current)

        // A page that is gone, and no other page of tabs, is open no more
        val gone = WindowLayout.of(state(main), area(800f, 1600f, fitted = false, selectedPage = chat))
        assertNull(gone.selectedPage)
        assertNull(gone.pane)
        assertTrue(gone.paneFrameTabs.isEmpty())
    }

    // A titled MXP frame "inner" opened in the frame "outer", its pages "inner" (its own console) and "innerPage", the
    // latter in front, each at [y] in outer, 200 by [h] below the tabs
    private fun nestedFrameWithTabs(y: Int, h: Int): List<ScriptWindow> {
        val tabs = ScriptWindow.FrameTabs(listOf(ScriptWindow.FrameTabs.Page("inner", "Inner"), ScriptWindow.FrameTabs.Page("innerPage", "Page")), 1)
        return listOf("inner", "innerPage").mapIndexed { i, name ->
            window(name, WindowKind.MXP_FRAME, x = 0, y = y, w = 200, h = h, parent = "outer", parentKind = WindowKind.MXP_FRAME, shown = i == 1)
                .let { if (i == 0) it.copy(frameTabs = tabs) else it }
        }
    }

    @Test
    fun theTabsOfAFrameOpenedInAnotherGoAboveItsPageInsideThatFrameFittedOrInThePane() {
        // Fitted, outer in the main area's left border, 2 screen pixels to each of Mudlet's: the tabs may go above the
        // page, inside outer
        val main = mainArea(800, 500, 100, 25, 200, 0, 600, 500)
        val outer = window("outer", WindowKind.MXP_FRAME, x = 0, y = 0, w = 200, h = 500)
        val fitted = WindowLayout.of(state(main, outer, *nestedFrameWithTabs(y = 230, h = 270).toTypedArray()), area(1600f, 1000f, fitted = true))
        assertEquals(listOf("outer", "innerPage"), fitted.windows.map { it.window.name })
        assertEquals(PxRect(0f, 460f, 400f, 540f), fitted.rectOf("innerPage"))
        val strip = fitted.frameTabs.single()
        assertEquals("inner", strip.frame.name)
        assertEquals(PxRect(0f, 360f, 400f, HEADER), strip.rect)

        // Stacked, outer is a page, its frame's tabs above its page in the pane: 4 by 5 screen pixels to each of outer's
        val stacked = WindowLayout.of(
            state(mainArea(1000, 500, 100, 25, 300, 50, 600, 400), outer.copy(y = 80, h = 284), *nestedFrameWithTabs(y = 130, h = 140).toTypedArray()),
            area(800f, 1600f, fitted = false, selectedPage = outer.key),
        )
        assertEquals(listOf("outer"), stacked.pages.map { it.name })
        assertTrue(stacked.frameTabs.isEmpty())
        assertEquals(listOf("outer", "innerPage"), stacked.paneWindows.map { it.window.name })
        assertEquals(PxRect(0f, 790f, 800f, 700f), stacked.paneWindows[1].rect)
        // Mudlet's 30 pixels are 150 here
        val inPane = stacked.paneFrameTabs.single()
        assertEquals("inner", inPane.frame.name)
        assertEquals(PxRect(0f, 640f, 800f, 150f), inPane.rect)
    }

    private companion object {
        const val HEADER = 100f
    }
}
