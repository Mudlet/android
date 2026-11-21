package org.mudlet.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour dialog's sums: hue, saturation and brightness to and from 0xRRGGBB, and its colours to tap. */
class ColourPickingTest {
    @Test
    fun theSixHuesAndTheGreysAreExact() {
        val primaries = mapOf(0f to 0xFF0000, 60f to 0xFFFF00, 120f to 0x00FF00, 180f to 0x00FFFF, 240f to 0x0000FF, 300f to 0xFF00FF, 360f to 0xFF0000)
        for ((hue, rgb) in primaries) assertEquals("hue $hue", hex(rgb), hex(Hsb(hue, 1f, 1f).toRgb()))
        assertEquals(hex(0x000000), hex(Hsb(200f, 0.7f, 0f).toRgb()))
        assertEquals(hex(0xFFFFFF), hex(Hsb(200f, 0f, 1f).toRgb()))
        assertEquals("Mudlet's red", hex(0x800000), hex(Hsb(0f, 1f, 128 / 255f).toRgb()))
    }

    @Test
    fun everyColourComesBackFromItsHueSaturationAndBrightness() {
        for (r in 0..255 step 15) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 5) {
                    val rgb = (r shl 16) or (g shl 8) or b
                    assertEquals(hex(rgb), hex(Hsb.of(rgb).toRgb()))
                }
            }
        }
    }

    @Test
    fun aColoursHueSaturationAndBrightnessAreTheUsualOnes() {
        val green = Hsb.of(0x12AB34)
        assertEquals(133.3f, green.hue, 0.1f)
        assertEquals(0.895f, green.saturation, 0.001f)
        assertEquals(0.671f, green.brightness, 0.001f)
        assertEquals(330f, Hsb.of(0xFF0080).hue, 0.5f)
    }

    @Test
    fun aGreyKeepsTheHueItHadAndBlackItsSaturationToo() {
        val before = Hsb(200f, 0.6f, 0.8f)
        assertEquals(Hsb(200f, 0f, 128 / 255f), Hsb.of(0x808080, before))
        assertEquals(Hsb(200f, 0.6f, 0f), Hsb.of(0x000000, before))
        assertEquals("with nothing before, a grey's hue is red's", Hsb(0f, 0f, 1f), Hsb.of(0xFFFFFF))
    }

    @Test
    fun talkBacksStepsGoFivePercentToTheNextMultipleAndStopAtTheEnds() {
        val colour = Hsb(10f, 0.53f, 128 / 255f)
        assertEquals(0.55f, colour.stepSaturation(1).saturation, 0.0001f)
        assertEquals(0.5f, colour.stepSaturation(-1).saturation, 0.0001f)
        assertEquals(0.55f, colour.stepBrightness(1).brightness, 0.0001f)
        assertEquals(0.45f, colour.stepBrightness(-1).brightness, 0.0001f)
        assertEquals(1f, Hsb(10f, 0.99f, 0f).stepSaturation(1).saturation, 0f)
        assertEquals(0f, Hsb(10f, 0.5f, 0.01f).stepBrightness(-1).brightness, 0f)
        assertEquals("the hue stays", 10f, colour.stepBrightness(1).hue, 0f)
    }

    @Test
    fun mudletsColourIsOfferedFirstUnlessItIsOneOfThePalettes() {
        val palette = listOf(0x000000, 0x800000, 0xFFFFFF)
        assertEquals(palette, quickPicks(palette, 0x800000))
        assertEquals(listOf(0x717100) + palette, quickPicks(palette, 0x717100))
    }

    @Test
    fun theSelectedMarkIsBlackOnLightColoursAndWhiteOnDarkOnes() {
        for (light in listOf(0xFFFFFF, 0xFFFF00, 0x00FF00, 0x00FFFF, 0xC0C0C0, 0xD3D3D3)) assertTrue(hex(light), darkMarkReads(light))
        for (dark in listOf(0x000000, 0x800000, 0x000080, 0x0000FF, 0x404040, 0x800080)) assertFalse(hex(dark), darkMarkReads(dark))
    }

    private fun hex(rgb: Int) = "#%06x".format(rgb)
}
