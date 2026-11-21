package org.mudlet.android.ui.settings

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A colour as the colour dialog's square and hue bar pick it: [hue] in degrees from 0 to 360, [saturation] and
 * [brightness] from 0 to 1.
 */
internal data class Hsb(val hue: Float, val saturation: Float, val brightness: Float) {
    /** As 0xRRGGBB, each channel the nearest of 0 to 255. */
    fun toRgb(): Int {
        val chroma = brightness * saturation
        val sector = hue.mod(360f) / 60f
        val x = chroma * (1 - abs(sector % 2 - 1))
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = brightness - chroma
        fun channel(c: Float) = ((c + m) * 255).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** [by] steps of TalkBack's actions on the square, each to the next multiple of a step. */
    fun stepSaturation(by: Int) = copy(saturation = stepped(saturation, by))

    fun stepBrightness(by: Int) = copy(brightness = stepped(brightness, by))

    companion object {
        /** How many steps TalkBack's actions on the square take from none to full: 5% each. */
        const val STEPS = 20

        // A value a quarter of a step or less from a multiple counts as on it, so a step never moves too little to hear
        private fun stepped(fraction: Float, by: Int): Float {
            val steps = fraction * STEPS
            val from = if (by > 0) floor(steps + 0.25f) else ceil(steps - 0.25f)
            return (from.toInt() + by).coerceIn(0, STEPS) / STEPS.toFloat()
        }

        /**
         * [rgb]'s. A grey has no hue, and black no saturation either, so those stay [previous]'s, and the square's and
         * hue bar's thumbs stay where the player left them.
         */
        fun of(rgb: Int, previous: Hsb? = null): Hsb {
            val r = (rgb shr 16 and 0xFF) / 255f
            val g = (rgb shr 8 and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val delta = max - minOf(r, g, b)
            val hue = when {
                delta == 0f -> previous?.hue ?: 0f
                max == r -> 60 * ((g - b) / delta).mod(6f)
                max == g -> 60 * ((b - r) / delta + 2)
                else -> 60 * ((r - g) / delta + 4)
            }
            val saturation = if (max == 0f) previous?.saturation ?: 0f else delta / max
            return Hsb(hue, saturation, max)
        }
    }
}

/** The colours the dialog offers to tap: [palette], after [mudlets] where it is none of them. */
internal fun quickPicks(palette: List<Int>, mudlets: Int): List<Int> = if (mudlets in palette) palette else listOf(mudlets) + palette

/** Whether a mark drawn on [rgb] reads better black than white: whichever has the higher contrast, as WCAG reckons it. */
internal fun darkMarkReads(rgb: Int): Boolean {
    val luminance = luminance(rgb)
    return (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05)
}

// WCAG 2's relative luminance
private fun luminance(rgb: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
}
