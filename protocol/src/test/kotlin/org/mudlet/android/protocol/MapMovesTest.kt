package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMovesTest {
    private val middle = MapPoint(200.0, 150.0)
    private val hall = MapRoomPlace(1, 200.0, 150.0, "Great Hall")
    private val kitchen = MapRoomPlace(2, 230.0, 150.0, "Kitchen")

    // As test_mudlet_engine.py's map_moves(): 20 rooms across a frame 300 high, so rooms of 15 pixels
    private fun view(moved: Int = 0, zoom: Double = 20.0, following: Boolean = true) = MapViewInfo(1, "Castle", 0, zoom, 3.0, 30.0, following, 1, 300 / zoom, moved, listOf(hall, kitchen))

    private fun assertPoint(expected: MapPoint, actual: MapPoint) {
        assertEquals("x", expected.x, actual.x, 1e-9)
        assertEquals("y", expected.y, actual.y, 1e-9)
    }

    @Test
    fun aShiftAppliesInvertsAndComposesAsMoveMapDoes() {
        val shift = MapShift.of(30.0, 0.0, 2.0, MapPoint(230.0, 150.0))
        // The pan puts room 1 at 230, where the zoom about it leaves it; room 2, 30 to its right, goes to 60 to its right
        assertPoint(MapPoint(230.0, 150.0), shift.apply(MapPoint(200.0, 150.0)))
        assertPoint(MapPoint(290.0, 150.0), shift.apply(MapPoint(230.0, 150.0)))
        assertPoint(MapPoint(200.0, 150.0), shift.invert(MapPoint(230.0, 150.0)))
        val both = MapShift.of(10.0, 5.0, 1.0, middle).then(MapShift.of(0.0, 0.0, 0.5, middle))
        assertPoint(MapPoint(105.0, 77.5), both.apply(MapPoint(0.0, 0.0)))
    }

    @Test
    fun nothingMovesWithoutAView() {
        val moves = MapMoves()
        assertFalse(moves.move(10.0, 0.0, 1.0, middle, middle))
        assertNull(moves.flush())
        moves.onFrame(view())
        assertTrue(moves.move(10.0, 0.0, 1.0, middle, middle))
        moves.onFrame(null)
        assertEquals(MapShift.NONE, moves.shift)
        assertNull(moves.flush())
    }

    @Test
    fun movesAreShownUntilAFrameDrawsThem() {
        val moves = MapMoves()
        moves.onFrame(view())
        moves.move(10.0, 0.0, 1.0, middle, middle)
        moves.move(20.0, 0.0, 1.0, middle, middle)
        assertTrue(moves.pending)
        val first = moves.flush()!!
        assertEquals(MapMove(1, 30.0, 0.0, 1.0, middle), first)
        assertFalse(moves.pending)
        moves.move(0.0, 0.0, 2.0, MapPoint(230.0, 150.0), middle)
        val second = moves.flush()!!
        assertEquals(2, second.seq)
        assertEquals(2.0, second.zoom, 1e-9)
        // The engine's next frame has only the first: the second is still shown, and both until then
        assertPoint(MapPoint(290.0, 150.0), moves.shift.apply(MapPoint(230.0, 150.0)))
        moves.onFrame(view(moved = 1))
        assertPoint(MapPoint(230.0, 150.0), moves.shift.apply(MapPoint(230.0, 150.0)))
        assertPoint(MapPoint(290.0, 150.0), moves.shift.apply(MapPoint(260.0, 150.0)))
        moves.onFrame(view(moved = 2, zoom = 10.0))
        assertEquals(MapShift.NONE, moves.shift)
    }

    @Test
    fun movesAreCountedOnFromAnEarlierUis() {
        val moves = MapMoves()
        moves.onFrame(view(moved = 57))
        moves.move(10.0, 0.0, 1.0, middle, middle)
        assertEquals(58, moves.flush()!!.seq)
        // A frame drawn before the engine had it still shows the move
        moves.onFrame(view(moved = 57))
        assertEquals(10.0, moves.shift.x, 1e-9)
    }

    @Test
    fun aFlushedMoveIsWhatTheEngineMakesOfIt() {
        val moves = MapMoves()
        moves.onFrame(view())
        moves.move(12.0, -7.0, 1.0, middle, middle)
        moves.move(0.0, 0.0, 1.5, MapPoint(50.0, 80.0), middle)
        moves.move(-3.0, 4.0, 0.9, MapPoint(120.0, 10.0), middle)
        val shown = moves.shift
        val move = moves.flush()!!
        val sent = MapShift.of(move.dx, move.dy, move.zoom, move.about)
        assertEquals(shown.scale, sent.scale, 1e-9)
        assertEquals(shown.x, sent.x, 1e-9)
        assertEquals(shown.y, sent.y, 1e-9)
    }

    @Test
    fun theZoomStaysWithinTheFramesBounds() {
        val moves = MapMoves()
        moves.onFrame(view())
        // 20 rooms across, at least 3: zoomed in by at most 20 / 3
        moves.move(0.0, 0.0, 100.0, middle, middle)
        assertEquals(20.0 / 3, moves.shift.scale, 1e-9)
        moves.move(0.0, 0.0, 2.0, middle, middle)
        assertEquals(20.0 / 3, moves.shift.scale, 1e-9)
        // And out to at most 30 across
        moves.move(0.0, 0.0, 0.0001, middle, middle)
        assertEquals(20.0 / 30, moves.shift.scale, 1e-9)
        assertFalse(moves.move(0.0, 0.0, 0.0, middle, middle))
        assertFalse(moves.move(Double.NaN, 0.0, 1.0, middle, middle))
    }

    @Test
    fun aPanOrAZoomOffTheMiddleStopsFollowingAndAZoomAboutItDoesNot() {
        val moves = MapMoves()
        moves.onFrame(view())
        moves.move(0.0, 0.0, 2.0, middle, middle)
        assertTrue(moves.following)
        moves.move(0.0, 0.0, 0.5, MapPoint(10.0, 10.0), middle)
        assertFalse(moves.following)
        moves.flush()
        assertFalse(moves.following)
        // Until the frame that draws it, which says itself whether the map follows
        moves.onFrame(view(moved = 1, following = false))
        assertFalse(moves.following)
        moves.onFrame(view(moved = 1, following = true))
        assertTrue(moves.following)
        moves.move(5.0, 0.0, 1.0, middle, middle)
        assertFalse(moves.following)
        moves.clear()
        assertTrue(moves.following)
        assertEquals(MapShift.NONE, moves.shift)
    }

    @Test
    fun aTapFindsTheNearestRoomWithinReach() {
        val moves = MapMoves()
        moves.onFrame(view())
        assertEquals(hall, moves.roomAt(MapPoint(212.0, 160.0), 20.0))
        assertEquals(kitchen, moves.roomAt(MapPoint(218.0, 150.0), 20.0))
        assertNull(moves.roomAt(MapPoint(100.0, 100.0), 20.0))
        // A room's own cell is in reach however small the reach
        assertEquals(hall, moves.roomAt(MapPoint(206.0, 150.0), 1.0))
        // Where the room is shown, with the moves not yet drawn
        moves.move(30.0, 0.0, 1.0, middle, middle)
        assertEquals(hall, moves.roomAt(MapPoint(230.0, 150.0), 1.0))
        assertPoint(MapPoint(260.0, 150.0), moves.shown(kitchen))
    }

    @Test
    fun aDragOfARoomGoesByWholeRoomsAsShown() {
        val moves = MapMoves()
        assertNull(moves.roomsMoved(middle, MapPoint(260.0, 150.0)))
        moves.onFrame(view())
        // Rooms 15 pixels apart: 37 to the right and 22 up is 2 east and 1 north, the frame's y going down
        assertEquals(2 to 1, moves.roomsMoved(middle, MapPoint(237.0, 128.0)))
        assertEquals(-1 to -3, moves.roomsMoved(middle, MapPoint(185.0, 195.0)))
        // Less than half a room is no move
        assertNull(moves.roomsMoved(middle, MapPoint(207.0, 143.0)))
        // Zoomed in twice as shown, rooms are 30 apart
        moves.move(0.0, 0.0, 2.0, middle, middle)
        assertEquals(1 to 0, moves.roomsMoved(middle, MapPoint(237.0, 150.0)))
    }
}
