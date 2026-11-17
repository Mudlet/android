package org.mudlet.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertCutOut("the colours", pixels(source), pixels(cut))
        // What is left is the decoder's, byte for byte, the translucent pixels too
        assertCutOut("the bytes kept", kept(source), kept(cut))
    }

    @Test
    fun aBitmapThatKeepsItsPixelsAnotherWayIsCutOutByItsColours() {
        val source = checkNotNull(Bitmap.createBitmap(frame(), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.RGB_565, false)) { "a copy at 16 bits a pixel" }
        val overText = checkNotNull(AppMapClient.overText(source))
        assertEquals(TextBehindMap.ground(pixels(source), WIDTH, HEIGHT), overText.ground)
        assertCutOut("the colours", pixels(source), pixels(overText.frame.asAndroidBitmap()))
    }

    // [cut] is [source] with each pixel of the background, which is its first pixel, transparent
    private fun assertCutOut(what: String, source: IntArray, cut: IntArray) {
        assertEquals("$what: the first pixel, of the background", "0", hex(cut[0]))
        for (i in source.indices) {
            assertEquals("$what: the pixel at ${i % WIDTH}, ${i / WIDTH}", hex(if (source[i] == source[0]) 0 else source[i]), hex(cut[i]))
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
        const val ROOM = 0x2060A0
        const val OTHER_ROOM = 0xA06020
        const val EXIT = 0xC0D0E0

        // A third opaque: what a bitmap keeps of its red, premultiplied, is 0x55 and not its 0xFF
        const val TRANSLUCENT = 0x55FF8040
    }
}
