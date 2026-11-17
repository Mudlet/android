package org.mudlet.android.ui

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.TextBehindMap
import java.io.ByteArrayOutputStream
import java.nio.IntBuffer

/**
 * The frame [AppMapClient] makes to draw over the console's text, of a PNG as Android decodes it: the JVM tests of
 * `TextBehindMap` take a bitmap's own pixels as they say Android keeps them, which only a device shows.
 */
@RunWith(AndroidJUnit4::class)
class AppMapOverTextTest {
    private var textBehind = false

    @Before
    fun theTextIsBehindTheMap() {
        textBehind = AppMapClient.textBehind
        AppMapClient.textBehind = true
    }

    @After
    fun asItWas() {
        AppMapClient.textBehind = textBehind
    }

    @Test
    fun aDecodedFrameIsCutOutOfItsOwnPixelsWithTheGroundItsColoursHave() {
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(frame(), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = AppMapClient.decode(EngineMessage.AppMap(png, WIDTH, HEIGHT, viewOnly = true))
        val source = checkNotNull(decoded.bitmap) { "the PNG decodes" }.asAndroidBitmap()
        assertEquals("the bitmap keeps its pixels as they are taken to be kept", Bitmap.Config.ARGB_8888, source.config)

        val overText = checkNotNull(decoded.overText) { "the frame is cut out as it is decoded" }
        assertEquals(TextBehindMap.Ground(BACKGROUND, listOf(ROOM, OTHER_ROOM)), overText.ground)
        val cut = overText.frame.asAndroidBitmap()
        assertCutOut("the colours", pixels(source), pixels(cut), OPAQUE or BACKGROUND)
        // What is left is the decoder's, byte for byte, the translucent pixels too
        assertCutOut("the bytes kept", kept(source), kept(cut), OPAQUE or BACKGROUND_KEPT)
    }

    @Test
    fun aBitmapThatKeepsItsPixelsAnotherWayIsCutOutByItsColours() {
        val sixteenBits = checkNotNull(Bitmap.createBitmap(frame(), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.RGB_565, false)) { "a copy at 16 bits a pixel" }
        val displayP3 = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        displayP3.setPixels(frame(), 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val notPremultiplied = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        notPremultiplied.isPremultiplied = false
        notPremultiplied.setPixels(frame(), 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        for ((what, source) in listOf("16 bits a pixel" to sixteenBits, "Display P3" to displayP3, "not premultiplied" to notPremultiplied)) {
            val overText = checkNotNull(AppMapClient.overText(source)) { what }
            val expected = pixels(source)
            val background = expected[0]
            assertEquals(what, TextBehindMap.cutOut(expected.copyOf(), WIDTH, HEIGHT), overText.ground)
            assertEquals("$what: the frame's first pixel is its background's", background, OPAQUE or checkNotNull(overText.ground.background) { "$what: a background" })
            for (i in expected.indices) if (expected[i] == background) expected[i] = 0
            val cut = Bitmap.createBitmap(expected, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            assertTrue("$what: the frame of its colours with the background's cut out", cut.sameAs(overText.frame.asAndroidBitmap()))
        }
    }

    @Test
    fun aFrameIsCutOutInTheArrayKeptFromTheLast() {
        val source = Bitmap.createBitmap(frame(), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val before = AppMapClient.cutOutPixels.use(WIDTH * HEIGHT) { it.apply { fill(0) } }
        val cut = checkNotNull(AppMapClient.overText(source)) { "the frame is cut out" }.frame.asAndroidBitmap()
        val after = AppMapClient.cutOutPixels.use(WIDTH * HEIGHT) { it }
        assertSame("no array is made for a frame of the last one's size", before, after)
        assertArrayEquals("the frame was cut out in it", kept(cut), after)
    }

    // [cut] is [source] with each pixel that is [background] transparent
    private fun assertCutOut(what: String, source: IntArray, cut: IntArray, background: Int) {
        assertTrue("$what: the background ${hex(background)} is in the frame", background in source)
        for (i in source.indices) {
            assertEquals("$what: the pixel at ${i % WIDTH}, ${i / WIDTH}", hex(if (source[i] == background) 0 else source[i]), hex(cut[i]))
        }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    // The bytes [bitmap] keeps, as Ints
    private fun kept(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.copyPixelsToBuffer(IntBuffer.wrap(it)) }

    private fun hex(pixel: Int) = pixel.toUInt().toString(16)

    // Two rooms and an exit between them on an opaque background, and a patch that is translucent, as 0xAARRGGBB: each
    // colour's red is not its blue, so a channel taken for the other shows
    private fun frame(): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { OPAQUE or BACKGROUND }
        fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
            for (row in y until y + h) for (column in x until x + w) pixels[row * WIDTH + column] = colour
        }
        rect(20, 20, 30, 30, OPAQUE or ROOM)
        rect(90, 20, 30, 30, OPAQUE or OTHER_ROOM)
        rect(50, 34, 40, 1, OPAQUE or EXIT)
        rect(20, 70, 60, 20, TRANSLUCENT)
        return pixels
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 100
        const val OPAQUE = 0xFF shl 24
        const val BACKGROUND = 0x102030

        // As a bitmap keeps it: red first, read as an Int of a little-endian device
        const val BACKGROUND_KEPT = 0x302010
        const val ROOM = 0x2060A0
        const val OTHER_ROOM = 0xA06020
        const val EXIT = 0xC0D0E0

        // A third opaque: what a bitmap keeps of its red, premultiplied, is 0x55 and not its 0xFF
        const val TRANSLUCENT = 0x55FF8040
    }
}
