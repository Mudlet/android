package org.mudlet.android.ui

import org.mudlet.android.protocol.TextBehindMap
import kotlin.math.abs
import kotlin.math.min

// DIAG copies, not for merging: ground's scan with each change on or off, for a bitmap's own pixels (ABGR)


class Scan(val ground: TextBehindMap.Ground, val weighed: Int)

private fun sampled(i: Int, count: Int, size: Int): Int = ((2L * i + 1) * size / (2 * count)).toInt()

object VPlain {
    fun scan(pixels: IntArray, width: Int, height: Int, layout: TextBehindMap.Layout): Scan? {
        if (width <= 0 || height <= 0) return null
        val background = background(pixels, width, height, layout)
        val ground = background?.let(::rgb) ?: NO_COLOUR
        val skip = if (background != null) OPAQUE or ground else 0
        val counts = Counts()
        var weighed = 0
        var run = 0
        var runColour = 0
        for (y in 1 until height - 1) {
            for (i in y * width + 1 until y * width + width - 1) {
                val pixel = pixels[i]
                if (pixel ushr 24 != 0xFF || pixel and 0xFFFFFF == ground) continue
                weighed++
                val along = solid(pixels, i, 1, width, ground, layout) || solid(pixels, i, width + 1, width - 1, ground, layout) ||
                    solid(pixels, i, width - 1, width + 1, ground, layout) || solid(pixels, i, width, 1, ground, layout)
                if (!along) continue
                if (pixel != runColour && run > 0) {
                    counts.add(rgb(runColour), run)
                    run = 0
                }
                runColour = pixel
                run++
            }
        }
        if (run > 0) counts.add(rgb(runColour), run)
        return Scan(TextBehindMap.Ground(background, counts.atLeast(TextBehindMap.MIN_PIXELS)), weighed)
    }

    private fun background(pixels: IntArray, width: Int, height: Int, layout: TextBehindMap.Layout): Int? {
        val rows = min(height, 128)
        val columns = min(width, 256)
        val opaque = IntArray(rows * columns)
        var count = 0
        for (r in 0 until rows) {
            val row = sampled(r, rows, height) * width
            for (c in 0 until columns) {
                val pixel = pixels[row + sampled(c, columns, width)]
                if (pixel ushr 24 == 0xFF) opaque[count++] = rgb(pixel)
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

    private fun solid(pixels: IntArray, i: Int, along: Int, across: Int, background: Int, layout: TextBehindMap.Layout): Boolean {
        val pixel = pixels[i]
        if (pixels[i - along] != pixel || pixels[i + along] != pixel) return false
        return background == NO_COLOUR || (!edge(pixel, pixels[i - across], background, layout) && !edge(pixel, pixels[i + across], background, layout))
    }

    private fun edge(pixel: Int, beside: Int, background: Int, layout: TextBehindMap.Layout): Boolean {
        if (beside ushr 24 != 0xFF || beside == pixel || beside and 0xFFFFFF == background) return false
        var widest = RED
        if (apart(beside, background, 8) > apart(beside, background, widest)) widest = 8
        if (apart(beside, background, BLUE) > apart(beside, background, widest)) widest = BLUE
        val share = ((pixel shr widest and 0xFF) - (background shr widest and 0xFF)).toDouble() / ((beside shr widest and 0xFF) - (background shr widest and 0xFF))
        if (share < 0 || share > 1) return false
        return blended(pixel, beside, background, share, 16) && blended(pixel, beside, background, share, 8) && blended(pixel, beside, background, share, 0)
    }

    private fun apart(a: Int, b: Int, shift: Int) = abs((a shr shift and 0xFF) - (b shr shift and 0xFF))

    private fun blended(pixel: Int, beside: Int, background: Int, share: Double, shift: Int): Boolean {
        val from = background shr shift and 0xFF
        return abs(from + share * ((beside shr shift and 0xFF) - from) - (pixel shr shift and 0xFF)) <= ROUNDING
    }

    private class Counts {
        private var colours = IntArray(64)
        private var counts = IntArray(64)
        private var used = 0

        fun add(colour: Int, count: Int) {
            if (2 * used >= colours.size) {
                val oldColours = colours
                val oldCounts = counts
                colours = IntArray(2 * oldColours.size)
                counts = IntArray(2 * oldCounts.size)
                for (i in oldColours.indices) if (oldCounts[i] > 0) put(oldColours[i], oldCounts[i])
            }
            if (put(colour, count)) used++
        }

        fun atLeast(least: Int): List<Int> = colours.filterIndexed { i, _ -> counts[i] >= least }.sorted()

        private fun put(colour: Int, count: Int): Boolean {
            val mask = colours.size - 1
            var at = (colour * MIX ushr 16) and mask
            while (counts[at] > 0 && colours[at] != colour) at = (at + 1) and mask
            val new = counts[at] == 0
            colours[at] = colour
            counts[at] += count
            return new
        }
    }

    private const val RED = 0
    private const val BLUE = 16

    private fun rgb(colour: Int) = (colour shr RED and 0xFF shl 16) or (colour and 0xFF00) or (colour shr BLUE and 0xFF)

    private const val ROUNDING = 2
    private const val NO_COLOUR = -1
    private const val MIX = -0x61c88647
    private const val OPAQUE = 0xFF shl 24
}

