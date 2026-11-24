package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own download of the map a game offers: once in a profile's life, and only into a map with no rooms. */
class MapAutoDownloadTest {
    @Test
    fun anOfferToAnEmptyMapDownloadsItOnce() {
        val auto = MapAutoDownload(decided = false)
        assertTrue("an offer asks whether the map has rooms", auto.offered(offer()))
        auto.asked(1)
        assertFalse("not decided until the engine answers", auto.decided)
        assertEquals(MapAutoDownload.Answer.DOWNLOAD, auto.areas(1, anyRooms = false))
        assertTrue(auto.decided)
        assertFalse("the same offer again asks nothing", auto.offered(offer()))
        assertFalse("nor its download running", auto.offered(offer(MapDownloadState.DOWNLOADING)))
    }

    @Test
    fun aMapWithRoomsIsKeptAndTheProfileDecided() {
        val auto = MapAutoDownload(decided = false)
        assertTrue(auto.offered(offer()))
        auto.asked(1)
        assertEquals(MapAutoDownload.Answer.KEEP_MAP, auto.areas(1, anyRooms = true))
        assertTrue(auto.decided)
        // The player may empty the map later: the first offer decided, and the button is theirs
        assertFalse(auto.offered(offer()))
        assertEquals(MapAutoDownload.Answer.NOT_ASKED, auto.areas(1, anyRooms = false))
    }

    @Test
    fun aDecidedProfileNeverAsksAgain() {
        val auto = MapAutoDownload(decided = true)
        assertFalse(auto.offered(offer()))
        assertEquals(MapAutoDownload.Answer.NOT_ASKED, auto.areas(1, anyRooms = false))
    }

    @Test
    fun aFailedDownloadIsNotTriedAgain() {
        val auto = MapAutoDownload(decided = false)
        auto.offered(offer())
        auto.asked(1)
        assertEquals(MapAutoDownload.Answer.DOWNLOAD, auto.areas(1, anyRooms = false))
        assertFalse(auto.offered(offer(MapDownloadState.DOWNLOADING)))
        assertFalse("its failure", auto.offered(offer(MapDownloadState.FAILED)))
        // A restarted engine, or the profile opened again, has downloaded nothing yet
        auto.attached()
        assertFalse("the offer of a restarted engine", auto.offered(offer()))
        assertTrue(auto.decided)
    }

    @Test
    fun onlyAnOfferFromAHostWithNoDownloadYetIsAskedAbout() {
        val auto = MapAutoDownload(decided = false)
        assertFalse("no offer", auto.offered(offer(offered = false)))
        assertFalse("a download running", auto.offered(offer(MapDownloadState.DOWNLOADING)))
        assertFalse("the player's download loaded", auto.offered(offer(MapDownloadState.LOADED)))
        assertFalse("failed", auto.offered(offer(MapDownloadState.FAILED)))
        assertFalse("cancelled", auto.offered(offer(MapDownloadState.CANCELLED)))
        assertFalse("a URL with no host, as a file's", auto.offered(offer(host = "")))
        assertFalse(auto.decided)
        assertTrue(auto.offered(offer()))
    }

    @Test
    fun oneAskAtATimeAndOnlyItsOwnAnswer() {
        val auto = MapAutoDownload(decided = false)
        assertTrue(auto.offered(offer()))
        auto.asked(7)
        assertFalse("the offer again while asking asks no second time", auto.offered(offer()))
        assertEquals(MapAutoDownload.Answer.NOT_ASKED, auto.areas(8, anyRooms = false))
        assertFalse(auto.decided)
        assertEquals(MapAutoDownload.Answer.DOWNLOAD, auto.areas(7, anyRooms = false))
    }

    @Test
    fun anOfferThatWentWhileAskedDecidesNothing() {
        val auto = MapAutoDownload(decided = false)
        auto.offered(offer())
        auto.asked(1)
        auto.offered(offer(offered = false))
        assertEquals(MapAutoDownload.Answer.WAIT, auto.areas(1, anyRooms = false))
        assertFalse(auto.decided)
        // The player's own download began meanwhile
        assertTrue(auto.offered(offer()))
        auto.asked(2)
        auto.offered(offer(MapDownloadState.DOWNLOADING))
        assertEquals(MapAutoDownload.Answer.WAIT, auto.areas(2, anyRooms = false))
        assertFalse(auto.decided)
        // The player tapped the button meanwhile, which Mudlet has not answered yet
        assertTrue(auto.offered(offer()))
        auto.asked(3)
        assertEquals(MapAutoDownload.Answer.WAIT, auto.areas(3, anyRooms = false, downloadAsked = true))
        assertFalse(auto.decided)
    }

    @Test
    fun aRefusedAskIsAskedAgainAtTheNextOffer() {
        val auto = MapAutoDownload(decided = false)
        auto.offered(offer())
        auto.asked(1)
        assertFalse("another request's refusal", auto.refused(2))
        assertTrue(auto.refused(1))
        assertFalse(auto.decided)
        assertEquals("a late answer to it is not taken", MapAutoDownload.Answer.NOT_ASKED, auto.areas(1, anyRooms = false))
        assertTrue(auto.offered(offer()))
    }

    @Test
    fun anAskTheLastLinkSentGoesUnansweredOnceAttachedAgain() {
        val auto = MapAutoDownload(decided = false)
        auto.offered(offer())
        auto.asked(1)
        auto.attached()
        assertEquals(MapAutoDownload.Answer.NOT_ASKED, auto.areas(1, anyRooms = false))
        assertTrue("the engine's offer to the new link is asked about", auto.offered(offer()))
        auto.asked(2)
        assertEquals(MapAutoDownload.Answer.DOWNLOAD, auto.areas(2, anyRooms = false))
    }

    private fun offer(state: MapDownloadState = MapDownloadState.NONE, offered: Boolean = true, host: String = "maps.example.org") = EngineMessage.MapDownload(offered, host, state, label = "", value = 0, maximum = 0, cancelable = false, error = "")
}
