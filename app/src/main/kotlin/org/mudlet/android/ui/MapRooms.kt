package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapArea
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.RoomInfo

/**
 * What the player asks of one map, [target], about its rooms and areas (docs/protocol.md, `roomInfo`, `mapAreas`,
 * `showMapLevel`, `showMapArea`, `walkToRoom`): the room they picked, shown until closed, the map's areas to switch to,
 * and its levels. Requests go with [send], each answer's `id` from [newId]; [mover]'s moves not yet drawn are dropped
 * when the map jumps to another level or area. Main thread only.
 */
internal class MapRooms(private val target: MapTarget, private val mover: MapMover, private val newId: () -> Long, private val send: (JSONObject) -> Boolean) {
    /** The room picked, as the engine last told it; null while none is. */
    var room by mutableStateOf<RoomInfo?>(null)
        private set

    /** The map's areas that have rooms, by name, as the engine last told them; null until it first does. */
    var areas by mutableStateOf<List<MapArea>?>(null)
        private set

    private var pendingRoom: Long? = null
    private var pendingAreas: Long? = null
    private var player = 0

    /** Asks for [room], shown once the engine answers. False as for [ProfileSession.send]. */
    fun ask(room: Int): Boolean {
        val id = newId()
        if (!send(Requests.roomInfo(room, id))) return false
        pendingRoom = id
        return true
    }

    /** The player closed the room, or picked none. */
    fun close() {
        pendingRoom = null
        room = null
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

    /** A new frame of this map: once the player is in another room the room shown is asked for again, as its steps changed. */
    fun onFrame(view: MapViewInfo?) {
        val now = view?.player ?: return
        if (now == player) return
        player = now
        room?.let { ask(it.id) }
    }

    /** The engine refused this map's [request], one of [REQUESTS]. */
    fun onRefused(request: String) {
        when (request) {
            "roomInfo" -> close()
            "mapAreas" -> pendingAreas = null
        }
    }

    /** What the last link did not answer is not answered; a new engine instance may have another map. */
    fun onAttached(sameInstance: Boolean) {
        pendingRoom = null
        pendingAreas = null
        if (!sameInstance) {
            room = null
            areas = null
            player = 0
        }
    }

    companion object {
        /** The requests a map sends from here, whose refusal is shown on the map that sent the last of them. */
        val REQUESTS = setOf("roomInfo", "walkToRoom", "mapAreas", "showMapLevel", "showMapArea")
    }
}
