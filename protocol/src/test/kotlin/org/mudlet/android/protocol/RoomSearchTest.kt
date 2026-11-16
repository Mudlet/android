package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSearchTest {
    @Test
    fun theMatchIsFoundIgnoringCase() {
        assertEquals(6..9, matchRange("Great Hall", "hall"))
        assertEquals(0..4, matchRange("Great Hall", "GREAT"))
        assertNull(matchRange("Great Hall", "kitchen"))
        // Found by its id, which its name does not hold
        assertNull(matchRange("Great Hall", "12"))
        assertNull(matchRange("Great Hall", ""))
    }

    @Test
    fun theAreasAreInTheOrderOfTheirBestRoomAndOnlyWhenThereAreEnough() {
        val rooms = listOf(
            RoomMatch(3, "Hallway", "Keep", 1),
            RoomMatch(9, "Hall of Fame", "Town", 2),
            RoomMatch(1, "Great Hall", "Keep", 1),
            RoomMatch(5, "Hall pond", "Garden", 3),
        )
        assertEquals(listOf(MapArea(1, "Keep"), MapArea(2, "Town"), MapArea(3, "Garden")), roomAreas(rooms, 3))
        assertEquals(emptyList<MapArea>(), roomAreas(rooms, 4))
        assertEquals(emptyList<MapArea>(), roomAreas(emptyList(), 1))
    }
}
