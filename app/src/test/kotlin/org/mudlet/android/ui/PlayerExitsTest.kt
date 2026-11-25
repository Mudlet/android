package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.RoomExit
import org.mudlet.android.protocol.RoomInfo
import org.mudlet.android.ui.game.MovementKey

/** The Room exits pad's ways out of the player's room, from the maps' frames and the answers to what it asks. */
class PlayerExitsTest {
    private val sent = ArrayList<JSONObject>()
    private val changes = ArrayList<Set<MovementKey>?>()
    private var attached = true
    private var lastId = 0L
    private val exits = PlayerExits({ ++lastId }, { message -> attached.also { if (it) sent.add(message) } }) { changes.add(it) }.apply { watch(true) }

    private fun view(player: Int) = MapViewInfo(1, "Area", 0, 1.0, 0.5, 4.0, true, player, 20.0, 0, emptyList())

    private fun room(id: Int, vararg directions: String) = RoomInfo(id, "Room $id", 1, "Area", 0, 0, 0, 0, directions.map { RoomExit(it, id + 1, "", false) })

    /** The roomInfo sent last, taken off, which must ask for [room]: its id. */
    private fun asked(room: Int): Long {
        val request = sent.removeAt(sent.lastIndex)
        assertEquals("roomInfo", request.getString("t"))
        assertEquals(room, request.getInt("room"))
        return request.getLong("id")
    }

    @Test
    fun aFrameWithThePlayersRoomAsksForItAndItsAnswerGivesTheTwelveWaysItHas() {
        exits.onFrame(MapTarget.APP, view(7))
        val id = asked(7)
        assertNull("not known till the answer", exits.exits)
        assertTrue(exits.onRoom(EngineMessage.Room(room(7, "n", "se", "up", "out", "climb rope"), id)))
        assertEquals(setOf(MovementKey.NORTH, MovementKey.SOUTHEAST, MovementKey.UP, MovementKey.OUT), exits.exits)
        assertEquals(listOf(exits.exits), changes)
    }

