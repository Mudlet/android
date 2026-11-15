package org.mudlet.android.protocol

/** The characters [start] until [end] of a line, which its runs mark as the link [link]. */
data class LinkSpan(val link: Int, val start: Int, val end: Int)

/**
 * The links of a line of [length] characters, in order, as its [runs] mark them (docs/protocol.md, "Runs"): runs of
 * one link next to each other, as a change of colour within it makes, are one span. A run past the line's end is cut
 * to it.
 */
fun linkSpans(runs: IntArray, length: Int): List<LinkSpan> {
    val spans = ArrayList<LinkSpan>()
    var i = 0
    while (i + RUN_STRIDE - 1 < runs.size) {
        val link = runs[i + 5]
        val start = runs[i].coerceIn(0, length)
        val end = (runs[i] + runs[i + 1]).coerceIn(start, length)
        i += RUN_STRIDE
        if (link <= 0 || start == end) continue
        val last = spans.lastOrNull()
        if (last != null && last.link == link && last.end == start) {
            spans[spans.lastIndex] = last.copy(end = end)
        } else {
            spans.add(LinkSpan(link, start, end))
        }
    }
    return spans
}

/** What a screen reader and a link's menu call the link drawn as [text]: its tool tip, or before getLink answers, or with none, [text]. */
fun linkLabel(link: EngineMessage.Link?, text: String): String = link?.tooltip?.trim()?.ifEmpty { null } ?: text.trim()
