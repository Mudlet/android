package org.mudlet.android.ui

import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.game.MovementKey
import java.util.EnumMap

/**
 * The ways out of the player's room the profile's map has, for the Room exits movement pad (docs/architecture.md, "The
 * movement pad's designs"): the maps' frames say which room the player is in, and a `roomInfo` of it, asked with an id
 * of its own from [newId] and sent with [send], says its exits, asked only while the pad [watch]es, so that a player
 * with another design asks nothing. [onChange] has them each time they change: null while the room is not known (no map
 * drawn, no room for the player, a room the map does not have, or no pad watching). An answer to another map's edit of
 * that room counts too. Main thread only.
 */
internal class PlayerExits(private val newId: () -> Long, private val send: (JSONObject) -> Boolean, private val onChange: (Set<MovementKey>?) -> Unit) {
    /** The twelve directions out of the player's room as the map last said, or null while not known. */
    var exits: Set<MovementKey>? = null
        private set

    // The player's room by the map whose frame said it, the app's before the mapper window's
    private val players = EnumMap<MapTarget, Int>(MapTarget::class.java)
    private var room = 0
    private var pending: Long? = null
    private var watching = false

    // Every roomInfo asked here and not answered: a refusal of an earlier one is this one's too, not a map's to show
    private val asked = HashSet<Long>()

    /** A new frame of [map] whose view is [view], null for none. */
    fun onFrame(map: MapTarget, view: MapViewInfo?) {
        val player = view?.player ?: 0
        if (player > 0) players[map] = player else players.remove(map)
        val now = players[MapTarget.APP] ?: players[MapTarget.MAPPER] ?: 0
        if (now == room) return
        room = now
        if (!watching) return
        // The last room's exits stay until this one's come, so the pad does not flicker between them at each step
        if (room > 0) ask() else forget()
    }

    /** Whether the Room exits pad is on the screen, [on], and wants the exits. */
    fun watch(on: Boolean) {
        if (on == watching) return
        watching = on
        if (on && room > 0) ask() else forget()
    }

    /** A `room` from the engine: true when it answers this, which takes no other map's answer. */
    fun onRoom(message: EngineMessage.Room): Boolean {
        val id = (message.replyTo as? Number)?.toLong()
        val mine = id != null && asked.remove(id)
        if (mine && id == pending) pending = null
        // Another map's edit of the room while this asks is followed by this answer, which has the edit too
        if (watching && message.room.id == room && (mine || pending == null)) {
            change(message.room.exits.mapNotNull { MovementKey.ofExit(it.direction) }.toSet())
        }
        return mine
    }

    /** The engine refused [request], whose id was [replyTo]: true when it was this, which no map shows. */
    fun onRefused(request: String, replyTo: Long?): Boolean {
        if (request != "roomInfo" || replyTo == null || !asked.remove(replyTo)) return false
        if (replyTo == pending) forget()
        return true
    }

    /** The link to the engine is back: what the last did not answer is asked again; a new instance has another map. */
    fun onAttached(sameInstance: Boolean) {
        asked.clear()
        pending = null
        if (!sameInstance) {
            players.clear()
            room = 0
            forget()
        } else if (watching && room > 0) {
            ask()
        }
    }

    private fun ask() {
        val id = newId()
        if (send(Requests.roomInfo(room, id))) {
            asked.add(id)
            pending = id
        } else {
            forget()
        }
    }

    private fun forget() {
        pending = null
        change(null)
    }

    private fun change(new: Set<MovementKey>?) {
        if (new == exits) return
        exits = new
        onChange(new)
    }
}
