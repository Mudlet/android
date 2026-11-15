package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A window for these tests and [WindowLayoutTest]: a shown mini console in the main window's area, unless told. */
fun window(
    name: String,
    kind: WindowKind = WindowKind.MINI_CONSOLE,
    x: Int = 0,
    y: Int = 0,
    w: Int = 100,
    h: Int = 100,
    parent: String = "",
    parentKind: WindowKind? = null,
    shown: Boolean = true,
    stack: Int = 0,
    dock: ScriptWindow.Dock? = null,
) = ScriptWindow(
    name = name,
    kind = kind,
    parent = parent,
    parentKind = parentKind,
    x = x,
    y = y,
    w = w,
    h = h,
    visible = shown,
    shown = shown,
    stack = stack,
    bg = -1,
    bgAlpha = 255,
    fontFamily = null,
    fontSize = 0.0,
    inDock = dock != null,
    dock = dock,
    console = if (kind in ScriptWindows.CONSOLE_KINDS) ScriptWindow.Console(null, 80, 20, false, false) else null,
)

/** A label's content with [text] and nothing else set. */
fun label(name: String, text: String = "", styleSheet: String = "", accessibleButton: Boolean = false) = LabelContent(
    name = name,
    text = text,
    image = "",
    svg = false,
    svgTint = -1,
    svgTintAlpha = 255,
    svgRotation = 0.0,
    svgShearX = 0.0,
    svgShearY = 0.0,
    movie = "",
    moviePaused = false,
    movieFrame = -1,
    movieSpeed = 100,
    movieW = null,
    movieH = null,
    toolTip = "",
    clickThrough = false,
    accessibleName = "",
    accessibleButton = accessibleButton,
    styleSheet = styleSheet,
)

fun text(window: String, vararg rows: String, replyTo: Any? = null) = EngineMessage.WindowText(window, rows.size, 0, rows.map { WindowRow(it, IntArray(0), false, false) }, replyTo)

fun mainArea(w: Int, h: Int, columns: Int, rows: Int, textX: Int, textY: Int, textW: Int, textH: Int) = EngineMessage.MainArea(w, h, columns, rows, textX, textY, textW, textH)

class ScriptWindowsTest {
    private fun windows(vararg windows: ScriptWindow, full: Boolean = false, removed: List<WindowKey> = emptyList()) = EngineMessage.Windows(full, windows.toList(), removed)

    @Test
    fun aChangeReplacesItsWindowInPlaceAndANewOneComesLast() {
        val state = ScriptWindows()
            .after(windows(window("a"), window("b"), full = true))
            .after(windows(window("a", x = 5), window("c")))
        assertEquals(listOf("a", "b", "c"), state.windows.map { it.name })
        assertEquals(5, state.windows[0].x)
    }

    @Test
    fun aWindowIsKnownByItsNameAndKind() {
        val line = window("chat", WindowKind.COMMAND_LINE, parent = "chat", parentKind = WindowKind.MINI_CONSOLE)
        val state = ScriptWindows().after(windows(window("chat"), line))
        assertEquals(2, state.windows.size)
        assertEquals(line, state.commandLineOf(state.windows[0]))
        val hidden = state.after(windows(line.copy(shown = false)))
        assertNull(hidden.commandLineOf(hidden.windows[0]))
        val gone = state.after(windows(removed = listOf(WindowKey("chat", WindowKind.COMMAND_LINE))))
        assertEquals(listOf(WindowKind.MINI_CONSOLE), gone.windows.map { it.kind })
        assertNull(gone.commandLineOf(gone.windows[0]))
    }

    @Test
    fun fullReplacesEverythingAndDropsTheTextOfConsolesNoLongerThere() {
        val state = ScriptWindows()
            .after(windows(window("a"), window("b")))
            .after(text("a", "one"))
            .after(text("b", "two"))
            .after(windows(window("b"), window("c"), full = true))
        assertEquals(listOf("b", "c"), state.windows.map { it.name })
        assertEquals(setOf("b"), state.text.keys)
    }

    @Test
    fun aRemovedConsoleTakesItsTextButTextMayComeBeforeItsWindow() {
        val early = ScriptWindows().after(text("chat", "hello"))
        assertEquals("hello", early.text.getValue("chat").lines.single().text)
        val described = early.after(windows(window("other")))
        assertEquals(setOf("chat"), described.text.keys)
        val removed = described.after(windows(window("chat"))).after(windows(removed = listOf(WindowKey("chat", WindowKind.MINI_CONSOLE))))
        assertEquals(emptySet<String>(), removed.text.keys)
        assertEquals(listOf("other"), removed.windows.map { it.name })
    }

    @Test
    fun theLatestPushedRowsWinAndAnswersToGetWindowTextAreLeftAlone() {
        val state = ScriptWindows().after(text("chat", "one")).after(text("chat", "one", "two"))
        assertEquals(listOf("one", "two"), state.text.getValue("chat").lines.map { it.text })
        assertSame(state, state.after(text("chat", "old", replyTo = 7L)))
    }

