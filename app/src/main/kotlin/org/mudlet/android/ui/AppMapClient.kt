package org.mudlet.android.ui

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.RoomMatch
import org.mudlet.android.ui.game.MapSource
import java.util.EnumMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The app's own map (docs/architecture.md, "The map behind the text"), apart from any a script opens: the engine draws
 * it at the size the game screen shows it at, behind the console or full screen, while it shows it, and searches its
 * rooms. Main thread only, as EngineClient's state; ScriptWindowsClient hands it the engine's refusals and each attach.
 */
internal object AppMapClient : MapSource {
    /** What shows the map: each asks for it at its own size, and the full-screen map's wins while it is open. */
    enum class Owner { BACKGROUND, FULL_SCREEN }

    /** The map at [w] by [h] of its pixels, which are dp, drawn at [scale] device pixels to each: `setAppMapSize`'s fields. */
    data class Size(val w: Int, val h: Int, val scale: Double) {
        companion object {
            /** No map: the engine's own until asked. */
            val NONE = Size(0, 0, 1.0)

            /**
             * The map filling [widthPx] by [heightPx] of the screen at [density] device pixels to the dp: as sharp as the
             * screen, within what the engine takes (1 to [MAX_SCALE] device pixels to each of its pixels, and at most
             * [MAX_SIDE] a side).
             */
            fun of(widthPx: Int, heightPx: Int, density: Float): Size {
                if (widthPx <= 0 || heightPx <= 0) return NONE
                val ideal = density.toDouble().coerceIn(1.0, MAX_SCALE)
                val w = (widthPx / ideal).roundToInt().coerceIn(1, MAX_SIDE)
                val h = (heightPx / ideal).roundToInt().coerceIn(1, MAX_SIDE)
                // In hundredths, down, so w and h by it never round past the most a side
                val scale = floor(minOf(ideal, MAX_SIDE.toDouble() / w, MAX_SIDE.toDouble() / h) * 100) / 100
                return Size(w, h, scale.coerceAtLeast(1.0))
            }
        }
    }

    /** What findRooms found for [text]: null [rooms] until the engine answers; [more] when it found more than it sent. */
    data class RoomSearch(val text: String, val rooms: List<RoomMatch>?, val more: Boolean = false)

    /** The map as the engine last drew it, at the size asked; null while it draws none (no profile, or no rooms). */
    var map by mutableStateOf<ScriptWindowsClient.MapImage?>(null)
        private set

    /** Why the engine refused the last request about this map, until the next one or the player dismisses it. */
    override val refusal: String? get() = refused

    private var refused by mutableStateOf<String?>(null)

    /** The last room search, until the player closes its results or picks one. */
    var search by mutableStateOf<RoomSearch?>(null)
        private set

    private val wanted = EnumMap<Owner, Size>(Owner::class.java)

    // The size this engine instance was last given; null when it could not be sent, so the next attach sends it
    private var sent: Size? = Size.NONE
    private var pendingSearch: Long? = null
    private var nextId = 1L

    // Whether the last setMapViewOnly was this map's, whose refusal it shows, or the mapper window's
    private var askedViewOnly = false

    /** [owner] shows the map at [size] until [hide]. */
    fun show(owner: Owner, size: Size) {
        wanted[owner] = size
        update()
    }

    fun hide(owner: Owner) {
        wanted.remove(owner)
        update()
    }

    // Asks for the map at the size of what shows it, the full-screen map before the background, or for none
    private fun update() {
        val size = wanted[Owner.FULL_SCREEN] ?: wanted[Owner.BACKGROUND] ?: Size.NONE
        if (size == sent) return
        sent = if (EngineClient.send(Requests.setAppMapSize(size.w, size.h, size.scale))) size else null
    }

    /** [message]'s PNG, decoded off the main thread; null for none, or one that does not decode. */
    internal fun decode(message: EngineMessage.AppMap): ImageBitmap? = if (message.png.isEmpty()) null else BitmapFactory.decodeByteArray(message.png, 0, message.png.size)?.asImageBitmap()

    /** A new `appMap`, with what [decode] made of it. */
    internal fun onAppMap(message: EngineMessage.AppMap, bitmap: ImageBitmap?) {
        if (message.w == 0 || message.h == 0) {
            map = null
            return
        }
        if (bitmap == null) Log.w(TAG, "the engine's app map, ${message.w} by ${message.h}, did not decode")
        map = ScriptWindowsClient.MapImage(bitmap, message.w, message.h, message.viewOnly)
    }

    /** The engine's answer to [findRooms]; one to an earlier search is not shown. */
    internal fun onRooms(message: EngineMessage.Rooms) {
        if ((message.replyTo as? Number)?.toLong() != pendingSearch) return
        pendingSearch = null
        search = RoomSearch(message.text, message.rooms, message.more)
    }

    /** The engine refused [request] for [reason]: true when it was this map's, which shows it. */
    internal fun onRefused(request: String, reason: String): Boolean {
        when (request) {
            "appMapPointer", "showRoom", "setAppMapSize" -> {}
            "setMapViewOnly" -> if (!askedViewOnly) return false
            "findRooms" -> closeSearch()
            else -> return false
        }
        refused = reason
        return true
    }

    /** The mapper window asked for the map viewed or edited: a refusal is its, not this map's. */
    internal fun viewOnlyAskedByMapper() {
        askedViewOnly = false
    }

    /** A new engine instance draws no map until asked, and answers nothing the last was asked. */
    internal fun onAttached(sameInstance: Boolean) {
        if (!sameInstance) {
            map = null
            refused = null
            sent = Size.NONE
        }
        if (pendingSearch != null) closeSearch()
        // Also a size that could not be sent while the link was down
        update()
    }

    /** As ScriptWindowsClient.mapPointer, on this map. */
    override fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int): Boolean {
        refused = null
        val button = if (type == MapPointerType.WHEEL) 0 else POINTER_LEFT
        return EngineClient.send(Requests.appMapPointer(type, at.x, at.y, button, 0, wheelY))
    }

    /** The map viewed only, or edited, which is the mapper window's mode too; the next [map] says which it is. */
    override fun setViewOnly(viewOnly: Boolean): Boolean {
        refused = null
        askedViewOnly = true
        return EngineClient.send(Requests.setMapViewOnly(viewOnly))
    }

    override fun dismissRefusal() {
        refused = null
    }

    /** The rooms whose name holds [text], or whose number it is, into [search]. False as for [EngineClient.send]. */
    fun findRooms(text: String): Boolean {
        refused = null
        val id = nextId++
        if (!EngineClient.send(Requests.findRooms(text, id))) return false
        pendingSearch = id
        search = RoomSearch(text, null)
        return true
    }

    /** Centres the map on [room], selected, and closes the search's results. False as for [EngineClient.send]. */
    fun showRoom(room: Int): Boolean {
        refused = null
        search = null
        return EngineClient.send(Requests.showRoom(room))
    }

    /** The player closed the search's results. */
    fun closeSearch() {
        pendingSearch = null
        search = null
    }

    /** As the engine takes `setAppMapSize`'s scale and sizes: IEngine::maxAppMapScale and PngImage::maxSide. */
    const val MAX_SCALE = 4.0
    const val MAX_SIDE = 4096

    private const val TAG = "AppMap"
}
