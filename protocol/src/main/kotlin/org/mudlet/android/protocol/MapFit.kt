package org.mudlet.android.protocol

import kotlin.math.ln
import kotlin.math.min

/** A point of a map frame, in its pixels from its top left: what `mapPointer` takes. */
data class MapPoint(val x: Double, val y: Double)

/**
 * Where the game screen draws a map frame [frameW] by [frameH] pixels in [box], a rectangle of the game area: scaled
 * alike across and down, as large as fits, and centred, so a touch on it goes back to the point of the frame under it.
 */
class MapFit(val frameW: Int, val frameH: Int, val box: PxRect) {
    /** Screen pixels per pixel of the frame; 0 for a frame or box with no size. */
    val scale: Float = if (frameW > 0 && frameH > 0 && box.w > 0 && box.h > 0) min(box.w / frameW, box.h / frameH) else 0f

    /** Where the frame is drawn, inside [box]. */
    val drawn: PxRect = PxRect(box.x + (box.w - frameW * scale) / 2, box.y + (box.h - frameH * scale) / 2, frameW * scale, frameH * scale)

    /** The frame's middle, where the zoom buttons zoom about. */
    val centre: MapPoint = MapPoint(frameW / 2.0, frameH / 2.0)

    /** The point of the frame under [x], [y] in the game area's pixels; null off what is drawn. */
    fun toFrame(x: Float, y: Float): MapPoint? {
        if (scale <= 0f || x < drawn.x || y < drawn.y || x >= drawn.right || y >= drawn.bottom) return null
        // Kept inside the frame, which float rounding at its far edges could leave
        return MapPoint(
            ((x - drawn.x) / scale).toDouble().coerceIn(0.0, frameW - 1.0),
            ((y - drawn.y) / scale).toDouble().coerceIn(0.0, frameH - 1.0),
        )
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
