package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBehindTextTest {
    private fun readable(foreground: Int, background: Int, alpha: Float) = listOf(0xFFFFFF, 0x000000, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF).all {
        MapBehindText.contrast(foreground, MapBehindText.blend(background, it, alpha)) >= MapBehindText.MIN_CONTRAST
    }

    @Test
    fun theDefaultColoursShowTheMapAtItsMost() {
        // The console's defaults, CONSOLE_FOREGROUND on CONSOLE_BACKGROUND
        val alpha = MapBehindText.alpha(0xC0C0C0, 0x000000)
        assertEquals(MapBehindText.MAX_ALPHA, alpha, 0f)
        assertTrue("grey text over a white room at that alpha", readable(0xC0C0C0, 0x000000, alpha))
        assertEquals(MapBehindText.MAX_ALPHA, MapBehindText.alpha(0x000000, 0xFFFFFF), 0f)
    }

    @Test
    fun dimmerTextGetsAFainterMapThatKeepsItReadable() {
        val alpha = MapBehindText.alpha(0x808080, 0x000000)
        assertTrue("fainter than the most, but some: $alpha", alpha > 0f && alpha < MapBehindText.MAX_ALPHA)
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
    }

    @Test
    fun textAlreadyHardToReadGetsNoMap() {
        // Mudlet's dark blue on black is below 4.5:1 on its own
        assertTrue(MapBehindText.contrast(0x000080, 0x000000) < MapBehindText.MIN_CONTRAST)
        assertEquals(0f, MapBehindText.alpha(0x000080, 0x000000), 0f)
    }

    @Test
    fun theContrastAndBlendAreWcagsAndSrgbs() {
        assertEquals(21.0, MapBehindText.contrast(0xFFFFFF, 0x000000), 1e-9)
        assertEquals(1.0, MapBehindText.contrast(0x123456, 0x123456), 1e-9)
        assertEquals(0x4D4D4D, MapBehindText.blend(0x000000, 0xFFFFFF, 0.3f))
        assertEquals(0x804020, MapBehindText.blend(0x804020, 0x000000, 0f))
    }
}
