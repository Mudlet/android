package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * Where a script's mapper window is drawn when there is room beside it for its buttons (docs/architecture.md, "The
 * map"): its [map], grown when small for the game area, its shape kept, from the corner of it nearest the area's corner,
 * and its buttons, area and level and map info in [panel] beside it, under it when [below] and over it otherwise,
 * against its right edge when [right] and its left otherwise. The map and the panel never overlap, and both are inside
 * the area. Made by [of].
 */
data class MapCorner(val map: PxRect, val panel: PxRect, val below: Boolean, val right: Boolean) {
    /** The least rect that holds the map and the panel. */
    val bounds: PxRect get() {
        val x = min(map.x, panel.x)
        val y = min(map.y, panel.y)
        return PxRect(x, y, max(map.right, panel.right) - x, max(map.bottom, panel.bottom) - y)
    }

    companion object {
        /** A small map grows to this share of the game area's width and height, within [MIN_DP] and [MAX_DP]. */
        const val FRACTION = 0.4f
        const val MIN_DP = 120f
        const val MAX_DP = 320f

        /** A row of 48 dp buttons with 4 dp round it: the least of the panel's height. */
        const val ROW_DP = 56f

        /** The least of the panel's width: three 48 dp buttons with 4 dp round them, so it is about a phone's map's width. */
        const val PANEL_WIDTH_DP = 152f

        // Steps of the search for how far the map grows before it reaches another window
        private const val SEARCH_STEPS = 24

        /**
         * Where the mapper window shown at [window] is drawn in the game area [area], in pixels of [dp] to the dp, or null
         * for one drawn where it is with its panel inside it: one with no room under or over it for a row of the
         * panel, or as large as a map grows whose panel would cover any of [others], the other things drawn over the
         * area, that the window does not. A small one grows over the console's text but stops short of any of [others]
         * it did not already overlap, and its panel goes beside it whatever it covers, as the map is too small to share
         * its room. The panel is at most as high as the map, so that the two leave the text below or above them in
         * sight, and scrolls.
         */
        fun of(window: PxRect, area: PxRect, others: List<PxRect>, dp: Float): MapCorner? {
            if (window.w <= 0 || window.h <= 0 || area.w <= 0 || area.h <= 0) return null
            val row = ROW_DP * dp
            val right = window.x + window.w / 2 > area.x + area.w / 2
            val below = window.y + window.h / 2 <= area.y + area.h / 2
            val boxW = (area.w * FRACTION).coerceIn(MIN_DP * dp, MAX_DP * dp)
            val boxH = (area.h * FRACTION).coerceIn(MIN_DP * dp, MAX_DP * dp)
            val roomW = if (right) window.right - area.x else area.right - window.x
            val roomH = (if (below) area.bottom - window.y else window.bottom - area.y) - row
            if (roomH < window.h) return null
            val most = minOf(boxW / window.w, boxH / window.h, roomW / window.w, roomH / window.h)

            fun grown(g: Float): PxRect {
                val w = window.w * g
                val h = window.h * g
                return PxRect(if (right) window.right - w else window.x, if (below) window.y else window.bottom - h, w, h)
            }
            var g = max(most, 1f)
            for (other in others) {
                if (other.intersect(window) != null || other.intersect(grown(g)) == null) continue
                var clear = 1f
                var hits = g
                repeat(SEARCH_STEPS) {
                    val mid = (clear + hits) / 2
                    if (other.intersect(grown(mid)) == null) clear = mid else hits = mid
                }
                g = clear
            }
            val map = grown(g)
            val panelW = min(max(map.w, PANEL_WIDTH_DP * dp), area.w)
            val panelX = if (right) max(area.x, map.right - panelW) else min(map.x, area.right - panelW)
            val panel = if (below) {
                PxRect(panelX, map.bottom, panelW, min(area.bottom - map.bottom, max(map.h, row)))
            } else {
                val top = max(area.y, map.y - max(map.h, row))
                PxRect(panelX, top, panelW, map.y - top)
            }
            if (most <= 1f && others.any { it.intersect(window) == null && it.intersect(panel) != null }) return null
            return MapCorner(map, panel, below, right)
        }

        /**
         * Where the app's own map goes in the console [area] (docs/architecture.md, "The map in the corner"), in pixels
         * of [dp] to the dp, or null where it has no room: as [of] places a square mapper window [MIN_DP] a side at the
         * area's top right, so it grows as a script's small mapper does, over the console's text but short of any of
         * [others], and its panel goes under it. None where that square would cover any of [others]: a script's mapper
         * is its own window, but this map would hide the script's.
         */
        fun ofAppMap(area: PxRect, others: List<PxRect>, dp: Float): MapCorner? {
            val side = MIN_DP * dp
            val seed = PxRect(area.right - side, area.y, side, side)
            if (area.w < side || others.any { it.intersect(seed) != null }) return null
            return of(seed, area, others, dp)
        }
    }
}

/**
 * Where this window as a whole, of which [PlacedWindow.rect] may show only a part, is when that rect is stretched to
 * [box]: the box to fit a mapper's map to, so that it is cut where the window is and fills [box] when it is not cut.
 */
fun PlacedWindow.wholeIn(box: PxRect): PxRect {
    if (rect.w <= 0 || rect.h <= 0) return box
    val sx = box.w / rect.w
    val sy = box.h / rect.h
    return PxRect(box.x + (full.x - rect.x) * sx, box.y + (full.y - rect.y) * sy, full.w * sx, full.h * sy)
}
