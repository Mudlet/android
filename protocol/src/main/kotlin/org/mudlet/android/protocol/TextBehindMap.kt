package org.mudlet.android.protocol

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How strongly the main console's text shows behind the app's map in front (docs/architecture.md, "The map behind the
 * text"), the swap of [MapBehindText]: as strongly as keeps each of the map's rooms and exit lines at WCAG 1.4.11's
 * 3:1 against the faint text drawn around it. Colours are 0xRRGGBB, blended in sRGB as Android draws them.
 *
 * The engine paints the frame's background opaque (Mudlet's map background colour), so text behind it would never
 * show. The text is drawn over that colour instead, and the frame on top with the pixels of exactly that colour cut out
 * ([cutOut]): the text shows where the map has nothing drawn, as it would behind a transparent one.
 */
object TextBehindMap {
    /** The most the text shows, whatever the map: no more than the map shows behind the text at most. */
    const val MAX_ALPHA = MapBehindText.MAX_ALPHA

    /** WCAG 1.4.11's least contrast for graphics, such as a map's rooms and exits. */
    const val MIN_CONTRAST = 3.0

    /** The fewest pixels a colour is solid in to count: fewer are stray, such as where two edges meet. */
    const val MIN_PIXELS = 64

    // How far a blend's channels may be from the exact mix, for the renderer's rounding
    private const val ROUNDING = 2

    /**
     * What of a frame the text behind it is weighed against: its [background], null where it is transparent, and
     * [solid], the colours other than it that its rooms and exit lines are drawn in.
     */
    data class Ground(val background: Int?, val solid: List<Int>)