    @Test
    fun onlyWhileThePadWatchesIsAnythingAskedOrKept() {
        exits.watch(false)
        exits.onFrame(MapTarget.APP, view(7))
        assertTrue("no Room exits pad, no request", sent.isEmpty())
        exits.watch(true)
        val id = asked(7)
        exits.watch(false)
        assertTrue("its own, though no longer wanted", exits.onRoom(EngineMessage.Room(room(7, "n"), id)))
        assertFalse(exits.onRoom(EngineMessage.Room(room(7, "n"), 12L)))
        assertNull(exits.exits)
        exits.watch(true)
        exits.onRoom(EngineMessage.Room(room(7, "e"), asked(7)))
        exits.watch(false)
        assertNull("not kept for a pad that comes back later", exits.exits)
        exits.onAttached(sameInstance = true)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theSameRoomAgainAsksNothingAndAnotherAsksWhileTheLastExitsStay() {
        exits.onFrame(MapTarget.APP, view(7))
        exits.onRoom(EngineMessage.Room(room(7, "n"), asked(7)))
        exits.onFrame(MapTarget.APP, view(7))
        assertTrue("each frame of one room", sent.isEmpty())
        exits.onFrame(MapTarget.APP, view(8))
        val id = asked(8)
        assertEquals("no flicker between the rooms", setOf(MovementKey.NORTH), exits.exits)
        exits.onRoom(EngineMessage.Room(room(8, "s"), id))
        assertEquals(setOf(MovementKey.SOUTH), exits.exits)
    }

    @Test
    fun aMapWithNoRoomForThePlayerHasNoExits() {
        exits.onFrame(MapTarget.APP, view(7))
        exits.onRoom(EngineMessage.Room(room(7, "n"), asked(7)))
        exits.onFrame(MapTarget.APP, view(0))
        assertNull(exits.exits)
        assertTrue(sent.isEmpty())
        exits.onFrame(MapTarget.APP, view(7))
        asked(7)
        exits.onFrame(MapTarget.APP, null)
        assertNull("the map gone", exits.exits)
        assertEquals(listOf(setOf(MovementKey.NORTH), null), changes)
    }

    @Test
    fun theMapperWindowsRoomCountsWhileTheAppsMapHasNone() {
        exits.onFrame(MapTarget.MAPPER, view(3))
        asked(3)
        exits.onFrame(MapTarget.APP, view(5))
        asked(5)
        exits.onFrame(MapTarget.MAPPER, view(4))
        assertTrue("the app's map first", sent.isEmpty())
        exits.onFrame(MapTarget.APP, null)
        asked(4)
    }

    @Test
    fun anAnswerForAnEarlierRoomOrAnotherClientsIsNotTaken() {
        exits.onFrame(MapTarget.APP, view(7))
        val first = asked(7)
        exits.onFrame(MapTarget.APP, view(8))
        val second = asked(8)
        assertTrue("its own, though late", exits.onRoom(EngineMessage.Room(room(7, "n"), first)))
        assertNull("not the player's room", exits.exits)
        assertFalse("another map's", exits.onRoom(EngineMessage.Room(room(8, "e"), 99L)))
        assertNull("while its own answer is coming", exits.exits)
        exits.onRoom(EngineMessage.Room(room(8, "w"), second))
        assertEquals(setOf(MovementKey.WEST), exits.exits)
        assertFalse("answered once", exits.onRoom(EngineMessage.Room(room(8, "w"), second)))
    }

    @Test
    fun anotherMapsEditOfThePlayersRoomChangesTheExitsButIsLeftToThatMap() {
        exits.onFrame(MapTarget.APP, view(7))
        exits.onRoom(EngineMessage.Room(room(7, "n"), asked(7)))
        assertFalse(exits.onRoom(EngineMessage.Room(room(7, "n", "in"), 12L)))
        assertEquals(setOf(MovementKey.NORTH, MovementKey.IN), exits.exits)
        assertFalse(exits.onRoom(EngineMessage.Room(room(9, "s"), null)))
        assertEquals("another room's edit", setOf(MovementKey.NORTH, MovementKey.IN), exits.exits)
    }

    @Test
    fun aRefusalOfWhatItAskedIsItsOwnAndLeavesTheRoomUnknown() {
        exits.onFrame(MapTarget.APP, view(7))
        exits.onRoom(EngineMessage.Room(room(7, "n"), asked(7)))
        exits.onFrame(MapTarget.APP, view(8))
        val id = asked(8)
        assertFalse("a map's own request", exits.onRefused("roomInfo", 99L))
        assertFalse(exits.onRefused("walkToRoom", id))
        assertFalse(exits.onRefused("roomInfo", null))
        assertEquals(setOf(MovementKey.NORTH), exits.exits)
        assertTrue(exits.onRefused("roomInfo", id))
        assertNull("a room the map does not have", exits.exits)
        assertFalse("refused once", exits.onRefused("roomInfo", id))
    }

    @Test
    fun aRequestThatCannotBeSentLeavesTheRoomUnknown() {
        attached = false
        exits.onFrame(MapTarget.APP, view(7))
        assertNull(exits.exits)
        attached = true
        exits.onAttached(sameInstance = true)
        asked(7)
    }

    @Test
    fun theLinkBackAsksAgainAndANewEngineForgetsTheRoom() {
        exits.onFrame(MapTarget.APP, view(7))
        val lost = asked(7)
        exits.onAttached(sameInstance = true)
        val again = asked(7)
        assertFalse("the last link's", exits.onRoom(EngineMessage.Room(room(7, "n"), lost)))
        exits.onRoom(EngineMessage.Room(room(7, "e"), again))
        assertEquals(setOf(MovementKey.EAST), exits.exits)

        exits.onAttached(sameInstance = false)
        assertNull(exits.exits)
        assertTrue(sent.isEmpty())
        exits.onFrame(MapTarget.APP, view(7))
        asked(7)
    }
}
