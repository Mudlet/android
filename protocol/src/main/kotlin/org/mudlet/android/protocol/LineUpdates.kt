package org.mudlet.android.protocol

/**
 * Puts each of [updates], in order, in place of the line of its seq in [lines], as [seqOf] gives a line's seq (0 for one
 * that has none, such as a marker), and [updated] makes the line anew. Only the lines from index [from] on are looked
 * at: those before it came from an earlier engine instance, which counted its seqs from 1 as well. A deleted line keeps
 * its place, as [updated] makes it, since a line's number is its place. An update for a line no longer held changes
 * nothing.
 */
fun <T> applyLineUpdates(lines: MutableList<T>, from: Int, updates: List<EngineMessage.LineUpdated>, seqOf: (T) -> Long, updated: (T, EngineMessage.LineUpdated) -> T) {
    if (updates.isEmpty()) return
    val at = HashMap<Long, Int>()
    for (i in from.coerceAtLeast(0) until lines.size) {
        val seq = seqOf(lines[i])
        if (seq > 0) at[seq] = i
    }
    for (update in updates) {
        val i = at[update.seq] ?: continue
        lines[i] = updated(lines[i], update)
    }
}
