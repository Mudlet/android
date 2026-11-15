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
