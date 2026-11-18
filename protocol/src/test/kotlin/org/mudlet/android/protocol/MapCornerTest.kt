package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapCornerTest {
    @Test
    fun aSmallMapInTheTopRightGrowsToTwoFifthsOfTheAreaWithItsPanelUnderIt() {
        // A phone's game area 400 by 600 dp at 2 pixels to the dp, and a mapper 70 dp square at its top right
        val area = PxRect(0f, 0f, 800f, 1200f)
        val corner = MapCorner.of(PxRect(640f, 20f, 140f, 140f), area, emptyList(), 2f)!!
        assertRect(PxRect(460f, 20f, 320f, 320f), corner.map)
        assertTrue(corner.below)
        assertTrue(corner.right)
        // As wide as four buttons, against the map's right edge, and as high as the map
        assertRect(PxRect(380f, 340f, 400f, 320f), corner.panel)
        assertRect(PxRect(380f, 20f, 400f, 640f), corner.bounds)
    }

    @Test
    fun theMapKeepsItsShapeAndGrowsNoFurtherThanEitherSideAllows() {
        // Twice as wide as high: the width reaches two fifths first
        val corner = MapCorner.of(PxRect(300f, 0f, 100f, 50f), PxRect(0f, 0f, 400f, 600f), emptyList(), 1f)!!
        assertRect(PxRect(240f, 0f, 160f, 80f), corner.map)
        // Twice as high as wide, in a wide area: the height does
        val tall = MapCorner.of(PxRect(0f, 0f, 40f, 80f), PxRect(0f, 0f, 1000f, 400f), emptyList(), 1f)!!
        assertRect(PxRect(0f, 0f, 80f, 160f), tall.map)
        assertFalse(tall.right)
    }

    @Test
    fun aNarrowAreaGrowsTheMapToTheLeastAndAWideOneNoFurtherThanTheMost() {
        // Two fifths of 250 dp is less than 120
        val narrow = MapCorner.of(PxRect(200f, 0f, 50f, 50f), PxRect(0f, 0f, 250f, 600f), emptyList(), 1f)!!
        assertRect(PxRect(130f, 0f, 120f, 120f), narrow.map)
        // The panel is never wider than the area
        val tiny = MapCorner.of(PxRect(100f, 0f, 50f, 50f), PxRect(0f, 0f, 150f, 600f), emptyList(), 1f)!!
        assertRect(PxRect(0f, 120f, 150f, 120f), tiny.panel)
        // And of 1200 dp more than 320
        val wide = MapCorner.of(PxRect(1100f, 0f, 100f, 100f), PxRect(0f, 0f, 1200f, 900f), emptyList(), 1f)!!
        assertRect(PxRect(880f, 0f, 320f, 320f), wide.map)
    }

    @Test
    fun aMapInTheBottomLeftGrowsUpAndRightWithItsPanelOverIt() {
        val corner = MapCorner.of(PxRect(10f, 500f, 50f, 50f), PxRect(0f, 0f, 400f, 600f), emptyList(), 1f)!!
        assertRect(PxRect(10f, 390f, 160f, 160f), corner.map)
        assertFalse(corner.below)
        assertFalse(corner.right)
        assertRect(PxRect(10f, 230f, 200f, 160f), corner.panel)
        assertRect(PxRect(10f, 230f, 200f, 320f), corner.bounds)
        // Near the top, the panel is as high as the room over the map
        val high = MapCorner.of(PxRect(10f, 100f, 50f, 50f), PxRect(0f, 0f, 400f, 200f), emptyList(), 1f)!!
        assertRect(PxRect(10f, 56f, 94f, 94f), high.map)
        assertRect(PxRect(10f, 0f, 200f, 56f), high.panel)
    }

    @Test
    fun aMapAsLargeAsItWouldGrowKeepsItsSizeAndOneWithNoRoomForThePanelIsDrawnWhereItIs() {
        val phone = PxRect(0f, 0f, 400f, 600f)
        val large = MapCorner.of(PxRect(200f, 0f, 200f, 300f), phone, emptyList(), 1f)!!
        assertRect(PxRect(200f, 0f, 200f, 300f), large.map)
        assertRect(PxRect(200f, 300f, 200f, 300f), large.panel)
        // Nor over another window, but over the background they are both on
        val chat = PxRect(200f, 300f, 200f, 100f)
        assertNull(MapCorner.of(PxRect(200f, 0f, 200f, 300f), phone, listOf(chat), 1f))
        val background = PxRect(200f, 0f, 200f, 600f)
        assertRect(PxRect(200f, 300f, 200f, 300f), MapCorner.of(PxRect(200f, 0f, 200f, 300f), phone, listOf(background), 1f)!!.panel)
        // A row of buttons more fits under it, and no less
        assertRect(PxRect(200f, 0f, 200f, 544f), MapCorner.of(PxRect(200f, 0f, 200f, 544f), phone, emptyList(), 1f)!!.map)
        assertNull(MapCorner.of(PxRect(200f, 0f, 200f, 545f), phone, emptyList(), 1f))
        // A short area, a keyboard's height under it: the map grows only as far as leaves a row of buttons under it
        val short = PxRect(0f, 0f, 800f, 100f)
        assertRect(PxRect(756f, 0f, 44f, 44f), MapCorner.of(PxRect(760f, 0f, 40f, 40f), short, emptyList(), 1f)!!.map)
        assertNull(MapCorner.of(PxRect(750f, 0f, 50f, 50f), short, emptyList(), 1f))
        // Nor one with no size
        assertNull(MapCorner.of(PxRect(0f, 0f, 0f, 50f), phone, emptyList(), 1f))
    }

    @Test
    fun theMapStopsShortOfAnotherWindowButNotOfOneItWasAlreadyOver() {
        val area = PxRect(0f, 0f, 400f, 600f)
        val window = PxRect(320f, 0f, 80f, 80f)
        // A gauge to its left, up to 280 across: it grows to 120 wide, its right edge kept
        val gauge = PxRect(260f, 0f, 20f, 20f)
        val stopped = MapCorner.of(window, area, listOf(gauge), 1f)!!
        assertEquals(400f, stopped.map.right, NEAR)
        assertEquals(120f, stopped.map.w, NEAR)
        assertNull(stopped.map.intersect(gauge))
        // The background the map is on, and a gauge already over it, stop nothing
        val background = PxRect(0f, 0f, 400f, 100f)
        val over = PxRect(330f, 10f, 20f, 20f)
        assertRect(PxRect(240f, 0f, 160f, 160f), MapCorner.of(window, area, listOf(background, over), 1f)!!.map)
        // Nor does one touching it
        val beside = PxRect(300f, 0f, 20f, 20f)
        val touching = MapCorner.of(window, area, listOf(beside), 1f)!!
        assertRect(PxRect(320f, 0f, 80f, 80f), touching.map)
        assertRect(PxRect(200f, 80f, 200f, 80f), touching.panel)
        // A small one stops short of a window under it, and its panel goes over that
        val under = MapCorner.of(window, area, listOf(PxRect(200f, 100f, 200f, 20f)), 1f)!!
        assertRect(PxRect(300f, 0f, 100f, 100f), under.map)
        assertRect(PxRect(200f, 100f, 200f, 100f), under.panel)
    }

    @Test
    fun theMapAndItsPanelAreInsideTheAreaAndNeverOverlap() {
        var grown = 0
        for ((aw, ah) in listOf(320f to 430f, 320f to 200f, 640f to 250f, 411f to 605f, 800f to 136f, 1200f to 800f)) {
            val area = PxRect(0f, 0f, aw, ah)
            for (x in 0..9) {
                for (y in 0..9) {
                    for (size in listOf(20f, 50f, 90f, 140f, 300f)) {
                        val window = PxRect((aw - size) * x / 9, (ah - size) * y / 9, size, size * 0.75f)
                        if (!window.within(area)) continue
                        val corner = MapCorner.of(window, area, emptyList(), 1f) ?: continue
                        grown++
                        val what = "$window in $area: $corner"
                        assertNull(what, corner.map.intersect(corner.panel))
                        assertTrue(what, corner.map.within(area) && corner.panel.within(area))
                        assertTrue(what, corner.map.w >= window.w && corner.map.h >= window.h - NEAR)
                        assertTrue(what, corner.panel.h >= MapCorner.ROW_DP - NEAR && corner.panel.h <= maxOf(corner.map.h, MapCorner.ROW_DP) + NEAR)
                        assertTrue(what, corner.map.w <= maxOf(aw * MapCorner.FRACTION, MapCorner.MIN_DP, window.w) + NEAR)
                    }
                }
            }
        }
        assertTrue("$grown grew", grown > 500)
    }

    @Test
    fun aMapperItsParentsCutIsCutAlikeWhereverItIsDrawn() {
        val mapper = ScriptWindow("map", WindowKind.MAPPER, "", null, 700, 0, 100, 100, true, true, 1, -1, 255, null, 0.0, false, null, null)
        // Shown whole, it fills the box, whatever the box's shape: the map is then fitted to the whole box, not shrunk
        val whole = PlacedWindow(mapper, PxRect(700f, 0f, 100f, 100f), PxRect(700f, 0f, 100f, 100f))
        assertRect(PxRect(0f, 0f, 200f, 200f), whole.wholeIn(PxRect(0f, 0f, 200f, 200f)))
        assertRect(PxRect(0f, 0f, 200f, 100f), whole.wholeIn(PxRect(0f, 0f, 200f, 100f)))
        // Its left half cut off by its parent: the half shown fills the box, the rest off its left
        val cut = PlacedWindow(mapper, PxRect(750f, 0f, 50f, 100f), PxRect(700f, 0f, 100f, 100f))
        assertRect(PxRect(-100f, 0f, 200f, 200f), cut.wholeIn(PxRect(0f, 0f, 100f, 200f)))
        assertRect(PxRect(-100f, 0f, 200f, 100f), cut.wholeIn(PxRect(0f, 0f, 100f, 100f)))
        // Nor is a box divided by a window with no size
        assertRect(PxRect(0f, 0f, 10f, 10f), PlacedWindow(mapper, PxRect(0f, 0f, 0f, 0f), PxRect(0f, 0f, 0f, 0f)).wholeIn(PxRect(0f, 0f, 10f, 10f)))
    }

    @Test
    fun theAppsMapGrowsFromASquareAtTheConsolesTopRightAsASmallMapperDoes() {
        // A phone's console, 360 by 500 dp: 0.4 of its width across and down, its panel under it, four buttons wide
        val phone = MapCorner.ofAppMap(PxRect(0f, 0f, 360f, 500f), emptyList(), 1f)!!
        assertRect(PxRect(216f, 0f, 144f, 144f), phone.map)
        assertRect(PxRect(160f, 144f, 200f, 144f), phone.panel)
        assertTrue(phone.right && phone.below)
        // A tablet's, at 2 pixels to the dp, below the title bar: 0.4 of its height, the shorter way
        val tablet = MapCorner.ofAppMap(PxRect(0f, 100f, 1600f, 1000f), emptyList(), 2f)!!
        assertRect(PxRect(1200f, 100f, 400f, 400f), tablet.map)
        assertRect(PxRect(1200f, 500f, 400f, 400f), tablet.panel)
        // A large one's is no larger than 320 dp
        assertRect(PxRect(1680f, 0f, 320f, 320f), MapCorner.ofAppMap(PxRect(0f, 0f, 2000f, 2000f), emptyList(), 1f)!!.map)

        // Too short for the least of it and a row of buttons, as a landscape phone's console above the keyboard, or too
        // narrow for the least of it: none
        assertNull(MapCorner.ofAppMap(PxRect(0f, 0f, 640f, 150f), emptyList(), 1f))
        assertNull(MapCorner.ofAppMap(PxRect(0f, 0f, 100f, 500f), emptyList(), 1f))

        // A label over the console that the least of it is clear of stops it short; with one where the least of it
        // would be, none, as it would hide the label
        val stopped = MapCorner.ofAppMap(PxRect(0f, 0f, 360f, 500f), listOf(PxRect(220f, 130f, 10f, 10f)), 1f)!!
        assertEquals(130f, stopped.map.w, NEAR)
        assertEquals(360f, stopped.map.right, NEAR)
        assertNull(MapCorner.ofAppMap(PxRect(0f, 0f, 360f, 500f), listOf(PxRect(300f, 50f, 20f, 20f)), 1f))
    }

    private fun assertRect(expected: PxRect, actual: PxRect) {
        val near = listOf(expected.x to actual.x, expected.y to actual.y, expected.w to actual.w, expected.h to actual.h).all { (e, a) -> abs(e - a) < NEAR }
        assertTrue("expected $expected but was $actual", near)
    }

    private fun PxRect.within(outer: PxRect) = x >= outer.x - NEAR && y >= outer.y - NEAR && right <= outer.right + NEAR && bottom <= outer.bottom + NEAR

    private companion object {
        // Float rounding of a pixel
        const val NEAR = 0.01f
    }
}
