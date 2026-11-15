package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How strongly the app's map shows behind the main console's text (docs/architecture.md, "The map behind the text"):
 * as strongly as keeps text in the console's default colour readable over any colour the map may have under it; text
 * the game colours is not weighed. Colours are 0xRRGGBB, and the map is blended over the console's background as
 * Android draws it, in sRGB.
 */
object MapBehindText {
    /** The most the map shows: a white room under the default grey text on black still leaves it [MIN_CONTRAST]. */
    const val MAX_ALPHA = 0.5f

    /** WCAG's least contrast for text, AA. */
    const val MIN_CONTRAST = 4.5

    /**
     * The alpha to draw the map at behind text in [foreground] on [background]: the most, up to [MAX_ALPHA], at which
     * the text keeps [MIN_CONTRAST] over the map's lightest and darkest colours alike, or 0 where even the bare
     * background gives it less, so the map keeps text in [foreground] readable.
     */
    fun alpha(foreground: Int, background: Int): Float {
        // In hundredths, down from the most
        for (hundredths in (MAX_ALPHA * 100).roundToInt() downTo 1) {
            val alpha = hundredths / 100f
            val lightest = contrast(foreground, blend(background, 0xFFFFFF, alpha))
            val darkest = contrast(foreground, blend(background, 0x000000, alpha))
            if (min(lightest, darkest) >= MIN_CONTRAST) return alpha
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

    // WCAG's relative luminance
    private fun luminance(rgb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
    }
}
