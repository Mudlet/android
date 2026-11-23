package org.mudlet.android.ui.scripts.designs

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mudlet.android.protocol.ScriptItemNode

class TreeRowsTest {
    private fun node(id: Int, parent: Int, folder: Boolean = false) = ScriptItemNode(id, parent, "n$id", folder, true, true, "", "", "")

    // A folder (1) with an item (2) and a folder (3) in it, which has an item (4); a trigger (5) that another (6) chains from
    private val items = listOf(node(1, 0, folder = true), node(2, 1), node(3, 1, folder = true), node(4, 3), node(5, 0), node(6, 5))

    private fun List<TreeRow>.shown() = map { Triple(it.node.id, it.depth, it.children) }

    @Test
    fun closedTheTopLevelShowsAloneWithWhatEachHolds() {
        assertEquals(listOf(Triple(1, 0, 2), Triple(5, 0, 1)), treeRows(items, emptySet()).shown())
    }

    @Test
    fun anOpenedFolderShowsWhatIsInItALevelDeeper() {
        assertEquals(listOf(Triple(1, 0, 2), Triple(2, 1, 0), Triple(3, 1, 1), Triple(5, 0, 1)), treeRows(items, setOf(1)).shown())
    }

    @Test
    fun aFolderOpenedInsideAClosedOneStaysHidden() {
        assertEquals(listOf(1, 5), treeRows(items, setOf(3)).map { it.node.id })
    }

    @Test
    fun anItemThatHoldsOthersOpensAsAFolderDoes() {
        assertEquals(listOf(Triple(1, 0, 2), Triple(5, 0, 1), Triple(6, 1, 0)), treeRows(items, setOf(5)).shown())
    }

    @Test
    fun aLoopOfParentsListsEachItemOnce() {
        val loop = listOf(node(1, 0, folder = true), node(2, 1, folder = true), node(1, 2, folder = true))
        assertEquals(listOf(1, 2), treeRows(loop, setOf(1, 2)).map { it.node.id })
    }
}
