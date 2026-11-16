package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapFitTest {
    @Test
    fun aFrameIsScaledAlikeBothWaysAndCentredInItsBox() {
        // 200 by 100 in a box 600 by 400 at (50, 20): scaled 3 times, 50 above and below
        val fit = MapFit(200, 100, PxRect(50f, 20f, 600f, 400f))
        assertEquals(3f, fit.scale)
        assertEquals(PxRect(50f, 70f, 600f, 300f), fit.drawn)
        assertEquals(MapPoint(100.0, 50.0), fit.centre)
    }

    @Test
    fun theMapIsAskedForAtTheScaleItIsDrawnAtWithinWhatTheEngineTakes() {
        assertEquals(3.0, MapFit(200, 100, PxRect(50f, 20f, 600f, 400f)).renderScale, 0.0)
        // A small mapper filling a phone's pane: drawn 6.17 times larger
        assertEquals(6.17, MapFit(175, 150, PxRect(0f, 0f, 1080f, 2000f)).renderScale, 1e-9)
        // Within the engine's 1 to 8, and 1 for no size
        assertEquals(1.0, MapFit(400, 300, PxRect(0f, 0f, 200f, 150f)).renderScale, 0.0)
        assertEquals(MapFit.MAX_RENDER_SCALE, MapFit(20, 20, PxRect(0f, 0f, 1000f, 1000f)).renderScale, 0.0)
        assertEquals(1.0, MapFit(0, 100, PxRect(0f, 0f, 100f, 100f)).renderScale, 0.0)
    }

    @Test
    fun aTouchGoesBackToThePointOfTheFrameUnderIt() {
        val fit = MapFit(200, 100, PxRect(50f, 20f, 600f, 400f))
        assertEquals(MapPoint(0.0, 0.0), fit.toFrame(50f, 70f))
        assertEquals(MapPoint(100.0, 50.0), fit.toFrame(350f, 220f))
        assertEquals(MapPoint(10.5, 20.0), fit.toFrame(81.5f, 130f))
        // Its last pixel is still inside the frame
        assertEquals(MapPoint(199.0, 99.0), fit.toFrame(649.9f, 369.9f))
        // Above the drawing, in the box's band, and right of the box: nothing
        assertNull(fit.toFrame(350f, 60f))
        assertNull(fit.toFrame(650f, 200f))
    }

    @Test
    fun aFrameOrBoxWithNoSizeTakesNoTouch() {
        assertNull(MapFit(0, 100, PxRect(0f, 0f, 100f, 100f)).toFrame(10f, 10f))
        assertNull(MapFit(100, 100, PxRect(0f, 0f, 0f, 100f)).toFrame(0f, 10f))
    }

    @Test
    fun aPinchIsWholeNotchesOf7PercentAndKeepsTheRest() {
        val notches = ZoomNotches()
        // 1.07 squared, and a little: two notches in
        assertEquals(2, notches.add(1.15f))
        // A notch more, with the little left over kept
        assertEquals(1, notches.add(1.07f))
        assertEquals(0, notches.add(1.0f))
        // Pinched back by 1.07 cubed and a little: three out
        assertEquals(-3, notches.add(0.8f))
        assertEquals(0, notches.add(Float.NaN))
        assertEquals(0, notches.add(0f))
    }
}
