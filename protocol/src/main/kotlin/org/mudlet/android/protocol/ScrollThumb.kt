package org.mudlet.android.protocol

/**
 * The thumb of a console's scroll bar (docs/phase-f.md, "Mini consoles and user windows"), along a track as long as
 * the rows' view is tall: its [offset] from the track's top and its [size], in pixels, and [rowsPerPixel], how far the
 * rows move, in pixels, for each pixel the thumb is dragged.
 */
data class ScrollThumb(val offset: Float, val size: Float, val rowsPerPixel: Float) {
    companion object {
        /**
         * The thumb of a list of [rows] rows, each about [rowHeight] pixels tall, in a [viewport] pixels tall: the
         * row [firstRow] is the first in view, [firstRowHidden] pixels of it above the top. [atEnd] is that the list
         * can go no further, which rows of unequal height would otherwise leave the thumb short of. The thumb is never
         * shorter than [minSize], so that a finger can take it. Null when it would have less than a pixel to move
         * along the track: every row is in view and there is nothing to scroll, or the track is too short for a thumb
         * of [minSize], which would tell nothing and could not be dragged.
         */
        fun of(rows: Int, firstRow: Int, firstRowHidden: Int, rowHeight: Float, viewport: Int, atEnd: Boolean, minSize: Float): ScrollThumb? {
            if (viewport <= 0) return null
            val content = rows * rowHeight
            // Rows that all fit make it at least as long as the track
            val size = (viewport * (viewport / content) / 2).coerceAtLeast(minSize / 2)
            val travel = viewport - size
            val range = content - viewport
            val scrolled = ((firstRow * rowHeight + (if (atEnd) firstRowHidden else 0)) / range).coerceIn(0f, 1f)
            return ScrollThumb(scrolled * travel, size, 2 * range / travel)
        }

        /**
         * Whether the thumb on a console whose colour has the relative [luminance] is the white one, else the black:
         * the one that contrasts more with it, white when they tie, as the desktop's `TConsole::updateScrollBarStyle()`
         * picks. The two contrast equally at 0.1791, where 1.05 / (l + 0.05) is (l + 0.05) / 0.05.
         */
        fun whiteOn(luminance: Float): Boolean = luminance > 0.1791f
    }
}
