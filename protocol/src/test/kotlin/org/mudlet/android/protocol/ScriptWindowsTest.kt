package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    liveRegion = LiveRegion.OFF,
    accessibleValue = null,
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
    fun aTextBoxsContentIsKeptAsTypedUntilItGoesOrAFullListLeavesItOut() {
        val notes = window("notes", WindowKind.TEXT_BOX)
        val state = ScriptWindows()
            .after(windows(notes, window("log", WindowKind.TEXT_BOX)))
            .after(EngineMessage.TextBox(TextBoxContent("notes", "first", false, "Notes", "", false)))
            .after(EngineMessage.TextBox(TextBoxContent("log", "", true, "", "", true)))
        // What the player typed is kept, as the engine does not send it back
        val typed = state.textBoxTyped("notes", "first draft", on = 1)
        assertEquals(mapOf("notes" to "first draft", "log" to ""), typed.textBoxes.mapValues { it.value.text })
        assertEquals("Notes", typed.textBoxes.getValue("notes").placeholder)
        // A box the engine has not described is not made up
        assertEquals(typed.textBoxes, typed.textBoxTyped("nope", "x", on = 1).textBoxes)
        // A script setting the text the box had before the player typed is a change
        val reset = typed.after(EngineMessage.TextBox(TextBoxContent("notes", "first", false, "Notes", "", false), typed = 1))
        assertEquals("first", reset.textBoxes.getValue("notes").text)
        // A label of the same name is another window
        assertEquals(setOf("notes", "log"), state.after(windows(removed = listOf(WindowKey("notes", WindowKind.LABEL)))).textBoxes.keys)
        assertEquals(setOf("log"), state.after(windows(removed = listOf(notes.key))).textBoxes.keys)
        assertEquals(setOf("notes"), state.after(windows(notes, full = true)).textBoxes.keys)
    }

    @Test
    fun aTextBoxsEditChangesOnlyWithTheEnginesTextAndOneSentBeforeThePlayersTypingKeepsIt() {
        fun box(text: String, placeholder: String = "Notes") = TextBoxContent("notes", text, false, placeholder, "", false)
        val state = ScriptWindows().after(windows(window("notes", WindowKind.TEXT_BOX))).after(EngineMessage.TextBox(box("first")))
        val first = state.textBoxEdits.getValue("notes")
        // The player's own text is no edit, and the engine's of the same text is none either
        val typed = state.textBoxTyped("notes", "abc", on = 1)
        assertEquals(mapOf("notes" to 1), typed.textBoxesTyped)
        assertEquals(first, typed.textBoxEdits["notes"])
        assertEquals(first, typed.after(EngineMessage.TextBox(box("abc", "Other"), typed = 1)).textBoxEdits["notes"])
        // Sent before the engine had the player's text: the rest of it is taken, the text not, and it is no edit
        val stale = typed.textBoxTyped("notes", "abcd", on = 1).after(EngineMessage.TextBox(box("abc", "Other"), typed = 1))
        assertEquals(box("abcd", "Other"), stale.textBoxes["notes"])
        assertEquals(first, stale.textBoxEdits["notes"])
        // A script's text after it is an edit, even the text the box had before the player typed
        val reset = stale.after(EngineMessage.TextBox(box("first"), typed = 2))
        assertEquals("first", reset.textBoxes.getValue("notes").text)
        assertNotEquals(first, reset.textBoxEdits.getValue("notes"))
        // A UI that attaches again on a new connection counts afresh, as the engine does, and takes the box it is sent whole
        val attached = stale.attached(2)
        assertEquals(emptyMap<String, Int>(), attached.textBoxesTyped)
        assertEquals("first", attached.after(EngineMessage.TextBox(box("first"))).textBoxes.getValue("notes").text)
        // One sent on the new connection before the UI sees it attach stays counted, as the engine counts it after its
        // hello: the box sent before the engine read it keeps the player's text, and is no edit
        val early = stale.textBoxTyped("notes", "abcde", on = 2).attached(2)
        assertEquals(mapOf("notes" to 1), early.textBoxesTyped)
        val crossed = early.after(EngineMessage.TextBox(box("abc")))
        assertEquals("abcde", crossed.textBoxes.getValue("notes").text)
        assertEquals(first, crossed.textBoxEdits["notes"])
        // One sent on the old connection counts on neither
        assertEquals(mapOf("notes" to 1), early.textBoxTyped("notes", "abcdef", on = 1).textBoxesTyped)
        // A new box's text is an edit; one removed has none
        assertNull(reset.after(windows(removed = listOf(WindowKey("notes", WindowKind.TEXT_BOX)))).textBoxEdits["notes"])
        assertNull(reset.after(windows(full = true)).textBoxEdits["notes"])
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
    fun aWindowsLinksAreKeptWhileItsTextHasThemOrUntilItGoes() {
        val state = ScriptWindows()
            .after(windows(window("chat"), window("side", WindowKind.USER_WINDOW)))
            .after(linkedText("chat", 1, 2))
            .after(linkedText("side", 1))
            .after(link(1, "chat", "go there"))
            .after(link(2, "chat", "stay"))
            .after(link(1, "side", "look here"))
        assertEquals(mapOf("chat" to mapOf(1 to "go there", 2 to "stay"), "side" to mapOf(1 to "look here")), state.links.tooltips())

        // New rows keep what was said of the links still in them, until the engine answers again, and drop the rest
        val newRows = state.after(linkedText("chat", 2, 3))
        assertEquals(mapOf("chat" to mapOf(2 to "stay"), "side" to mapOf(1 to "look here")), newRows.links.tooltips())
        assertEquals(mapOf(2 to "stay now"), newRows.after(link(2, "chat", "stay now")).links["chat"]?.mapValues { it.value.tooltip })
        assertEquals(mapOf("side" to mapOf(1 to "look here")), state.after(text("chat", "go", "on")).links.tooltips())
        // An answer to getWindowText changes nothing
        assertSame(state, state.after(text("chat", "old", replyTo = 3L)))
        assertEquals(setOf("side"), state.after(windows(removed = listOf(WindowKey("chat", WindowKind.MINI_CONSOLE)))).links.keys)
        assertEquals(setOf("chat"), state.after(windows(window("chat"), full = true)).links.keys)
        // A label of the same name is another window
        assertEquals(setOf("chat", "side"), state.after(windows(removed = listOf(WindowKey("chat", WindowKind.LABEL)))).links.keys)
    }

    @Test
    fun aLinkOfTheMainConsoleOrOfAConsoleWithNoTextIsNotKept() {
        val state = ScriptWindows().after(windows(window("chat"))).after(text("chat", "go"))
        assertSame(state, state.after(link(1, null, "the main console's")))
        assertSame(state, state.after(link(1, "gone", "no text")))
    }

    // A row of one character for each link of [ids], each its own run
    private fun linkedText(window: String, vararg ids: Int) = EngineMessage.WindowText(
        window,
        1,
        0,
        listOf(WindowRow("x".repeat(ids.size), ids.withIndex().flatMap { (i, id) -> listOf(i, 1, -1, -1, 0, id) }.toIntArray(), false, false)),
        null,
    )

    private fun link(id: Int, window: String?, tooltip: String) = EngineMessage.Link(id, window, tooltip, false, "", emptyList(), false, false, false, false, false, false, null, null, null, null)

    private fun Map<String, Map<Int, EngineMessage.Link>>.tooltips() = mapValues { (_, links) -> links.mapValues { it.value.tooltip } }

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
