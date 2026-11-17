package org.mudlet.android.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A PNG from the engine's own writer for maps (`native/enginehost/MapPng.cpp`), read by Android's decoder as the app
 * reads a map frame: the host tests read the writer's PNGs with Qt and zlib only. The picture is the one
 * `native/tests/map_png_test.cpp` makes, which checks that the asset is still what the writer makes of it.
 */
@RunWith(AndroidJUnit4::class)
class MapPngTest {
    @Test
    fun androidReadsEachPixelOfTheMapWritersPng() {
        val png = InstrumentationRegistry.getInstrumentation().context.assets.open("map-picture.png").use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull("Android decodes the PNG", bitmap)
        assertEquals("its size", WIDTH to HEIGHT, bitmap.width to bitmap.height)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                assertEquals("the pixel at $x, $y", pixel(x, y).toUInt().toString(16), bitmap.getPixel(x, y).toUInt().toString(16))
            }
        }
    }

    /** `picturePixel()` in `map_png_test.cpp`, as ARGB. */
    private fun pixel(x: Int, y: Int): Int = when {
        y < 4 -> Color.TRANSPARENT
        x == 7 -> Color.rgb(255, 255, 255)
        y < 12 -> Color.rgb(0x20, 0x40, 0x60)
        y < 16 -> if (x < 74) Color.rgb(0x33, 0x66, 0x99) else Color.rgb(0, 0, 0)
        x < 60 -> Color.rgb(0x80, 0, 0)
        else -> Color.rgb((x * 37 + y * 101) and 0xFF, ((x * 59) xor (y * 83)) and 0xFF, (x + y * 7) and 0xFF)
    }

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 24
    }
}
