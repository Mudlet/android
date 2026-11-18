package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class LineUpdatesTest {
    private data class Shown(val seq: Long, val text: String, val deleted: Boolean = false)

    private fun apply(lines: MutableList<Shown>, from: Int, vararg updates: EngineMessage.LineUpdated) = applyLineUpdates(lines, from, updates.toList(), { it.seq }) { line, update ->
        if (update.deleted) Shown(line.seq, "", deleted = true) else Shown(line.seq, update.text)
    }

    private fun edit(seq: Long, text: String) = EngineMessage.LineUpdated(seq, text, IntArray(0), prompt = false, deleted = false)

    private fun delete(seq: Long) = EngineMessage.LineUpdated(seq, "", IntArray(0), prompt = false, deleted = true)

    @Test
    fun anEditReplacesTheLineOfItsSeqWhereItStands() {
        val lines = mutableListOf(Shown(1, "alpha"), Shown(2, "beta"), Shown(3, "gamma"))
        apply(lines, 0, edit(2, "BETA"))
        assertEquals(listOf(Shown(1, "alpha"), Shown(2, "BETA"), Shown(3, "gamma")), lines)
    }

    @Test
    fun aDeletedLineKeepsItsPlaceSoTheLinesAfterItKeepTheirNumbers() {
        val lines = mutableListOf(Shown(1, "alpha"), Shown(2, "beta"), Shown(3, "gamma"))
        apply(lines, 0, delete(2))
        assertEquals(listOf(Shown(1, "alpha"), Shown(2, "", deleted = true), Shown(3, "gamma")), lines)
    }

    @Test
    fun anEarlierEngineInstancesLinesAreNotTouchedThoughTheirSeqsRepeat() {
        // Seqs 1 to 3 of the old instance, a marker, then the new instance's own 1 and 2, and its 3 that a gap skipped
        val lines = mutableListOf(Shown(1, "old one"), Shown(2, "old two"), Shown(3, "old three"), Shown(0, "new engine"), Shown(1, "new one"), Shown(2, "new two"))
        apply(lines, 4, edit(1, "NEW ONE"), delete(2), edit(3, "never shown"))
        assertEquals(
            listOf(Shown(1, "old one"), Shown(2, "old two"), Shown(3, "old three"), Shown(0, "new engine"), Shown(1, "NEW ONE"), Shown(2, "", deleted = true)),
            lines,
        )
    }

    @Test
    fun updatesApplyInOrderAndOneForALineNoLongerHeldChangesNothing() {
        val lines = mutableListOf(Shown(7, "seven"), Shown(0, "marker"), Shown(8, "eight"))
        apply(lines, 0, edit(5, "dropped long ago"), edit(8, "first"), edit(8, "second"), edit(0, "no seq"))
        assertEquals(listOf(Shown(7, "seven"), Shown(0, "marker"), Shown(8, "second")), lines)
    }
}
