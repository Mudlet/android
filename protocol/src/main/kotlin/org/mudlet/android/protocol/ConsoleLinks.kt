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

// The attributes a link's `styles` replaces (docs/protocol.md, "link"); reverse and blink stay the run's
private const val LINK_STYLES = RUN_BOLD or RUN_ITALIC or RUN_UNDERLINE or RUN_OVERLINE or RUN_STRIKE

/**
 * [runs] with each link painted as [info] says it is now: its `fg`, `bg` and `styles` over the run's own where given,
 * and concealed where the game hides it. The same array where no answer changes a run.
 */
fun linkRuns(runs: IntArray, info: (Int) -> EngineMessage.Link?): IntArray {
    var painted = runs
    var i = 0
    while (i + RUN_STRIDE - 1 < runs.size) {
        val link = runs[i + 5].takeIf { it > 0 }?.let(info)
        if (link != null) {
            val fg = link.fg ?: runs[i + 2]
            val bg = link.bg ?: runs[i + 3]
            var attributes = link.styles?.let { (runs[i + 4] and LINK_STYLES.inv()) or (it and LINK_STYLES) } ?: runs[i + 4]
            if (link.concealed) attributes = attributes or RUN_CONCEALED
            if (fg != runs[i + 2] || bg != runs[i + 3] || attributes != runs[i + 4]) {
                if (painted === runs) painted = runs.copyOf()
                painted[i + 2] = fg
                painted[i + 3] = bg
                painted[i + 4] = attributes
            }
        }
        i += RUN_STRIDE
    }
    return painted
}

/** [text] with the characters of each of its [spans] that [info] says the game conceals as blanks, as they are drawn. */
fun concealLinks(text: String, spans: List<LinkSpan>, info: (Int) -> EngineMessage.Link?): String {
    val hidden = spans.filter { info(it.link)?.concealed == true }
    if (hidden.isEmpty()) return text
    val characters = text.toCharArray()
    for (span in hidden) characters.fill(' ', span.start, span.end)
    return String(characters)
}
