package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MapBehindTextTest {
    private fun readable(foreground: Int, background: Int, alpha: Float) = listOf(0xFFFFFF, 0x000000, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF).all {
        MapBehindText.contrast(foreground, MapBehindText.blend(background, it, alpha)) >= MapBehindText.MIN_CONTRAST
    }

    // A frame as an array of 0xAARRGGBB pixels, row by row, with its tones
    private class Frame(val width: Int, val height: Int, fill: Int) {
        val pixels = IntArray(width * height) { fill }

        fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
            for (row in y until y + h) for (column in x until x + w) pixels[row * width + column] = colour
        }

        fun tones() = MapBehindText.tones(width, height) { y, into -> pixels.copyInto(into, 0, y * width, y * width + width) }

        fun share(colour: Int) = pixels.count { it == colour }.toDouble() / pixels.size
    }

    // Mudlet's default map: DarkGreen and DarkCyan rooms on its black background, joined by exits in LightGray a
    // pixel wide, the player's room DarkRed; [roomColours] instead where given
    private fun mudletMap(roomColours: IntArray = intArrayOf(DARK_GREEN, DARK_CYAN)): Frame {
        val frame = Frame(1080, 1500, OPAQUE or 0x000000)
        val step = 120
        for (y in step / 2 until frame.height - step / 2 step step) {
            for (x in step / 2 until frame.width - step / 2 step step) {
                frame.rect(x, y, step, 1, OPAQUE or LIGHT_GRAY)
                frame.rect(x - 20, y - 20, 40, 40, OPAQUE or roomColours[(x + y) / step % roomColours.size])
            }
        }
        frame.rect(frame.width / 2 - 20, frame.height / 2 - 20, 40, 40, OPAQUE or DARK_RED)
        return frame
    }

    @Test
    fun mudletsDefaultMapShowsFarMoreThanTheWorstCase() {
        val frame = mudletMap()
        val exits = frame.share(OPAQUE or LIGHT_GRAY)
        assertTrue("the exits are a thin share of it: $exits", exits < MapBehindText.OUTLIERS)
        val tones = frame.tones()!!
        assertEquals("the rooms' colours, not the thin exits'", MapBehindText.Tones(0x008080, 0x000000), tones)
        val worst = MapBehindText.alpha(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND)
        val alpha = MapBehindText.alpha(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND, tones)
        assertEquals(0.31f, worst, 0f)
        assertEquals("as strong as it goes", MapBehindText.MAX_ALPHA, alpha, 0f)
        for (room in intArrayOf(DARK_GREEN, DARK_CYAN, 0x000000)) {
            val over = MapBehindText.blend(CONSOLE_BACKGROUND, room, alpha)
            assertTrue("default text over $room", MapBehindText.contrast(CONSOLE_FOREGROUND, over) >= MapBehindText.MIN_CONTRAST)
        }
    }

    @Test
    fun aWhiteRoomKeepsTheMapAsFaintAsTheWorstCase() {
        val frame = mudletMap(intArrayOf(DARK_GREEN, 0xFFFFFF))
        val tones = frame.tones()!!
        assertEquals(0xFFFFFF, tones.lightest)
        val alpha = MapBehindText.alpha(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND, tones)
        assertEquals(MapBehindText.alpha(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), alpha, 0f)
        assertEquals(0.31f, alpha, 0f)
        assertTrue(readable(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND, alpha))
    }

    @Test
    fun aFewBrightPixelsDoNotDecideIt() {
        val frame = mudletMap()
        val before = frame.tones()
        // A white column, which one of the 256 columns sampled falls on
        frame.rect(2, 0, 1, frame.height, OPAQUE or 0xFFFFFF)
        assertEquals(before, frame.tones())
        // A white band across 3% of it counts
        frame.rect(0, 0, frame.width, frame.height * 3 / 100, OPAQUE or 0xFFFFFF)
        assertEquals(0xFFFFFF, frame.tones()!!.lightest)
    }

    @Test
    fun transparentPixelsAreTheBackgroundAndAnEmptyFrameHasNoTones() {
        val frame = mudletMap()
        val tones = frame.tones()
        frame.rect(0, 0, frame.width, frame.height / 2, 0x00FFFFFF)
        assertEquals("a transparent white is not white", tones, frame.tones())
        assertNull(Frame(10, 10, 0).tones())
        assertNull(MapBehindText.tones(0, 10) { _, _ -> })
    }

    @Test
    fun textAlreadyHardToReadGetsNoMapHoweverDarkTheMap() {
        // Mudlet's dark blue on black is below 4.5:1 on its own
        assertTrue(MapBehindText.contrast(0x000080, 0x000000) < MapBehindText.MIN_CONTRAST)
        assertEquals(0f, MapBehindText.alpha(0x000080, 0x000000), 0f)
        assertEquals(0f, MapBehindText.alpha(0x000080, 0x000000, mudletMap().tones()!!), 0f)
        assertEquals(0f, MapBehindText.alpha(0x000080, 0x000000, MapBehindText.Tones(0x000000, 0x000000)), 0f)
    }

    @Test
    fun theMostIsWhereDefaultTextOverADefaultExitKeepsThreeToOne() {
        fun overExit(alpha: Float) = MapBehindText.contrast(CONSOLE_FOREGROUND, MapBehindText.blend(CONSOLE_BACKGROUND, LIGHT_GRAY, alpha))
        assertTrue(overExit(MapBehindText.MAX_ALPHA) >= 3.0)
        assertTrue(overExit(MapBehindText.MAX_ALPHA + 0.01f) < 3.0)
        // A map of the background's colour alone changes nothing, and shows at the most
        assertEquals(MapBehindText.MAX_ALPHA, MapBehindText.alpha(CONSOLE_FOREGROUND, CONSOLE_BACKGROUND, MapBehindText.Tones(0, 0)), 0f)
    }

    @Test
    fun textKeepsItsContrastOverEveryColourBetweenTheTonesAndTheBackground() {
        val random = Random(9011)
        repeat(2000) {
            val foreground = random.nextInt(0x1000000)
            val background = random.nextInt(0x1000000)
            val a = random.nextInt(0x1000000)
            val b = random.nextInt(0x1000000)
            val tones = MapBehindText.Tones(channels(a, b, ::maxOf), channels(a, b, ::minOf))
            val alpha = MapBehindText.alpha(foreground, background, tones)
            if (alpha == 0f) return@repeat
            repeat(50) {
                // A colour between the tones, or the background, drawn at the alpha or less, as a translucent pixel is
                val between = (0 until 3).fold(0) { rgb, channel ->
                    val shift = 16 - 8 * channel
                    val low = minOf(tones.darkest shr shift and 0xFF, background shr shift and 0xFF)
                    val high = maxOf(tones.lightest shr shift and 0xFF, background shr shift and 0xFF)
                    rgb or (random.nextInt(low, high + 1) shl shift)
                }
                val over = MapBehindText.blend(background, between, alpha * random.nextFloat())
                val contrast = MapBehindText.contrast(foreground, over)
                assertTrue("#%06x on #%06x at $alpha over #%06x: $contrast".format(foreground, background, between), contrast >= MapBehindText.MIN_CONTRAST - 0.05)
            }
        }
    }

    @Test
    fun dimmerTextGetsAFainterMapThatKeepsItReadable() {
        val alpha = MapBehindText.alpha(0x808080, 0x000000)
        assertTrue("fainter than the worst case's for the defaults, but some: $alpha", alpha > 0f && alpha < 0.31f)
        assertTrue(readable(0x808080, 0x000000, alpha))
        assertTrue("the next step up would not be", !readable(0x808080, 0x000000, alpha + 0.01f))
    }

    @Test
    fun onALightBackgroundTheMapsDarkRoomsAreWhatDimItsText() {
        // A white room changes nothing on white: the dark ones are what the alpha is held down by
        val alpha = MapBehindText.alpha(0x606060, 0xFFFFFF)
        assertTrue("fainter than the most, but some: $alpha", alpha > 0f && alpha < MapBehindText.MAX_ALPHA)
        assertTrue(readable(0x606060, 0xFFFFFF, alpha))
        assertTrue("the next step up would not be", !readable(0x606060, 0xFFFFFF, alpha + 0.01f))
        assertEquals(alpha, MapBehindText.alpha(0x606060, 0xFFFFFF, MapBehindText.Tones(0xFFFFFF, 0x000000)), 0f)
    }

    @Test
    fun theContrastAndBlendAreWcagsAndSrgbs() {
        assertEquals(21.0, MapBehindText.contrast(0xFFFFFF, 0x000000), 1e-9)
        assertEquals(1.0, MapBehindText.contrast(0x123456, 0x123456), 1e-9)
        assertEquals(0x4D4D4D, MapBehindText.blend(0x000000, 0xFFFFFF, 0.3f))
        assertEquals(0x804020, MapBehindText.blend(0x804020, 0x000000, 0f))
    }

    private fun channels(a: Int, b: Int, pick: (Int, Int) -> Int) = (0 until 3).fold(0) { rgb, channel ->
        val shift = 16 - 8 * channel
        rgb or (pick(a shr shift and 0xFF, b shr shift and 0xFF) shl shift)
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24

        // The console's defaults, CONSOLE_FOREGROUND on CONSOLE_BACKGROUND
        const val CONSOLE_FOREGROUND = 0xC0C0C0
        const val CONSOLE_BACKGROUND = 0x000000

        // Qt's colours that Mudlet's map uses by default (Host's mGreen_2, mCyan_2, mRed_2 and mFgColor_2)
        const val DARK_GREEN = 0x008000
        const val DARK_CYAN = 0x008080
        const val DARK_RED = 0x800000
        const val LIGHT_GRAY = 0xC0C0C0
    }
}
