package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPlacementTest {
    @Test
    fun onAnExpandedScreenTheMapIsBesideTheTextFromTheFirst() {
        assertTrue(MapPlacement.fitsBeside(EXPANDED, beside = true, scriptMapper = false))
        assertTrue(MapPlacement.fitsBeside(TABLET, beside = true, scriptMapper = false))
        assertEquals(MapPlace.BESIDE, MapPlacement().place(fits = true))
        // Just short of expanded, a phone in landscape or a half-unfolded foldable, it is not
        assertFalse(MapPlacement.fitsBeside(EXPANDED - 1, beside = true, scriptMapper = false))
        assertFalse(MapPlacement.fitsBeside(PHONE, beside = true, scriptMapper = false))
        assertEquals(MapPlace.NONE, MapPlacement().place(fits = false))
    }

    @Test
    fun withoutTheSettingOrWithAScriptsMapperTheMapIsNeverBeside() {
        assertFalse(MapPlacement.fitsBeside(TABLET, beside = false, scriptMapper = false))
        assertFalse(MapPlacement.fitsBeside(TABLET, beside = true, scriptMapper = true))
        // The map button then brings it to the front, as on a phone
        assertEquals(MapPlace.IN_FRONT, MapPlacement().toggled(fits = false).place(fits = false))
    }

    @Test
    fun theMapButtonPutsTheMapBesideTheTextAwayAndBringsItBack() {
        val hidden = MapPlacement().toggled(fits = true)
        assertEquals(MapPlace.NONE, hidden.place(fits = true))
        val back = hidden.toggled(fits = true)
        assertEquals(MapPlace.BESIDE, back.place(fits = true))
        assertEquals(MapPlacement(), back)
    }

    @Test
    fun onAPhoneTheMapButtonSwapsTheMapAndTheText() {
        val front = MapPlacement().toggled(fits = false)
        assertEquals(MapPlace.IN_FRONT, front.place(fits = false))
        assertEquals(MapPlace.NONE, front.toggled(fits = false).place(fits = false))
    }

    @Test
    fun aFoldOrAResizeKeepsWhatThePlayerChose() {
        // The map in front on the folded phone goes beside the text as it unfolds, and to the front again as it folds
        val front = MapPlacement().toggled(fits = false)
        assertEquals(MapPlace.BESIDE, front.place(fits = true))
        assertEquals(MapPlace.IN_FRONT, front.place(fits = false))
        // Put away there, with the button, it stays away on the folded phone and the unfolded one
        val away = front.toggled(fits = true)
        assertEquals(MapPlace.NONE, away.place(fits = false))
        assertEquals(MapPlace.NONE, away.place(fits = true))
        // A map beside the text that the screen narrows under leaves the text, rather than covering it
        assertEquals(MapPlace.NONE, MapPlacement().place(fits = false))
        // Hidden beside the text, the button on the folded phone brings it to the front, which is beside again unfolded
        val frontAgain = away.toggled(fits = false)
        assertEquals(MapPlace.IN_FRONT, frontAgain.place(fits = false))
        assertEquals(MapPlace.BESIDE, frontAgain.place(fits = true))
    }

    @Test
    fun theMapBesideTheTextLeavesBothTheirLeast() {
        assertEquals(MapPlacement.MAP_LEAST_DP, MapPlacement.besideWidthDp(EXPANDED))
        assertEquals(EXPANDED - MapPlacement.CONSOLE_LEAST_DP, MapPlacement.besideWidthDp(EXPANDED))
        assertEquals(TABLET * 0.4f, MapPlacement.besideWidthDp(TABLET))
        for (width in listOf(EXPANDED, 900f, TABLET, 2560f)) {
            val map = MapPlacement.besideWidthDp(width)
            assertTrue("$width: the map $map", map >= MapPlacement.MAP_LEAST_DP && width - map >= MapPlacement.CONSOLE_LEAST_DP)
        }
    }

    @Test
    fun aScriptsMapperCountsOnlyWhileItIsOnTheScreen() {
        assertTrue(MapPlacement.scriptMapperShown(listOf(window("chat"), window("map", WindowKind.MAPPER))))
        assertFalse(MapPlacement.scriptMapperShown(listOf(window("map", WindowKind.MAPPER, shown = false))))
        assertFalse(MapPlacement.scriptMapperShown(listOf(window("chat"))))
    }

    private companion object {
        const val PHONE = 411f
        const val EXPANDED = MapPlacement.EXPANDED_WIDTH_DP
        const val TABLET = 1280f
    }
}
