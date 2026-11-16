package org.mudlet.android.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.MapMoves
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapRoomPlace
import org.mudlet.android.protocol.MapShift
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MapViewInfo
import org.mudlet.android.protocol.Requests

// A drag sends at most one moveMap this often: the engine draws a frame for each, and what the finger did meanwhile is
// shown at once anyway
private const val SEND_INTERVAL_MS = 50L

/**
 * The moves by touch of one map, [target] (docs/protocol.md, "Moving a map"): [MapMoves], as the screen draws it, sent
 * with [send]. Main thread only.
 */
internal class MapMover(private val target: MapTarget, private val send: (JSONObject) -> Boolean) {
    private val moves = MapMoves()
    private var lastSent = 0L

    // The last moveMap sent, until a frame draws it, and a followPlayer sent, until a frame follows the player: a
    // refusal of either is this map's
    private var awaitedSeq: Int? = null
    private var awaitingFollow = false

    /** How the last frame is drawn, moved by what the engine has not drawn yet. */
    var shift by mutableStateOf(MapShift.NONE)
        private set

    /** What the last frame laid out; null while it showed no area, or none came. */
    var view by mutableStateOf<MapViewInfo?>(null)
        private set

    /** Whether the map follows the player, once what is shown is drawn. */
    var following by mutableStateOf(true)
        private set

    /** Whether a refusal of a moveMap or followPlayer now would be this map's: one it sent is not yet drawn. */
    val awaiting: Boolean get() = awaitedSeq != null || awaitingFollow

    /** A new frame of this map, with its [view]. */
    fun onFrame(view: MapViewInfo?) {
        moves.onFrame(view)
        if (view == null || awaitedSeq?.let { it <= view.moved } == true) awaitedSeq = null
        // A frame drawn before the engine had the followPlayer does not follow yet
        if (view == null || view.following) awaitingFollow = false
        sync()
    }

    /** The engine refused this map's moveMap or followPlayer, or a new engine instance has none of its moves. */
    fun clear() {
        moves.clear()
        awaitedSeq = null
        awaitingFollow = false
        sync()
    }

    /** As [MapMoves.move], sending what was moved unless a move went a moment ago, or anyway with [now]. */
    fun move(dx: Double, dy: Double, zoom: Double, about: MapPoint, middle: MapPoint, now: Boolean = false): Boolean {
        if (!moves.move(dx, dy, zoom, about, middle)) return false
        if (now || SystemClock.uptimeMillis() - lastSent >= SEND_INTERVAL_MS) flush() else sync()
        return true
    }

    /** Sends what was moved and not yet sent, as one moveMap. */
    fun flush() {
        val move = moves.flush()
        if (move != null) {
            lastSent = SystemClock.uptimeMillis()
            if (send(Requests.moveMap(target, move.dx, move.dy, move.zoom, move.about.x, move.about.y, move.seq))) {
                awaitedSeq = move.seq
            } else {
                moves.clear()
            }
        }
        sync()
    }

    /** The map centred on the player, following them again. False as for [ProfileSession.send]. */
    fun followPlayer(): Boolean {
        moves.clear()
        sync()
        awaitingFollow = send(Requests.followPlayer(target))
        return awaitingFollow
    }

    /** As [MapMoves.roomAt]. */
    fun roomAt(at: MapPoint, reach: Double): MapRoomPlace? = moves.roomAt(at, reach)

    private fun sync() {
        shift = moves.shift
        view = moves.view
        following = moves.following
    }
}
