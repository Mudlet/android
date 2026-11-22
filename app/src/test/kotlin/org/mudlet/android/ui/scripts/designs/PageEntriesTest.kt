package org.mudlet.android.ui.scripts.designs

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.scripts.ScriptItemKey

class PageEntriesTest {
    private fun node(id: Int, parent: Int, folder: Boolean = false) = ScriptItemNode(id, parent, "n$id", folder, true, true, "", "", "")

    // At the top an item (1) that another (2) chains from, and a folder (3) with an item (4) and a folder (5) with an item (6) in it
    private val triggers = listOf(node(1, 0), node(2, 1), node(3, 0, folder = true), node(4, 3), node(5, 3, folder = true), node(6, 5))

    private fun List<PageEntry>.shown() = map {
        when (it) {
            is PageEntry.Heading -> "h${it.folder?.id ?: 0}:${it.path.map { p -> p.id }}:${it.count}" + if (it.collapsed) "-" else ""
            is PageEntry.Item -> "i${it.node.id}@${it.index}/${it.count}+${it.depth}"
        }
    }

    @Test
    fun eachFolderIsAHeadingWithTheItemsInItAndWhatTheyHold() {
        assertEquals(
            listOf("h0:[]:2", "i1@0/2+0", "i2@1/2+1", "h3:[3]:1", "i4@0/1+0", "h5:[3, 5]:1", "i6@0/1+0"),
            pageEntries(mapOf(ScriptItemKind.TRIGGER to triggers), listOf(ScriptItemKind.TRIGGER), emptySet()).shown(),
        )
    }

    @Test
    fun aCollapsedFolderHidesItsItemsAndTheFoldersInIt() {
        assertEquals(
            listOf("h0:[]:2", "i1@0/2+0", "i2@1/2+1", "h3:[3]:1-"),
            pageEntries(mapOf(ScriptItemKind.TRIGGER to triggers), listOf(ScriptItemKind.TRIGGER), setOf(ScriptItemKey(ScriptItemKind.TRIGGER, 3))).shown(),
        )
    }

    @Test
    fun aFolderInsideAnItemIsAHeadingAfterTheItems() {
        val timers = listOf(node(1, 0), node(2, 1, folder = true), node(3, 2), node(4, 0))
        assertEquals(
            listOf("h0:[]:2", "i1@0/2+0", "i4@1/2+0", "h2:[2]:1", "i3@0/1+0"),
            pageEntries(mapOf(ScriptItemKind.TIMER to timers), listOf(ScriptItemKind.TIMER), emptySet()).shown(),
        )
    }

    @Test
    fun onlyTheKindsAskedForAndListedShow() {
        val lists = mapOf(ScriptItemKind.TRIGGER to triggers, ScriptItemKind.ALIAS to listOf(node(1, 0)), ScriptItemKind.SCRIPT to null)
        assertEquals(
            listOf(ScriptItemKind.ALIAS, ScriptItemKind.ALIAS),
            pageEntries(lists, listOf(ScriptItemKind.ALIAS, ScriptItemKind.SCRIPT), emptySet()).map { (it as? PageEntry.Heading)?.kind ?: (it as PageEntry.Item).kind },
        )
    }

    @Test
    fun aLoopOfParentsListsEachItemOnce() {
        val loop = listOf(node(1, 0, folder = true), node(2, 1), node(3, 2), node(2, 3))
        assertEquals(listOf("h0:[]:0", "h1:[1]:2", "i2@0/2+0", "i3@1/2+1"), pageEntries(mapOf(ScriptItemKind.TRIGGER to loop), listOf(ScriptItemKind.TRIGGER), emptySet()).shown())
    }
}
