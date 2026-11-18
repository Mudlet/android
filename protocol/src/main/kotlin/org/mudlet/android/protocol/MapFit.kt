package org.mudlet.android.protocol

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

/** A point of a map frame, in its pixels from its top left: what `mapPointer` takes. */
data class MapPoint(val x: Double, val y: Double)

/**
 * Where the game screen draws a map frame [frameW] by [frameH] pixels in [box], in the pixels of what draws it: scaled
 * alike across and down and centred, so a touch on it goes back to the point of the frame under it. As large as fits;
 * or, [asRedrawn], a frame the engine drew for a box of another size as it will draw the map for this one. Mudlet sizes
 * its rooms by the shorter side of what it draws in and keeps the map's middle in its middle (`T2DMap::paintEvent()`),
 * so that frame is scaled by the shorter sides of the two: the map stays where it is, and as large, when the frame for
 * this box comes, cut off where the frame is larger than the box and short of its edges where it is smaller.
 */
class MapFit(val frameW: Int, val frameH: Int, val box: PxRect, asRedrawn: Boolean = false) {
    /** Screen pixels per pixel of the frame; 0 for a frame or box with no size. */
    val scale: Float = when {
        frameW <= 0 || frameH <= 0 || box.w <= 0 || box.h <= 0 -> 0f
        asRedrawn -> min(box.w, box.h) / min(frameW, frameH)
        else -> min(box.w / frameW, box.h / frameH)
    }

    /**
     * What to ask the engine to draw the mapper's map at (`setMapScale`): [scale] in hundredths, down, so a box a
     * fraction of a pixel larger asks for no new map; at least 1 and at most [MAX_RENDER_SCALE], which the engine takes.
     */
    val renderScale: Double = (floor(scale * 100.0) / 100).coerceIn(1.0, MAX_RENDER_SCALE)

    /** Where the frame is drawn, inside [box]. */
    val drawn: PxRect = PxRect(box.x + (box.w - frameW * scale) / 2, box.y + (box.h - frameH * scale) / 2, frameW * scale, frameH * scale)

    /** The frame's middle, where the zoom buttons zoom about. */
    val centre: MapPoint = MapPoint(frameW / 2.0, frameH / 2.0)

    /** The point of the frame, as drawn unmoved, at [x], [y] in [box]'s pixels, off it or not; null for a fit with no size. */
    fun toMap(x: Float, y: Float): MapPoint? = if (scale > 0f) MapPoint(((x - drawn.x) / scale).toDouble(), ((y - drawn.y) / scale).toDouble()) else null

    /** The point of the frame under [x], [y], in [box]'s pixels; null off what is drawn. */
    fun toFrame(x: Float, y: Float): MapPoint? {
        if (scale <= 0f || x < drawn.x || y < drawn.y || x >= drawn.right || y >= drawn.bottom) return null
        // Kept inside the frame, which float rounding at its far edges could leave
        return MapPoint(
            ((x - drawn.x) / scale).toDouble().coerceIn(0.0, frameW - 1.0),
            ((y - drawn.y) / scale).toDouble().coerceIn(0.0, frameH - 1.0),
        )
    }

    companion object {
        /** The largest `setMapScale` the engine takes: IEngine::maxMapScale. */
        const val MAX_RENDER_SCALE = 8.0

        /**
         * A frame the engine drew for [whole], as large as it is drawn there ([asRedrawn]) but with its middle in [box]'s
         * middle, cut off where it is larger than the box: the map behind the text shown in the corner, its rooms as
         * large as they are drawn behind the text and the player, whom it follows, in the box's middle.
         */
        fun centred(frameW: Int, frameH: Int, whole: PxRect, box: PxRect): MapFit {
            val scale = MapFit(frameW, frameH, whole, asRedrawn = true).scale
            val w = frameW * scale
            val h = frameH * scale
            return MapFit(frameW, frameH, PxRect(box.x + (box.w - w) / 2, box.y + (box.h - h) / 2, w, h))
        }
    }
}

/**
 * A pinch as turns of a mouse wheel, which is how Mudlet's map zooms: each notch ([WHEEL_NOTCH]) zooms it by 7%
 * (`TMapPainter::zoomAtPoint()`), in unless the player inverted the map's zoom. What does not make a whole notch yet
 * is kept for the pinch's next step, so a slow pinch zooms as far as a quick one.
 */
class ZoomNotches {
    private var logZoom = 0.0

    /** Adds a step of the pinch that scaled it by [zoom]; the whole notches it now makes, positive spreading the fingers. */
    fun add(zoom: Float): Int {
        if (zoom <= 0f || zoom.isNaN() || zoom.isInfinite()) return 0
        logZoom += ln(zoom.toDouble())
        val notches = (logZoom / LN_NOTCH).toInt()
        logZoom -= notches * LN_NOTCH
        return notches
    }

    companion object {
        /** A notch of the wheel, in `wheelY`'s eighths of a degree. */
        const val WHEEL_NOTCH = 120

        // What one notch scales the map by
        private val LN_NOTCH = ln(1.07)
    }
}
