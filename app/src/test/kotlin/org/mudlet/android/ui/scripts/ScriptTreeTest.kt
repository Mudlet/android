package org.mudlet.android.ui.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType

/** The script library's tree, search and the detail page's edits, which need no engine */
class ScriptTreeTest {
    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false) = ScriptItemNode(id, parent, name, folder, true, true, "", "")

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
        assertNull(ScriptItemKey.decode("timer/1"))
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
}
