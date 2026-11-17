package org.mudlet.android.ui

import org.mudlet.android.protocol.TextBehindMap

import kotlin.math.abs
import kotlin.math.min

/** DIAG copy, not for merging. */
object MainTextBehindMap {
    const val MIN_PIXELS = 64

    // How far a blend's channels may be from the exact mix, for the renderer's rounding
    private const val ROUNDING = 2


    /**
     * The [Ground] of a frame [width] by [height] of 0xAARRGGBB [pixels], not premultiplied, row by row. Its background
     * is the commonest colour of the grid [MapBehindText.tones] samples, or none where at least half of them are not
     * opaque. A solid colour is one at least [MIN_PIXELS] opaque pixels have on both sides along a row, a column or a
     * diagonal, but not those of them that lie between the background and a colour beside them across that line, the
     * antialiased edge of a room or of a line drawn between pixels, which is seen as part of what it edges. Null for a
     * frame of no pixels.
     */
    fun ground(pixels: IntArray, width: Int, height: Int): TextBehindMap.Ground? {
        if (width <= 0 || height <= 0) return null
        val background = background(pixels, width, height)
        // No colour's, so that no pixel is taken for the background of a frame that has none
        val ground = background ?: NO_COLOUR
        val counts = Counts()
        // Counted a run at a time, as a room's pixels come one after another
        var run = 0
        var runColour = 0
        for (y in 1 until height - 1) {
            for (i in y * width + 1 until y * width + width - 1) {
                val pixel = pixels[i]
                if (pixel ushr 24 != 0xFF || pixel and 0xFFFFFF == ground) continue
                // A row, the two diagonals and a column, each with the one across it
                val along = solid(pixels, i, 1, width, ground) || solid(pixels, i, width + 1, width - 1, ground) || solid(pixels, i, width - 1, width + 1, ground) || solid(pixels, i, width, 1, ground)
                if (!along) continue
                if (pixel != runColour && run > 0) {
                    counts.add(runColour and 0xFFFFFF, run)
                    run = 0
                }
                runColour = pixel
                run++
            }
        }
        if (run > 0) counts.add(runColour and 0xFFFFFF, run)
        return TextBehindMap.Ground(background, counts.atLeast(MIN_PIXELS))
    }

    /** Makes the [pixels] (0xAARRGGBB) of exactly the opaque [background] transparent, so what is under them shows. */
    fun cutOut(pixels: IntArray, background: Int) {
        val opaque = OPAQUE or background
        for (i in pixels.indices) if (pixels[i] == opaque) pixels[i] = 0
    }

    // The commonest colour of the sampled grid's opaque pixels, or null where at least half are not opaque
    private fun background(pixels: IntArray, width: Int, height: Int): Int? {
        val rows = min(height, 128)
        val columns = min(width, 256)
        val opaque = IntArray(rows * columns)
        var count = 0
        for (r in 0 until rows) {
            val row = sampled(r, rows, height) * width
            for (c in 0 until columns) {
                val pixel = pixels[row + sampled(c, columns, width)]
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

    // Whether the pixel at [i] has its colour on both sides [along] a line, and is not the blend of [background], if any,
    // and the pixel on either side [across] it. This runs for every pixel of a frame that is not its background, so it
    // makes no object
    private fun solid(pixels: IntArray, i: Int, along: Int, across: Int, background: Int): Boolean {
        val pixel = pixels[i]
        if (pixels[i - along] != pixel || pixels[i + along] != pixel) return false
        return background == NO_COLOUR || (!edge(pixel, pixels[i - across], background) && !edge(pixel, pixels[i + across], background))
    }

    // Whether [pixel] is a blend of [background] and the opaque [beside] it, another colour, to within rounding
    private fun edge(pixel: Int, beside: Int, background: Int): Boolean {
        if (beside ushr 24 != 0xFF || beside == pixel || beside and 0xFFFFFF == background) return false
        // How far along from the background to the colour beside it, by the channel in which they are furthest apart
        var widest = 16
        if (apart(beside, background, 8) > apart(beside, background, widest)) widest = 8
        if (apart(beside, background, 0) > apart(beside, background, widest)) widest = 0
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

    private fun sampled(i: Int, count: Int, size: Int): Int = ((2L * i + 1) * size / (2 * count)).toInt()

    private const val NO_COLOUR = -1

    // 2^32 over the golden ratio, which spreads keys that differ by little
    private const val MIX = -0x61c88647

    private const val OPAQUE = 0xFF shl 24
}
