package org.mudlet.android.protocol

/**
 * A Geyser gauge (docs/phase-f.md, "Gauges"): the labels `<name>_back`, `<name>_front` and, if there, `<name>_text`
 * in one parent. Upstream gives no gauge its value (gap 2), so it is read from the labels: the front's width against
 * the back's, or its height in a vertical gauge, from 0 to 1.
 */
data class Gauge(val name: String, val back: ScriptWindow, val front: ScriptWindow, val text: ScriptWindow?, val value: Float) {
    /** The value as a whole percentage, as a screen reader says it. */
    val percent: Int get() = Math.round(value * 100)

    /** The gauge's three labels, by key. */
    val parts: Set<WindowKey> get() = setOfNotNull(back.key, front.key, text?.key)

    companion object {
        const val BACK = "_back"
        const val FRONT = "_front"
        const val TEXT = "_text"

        /** The gauges among [windows], by their back label's name. */
        fun of(windows: List<ScriptWindow>): Map<String, Gauge> {
            val labels = windows.filter { it.kind == WindowKind.LABEL }
            val byName = labels.groupBy { it.name }
            val out = HashMap<String, Gauge>()
            for (back in labels) {
                if (!back.name.endsWith(BACK) || !back.shown) continue
                val name = back.name.removeSuffix(BACK)
                fun sibling(suffix: String) = byName[name + suffix]?.firstOrNull { it.parent == back.parent && it.parentKind == back.parentKind }
                val front = sibling(FRONT) ?: continue
                out[back.name] = Gauge(name, back, front, sibling(TEXT), value(back, front))
            }
            return out
        }

        // A horizontal gauge's front is as high as its back, less the back's margins, and shorter across; a vertical
        // one's the other way round
        private fun value(back: ScriptWindow, front: ScriptWindow): Float {
            if (!front.shown) return 0f
            val vertical = back.w - front.w < back.h - front.h
            val (part, whole) = if (vertical) front.h to back.h else front.w to back.w
            return if (whole > 0) (1f - part.toFloat() / whole).coerceIn(0f, 1f) else 0f
        }
    }
}
