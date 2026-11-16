package org.mudlet.android.protocol

/**
 * Where findRooms' [text] is in a found room's [name], ignoring case as the engine does, to show it marked; null where
 * the name does not hold it, as for a room found by its id.
 */
fun matchRange(name: String, text: String): IntRange? {
    if (text.isEmpty()) return null
    val at = name.indexOf(text, ignoreCase = true)
    return if (at < 0) null else at until at + text.length
}

/**
 * The areas [rooms] are in, in the order of each one's first room, so best first as findRooms sorts them; empty where
 * they are in fewer than [least], too few to pick from.
 */
fun roomAreas(rooms: List<RoomMatch>, least: Int): List<MapArea> {
    val areas = rooms.distinctBy { it.areaId }.map { MapArea(it.areaId, it.area) }
    return if (areas.size < least) emptyList() else areas
}