    /**
     * The [Ground] of a frame [width] by [height] of 0xAARRGGBB [pixels], not premultiplied, row by row. Its background
     * is the commonest colour of the grid [MapBehindText.tones] samples, or none where at least half of them are not
     * opaque. A solid colour is one at least [MIN_PIXELS] opaque pixels have on both sides along a row, a column or a
     * diagonal, but not those of them that lie between the background and a colour beside them across that line, the
     * antialiased edge of a room or of a line drawn between pixels, which is seen as part of what it edges. Null for a
     * frame of no pixels.
     */
    fun ground(pixels: IntArray, width: Int, height: Int): Ground? {
        if (width <= 0 || height <= 0) return null
        val background = background(pixels, width, height)
        val counts = HashMap<Int, Int>()
        // Counted a run at a time, as a room's pixels come one after another: boxing each would be most of the work
        var run = 0
        var runColour = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val pixel = pixels[i]
                if (pixel ushr 24 != 0xFF || pixel and 0xFFFFFF == background) continue
                val along = AXES_Y.indices.any { axis ->
                    val step = AXES_Y[axis] * width + AXES_X[axis]
                    val across = AXES_Y[3 - axis] * width + AXES_X[3 - axis]
                    pixels[i - step] == pixel && pixels[i + step] == pixel && (background == null || (!edge(pixel, pixels[i - across], background) && !edge(pixel, pixels[i + across], background)))
                }
                if (!along) continue
                if (pixel != runColour && run > 0) {
                    counts.merge(runColour and 0xFFFFFF, run, Int::plus)
                    run = 0
                }
                runColour = pixel
                run++
            }
        }
        if (run > 0) counts.merge(runColour and 0xFFFFFF, run, Int::plus)
        return Ground(background, counts.filterValues { it >= MIN_PIXELS }.keys.sorted())
    }

    /**
     * The alpha to draw the console at behind a map of [ground]: the text in [foreground] on [background], with [around]
     * about the console, each blended over the frame's background, or over [background] where the frame has none. The
     * most, up to [MAX_ALPHA], at which every solid colour of the frame that has [MIN_CONTRAST] against the frame's
     * background keeps it against every colour between those blends; colours of the frame already below it are not
     * weighed, as no alpha would give it them. 0 with no frame, or where even a hundredth would leave a colour short.
     */
    fun alpha(ground: Ground?, foreground: Int, background: Int, around: Int = background): Float {
        if (ground == null) return 0f
        if (ground.background != Int.MIN_VALUE) return MAX_ALPHA
        val under = ground.background ?: background
        val luminance = MapBehindText.luminance(under)
        // Only the two nearest the background's luminance, one on each side, can lose their contrast first
        var lighter = Double.POSITIVE_INFINITY
        var darker = Double.NEGATIVE_INFINITY
        for (colour in ground.solid) {
            val l = MapBehindText.luminance(colour)
            if (MapBehindText.ratio(l, luminance) < MIN_CONTRAST) continue
            if (l > luminance) lighter = min(lighter, l) else darker = max(darker, l)
        }
        for (hundredths in (MAX_ALPHA * 100).roundToInt() downTo 1) {
            val alpha = hundredths / 100f
            val drawn = intArrayOf(under, MapBehindText.blend(under, foreground, alpha), MapBehindText.blend(under, background, alpha), MapBehindText.blend(under, around, alpha))
            // Every colour between the blends, channel by channel, has a luminance between these
            val darkest = MapBehindText.luminance(drawn.reduce { a, b -> MapBehindText.channels(a, b, ::min) })
            val lightest = MapBehindText.luminance(drawn.reduce { a, b -> MapBehindText.channels(a, b, ::max) })
            val lighterKeeps = lighter == Double.POSITIVE_INFINITY || (lighter > lightest && MapBehindText.ratio(lighter, lightest) >= MIN_CONTRAST)
            val darkerKeeps = darker == Double.NEGATIVE_INFINITY || (darker < darkest && MapBehindText.ratio(darker, darkest) >= MIN_CONTRAST)
            if (lighterKeeps && darkerKeeps) return alpha
        }
        return 0f
    }

    /** Makes the [pixels] (0xAARRGGBB) of exactly the opaque [background] transparent, so what is under them shows. */
    fun cutOut(pixels: IntArray, background: Int) {
        val opaque = OPAQUE or background
        for (i in pixels.indices) if (pixels[i] == opaque) pixels[i] = 0
    }

    // The commonest colour of the sampled grid's opaque pixels, or null where at least half are not opaque
    private fun background(pixels: IntArray, width: Int, height: Int): Int? {
        val rows = min(height, MapBehindText.SAMPLE_ROWS)
        val columns = min(width, MapBehindText.SAMPLE_COLUMNS)
        val opaque = IntArray(rows * columns)
        var count = 0
        for (r in 0 until rows) {
            val row = MapBehindText.sampled(r, rows, height) * width
            for (c in 0 until columns) {
                val pixel = pixels[row + MapBehindText.sampled(c, columns, width)]
                if (pixel ushr 24 == 0xFF) opaque[count++] = pixel and 0xFFFFFF
            }
        }
        if (2 * count <= rows * columns) return null
        opaque.sort(0, count)
        var best = opaque[0]
        var bestRun = 0
        var start = 0
        for (i in 1..count) {
            if (i < count && opaque[i] == opaque[start]) continue
            if (i - start > bestRun) {
                bestRun = i - start
                best = opaque[start]
            }
            start = i
        }
        return best
    }

    // Whether [pixel] is a blend of [background] and the opaque [beside] it, another colour, to within rounding
    private fun edge(pixel: Int, beside: Int, background: Int): Boolean {
        if (beside ushr 24 != 0xFF || beside == pixel || beside and 0xFFFFFF == background) return false
        val widest = intArrayOf(16, 8, 0).maxBy { abs((beside shr it and 0xFF) - (background shr it and 0xFF)) }
        val share = ((pixel shr widest and 0xFF) - (background shr widest and 0xFF)).toDouble() / ((beside shr widest and 0xFF) - (background shr widest and 0xFF))
        if (share < 0 || share > 1) return false
        return intArrayOf(16, 8, 0).all { shift ->
            val from = background shr shift and 0xFF
            abs(from + share * ((beside shr shift and 0xFF) - from) - (pixel shr shift and 0xFF)) <= ROUNDING
        }
    }

    // A row, the two diagonals and a column as steps in y and x; the one across axis i is 3 - i
    private val AXES_Y = intArrayOf(0, 1, -1, 1)
    private val AXES_X = intArrayOf(1, 1, 1, 0)

    private const val OPAQUE = 0xFF shl 24
}
