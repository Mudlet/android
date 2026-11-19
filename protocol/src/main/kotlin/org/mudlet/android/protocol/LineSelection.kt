package org.mudlet.android.protocol

/**
 * Whole lines of a console selected by their numbers, from [anchor], the line the selection began on, to [extent], the
 * line it was taken to, above or below it. Numbers rather than places in the list, so it stays on its lines as new ones
 * come and the oldest are dropped.
 */
data class LineSelection(val anchor: Long, val extent: Long) {
    val first: Long get() = minOf(anchor, extent)
    val last: Long get() = maxOf(anchor, extent)

    operator fun contains(line: Long): Boolean = line in first..last

    /** The selection from the same line it began on to [line]. */
    fun extendTo(line: Long) = LineSelection(anchor, line)

    /** The indices of the selected lines among [count] lines numbered from [firstLine]: those still kept, maybe none. */
    fun indicesIn(firstLine: Long, count: Int): IntRange {
        val from = maxOf(first - firstLine, 0L)
        val to = minOf(last - firstLine, count - 1L)
        return if (from > to) IntRange.EMPTY else from.toInt()..to.toInt()
    }
}

/**
 * The most characters a copy or share of lines takes: Android hands the text to the clipboard or the share sheet in one
 * binder transaction, whose 1 MB limit, UTF-16 and shared with the app's other calls, would otherwise crash the app.
 */
const val MOST_COPIED_CHARS = 200_000

/**
 * The text a copy of [selection] gives, joined as desktop's copy joins lines (`TTextEdit::getSelectedText`): each line
 * as [line] gives it at its index among [count] lines numbered from [firstLine], a line feed between two and none after
 * the last. A line [line] gives null for, one a script deleted, is left out, as desktop's buffer no longer has it.
 * Null when the text would be longer than [most] characters.
 */
fun copiedLines(selection: LineSelection, firstLine: Long, count: Int, most: Int = MOST_COPIED_CHARS, line: (Int) -> String?): String? {
    val text = StringBuilder()
    var joined = false
    for (i in selection.indicesIn(firstLine, count)) {
        val read = line(i) ?: continue
        if (joined) text.append('\n')
        text.append(read)
        joined = true
        if (text.length > most) return null
    }
    return text.toString()
}
