package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapArea
import org.mudlet.android.protocol.MapEnv
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.RoomEdit
import org.mudlet.android.protocol.RoomInfo
import org.mudlet.android.protocol.RoomMatch

/**
 * What the player asks of one map, [target], about its rooms and areas (docs/protocol.md, `roomInfo`, `mapAreas`,
 * `showMapLevel`, `showMapArea`, `walkToRoom`, `findRooms`, `showRoom`): the room they picked, shown until closed, the
 * rooms they searched for, the map's areas to switch to, and its levels; and the edits of the map ("Editing a map"),
 * each of which shows the room it gives. Requests go with [send], each answer's `id` from [newId]; [mover]'s moves not
 * yet drawn are dropped when the map jumps to another level or area. Main thread only.
 */
internal class MapRooms(private val target: MapTarget, private val mover: MapMover, private val newId: () -> Long, private val send: (JSONObject) -> Boolean) {
    /**
     * What findRooms found for [text]: null [rooms] until the engine answers; [more] when it found more than it sent;
     * [refusal], with no rooms, why the engine refused it.
     */
    data class RoomSearch(val text: String, val rooms: List<RoomMatch>?, val more: Boolean = false, val refusal: String? = null)

    /** The room picked, as the engine last told it; null while none is. */
    var room by mutableStateOf<RoomInfo?>(null)
        private set

    /** The map's areas that have rooms, by name, as the engine last told them; null until it first does. */
    var areas by mutableStateOf<List<MapArea>?>(null)
        private set

    /** The environments a room can be given, by number, as the engine last told them; null until it first does. */
    var envs by mutableStateOf<List<MapEnv>?>(null)
        private set

    /** An exit of [room] to lead the [direction] way to the room the player taps next ([link]), [bothWays] or not. */
    data class ExitLink(val room: Int, val direction: String, val bothWays: Boolean)

    /** The exit waiting for the player to tap the room it leads to; null while none is. */
    var linking by mutableStateOf<ExitLink?>(null)
        private set

    /** The last room search, until the player closes its results or picks one. */
    var search by mutableStateOf<RoomSearch?>(null)
        private set

    private var pendingSearch: Long? = null
    private var pendingRoom: Long? = null
    private var pendingAreas: Long? = null
    private var pendingEnvs: Long? = null
    private var player = 0

    /** Asks for [room], shown once the engine answers. False as for [ProfileSession.send]. */
    fun ask(room: Int): Boolean = askRoom { Requests.roomInfo(room, it) }

    /** The player closed the room, or picked none. */
    fun close() {
        pendingRoom = null
        room = null
        linking = null
    }

    /** [edit] made to [room], which shows as it then is. False as for [ProfileSession.send]. */
    fun edit(room: Int, edit: RoomEdit): Boolean = askRoom { Requests.editRoom(room, edit, it) }

    /** [room]'s exit [direction] led to [to], or removed for 0, with the exit back too where [bothWays]. False as for [ProfileSession.send]. */
    fun setExit(room: Int, direction: String, to: Int, bothWays: Boolean): Boolean = askRoom { Requests.setRoomExit(room, direction, to, bothWays, it) }

    /** [room]'s exit [direction] locked or not. False as for [ProfileSession.send]. */
    fun lockExit(room: Int, direction: String, locked: Boolean): Boolean = askRoom { Requests.lockRoomExit(room, direction, locked, it) }

    /** A new room one place from [room] the [direction] way, which shows once made. False as for [ProfileSession.send]. */
    fun addRoom(room: Int, direction: String): Boolean = askRoom { Requests.addRoom(room, direction, it) }

    /** [room] deleted, and no longer shown. False as for [ProfileSession.send]. */
    fun delete(room: Int): Boolean {
        if (!send(Requests.deleteRoom(room))) return false
        close()
        return true
    }

    /** The player placed in [room]; the next frame says so, and the room's steps are asked anew then. False as for [ProfileSession.send]. */
    fun placePlayer(room: Int): Boolean = send(Requests.setPlayerRoom(room))

    /** [area] renamed [name], and the room shown asked for again, as its area's name changed. False as for [ProfileSession.send]. */
    fun renameArea(area: Int, name: String): Boolean {
        if (!send(Requests.renameArea(area, name))) return false
        room?.let { ask(it.id) }
        return true
    }

    /** Asks for [envs] anew. False as for [ProfileSession.send]. */
    fun askEnvs(): Boolean {
        val id = newId()
        if (!send(Requests.mapEnvs(id))) return false
        pendingEnvs = id
        return true
    }

    /** The next room the player taps is the one [exit] leads to, unless they cancel it with null. */
    fun pickExitRoom(exit: ExitLink?) {
        linking = exit
    }

    /** The room the player tapped for [linking], which then leads there. False when none waits, or as for [ProfileSession.send]. */
    fun link(to: Int): Boolean {
        val exit = linking ?: return false
        linking = null
        return setExit(exit.room, exit.direction, to, exit.bothWays)
    }