    @Test
    fun aCommandLineTextWaitsUntilShown() {
        val state = ScriptWindows().after(EngineMessage.WindowCommandLine("chat", "say hi"))
        assertEquals(mapOf("chat" to "say hi"), state.commandLines)
        assertEquals(emptyMap<String, String>(), state.commandLineShown("chat").commandLines)
    }

    @Test
    fun aLabelsContentIsKeptUntilItGoesOrAFullListLeavesItOut() {
        val hp = window("hp", WindowKind.LABEL)
        val state = ScriptWindows()
            .after(windows(hp, window("mp", WindowKind.LABEL)))
            .after(EngineMessage.Label(label("hp", "100")))
            .after(EngineMessage.Label(label("mp", "50")))
            .after(EngineMessage.Label(label("hp", "90")))
        assertEquals(mapOf("hp" to "90", "mp" to "50"), state.labels.mapValues { it.value.text })
        // A console of the same name is another window
        val consoleGone = state.after(windows(removed = listOf(WindowKey("hp", WindowKind.MINI_CONSOLE))))
        assertEquals(setOf("hp", "mp"), consoleGone.labels.keys)
        assertEquals(setOf("mp"), state.after(windows(removed = listOf(hp.key))).labels.keys)
        assertEquals(setOf("hp"), state.after(windows(hp, full = true)).labels.keys)
    }

    @Test
    fun aGaugeIsItsThreeLabelsWithTheFrontsShareOfTheBackAsItsValue() {
        val back = window("hp_back", WindowKind.LABEL, x = 10, y = 10, w = 200, h = 20)
        val front = window("hp_front", WindowKind.LABEL, x = 10, y = 10, w = 50, h = 20)
        val text = window("hp_text", WindowKind.LABEL, x = 10, y = 10, w = 200, h = 20)
        val state = ScriptWindows().after(windows(back, front, text, window("other", WindowKind.LABEL)))
        val gauge = state.gauges.getValue("hp_back")
        assertEquals(mapOf(back.key to gauge, front.key to gauge, text.key to gauge), state.gaugeParts)
        assertEquals("hp", gauge.name)
        assertEquals(0.25f, gauge.value, 1e-6f)
        assertEquals(25, gauge.percent)
        assertEquals(setOf(back.key, front.key, text.key), gauge.parts)

        // Vertical, inset by the back's margins of 2 px: the height decides
        val vertical = Gauge.of(listOf(window("mp_back", WindowKind.LABEL, w = 24, h = 100), window("mp_front", WindowKind.LABEL, x = 2, y = 62, w = 20, h = 36)))
        assertEquals(0.36f, vertical.getValue("mp_back").value, 1e-6f)
        assertNull(vertical.getValue("mp_back").text)

        // A hidden front is empty; a front in another parent, or with no back, is no gauge
        assertEquals(0f, Gauge.of(listOf(back, front.copy(shown = false))).getValue("hp_back").value)
        assertEquals(emptyMap<String, Gauge>(), Gauge.of(listOf(back, front.copy(parent = "box", parentKind = WindowKind.LABEL))))
        assertEquals(emptyMap<String, Gauge>(), Gauge.of(listOf(front, text)))
        assertEquals(emptyMap<String, Gauge>(), Gauge.of(listOf(back.copy(kind = WindowKind.MINI_CONSOLE), front)))
    }

    @Test
    fun otherMessagesLeaveItAsItIs() {
        val state = ScriptWindows().after(windows(window("a")))
        assertSame(state, state.after(EngineMessage.Clear))
    }

    @Test
    fun theCharacterWidthNarrowsWithEachGridAndStartsAgainWhenTheFontChanges() {
        // 100 columns in 800 px: more than 800/101, at most 8
        var state = ScriptWindows().after(mainArea(0, 0, 100, 30, 0, 0, 800, 600))
        assertEquals(CharWidth(800 / 101.0, 8.0), state.charWidth)
        // 120 columns in 970 px: (970/121, 970/120] = (8.016, 8.083], which leaves (8.016, 8] empty: a new font
        state = state.after(mainArea(0, 0, 120, 30, 0, 0, 970, 600))
        assertEquals(CharWidth(970 / 121.0, 970 / 120.0), state.charWidth)
        // 110 columns in 885 px: (7.98, 8.045], narrowing it to (8.016, 8.045]
        state = state.after(mainArea(0, 0, 110, 30, 0, 0, 885, 600))
        assertEquals(CharWidth(970 / 121.0, 885 / 110.0), state.charWidth)
        assertEquals((970 / 121.0 + 885 / 110.0) / 2, state.charWidth!!.estimate, 1e-9)
    }

    @Test
    fun aGridAtMudletsFloorSaysNothingOfTheFont() {
        val state = ScriptWindows().after(mainArea(300, 200, CharWidth.MIN_COLUMNS, 10, 0, 0, 200, 200))
        assertNull(state.charWidth)
        assertEquals(300, state.mainArea!!.w)
    }
}
