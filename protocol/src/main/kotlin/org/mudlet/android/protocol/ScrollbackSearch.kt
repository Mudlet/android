package org.mudlet.android.protocol

/**
 * Where [query] starts in [text], as desktop's buffer search finds it (`TConsole::slot_searchBufferUp`): each match from
 * the character after the last one's start, so matches may overlap, and with case ignored.
 */
fun matchStarts(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val starts = ArrayList<Int>()
    var at = text.indexOf(query, 0, ignoreCase = true)
    while (at >= 0) {
        starts.add(at)
        at = text.indexOf(query, at + 1, ignoreCase = true)
    }
    return starts
}

/**
 * The index of the nearest of [texts] that holds [query], case ignored, above [from] when [up], else below it; null where none does.
 * [from] may be one past either end, to search from the bottom or the top, as desktop's first search starts at the bottom.
 */
fun nextMatchingLine(texts: List<String>, query: String, from: Int, up: Boolean): Int? {
    if (query.isEmpty()) return null
    val order = if (up) (minOf(from, texts.size) - 1 downTo 0) else (maxOf(from, -1) + 1 until texts.size)
    return order.firstOrNull { texts[it].contains(query, ignoreCase = true) }
}
