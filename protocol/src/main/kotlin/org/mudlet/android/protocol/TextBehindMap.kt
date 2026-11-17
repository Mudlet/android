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
     * How a pixel's bytes lie in the Int it is read as. Only an opaque pixel's colour is read, so the pixels may be
     * premultiplied or not.
     */
    enum class Layout(internal val red: Int, internal val blue: Int) {
        /** 0xAARRGGBB, as Android's `Bitmap.getPixels` gives them. */
        ARGB(16, 0),

        /** 0xAABBGGRR: an ARGB_8888 bitmap's own bytes, red first, read as the Ints of a little-endian device. */
        ABGR(0, 16),
        ;

        // The colour of a pixel of this layout as 0xRRGGBB, and of a 0xRRGGBB as this layout has it: red and blue stay or
        // change places
        internal fun rgb(colour: Int) = (colour shr red and 0xFF shl 16) or (colour and 0xFF00) or (colour shr blue and 0xFF)
    }

    /**
     * Makes the pixels of a frame's background transparent, so what is under them shows, and gives the [Ground] of the
     * frame, [width] by [height] of [pixels] in [layout], row by row, the same whichever. Its background is the
     * commonest colour of the grid [MapBehindText.tones] samples, or none where at least half of them are not opaque: no
     * pixel changes then. A solid colour is one at least [MIN_PIXELS] opaque pixels have on both sides along a row, a
     * column or a diagonal, but not those of them that lie between the background and a colour beside them across that
     * line, the antialiased edge of a room or of a line drawn between pixels, which is seen as part of what it edges.
     * Null for a frame of no pixels.
     */
    fun cutOut(pixels: IntArray, width: Int, height: Int, layout: Layout = Layout.ARGB): Ground? = scan(pixels, width, height, layout)?.ground

    /**
     * What [cutOut] found, and what it took: [steps], the times its pass went on, once for each run of the background
     * along a row and once for each other pixel, and [weighed], the pixels it tried line by line, which those within a
     * room are not.
     */
    internal class Scan(val ground: Ground, val steps: Int, val weighed: Int)

    // One pass for the colours and the cut: a frame is ten megabytes, and a second pass over it costs as much again as
    // passing its background does. A pixel that was cut counts for those after it as the background did: it is not
    // their colour, and edges nothing
    internal fun scan(pixels: IntArray, width: Int, height: Int, layout: Layout): Scan? {
        if (width <= 0 || height <= 0) return null
        val background = background(pixels, width, height, layout)
        // No colour's, so that no pixel is taken for the background of a frame that has none
        val ground = background?.let(layout::rgb) ?: NO_COLOUR
        // The pixel that is passed and cut: with no background the transparent one, which stays what it is
        val skip = if (background != null) OPAQUE or ground else 0
        val counts = Counts()
        // Counted a run at a time, as a room's pixels come one after another
        var run = 0
        var runColour = 0
        var steps = 0
        var weighed = 0
        // The pixels at a frame's sides have none on both sides of them, so they are only cut
        cut(pixels, 0, width, skip)
        cut(pixels, (height - 1) * width, height * width, skip)
        for (y in 1 until height - 1) {
            val row = y * width
            val end = row + width - 1
            if (pixels[row] == skip) pixels[row] = 0
            if (pixels[end] == skip) pixels[end] = 0
            var i = row + 1
            while (i < end) {
                steps++
                val pixel = pixels[i]
                if (pixel == skip) {
                    // Most of a map is its background, so a run of it is passed with one comparison a pixel
                    do pixels[i++] = 0 while (i < end && pixels[i] == skip)
                    continue
                }
                val at = i++
                if (pixel ushr 24 != 0xFF) continue
                // Within a room, its colour above, below and on both sides, it is solid along the row with no edge to
                // weigh. Else a row, the two diagonals and a column, each with the one across it
                if (pixels[at - 1] != pixel || pixels[at + 1] != pixel || pixels[at - width] != pixel || pixels[at + width] != pixel) {
                    weighed++
                    val along = solid(pixels, at, 1, width, ground, layout) || solid(pixels, at, width + 1, width - 1, ground, layout) ||
                        solid(pixels, at, width - 1, width + 1, ground, layout) || solid(pixels, at, width, 1, ground, layout)
                    if (!along) continue
                }
                if (pixel != runColour && run > 0) {
                    counts.add(layout.rgb(runColour), run)
                    run = 0
                }
                runColour = pixel
                run++
            }
        }
        if (run > 0) counts.add(layout.rgb(runColour), run)
        return Scan(Ground(background, counts.atLeast(MIN_PIXELS)), steps, weighed)
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

    private fun cut(pixels: IntArray, from: Int, until: Int, skip: Int) {
        for (i in from until until) if (pixels[i] == skip) pixels[i] = 0
    }

    // The commonest colour of the sampled grid's opaque pixels, or null where at least half are not opaque
    private fun background(pixels: IntArray, width: Int, height: Int, layout: Layout): Int? {
        val rows = min(height, MapBehindText.SAMPLE_ROWS)
        val columns = min(width, MapBehindText.SAMPLE_COLUMNS)
        val opaque = IntArray(rows * columns)
        var count = 0
        for (r in 0 until rows) {
            val row = MapBehindText.sampled(r, rows, height) * width
            for (c in 0 until columns) {
                val pixel = pixels[row + MapBehindText.sampled(c, columns, width)]
                if (pixel ushr 24 == 0xFF) opaque[count++] = layout.rgb(pixel)
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

    // Whether the pixel at [i] has its colour on both sides [along] a line, and is not the blend of [background], if any,
    // and the pixel on either side [across] it, all in [layout]. This runs for every pixel of a frame that is not its
    // background or within a room, so it makes no object
    private fun solid(pixels: IntArray, i: Int, along: Int, across: Int, background: Int, layout: Layout): Boolean {
        val pixel = pixels[i]
        if (pixels[i - along] != pixel || pixels[i + along] != pixel) return false
        return background == NO_COLOUR || (!edge(pixel, pixels[i - across], background, layout) && !edge(pixel, pixels[i + across], background, layout))
    }

    // Whether [pixel] is a blend of [background] and the opaque [beside] it, another colour, to within rounding
    private fun edge(pixel: Int, beside: Int, background: Int, layout: Layout): Boolean {
        if (beside ushr 24 != 0xFF || beside == pixel || beside and 0xFFFFFF == background) return false
        // How far along from the background to the colour beside it, by the channel in which they are furthest apart:
        // red's before green's before blue's where they are as far, whichever layout
        var widest = layout.red
        if (apart(beside, background, 8) > apart(beside, background, widest)) widest = 8
        if (apart(beside, background, layout.blue) > apart(beside, background, widest)) widest = layout.blue
        val share = ((pixel shr widest and 0xFF) - (background shr widest and 0xFF)).toDouble() / ((beside shr widest and 0xFF) - (background shr widest and 0xFF))
        if (share < 0 || share > 1) return false
        return blended(pixel, beside, background, share, 16) && blended(pixel, beside, background, share, 8) && blended(pixel, beside, background, share, 0)
    }

    private fun apart(a: Int, b: Int, shift: Int) = abs((a shr shift and 0xFF) - (b shr shift and 0xFF))

    private fun blended(pixel: Int, beside: Int, background: Int, share: Double, shift: Int): Boolean {
        val from = background shr shift and 0xFF
        return abs(from + share * ((beside shr shift and 0xFF) - from) - (pixel shr shift and 0xFF)) <= ROUNDING
    }

    // How many pixels each 0xRRGGBB colour has, in two arrays hashed by colour: a map of boxed numbers would make
    // tens of thousands of them for each frame, one for each run of a colour along its rows
    private class Counts {
        private var colours = IntArray(64)
        private var counts = IntArray(64)
        private var used = 0

        fun add(colour: Int, count: Int) {
            // At most half full, so a colour's place is found in a step or two
            if (2 * used >= colours.size) {
                val oldColours = colours
                val oldCounts = counts
                colours = IntArray(2 * oldColours.size)
                counts = IntArray(2 * oldCounts.size)
                for (i in oldColours.indices) if (oldCounts[i] > 0) put(oldColours[i], oldCounts[i])
            }
            if (put(colour, count)) used++
        }

        /** The colours with at least [least] pixels, in order. */
        fun atLeast(least: Int): List<Int> = colours.filterIndexed { i, _ -> counts[i] >= least }.sorted()

        // Whether [colour] is new. An empty place has a count of 0, which no colour that was added has
        private fun put(colour: Int, count: Int): Boolean {
            val mask = colours.size - 1
            // The colour's bits mixed, as a map's colours often differ in one channel alone
            var at = (colour * MIX ushr 16) and mask
            while (counts[at] > 0 && colours[at] != colour) at = (at + 1) and mask
            val new = counts[at] == 0
            colours[at] = colour
            counts[at] += count
            return new
        }
    }

    private const val NO_COLOUR = -1

    // 2^32 over the golden ratio, which spreads keys that differ by little
    private const val MIX = -0x61c88647

    private const val OPAQUE = 0xFF shl 24
}
