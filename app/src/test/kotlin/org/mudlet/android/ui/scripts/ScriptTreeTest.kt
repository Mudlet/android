package org.mudlet.android.ui.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.ScriptSearchField
import org.mudlet.android.protocol.ScriptSearchMatch
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType

/** The script library's tree, search and the detail page's edits, which need no engine */
class ScriptTreeTest {
    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false, summary: String = "", packageName: String = "") = ScriptItemNode(id, parent, name, folder, true, true, packageName, "", summary)

    // Combat/Hits/"You hit", Combat/"Flee", "Greet", as the engine lists them depth first
    private val triggers = listOf(
        node(1, 0, "Combat", folder = true),
        node(2, 1, "Hits", folder = true),
        node(3, 2, "You hit"),
        node(4, 1, "Flee"),
        node(5, 0, "Greet"),
    )

    @Test
    fun aKeyComesBackFromItsText() {
        val key = ScriptItemKey(ScriptItemKind.ALIAS, 42)
        assertEquals(key, ScriptItemKey.decode(key.encode()))
        assertEquals(ScriptItemKey(ScriptItemKind.TIMER, 1), ScriptItemKey.decode("timer/1"))
        assertNull(ScriptItemKey.decode("gauge/1"))
        assertNull(ScriptItemKey.decode("trigger/"))
        assertNull(ScriptItemKey.decode("trigger"))
    }

    @Test
    fun aFolderHasOnlyWhatIsDirectlyInIt() {
        assertEquals(listOf(1, 5), triggers.childrenOf(0).map { it.id })
        assertEquals(listOf(2, 4), triggers.childrenOf(1).map { it.id })
        assertEquals(emptyList<Int>(), triggers.childrenOf(3).map { it.id })
    }

    @Test
    fun theBreadcrumbGoesFromTheTopDown() {
        assertEquals(listOf("Combat", "Hits"), triggers.pathTo(2).map { it.name })
        assertEquals(emptyList<String>(), triggers.pathTo(0).map { it.name })
        assertEquals("a folder not listed has no path", emptyList<String>(), triggers.pathTo(99).map { it.name })
        val loop = listOf(node(1, 2, "A", folder = true), node(2, 1, "B", folder = true))
        assertEquals("parents that loop end", listOf("A", "B"), loop.pathTo(2).map { it.name })
    }

    @Test
    fun aSearchFindsNamesOfEveryKindWithWhereTheyLive() {
        val aliases = listOf(node(1, 0, "hit them"))
        val found = searchScripts("HIT", mapOf(ScriptItemKind.TRIGGER to triggers, ScriptItemKind.ALIAS to aliases))
        assertEquals(listOf("Hits", "You hit", "hit them"), found.map { it.item.name })
        assertEquals(listOf("Combat", "Hits"), found[1].folders.map { it.name })
        assertEquals(ScriptItemKind.ALIAS, found[2].kind)
        assertTrue(searchScripts("flee greet", mapOf(ScriptItemKind.TRIGGER to triggers)).isEmpty())
    }

    private fun place(id: Int, field: ScriptSearchField, index: Int = 0) = ScriptSearchMatch(ScriptItemKind.TRIGGER, id, "", field, index, "send(\"hp\")", 6)

    @Test
    fun aSearchAddsWhereTheEngineFoundTheTextToTheItemsItFound() {
        val lists = mapOf(ScriptItemKind.TRIGGER to triggers)
        val answer = EngineMessage.ScriptSearch("hp", false, false, listOf(place(4, ScriptSearchField.LUA, 2), place(3, ScriptSearchField.NAME), place(4, ScriptSearchField.LUA, 5)), false, 1L)
        val found = searchScripts("hp", lists, found = answer)
        assertEquals("in the list's order, not the engine's", listOf("You hit", "Flee"), found.map { it.item.name })
        assertEquals("a name is on its row already", emptyList<ScriptSearchMatch>(), found[0].places)
        assertEquals(listOf(2, 5), found[1].places.map { it.index })
        assertEquals(listOf("Combat"), found[1].folders.map { it.name })
    }

    @Test
    fun aSearchByTheEngineIsUsedOnlyForWhatItWasAskedAndTheOptionsLeaveTheNamesToIt() {
        val lists = mapOf(ScriptItemKind.TRIGGER to triggers)
        val answer = EngineMessage.ScriptSearch("hp", true, false, listOf(place(4, ScriptSearchField.COMMAND)), false, 1L)
        assertTrue("not for other text", searchScripts("hpx", lists, SearchOptions(caseSensitive = true), answer).isEmpty())
        assertTrue("not for other options", searchScripts("hp", lists, SearchOptions(), answer).isEmpty())
        assertEquals(listOf("Flee"), searchScripts("hp", lists, SearchOptions(caseSensitive = true), answer).map { it.item.name })
        assertTrue("with an option, only the engine knows what it finds", searchScripts("Greet", lists, SearchOptions(wholeWord = true)).isEmpty())
        assertEquals(listOf("Greet"), searchScripts("Greet", lists).map { it.item.name })
    }

    @Test
    fun aPatternMovesWithinTheList() {
        val patterns = listOf("a", "b", "c").map { TriggerPattern(it, TriggerPatternType.SUBSTRING) }
        assertEquals(listOf("b", "a", "c"), patterns.moved(0, 1).map { it.text })
        assertEquals(listOf("a", "c", "b"), patterns.moved(2, 1).map { it.text })
        assertEquals(patterns, patterns.moved(2, 3))
        assertEquals(patterns, patterns.moved(-1, 0))
    }

    @Test
    fun aColourPatternReadsAsMudletDecodesIt() {
        assertEquals(ColorPattern(1, ColorPattern.ANY_COLOR), ColorPattern.parse("ANSI_COLORS_F{1}_B{IGNORE}"))
        assertEquals(ColorPattern(ColorPattern.DEFAULT_COLOR, 255), ColorPattern.parse("ANSI_COLORS_F{DEFAULT}_B{255}"))
        assertEquals(ColorPattern(ColorPattern.ANY_COLOR, ColorPattern.ANY_COLOR), ColorPattern.parse("red"))
    }

    private val trigger = ScriptItemDetails.Trigger(
        listOf(TriggerPattern("You hit", TriggerPatternType.START_OF_LINE)),
        command = "",
        lineDelta = -1,
        matchAll = false,
        filter = false,
        fireLength = 0,
        sound = false,
        soundFile = "",
        highlight = false,
        highlightFg = null,
        highlightBg = null,
    )

    @Test
    fun matchAllKeepsItsLinesWithinWhatTheDesktopAllows() {
        assertEquals(0, trigger.withMatchAll(true).lineDelta)
        assertEquals(5, trigger.withMatchAll(true, 5).lineDelta)
        assertEquals(MAX_MATCH_LINES, trigger.withMatchAll(true, 5000).lineDelta)
        assertEquals(-1, trigger.copy(lineDelta = 5).withMatchAll(false).lineDelta)
        assertEquals("All again keeps the lines it had", 5, trigger.copy(lineDelta = 5).withMatchAll(true).lineDelta)
    }

    @Test
    fun aDraftDiffersOnlyInWhatThePageEdits() {
        val saved = ScriptItem(node(3, 2, "You hit"), "echo('hit')", trigger)
        assertFalse(saved.copy(node = saved.node.copy(enabled = false)).changedFrom(saved))
        assertTrue(saved.copy(node = saved.node.copy(name = "Hit")).changedFrom(saved))
        assertTrue(saved.copy(script = "").changedFrom(saved))
        assertTrue(saved.copy(details = trigger.copy(matchAll = true)).changedFrom(saved))
        assertTrue("nothing saved to compare with", saved.changedFrom(null))
    }

    @Test
    fun aNewSpacerSpansOneLine() {
        assertEquals("1", newPattern(TriggerPatternType.SPACER).text)
        assertEquals("", newPattern(TriggerPatternType.REGEX).text)
    }

    @Test
    fun aSearchFindsWhatShowsUnderAName() {
        val timers = listOf(node(1, 0, "Heal", summary = "00:00:02.500"))
        val keys = listOf(node(1, 0, "North", summary = "keypad + 8"))
        val found = searchScripts("keypad", mapOf(ScriptItemKind.TIMER to timers, ScriptItemKind.KEY to keys))
        assertEquals(listOf(ScriptItemKind.KEY to "North"), found.map { it.kind to it.item.name })
        assertEquals(listOf("Heal"), searchScripts("02.5", mapOf(ScriptItemKind.TIMER to timers)).map { it.item.name })
    }

    @Test
    fun aTimeSplitsIntoTheEditorsFourFields() {
        val time = 2 * 3_600_000 + 3 * 60_000 + 4 * 1_000 + 5
        assertEquals(listOf(2, 3, 4, 5), TimePart.entries.map { time.timePart(it) })
        assertEquals("only its own part changes", listOf(2, 59, 4, 5), TimePart.entries.map { time.withTimePart(TimePart.MINUTES, 59).timePart(it) })
        assertEquals(750, 0.withTimePart(TimePart.MILLISECONDS, 750))
        assertEquals("a part is held to its range", 23 * 3_600_000, 0.withTimePart(TimePart.HOURS, 99))
        assertEquals(0, 5_000.withTimePart(TimePart.SECONDS, -1))
        val most = TimePart.entries.fold(0) { millis, part -> millis.withTimePart(part, part.max) }
        assertEquals("the most the four fields give is the engine's, a millisecond under a day", 86_399_999, most)
    }

    @Test
    fun theKeysToPickAreNamedOrWritten() {
        assertEquals("F1", QtKeys.symbol(QtKeys.F1))
        assertEquals("F12", QtKeys.symbol(QtKeys.F1 + 11))
        assertNull("F13 is not offered", QtKeys.symbol(QtKeys.F1 + 12))
        assertEquals("8", QtKeys.symbol(0x38))
        assertEquals("A", QtKeys.symbol(0x41))
        assertEquals("+", QtKeys.symbol(0x2b))
        assertNull("a named key is given its name by the screen", QtKeys.symbol(QtKeys.ESCAPE))
        assertEquals("no key is offered twice", QtKeys.PICKABLE.size, QtKeys.PICKABLE.toSet().size)
        assertTrue("each key offered has a name or is written", QtKeys.PICKABLE.all { it in QtKeys.NAMED || QtKeys.symbol(it) != null })
        assertFalse("0 is no key, and has a choice of its own", 0 in QtKeys.PICKABLE)
    }

    @Test
    fun theKeysHeldAreBitsOfQtsModifiers() {
        val controlKeypad = 0x24000000
        assertEquals(listOf(KeyModifier.CONTROL, KeyModifier.KEYPAD), heldKeys(controlKeypad))
        assertEquals(emptyList<KeyModifier>(), heldKeys(0))
        assertEquals(0x26000000, withHeld(controlKeypad, KeyModifier.SHIFT, true))
        assertEquals(0x20000000, withHeld(controlKeypad, KeyModifier.CONTROL, false))
        assertEquals("holding one twice changes nothing", controlKeypad, withHeld(controlKeypad, KeyModifier.CONTROL, true))
        val groupSwitch = 0x40000000
        assertEquals("a bit the page has no chip for is kept", groupSwitch or 0x02000000, withHeld(groupSwitch, KeyModifier.SHIFT, true))
    }

    @Test
    fun aKeyTheAppCannotNameGoesByTheEnginesName() {
        fun key(name: String) = ScriptItemDetails.Key("", 0x01000020, 0x04000000, name)
        assertEquals("Shift", key("control + Shift").engineKeyName)
        assertEquals("Shift", key("Shift").engineKeyName)
        assertEquals("", key("").engineKeyName)
    }

    // A package's folder with a toolbar and its menu, then the player's toolbar with a button, and a button outside any
    private val buttons = listOf(
        node(1, 0, "ui-pack", folder = true, packageName = "ui-pack"),
        node(2, 1, "Bar", folder = true, packageName = "ui-pack"),
        node(3, 2, "More", folder = true, packageName = "ui-pack"),
        node(4, 0, "Mine", folder = true),
        node(5, 4, "Sword"),
        node(6, 4, "Spells", folder = true),
        node(7, 0, "Loose"),
    )

    @Test
    fun aButtonsFolderIsAToolbarAMenuOrAPackages() {
        fun part(id: Int) = buttons.buttonPart(buttons.first { it.id == id })
        assertEquals(ButtonPart.PACKAGE, part(1))
        assertEquals("a folder in a package's folder is a toolbar", ButtonPart.TOOLBAR, part(2))
        assertEquals(ButtonPart.MENU, part(3))
        assertEquals(ButtonPart.TOOLBAR, part(4))
        assertEquals(ButtonPart.BUTTON, part(5))
        assertEquals(ButtonPart.MENU, part(6))
        assertEquals(ButtonPart.BUTTON, part(7))
        assertEquals("until its folder is listed, a folder inside another is a menu", ButtonPart.MENU, emptyList<ScriptItemNode>().buttonPart(buttons[1]))
    }

    @Test
    fun theLibraryOffersWhatMudletTakesWhereItIs() {
        assertEquals(Adds.ITEM_AND_FOLDER, adds(ScriptItemKind.TRIGGER, null))
        assertEquals(Adds.ITEM_AND_FOLDER, adds(ScriptItemKind.TRIGGER, triggers[0]))
        assertEquals("only a folder is added to", Adds.NOTHING, adds(ScriptItemKind.TRIGGER, triggers[4]))
        assertEquals("the buttons' top level takes toolbars", Adds.TOOLBAR, adds(ScriptItemKind.BUTTON, null))
        assertEquals("and so does a package's folder", Adds.TOOLBAR, adds(ScriptItemKind.BUTTON, buttons[0]))
        assertEquals("a package's folder of triggers takes triggers", Adds.ITEM_AND_FOLDER, adds(ScriptItemKind.TRIGGER, buttons[0]))
        assertEquals(Adds.ITEM_AND_FOLDER, adds(ScriptItemKind.BUTTON, buttons[3]))
        assertEquals(Adds.NOTHING, adds(ScriptItemKind.BUTTON, buttons[4]))
    }

    @Test
    fun anItemWithOthersInItIsGoneIntoAsAFolderIs() {
        assertTrue(triggers[0].holdsItems(0))
        assertTrue("a trigger others chain from", triggers[4].holdsItems(2))
        assertFalse(triggers[4].holdsItems(0))
    }

    @Test
    fun aRowSaysTheOneThingMostWorthSaying() {
        val item = node(5, 0, "Greet", packageName = "pack")
        assertEquals(RowStatus.FromPackage("pack"), item.rowStatus(0))
        assertNull("the player's own, running", item.copy(packageName = "").rowStatus(0))
        assertEquals("it holds others", RowStatus.Holds(2), item.rowStatus(2))
        assertEquals(RowStatus.Idle, item.copy(active = false).rowStatus(2))
        assertEquals("off comes before idle", RowStatus.Off, item.copy(enabled = false, active = false).rowStatus(2))
        assertEquals("a folder says what it has, on or off", RowStatus.Holds(0), item.copy(folder = true, enabled = false).rowStatus(0))
        assertEquals("an error comes first", RowStatus.NotRunning("no time"), item.copy(folder = true, enabled = false, error = "no time").rowStatus(3))
    }
}
