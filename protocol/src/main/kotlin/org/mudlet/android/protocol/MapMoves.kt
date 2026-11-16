package org.mudlet.android.protocol

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * How a map frame is shown moved, until a frame that draws the move comes: each point p of the frame at [scale] p plus
 * ([x], [y]), in the frame's pixels.
 */
data class MapShift(val scale: Double, val x: Double, val y: Double) {
    fun apply(p: MapPoint) = MapPoint(scale * p.x + x, scale * p.y + y)

    fun invert(p: MapPoint) = MapPoint((p.x - x) / scale, (p.y - y) / scale)

    /** This, then [next]. */
    fun then(next: MapShift) = MapShift(scale * next.scale, next.scale * x + next.x, next.scale * y + next.y)

    companion object {
        val NONE = MapShift(1.0, 0.0, 0.0)

        /** A pan by [dx], [dy], then a zoom by [zoom] about [about]: what `moveMap` asks. */
        fun of(dx: Double, dy: Double, zoom: Double, about: MapPoint) = MapShift(zoom, zoom * (dx - about.x) + about.x, zoom * (dy - about.y) + about.y)
    }
}

/** A `moveMap` to send (Requests.moveMap). */
data class MapMove(val seq: Int, val dx: Double, val dy: Double, val zoom: Double, val about: MapPoint)

/**
 * A map's moves by touch (docs/protocol.md, "Moving a map"): what the player's drags and pinches moved the map by since
 * its last frame, shown at once as [shift] of that frame, and sent as `moveMap` with [flush]. The engine draws the moves
 * in order and each frame says the last it drew, so [onFrame] drops those and keeps showing the rest. The zoom is kept
 * within the frame's bounds as the engine keeps it, so what is shown is what it will draw.
 */
class MapMoves {
    // Sent and not yet drawn, oldest first, each with the shift it makes
    private val sent = ArrayDeque<Pair<Int, MapShift>>()

    // Moved since the last flush: one move, as many touches make, about the last point they were about
    private var unsent = MapShift.NONE
    private var unsentAbout = MapPoint(0.0, 0.0)
    private var unsentLeavesPlayer = false
    private var sentLeavesPlayer = false
    private var nextSeq = 1

    /** What the last frame laid out; null while it showed no area, which cannot be moved. */
    var view: MapViewInfo? = null
        private set

    /** How the last frame is shown: moved by what was sent and not yet drawn, and what is not sent yet. */
    val shift: MapShift get() = sentShift().then(unsent)

    /** Whether the map follows the player, once the moves shown are drawn: not after a pan or a zoom off the middle. */
    val following: Boolean get() = (view?.following ?: true) && !sentLeavesPlayer && !unsentLeavesPlayer

    /** Moves not yet flushed. */
    val pending: Boolean get() = unsent != MapShift.NONE

    /** A new frame, which laid out [view] and drew the moves to its [MapViewInfo.moved]; one with none drops them all. */
    fun onFrame(view: MapViewInfo?) {
        this.view = view
        if (view == null) {
            clear()
            return
        }
        while (sent.isNotEmpty() && sent.first().first <= view.moved) sent.removeFirst()
        // Counted on from an earlier UI's moves, which the engine still says it drew, so this one's are told apart
        if (view.moved >= nextSeq) nextSeq = view.moved + 1
        // What is drawn now says whether the map follows; what is still to be drawn moved it off the player if it did
        // then, which the engine says in a later frame
        if (sent.isEmpty()) sentLeavesPlayer = false
    }

    /** The engine refused a move, or the map was centred on the player: what was not drawn is not shown either. */
    fun clear() {
        sent.clear()
        unsent = MapShift.NONE
        sentLeavesPlayer = false
        unsentLeavesPlayer = false
    }

    /**
     * The map shown moved by [dx], [dy] of the frame's pixels, then zoomed by [zoom] about [about], a point of the map
     * as shown, which [middle] is the middle of: the zoom kept within the frame's bounds. False while there is no view
     * to move.
     */
    fun move(dx: Double, dy: Double, zoom: Double, about: MapPoint, middle: MapPoint): Boolean {
        val view = view ?: return false
        if (!dx.isFinite() || !dy.isFinite() || !(zoom > 0.0) || !zoom.isFinite()) return false
        // Rooms across, as the engine counts the zoom, once what is shown is drawn
        val zoomNow = view.zoom / shift.scale
        val most = maxOf(view.maxZoom, zoomNow)
        val kept = (zoomNow / (zoomNow / zoom).coerceIn(view.minZoom, most)).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        unsent = unsent.then(MapShift.of(dx, dy, kept, about))
        unsentAbout = about
        if (dx != 0.0 || dy != 0.0 || (kept != 1.0 && about != middle)) unsentLeavesPlayer = true
        return true
    }

    /** What was moved since the last flush, as one `moveMap`; null for nothing. */
    fun flush(): MapMove? {
        if (unsent == MapShift.NONE || view == null) return null
        val shift = unsent
        val about = unsentAbout
        val move = MapMove(
            nextSeq++,
            (shift.x - about.x) / shift.scale + about.x,
            (shift.y - about.y) / shift.scale + about.y,
            shift.scale,
            about,
        )
        sent.addLast(move.seq to shift)
        sentLeavesPlayer = sentLeavesPlayer || unsentLeavesPlayer
        unsent = MapShift.NONE
        unsentLeavesPlayer = false
        return move
    }

    /**
     * The room the last frame drew nearest [at], a point of the map as shown, within [reach] of it in the map's pixels as
     * shown, or within its own cell; null for none.
     */
    fun roomAt(at: MapPoint, reach: Double): MapRoomPlace? {
        val view = view ?: return null
        val shift = shift
        val point = shift.invert(at)
        val within = maxOf(reach / shift.scale, view.roomSize / 2)
        return view.rooms.map { it to hypot(it.x - point.x, it.y - point.y) }.filter { it.second <= within }.minByOrNull { it.second }?.first
    }

    /**
     * How many rooms east and north a drag from [from] to [to], points of the map as shown, moves a room, to the nearest
     * whole room by the last frame's cell as shown; null with no view, or for a drag of less than half a room each way.
     */
    fun roomsMoved(from: MapPoint, to: MapPoint): Pair<Int, Int>? {
        val view = view ?: return null
        val cell = view.roomSize * shift.scale
        if (!(cell > 0.0)) return null
        val east = ((to.x - from.x) / cell).roundToInt()
        // The frame's y goes down, the map's north up
        val north = ((from.y - to.y) / cell).roundToInt()
        return (east to north).takeIf { east != 0 || north != 0 }
    }

    /** Where [room], of the last frame, is on the map as shown. */
    fun shown(room: MapRoomPlace): MapPoint = shift.apply(MapPoint(room.x, room.y))

    private fun sentShift(): MapShift = sent.fold(MapShift.NONE) { all, (_, shift) -> all.then(shift) }
}
