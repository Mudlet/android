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

/** The selection of all [count] lines numbered from [firstLine]; null when there are none. */
fun allLines(firstLine: Long, count: Int): LineSelection? = if (count > 0) LineSelection(firstLine, firstLine + count - 1) else null

/** What the floating toolbar of a selection of lines offers, before the apps that take text (ACTION_PROCESS_TEXT). */
enum class SelectionItem { COPY, SHARE, SELECT_ALL, TO_COMMAND_LINE, MAKE_TRIGGER }

/**
 * The items of the toolbar of [selection] among [count] lines numbered from [firstLine], in their order: Select all
 * unless every line is selected already, as Android's own text selection leaves it out, and Make a trigger only with
 * [oneTriggerLine], one line of the game's own text selected, which a trigger can be made from.
 */
fun selectionItems(selection: LineSelection, firstLine: Long, count: Int, oneTriggerLine: Boolean): List<SelectionItem> = buildList {
    add(SelectionItem.COPY)
    add(SelectionItem.SHARE)
    if (count > 0 && (selection.first > firstLine || selection.last < firstLine + count - 1)) add(SelectionItem.SELECT_ALL)
    add(SelectionItem.TO_COMMAND_LINE)
    if (oneTriggerLine) add(SelectionItem.MAKE_TRIGGER)
}

/** The text the selection's "Put on command line" gives the one-line command line: its lines joined by spaces. */
fun commandLineText(copied: String): String = copied.replace('\n', ' ')

/** A line of a list as drawn: its index in the list, and its top and bottom in pixels from the list's top. */
data class DrawnLine(val index: Int, val top: Int, val bottom: Int)

/**
 * Where the lines at [selected] are among the lines [drawn], in index order, of a list [height] pixels high: from the top
 * of the first to the bottom of the last, an end scrolled out of the list at that edge of it, so a selection scrolled
 * out above is the list's top and one below its bottom. Null with nothing selected or drawn.
 */
fun selectedSpan(selected: IntRange, drawn: List<DrawnLine>, height: Int): IntRange? {
    if (selected.isEmpty() || drawn.isEmpty()) return null
    // A line in view is drawn on each side of a line scrolled out, so that one's edge is past the list's
    val top = (drawn.firstOrNull { it.index >= selected.first }?.top ?: height).coerceIn(0, height)
    val bottom = drawn.lastOrNull { it.index <= selected.last }?.bottom ?: 0
    return top..bottom.coerceIn(top, height)
}

/**
 * The index of the line drawn at [y] pixels from the top of a list of the lines [drawn], in index order, past either end
 * the nearest one; a line drawn with no height, one a script deleted, is never it. Null with none drawn.
 */
fun lineUnder(y: Int, drawn: List<DrawnLine>): Int? {
    val shown = drawn.filter { it.bottom > it.top }
    return (shown.firstOrNull { y < it.bottom } ?: shown.lastOrNull())?.index
}

/**
 * How far to scroll a list [height] high, this frame, while a selection's handle is dragged [y] from its top: within
 * [edge] of the top up, within it of the bottom down, faster nearer the edge and at most [most]; else not at all.
 */
fun edgeScroll(y: Float, height: Float, edge: Float, most: Float): Float = when {
    edge <= 0f -> 0f
    y < edge -> -most * ((edge - y) / edge).coerceAtMost(1f)
    y > height - edge -> most * ((y - (height - edge)) / edge).coerceAtMost(1f)
    else -> 0f
}
