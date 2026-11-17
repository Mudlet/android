package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How strongly the app's map shows behind the main console's text (docs/architecture.md, "The map behind the text"):
 * as strongly as keeps text in the console's default colour readable over the colours the map's frame has; text the
 * game colours is not weighed. Colours are 0xRRGGBB, and the map is blended over the console's background as Android
 * draws it, in sRGB.
 */
object MapBehindText {
    /**
     * The most the map shows, whatever its colours: where a glyph of Mudlet's default text (0xC0C0C0) crosses one of
     * the exit lines of Mudlet's default map (LightGray, 0xC0C0C0), which [Tones] leaves out where they are fewer than
     * [OUTLIERS], it still keeps WCAG's 3:1 for large text and graphics.
     */
    const val MAX_ALPHA = 0.54f

    /** WCAG's least contrast for text, AA. */
    const val MIN_CONTRAST = 4.5

    /** The share of a frame's samples, in each channel and at each end, that [Tones] leaves out. */
    const val OUTLIERS = 0.01

    // How many rows and columns of a frame are sampled, at most
    private const val SAMPLE_ROWS = 128
    private const val SAMPLE_COLUMNS = 256

    /**
     * A map frame's lightest and darkest colours, each channel on its own, without the lightest and darkest [OUTLIERS]
     * of each: every colour of the frame bar those few lies between them, channel by channel, so blended over the
     * background it is no lighter than [lightest] blended and no darker than [darkest]. [ANY] is any colour at all.
     */
    data class Tones(val lightest: Int, val darkest: Int) {
        companion object {
            val ANY = Tones(0xFFFFFF, 0x000000)
        }
    }

    /**
     * The [Tones] of a frame [width] by [height], from a grid of its pixels: [readRow] puts row y's 0xAARRGGBB pixels,
     * not premultiplied, into the array it is given, which is [width] long. Fully transparent pixels show the
     * background, so are not counted; others count as opaque, as at less they are nearer the background, which
     * [alpha] weighs anyway. Null where no pixel counts.
     */
    fun tones(width: Int, height: Int, readRow: (y: Int, into: IntArray) -> Unit): Tones? {
        if (width <= 0 || height <= 0) return null
        // Each channel's 256 counts, red's, then green's, then blue's
        val counts = IntArray(3 * 256)
        val row = IntArray(width)
        val rows = min(height, SAMPLE_ROWS)
        val columns = min(width, SAMPLE_COLUMNS)
        var sampled = 0
        for (r in 0 until rows) {
            readRow(((2L * r + 1) * height / (2 * rows)).toInt(), row)
            for (c in 0 until columns) {
                val pixel = row[((2L * c + 1) * width / (2 * columns)).toInt()]
                if (pixel ushr 24 == 0) continue
                counts[pixel shr 16 and 0xFF]++
                counts[256 + (pixel shr 8 and 0xFF)]++
                counts[512 + (pixel and 0xFF)]++
                sampled++
            }
        }
        if (sampled == 0) return null
        val leftOut = (sampled * OUTLIERS).toInt()
        var lightest = 0
        var darkest = 0
        for (channel in 0 until 3) {
            val base = channel * 256
            var high = 255
            var above = counts[base + high]
            while (high > 0 && above <= leftOut) above += counts[base + --high]
            var low = 0
            var below = counts[base + low]
            while (low < 255 && below <= leftOut) below += counts[base + ++low]
            val shift = 16 - 8 * channel
            lightest = lightest or (high shl shift)
            darkest = darkest or (low shl shift)
        }
        return Tones(lightest, darkest)
    }

    /**
     * The alpha to draw a map of [tones] at behind text in [foreground] on [background]: the most, up to [MAX_ALPHA],
     * at which the text keeps [MIN_CONTRAST] over every colour between them, or 0 where even the bare background gives
     * it less, so the map keeps text in [foreground] readable.
     */
    fun alpha(foreground: Int, background: Int, tones: Tones = Tones.ANY): Float {
        // The background shows where the map is transparent, so it is among the colours weighed
        val lightest = channels(Tones.ANY.lightest, background, ::max)
        val darkest = channels(tones.darkest, background, ::min)
        // In hundredths, down from the most: the colours blended spread from the background's as the alpha grows. The
        // two ends bound every colour between only while the text's luminance is not between them too, which up to
        // MAX_ALPHA it cannot be with both at 4.5:1: that takes ends near black and near white at once
        for (hundredths in (MAX_ALPHA * 100).roundToInt() downTo 1) {
            val alpha = hundredths / 100f
            val light = contrast(foreground, blend(background, lightest, alpha))
            val dark = contrast(foreground, blend(background, darkest, alpha))
            if (min(light, dark) >= MIN_CONTRAST) return alpha
        }
        return 0f
    }

    /** WCAG's contrast ratio of two colours, from 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [over] drawn at [alpha] on [under], channel by channel in sRGB. */
    fun blend(under: Int, over: Int, alpha: Float): Int {
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = (under shr shift and 0xFF) * (1 - alpha) + (over shr shift and 0xFF) * alpha
            rgb = rgb or (channel.roundToInt().coerceIn(0, 0xFF) shl shift)
        }
        return rgb
    }

    private fun channels(a: Int, b: Int, pick: (Int, Int) -> Int): Int {
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) rgb = rgb or (pick(a shr shift and 0xFF, b shr shift and 0xFF) shl shift)
        return rgb
    }

    // WCAG's relative luminance
    private fun luminance(rgb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
    }
}
