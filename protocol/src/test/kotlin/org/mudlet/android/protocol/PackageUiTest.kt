package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PackageUiTest {
    private fun ScriptWindow.by(vararg packages: String, mobile: Boolean = false) = copy(packages = packages.toList(), mobile = mobile)

    private fun names(state: ScriptWindows) = state.windows.map { it.name }

    private fun area(textX: Int = 0, textY: Int = 0, textW: Int = 800, textH: Int = 600, vararg looks: LookSetters) = EngineMessage.MainArea(800, 600, 80, 30, textX, textY, textW, textH, looks = looks.toList())

    private fun border(look: ProfileLook, vararg packages: String, mobile: Boolean = false) = LookSetters(look, packages.toList(), mobile)

    @Test
    fun anUndeclaredPackagesWindowsAreLeftOutAndTheRestDrawn() {
        val state = ScriptWindows(
            windows = listOf(
                window("hp", WindowKind.LABEL).by("GUI"),
                window("chat").by("Chat", mobile = true),
                window("mine", WindowKind.LABEL),
                window("map", WindowKind.MAPPER),
                // No package made it, so none could show it
                window("odd", WindowKind.LABEL).copy(mobile = false),
            ),
        )
        assertEquals(listOf("chat", "mine", "map", "odd"), names(state.drawn(emptySet())))
    }

    @Test
    fun nothingLeftOutIsTheSameState() {
        val state = ScriptWindows(windows = listOf(window("chat").by("Chat", mobile = true), window("mine")), mainArea = area())
        assertSame(state, state.drawn(emptySet()))
    }

    @Test
    fun thePlayerShowsAPackageAnyway() {
        val state = ScriptWindows(windows = listOf(window("hp", WindowKind.LABEL).by("GUI"), window("both", WindowKind.LABEL).by("GUI", "Lib")))
        assertEquals(listOf("hp", "both"), names(state.drawn(setOf("GUI"))))
        // One of the packages that made it is enough
        assertEquals(listOf("both"), names(state.drawn(setOf("Lib"))))
    }

    @Test
    fun whatAWindowLeftOutHoldsGoesWithIt() {
        val state = ScriptWindows(
            windows = listOf(
                window("frame", WindowKind.USER_WINDOW).by("GUI"),
                window("inner", WindowKind.LABEL, parent = "frame", parentKind = WindowKind.USER_WINDOW).by("GUI"),
                // The profile's own, put in the package's window
                window("mine", WindowKind.LABEL, parent = "inner", parentKind = WindowKind.LABEL),
                window("frame", WindowKind.COMMAND_LINE, parent = "frame", parentKind = WindowKind.USER_WINDOW).by("GUI"),
                window("other", WindowKind.LABEL),
            ),
        )
        assertEquals(listOf("other"), names(state.drawn(emptySet())))
    }

    @Test
    fun aMapperOrAWindowDrawnForItsOwnPackageKeepsWhatHoldsIt() {
        val state = ScriptWindows(
            windows = listOf(
                window("box", WindowKind.SCROLL_BOX).by("GUI"),
                window("pane", WindowKind.LABEL, parent = "box", parentKind = WindowKind.SCROLL_BOX).by("GUI"),
                window("map", WindowKind.MAPPER, parent = "pane", parentKind = WindowKind.LABEL),
                window("deco", WindowKind.LABEL, parent = "box", parentKind = WindowKind.SCROLL_BOX).by("GUI"),
                window("holder", WindowKind.USER_WINDOW).by("GUI"),
                window("gauge", WindowKind.LABEL, parent = "holder", parentKind = WindowKind.USER_WINDOW).by("Gauges", mobile = true),
                window("shell", WindowKind.USER_WINDOW).by("GUI"),
                // The profile's own keeps nothing: code compiled from a string is the profile's too
                window("mine", WindowKind.LABEL, parent = "shell", parentKind = WindowKind.USER_WINDOW),
            ),
        )
        assertEquals(listOf("box", "pane", "map", "holder", "gauge"), names(state.drawn(emptySet())))
    }

    @Test
    fun theUndeclaredUiNamesThePackagesOfWhatIsLeftOut() {
        val state = ScriptWindows(
            windows = listOf(window("hp", WindowKind.LABEL).by("GUI", "Lib"), window("chat").by("Chat", mobile = true), window("mine")),
            mainArea = area(textY = 40, textH = 560, looks = arrayOf(border(ProfileLook.BORDER_TOP, "Frame"), border(ProfileLook.APP_STYLE_SHEET, "Theme", mobile = true))),
        )
        assertEquals(setOf("GUI", "Lib", "Frame"), state.undeclaredUi)
        assertEquals(emptySet<String>(), ScriptWindows().undeclaredUi)
    }

    @Test
    fun aBorderLeftOutGivesItsStripToTheConsole() {
        val looks = arrayOf(
            border(ProfileLook.BORDER_TOP, "GUI"),
            border(ProfileLook.BORDER_BOTTOM, "GUI"),
            border(ProfileLook.BORDER_LEFT, "GUI"),
            border(ProfileLook.BORDER_RIGHT, "GUI"),
        )
        val state = ScriptWindows(mainArea = area(100, 40, 600, 500, *looks), charWidth = CharWidth(8.0, 9.0))
        val drawn = state.drawn(emptySet())
        assertEquals(area(0, 0, 800, 600, *looks), drawn.mainArea)
        // What the layout scales Mudlet's pixels by is the engine's
        assertEquals(state.charWidth, drawn.charWidth)
        assertSame(state, state.drawn(setOf("GUI")))
    }

    @Test
    fun aDeclaredBorderOrOneThePlayerShowsStays() {
        val looks = arrayOf(border(ProfileLook.BORDER_TOP, "GUI", mobile = true), border(ProfileLook.BORDER_LEFT, "Frame"), border(ProfileLook.BORDER_RIGHT, "Lib"))
        val state = ScriptWindows(mainArea = area(100, 40, 600, 560, *looks))
        assertEquals(area(0, 40, 700, 560, *looks), state.drawn(setOf("Lib")).mainArea)
    }

    @Test
    fun aBorderLeftOutStopsAtAWindowDrawnInIt() {
        val looks = arrayOf(border(ProfileLook.BORDER_TOP, "GUI"), border(ProfileLook.BORDER_RIGHT, "GUI"))
        val state = ScriptWindows(
            windows = listOf(
                window("gauges", WindowKind.LABEL, x = 0, y = 0, w = 300, h = 25).by("Gauges", mobile = true),
                window("frame", WindowKind.LABEL, x = 0, y = 25, w = 800, h = 15).by("GUI"),
                window("side", WindowKind.MAPPER, x = 750, y = 100, w = 50, h = 50),
            ),
            mainArea = area(0, 40, 700, 560, *looks),
        )
        val drawn = state.drawn(emptySet())
        assertEquals(listOf("gauges", "side"), names(drawn))
        assertEquals(area(0, 25, 750, 575, *looks), drawn.mainArea)
    }

    @Test
    fun theLayoutOfWhatIsDrawnLeavesOutWhatIsLeftOut() {
        val state = ScriptWindows(
            windows = listOf(
                window("top", WindowKind.LABEL, x = 0, y = 0, w = 800, h = 40).by("GUI"),
                window("chat", x = 400, y = 100, w = 300, h = 200).by("Chat", mobile = true),
            ),
            mainArea = area(0, 40, 800, 560, border(ProfileLook.BORDER_TOP, "GUI")),
            charWidth = CharWidth(9.5, 10.0),
        )
        val areaPx = WindowLayout.Area(800f, 1200f, fitted = false, appCharWidth = 20f, headerHeight = 40f, selectedPage = null)
        val asShown = WindowLayout.of(state, areaPx)
        val drawn = WindowLayout.of(state.drawn(emptySet()), areaPx)
        assertEquals(listOf("top", "chat"), asShown.windows.map { it.window.name })
        assertEquals(listOf("chat"), drawn.windows.map { it.window.name })
        // Stacked, the top border's band goes to the console; Mudlet is told the same size either way
        assertEquals(0f, drawn.console.y)
        assertEquals(asShown.mainWindowSize, drawn.mainWindowSize)
    }
}