    // Sends what [request] makes of a new id, whose answer is the room then shown
    private fun askRoom(request: (Long) -> JSONObject): Boolean {
        val id = newId()
        if (!send(request(id))) return false
        pendingRoom = id
        return true
    }

    /** The player's character walks to [room], as a double tap on it does. False as for [ProfileSession.send]. */
    fun walk(room: Int): Boolean = send(Requests.walkToRoom(room))

    /** Asks for [areas] anew, as the map may have changed since. False as for [ProfileSession.send]. */
    fun askAreas(): Boolean {
        val id = newId()
        if (!send(Requests.mapAreas(id))) return false
        pendingAreas = id
        return true
    }

    /** The map showing [level], one of its view's levels. False as for [ProfileSession.send]. */
    fun showLevel(level: Int): Boolean {
        mover.clear()
        return send(Requests.showMapLevel(target, level))
    }

    /** The map showing [area], one of [areas]. False as for [ProfileSession.send]. */
    fun showArea(area: Int): Boolean {
        mover.clear()
        return send(Requests.showMapArea(target, area))
    }

    /** The rooms whose name holds [text], or whose number it is, into [search]. False as for [ProfileSession.send]. */
    fun find(text: String): Boolean {
        val id = newId()
        if (!send(Requests.findRooms(text, id))) return false
        pendingSearch = id
        search = RoomSearch(text, null)
        return true
    }

    /** Whether [id] is that of this map's search the engine has not answered. */
    fun searching(id: Long?): Boolean = id != null && id == pendingSearch

    /** The player closed the search's results. */
    fun closeSearch() {
        pendingSearch = null
        search = null
    }

    /**
     * Centres the map on [room] and closes the search's results, keeping what was searched for to search again. False
     * as for [ProfileSession.send].
     */
    fun showRoom(room: Int): Boolean {
        closeSearch()
        return send(Requests.showRoom(target, room))
    }

    /** The engine's answer to [find]: false when this map did not ask it, or asked again since. */
    fun onRooms(message: EngineMessage.Rooms): Boolean {
        if ((message.replyTo as? Number)?.toLong() != pendingSearch) return false
        pendingSearch = null
        search = RoomSearch(message.text, message.rooms, message.more)
        return true
    }

    /** The engine's answer to [ask]: false when this map did not ask it, or asked again since. */
    fun onRoom(message: EngineMessage.Room): Boolean {
        if ((message.replyTo as? Number)?.toLong() != pendingRoom) return false
        pendingRoom = null
        room = message.room
        return true
    }

    /** The engine's answer to [askAreas]: false when this map did not ask it, or asked again since. */
    fun onAreas(message: EngineMessage.Areas): Boolean {
        if ((message.replyTo as? Number)?.toLong() != pendingAreas) return false
        pendingAreas = null
        areas = message.areas
        return true
    }

    /** The engine's answer to [askEnvs]: false when this map did not ask it, or asked again since. */
    fun onEnvs(message: EngineMessage.Envs): Boolean {
        if ((message.replyTo as? Number)?.toLong() != pendingEnvs) return false
        pendingEnvs = null
        envs = message.envs
        return true
    }

    /** A new frame of this map: once the player is in another room the room shown is asked for again, as its steps changed. */
    fun onFrame(view: MapViewInfo?) {
        val now = view?.player ?: return
        if (now == player) return
        player = now
        room?.let { ask(it.id) }
    }

    /** The engine refused this map's [request], one of [REQUESTS]. */
    fun onRefused(request: String) {
        // A refused edit leaves the room shown as it was
        when (request) {
            "roomInfo" -> close()
            "findRooms" -> closeSearch()
            "mapAreas" -> pendingAreas = null
            "mapEnvs" -> pendingEnvs = null
            in ROOM_EDITS -> pendingRoom = null
        }
    }

    /** What the last link did not answer is not answered; a new engine instance may have another map. */
    fun onAttached(sameInstance: Boolean) {
        if (pendingSearch != null) closeSearch()
        pendingRoom = null
        pendingAreas = null
        pendingEnvs = null
        if (!sameInstance) {
            room = null
            areas = null
            envs = null
            linking = null
            player = 0
        }
    }

    companion object {
        // The edits answered by the room they leave shown; before REQUESTS, which it goes into
        private val ROOM_EDITS = setOf("editRoom", "setRoomExit", "lockRoomExit", "addRoom")

        /** The requests a map sends from here, whose refusal is shown on the map that sent the last of them. */
        val REQUESTS = setOf("roomInfo", "walkToRoom", "mapAreas", "showMapLevel", "showMapArea", "deleteRoom", "setPlayerRoom", "renameArea", "mapEnvs", "findRooms", "showRoom") + ROOM_EDITS
    }
}
