package org.mudlet.android.protocol

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt
import kotlin.random.Random

class TextBehindMapTest {
    // A frame as an array of 0xAARRGGBB pixels, row by row
    private class Frame(val width: Int, val height: Int, fill: Int) {
        val pixels = IntArray(width * height) { fill }

        fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
            for (row in y until y + h) for (column in x until x + w) pixels[row * width + column] = colour
        }

        fun ground() = TextBehindMap.ground(pixels, width, height)
    }

    // Mudlet's default map: DarkGreen and DarkCyan rooms on its black background, joined by exits in LightGray a
    // pixel wide, the player's room DarkRed; [roomColours] and [background] instead where given
    private fun mudletMap(roomColours: IntArray = intArrayOf(DARK_GREEN, DARK_CYAN), background: Int = OPAQUE or 0x000000): Frame {
        val frame = Frame(1080, 1500, background)
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

    // Whether each solid colour of [ground] with 3:1 against its background keeps it against the text drawn at [alpha]:
    // the blends of the console's colours over that background, and every colour between them
    private fun keeps(ground: TextBehindMap.Ground, alpha: Float, foreground: Int = CONSOLE_FOREGROUND, background: Int = CONSOLE_BACKGROUND): Boolean {
        val under = ground.background ?: background
        val drawn = listOf(under, MapBehindText.blend(under, foreground, alpha), MapBehindText.blend(under, background, alpha))
        val corners = (0 until 8).map { corner ->
            (0 until 3).fold(0) { rgb, channel ->
                val shift = 16 - 8 * channel
                val values = drawn.map { it shr shift and 0xFF }
                rgb or ((if (corner shr channel and 1 == 0) values.min() else values.max()) shl shift)
            }
        }
        val luminances = corners.map { MapBehindText.luminance(it) }
        return ground.solid.filter { MapBehindText.contrast(it, under) >= TextBehindMap.MIN_CONTRAST }.all { colour ->
            MapBehindText.luminance(colour) !in luminances.min()..luminances.max() && (drawn + corners).all { MapBehindText.contrast(colour, it) >= TextBehindMap.MIN_CONTRAST }
        }
    }

    // The next alpha up from [alpha] that TextBehindMap tries, made as it makes it: adding a hundredth to a float can blend
    // a channel differently
    private fun step(alpha: Float) = ((alpha * 100).roundToInt() + 1) / 100f

    @Test
    fun mudletsDefaultMapIsHeldDownByItsDarkGreenRooms() {
        val ground = mudletMap().ground()!!
        assertEquals("the rooms' and the exits' colours on its black", TextBehindMap.Ground(0x000000, listOf(DARK_GREEN, DARK_CYAN, DARK_RED, LIGHT_GRAY)), ground)
        val alpha = TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND)
        assertEquals(0.19f, alpha, 0f)
        assertTrue(keeps(ground, alpha))
        assertFalse("the next step up loses DarkGreen's 3:1", keeps(TextBehindMap.Ground(0, listOf(DARK_GREEN)), step(alpha)))
    }

    @Test
    fun whiteRoomsLeaveItToTheExitsWhichAllowTheMost() {
        val ground = mudletMap(intArrayOf(0xFFFFFF)).ground()!!
        val alpha = TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND)
        assertEquals("the swap of the map behind the text: default text over a default exit at 3:1", TextBehindMap.MAX_ALPHA, alpha, 0f)
        assertTrue(keeps(ground, alpha))
        assertFalse(keeps(TextBehindMap.Ground(0, listOf(LIGHT_GRAY)), step(alpha)))
    }

    @Test
    fun roomsBarelyAtThreeToOneLeaveNoRoomForText() {
        assertTrue(MapBehindText.contrast(0x575A56, 0x000000) in 3.0..3.01)
        val ground = mudletMap(intArrayOf(0x575A56)).ground()!!
        assertTrue(0x575A56 in ground.solid)
        assertEquals(0f, TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), 0f)
    }

    @Test
    fun colourBelowThreeToOneAlreadyIsNotWeighed() {
        // Mudlet's dark blue on black has no 3:1 to lose
        val ground = TextBehindMap.Ground(0x000000, listOf(0x000080, LIGHT_GRAY))
        assertEquals(TextBehindMap.MAX_ALPHA, TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), 0f)
    }

    @Test
    fun noFrameShowsNoTextAndAnEmptyOneTheMost() {
        assertEquals(0f, TextBehindMap.alpha(null, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), 0f)
        assertNull(TextBehindMap.ground(IntArray(0), 0, 10))
        val empty = Frame(200, 100, OPAQUE or 0x102030).ground()!!
        assertEquals(TextBehindMap.Ground(0x102030, emptyList()), empty)
        assertEquals(TextBehindMap.MAX_ALPHA, TextBehindMap.alpha(empty, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), 0f)
    }

    @Test
    fun antialiasedEdgesAreNotRoomsOfTheirOwn() {
        val frame = Frame(600, 600, OPAQUE or 0x000000)
        for (x in 50 until 550 step 100) {
            // A LightGray room with an edge a pixel wide of half LightGray all round it
            frame.rect(x - 1, 49, 42, 42, OPAQUE or 0x606060)
            frame.rect(x, 50, 40, 40, OPAQUE or LIGHT_GRAY)
            // A line drawn between rows, as two rows of different shares of it
            frame.rect(x, 300, 80, 1, OPAQUE or 0x686868)
            frame.rect(x, 301, 80, 1, OPAQUE or 0x989898)
            // and one on a row of its own, alone
            frame.rect(x, 400, 80, 1, OPAQUE or 0x707070)
            // and a diagonal one
            for (i in 0 until 60) frame.rect(x + i, 450 + i, 1, 1, OPAQUE or 0x7070A0)
        }
        assertTrue("the edges would count", MapBehindText.contrast(0x606060, 0x000000) > 3.0 && MapBehindText.contrast(0x686868, 0x000000) > 3.0)
        assertEquals(listOf(0x707070, 0x7070A0, 0x989898, LIGHT_GRAY), frame.ground()!!.solid)
    }

    @Test
    fun strayPixelsDoNotCount() {
        val frame = mudletMap()
        frame.rect(5, 5, TextBehindMap.MIN_PIXELS + 1, 1, OPAQUE or 0x575A56)
        assertFalse("63 inside a row of 65", 0x575A56 in frame.ground()!!.solid)
        frame.rect(5, 5, TextBehindMap.MIN_PIXELS + 2, 1, OPAQUE or 0x575A56)
        assertTrue(0x575A56 in frame.ground()!!.solid)
    }

    @Test
    fun onALightMapItsDarkRoomsAreWhatHoldTheTextDown() {
        val ground = mudletMap(intArrayOf(0x000080, 0x404040), background = OPAQUE or 0xFFFFFF).ground()!!
        assertEquals(0xFFFFFF, ground.background)
        val alpha = TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND)
        assertTrue("some, but less than the most: $alpha", alpha > 0f && alpha < TextBehindMap.MAX_ALPHA)
        assertTrue(keeps(ground, alpha))
        assertFalse(keeps(ground, step(alpha)))
    }

    @Test
    fun aTransparentFrameIsWeighedOverTheConsolesBackground() {
        val frame = mudletMap(background = 0)
        val ground = frame.ground()!!
        assertNull(ground.background)
        assertEquals(listOf(DARK_GREEN, DARK_CYAN, DARK_RED, LIGHT_GRAY), ground.solid)
        assertEquals(TextBehindMap.alpha(mudletMap().ground(), CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), TextBehindMap.alpha(ground, CONSOLE_FOREGROUND, CONSOLE_BACKGROUND), 0f)
        // Translucent pixels are an edge's, not a room's
        frame.rect(0, 0, 200, 200, 0x80FFFFFF.toInt())
        assertEquals(ground, frame.ground())
    }

    @Test
    fun aFramesGroundIsFoundWithoutAnObjectForEachOfItsPixels() {
        // Each frame of the map in front is weighed as it is decoded, and each behind the text before the swap: an
        // object or two for each pixel that is not the background, tens of megabytes a frame, is what made it slow
        val frame = mudletMap()
        // A first frame apart, as loading the classes it needs is counted too
        Frame(8, 8, OPAQUE).ground()
        val thread = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val id = Thread.currentThread().id
        val before = thread.getThreadAllocatedBytes(id)
        val ground = frame.ground()
        val allocated = thread.getThreadAllocatedBytes(id) - before
        assertEquals(listOf(DARK_GREEN, DARK_CYAN, DARK_RED, LIGHT_GRAY), ground?.solid)
        assertTrue("$allocated bytes for a frame of ${frame.pixels.size} pixels", allocated < frame.pixels.size / 4)
    }

    @Test
    fun theCutOutClearsTheBackgroundAlone() {
        val pixels = intArrayOf(OPAQUE or 0x102030, OPAQUE or 0x102031, 0x7F102030, OPAQUE or LIGHT_GRAY, OPAQUE or 0x102030)
        TextBehindMap.cutOut(pixels, 0x102030)
        assertEquals(listOf(0, OPAQUE or 0x102031, 0x7F102030, OPAQUE or LIGHT_GRAY, 0), pixels.toList())
    }

    @Test
    fun everyRoomKeepsThreeToOneAndOneMoreStepWouldLoseIt() {
        val random = Random(9011)
        repeat(2000) {
            val ground = TextBehindMap.Ground(random.nextInt(0x1000000), List(random.nextInt(1, 5)) { random.nextInt(0x1000000) })
            val foreground = random.nextInt(0x1000000)
            val background = random.nextInt(0x1000000)
            val alpha = TextBehindMap.alpha(ground, foreground, background)
            val message = "%s, #%06x on #%06x: $alpha".format(ground, foreground, background)
            if (alpha > 0f) assertTrue(message, keeps(ground, alpha, foreground, background))
            if (alpha < TextBehindMap.MAX_ALPHA) assertFalse(message, keeps(ground, step(alpha), foreground, background))
        }
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
