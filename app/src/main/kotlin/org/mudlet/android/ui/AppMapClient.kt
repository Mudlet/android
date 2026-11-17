package org.mudlet.android.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapBehindText
import org.mudlet.android.protocol.MapDownloadState
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.RoomMatch
import org.mudlet.android.protocol.TextBehindMap
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.MapSource
import java.nio.IntBuffer
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The app's own map (docs/architecture.md, "The map behind the text"), apart from any a script opens: the engine draws
 * it at the size the game screen has it at, behind the console or in front, while it is up, and searches its
 * rooms, and downloads the map the game offers (docs/architecture.md, "Downloading the map"). Each [ProfileSession] has
 * one, and only the front profile's is drawn. Main thread only, as the session's state; its [ScriptWindowsClient] hands
 * it the engine's refusals and each attach.
 */
internal class AppMapClient internal constructor(private val session: ProfileSession) : MapSource {
    /** What has the map: each asks for it at its own size, and the full-screen map's wins while it is open. */
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

    /**
     * What findRooms found for [text]: null [rooms] until the engine answers; [more] when it found more than it sent;
     * [refusal], with no rooms, why the engine refused it.
     */
    data class RoomSearch(val text: String, val rooms: List<RoomMatch>?, val more: Boolean = false, val refusal: String? = null)

    /**
     * An `appMap`'s frame as [decode] made it: its [bitmap] and [tones], null where there is none, and with [textBehind]
     * its [overText], which is made after it has come while only [cutOutAhead] wants it.
     */
    class Decoded(val bitmap: ImageBitmap?, val tones: MapBehindText.Tones?, val overText: OverText?)

    /**
     * A frame to draw over the console's text: its [ground], and the [frame] itself with the pixels of its background
     * colour made transparent, or as it is where it has none ([TextBehindMap]).
     */
    class OverText(val ground: TextBehindMap.Ground, val frame: ImageBitmap)

    /** The map as the engine last drew it, at the size asked; null while it draws none (no profile, or no rooms). */
    var map by mutableStateOf<ScriptWindowsClient.MapImage?>(null)
        private set

    /** The colours of [map]'s frame, which set how strongly it shows behind the text; null for any colour at all. */
    var tones by mutableStateOf<MapBehindText.Tones?>(null)
        private set

    /**
     * [map]'s frame made to draw over the text, while [textBehind] or [cutOutAhead] wants it; null while neither does,
     * there is none, or it is not cut out yet.
     */
    var overText by mutableStateOf<OverText?>(null)
        private set

    /** Why the engine refused the last request about this map, until the next one or the player dismisses it. */
    override val refusal: String? get() = refused

    private var refused by mutableStateOf<String?>(null)

    override val mover = MapMover(MapTarget.APP) {
        refused = null
        session.send(it)
    }

    override val rooms = MapRooms(MapTarget.APP, mover, session::newRequestId) {
        refused = null
        askedRooms = true
        session.send(it)
    }

    /** The map the game offers and how its download goes, as the engine last said: [NO_DOWNLOAD] until it says. */
    var download by mutableStateOf(NO_DOWNLOAD)
        private set

    /**
     * How the last download ended (loaded, failed or cancelled), until the player dismisses it, downloads again or the
     * profile closes; a load only for a moment once shown (MapDownloadEnd).
     */
    var downloadEnd by mutableStateOf<EngineMessage.MapDownload?>(null)
        private set

    /** downloadMap was sent and the engine has not answered it yet: the button waits, so a second tap sends no second. */
    var downloadAsked by mutableStateOf(false)
        private set

    /** The last room search, until the player closes its results or picks one. */
    var search by mutableStateOf<RoomSearch?>(null)
        private set

    /**
     * The rooms found for the room an exit leads to ([findDestinations]), apart from [search], so the exits dialog lists
     * them over either map; null while none is asked. A refusal shows here, not on the map.
     */
    var destinations by mutableStateOf<RoomSearch?>(null)
        private set

    // The size the engine was last given since the UI attached; null when it could not be sent
    private var sent: Size? = Size.NONE
    private var pendingSearch: Long? = null
    private var pendingDestinations: Long? = null

    // Every findDestinations not yet answered, the last one's id being pendingDestinations: an earlier one's refusal is
    // still the dialog's, not the room search's to close and show over the map
    private val destinationRequests = mutableSetOf<Long>()

    // Whether the last setMapViewOnly was this map's, whose refusal it shows, or the mapper window's; and so for the
    // last of MapRooms.REQUESTS
    private var askedViewOnly = false
    private var askedRooms = false

    // Asks for the map at the size of what has it, the full-screen map before the background; for none while the
    // profile is not the front, as only the front's is drawn. Nothing is asked while the one of them that counts has no
    // size yet: the engine would draw the map at the other's size first, or let go of the one it has
    internal fun update() {
        val owner = if (Owner.FULL_SCREEN in wanted) Owner.FULL_SCREEN else Owner.BACKGROUND
        val size = when {
            session !== EngineClient.front -> Size.NONE
            owner in wanted -> wanted[owner] ?: return
            else -> Size.NONE
        }
        if (size == sent) return
        sent = if (session.send(Requests.setAppMapSize(size.w, size.h, size.scale))) size else null
    }

    /** A new `appMap`, with what [decode] made of it. */
    internal fun onAppMap(message: EngineMessage.AppMap, frame: Decoded) {
        tones = frame.tones
        overText = frame.overText
        if (message.w == 0 || message.h == 0) {
            map = null
            mover.onFrame(null)
            return
        }
        if (frame.bitmap == null) Log.w(TAG, "the engine's app map, ${message.w} by ${message.h}, did not decode")
        map = ScriptWindowsClient.MapImage(frame.bitmap, message.w, message.h, message.viewOnly, message.info)
        mover.onFrame(message.view)
        rooms.onFrame(message.view)
        cutOut()
    }

    // While [cutOutAhead], makes [overText] of [map]'s frame where it came without one, on a thread of its own: the
    // reader's has the game's text to read, and the frame is drawn behind the text as it came meanwhile
    private fun cutOut() {
        val frame = map?.bitmap ?: return
        if (!cutOutAhead || overText != null) return
        cutter.execute {
            // Frames can come faster than they are cut out, and only the last is drawn
            if (map?.bitmap !== frame) return@execute
            val made = overText(frame.asAndroidBitmap())
            session.post { if (map?.bitmap === frame) overText = made }
        }
    }

    /** The engine's answer to [findRooms] or [findDestinations]; one to an earlier search is not shown. */
    internal fun onRooms(message: EngineMessage.Rooms) {
        val id = (message.replyTo as? Number)?.toLong() ?: return
        destinationRequests.remove(id)
        val found = RoomSearch(message.text, message.rooms, message.more)
        when (id) {
            pendingSearch -> {
                pendingSearch = null
                search = found
            }

            pendingDestinations -> {
                pendingDestinations = null
                destinations = found
            }
        }
    }

    /**
     * The engine refused [request], whose id was [replyTo], for [reason]: true when it was this map's, which shows it, or
     * [findDestinations]'s, which the exits dialog shows.
     */
    internal fun onRefused(request: String, reason: String, replyTo: Long? = null): Boolean {
        if (request == "findRooms" && replyTo != null && destinationRequests.remove(replyTo)) {
            if (replyTo == pendingDestinations) {
                pendingDestinations = null
                destinations = destinations?.copy(rooms = emptyList(), refusal = reason)
            }
            return true
        }
        when (request) {
            "appMapPointer", "showRoom", "setAppMapSize", "cancelMapDownload" -> {}
            "downloadMap" -> downloadAsked = false
            "setMapViewOnly" -> if (!askedViewOnly) return false
            "findRooms" -> closeSearch()
            "moveMap", "followPlayer" -> if (mover.awaiting) mover.clear() else return false
            in MapRooms.REQUESTS -> if (askedRooms) rooms.onRefused(request) else return false
            else -> return false
        }
        refused = reason
        return true
    }

    /** The mapper window asked for the map viewed or edited: a refusal is its, not this map's. */
    internal fun viewOnlyAskedByMapper() {
        askedViewOnly = false
    }

    /** The mapper window sent one of [MapRooms.REQUESTS]: a refusal is its, not this map's. */
    internal fun roomsAskedByMapper() {
        askedRooms = false
    }

    /**
     * The engine forgets the size when the UI goes, so it is asked again at each attach; a new instance has no map to
     * show either, and answers nothing the last was asked.
     */
    internal fun onAttached(sameInstance: Boolean) {
        // What was moved and not drawn may not have reached the engine
        mover.clear()
        if (!sameInstance) {
            map = null
            overText = null
            mover.onFrame(null)
            refused = null
            download = NO_DOWNLOAD
            downloadEnd = null
        }
        downloadAsked = false
        rooms.onAttached(sameInstance)
        sent = Size.NONE
        if (pendingSearch != null) closeSearch()
        if (pendingDestinations != null) closeDestinations()
        destinationRequests.clear()
        update()
    }

    /** As [ScriptWindowsClient.mapPointer], on this map. */
    override fun pointer(type: MapPointerType, at: MapPoint, wheelY: Int): Boolean {
        refused = null
        session.windows.flushTyping()
        val button = if (type == MapPointerType.WHEEL) 0 else POINTER_LEFT
        return session.send(Requests.appMapPointer(type, at.x, at.y, button, 0, wheelY))
    }

    /** The map viewed only, or edited, which is the mapper window's mode too; the next [map] says which it is. */
    override fun setViewOnly(viewOnly: Boolean): Boolean {
        refused = null
        askedViewOnly = true
        return session.send(Requests.setMapViewOnly(viewOnly))
    }

    override fun dismissRefusal() {
        refused = null
    }

    /** The rooms whose name holds [text], or whose number it is, into [search]. False as for [ProfileSession.send]. */
    fun findRooms(text: String): Boolean {
        refused = null
        val id = session.newRequestId()
        if (!session.send(Requests.findRooms(text, id))) return false
        pendingSearch = id
        search = RoomSearch(text, null)
        return true
    }

    /** The rooms whose name holds [text], or whose number it is, into [destinations]. False as for [ProfileSession.send]. */
    fun findDestinations(text: String): Boolean {
        val id = session.newRequestId()
        // Or the rooms found for what was typed before stay listed under what is typed now
        if (!session.send(Requests.findRooms(text, id))) {
            closeDestinations()
            return false
        }
        pendingDestinations = id
        destinationRequests += id
        destinations = RoomSearch(text, null)
        return true
    }

    /** The exits dialog no longer lists [destinations]; a late answer is not shown. */
    fun closeDestinations() {
        pendingDestinations = null
        destinations = null
    }

    /**
     * Centres the map on [room], selected, and closes the search's results, keeping what was searched for to search
     * again. False as for [ProfileSession.send].
     */
    fun showRoom(room: Int): Boolean {
        refused = null
        closeSearch()
        return session.send(Requests.showRoom(room))
    }

    /**
     * A new `mapDownload`. A download that ends is kept in [downloadEnd] and, as it ends, told to TalkBack in
     * [resources]' words if [speak], its failure interrupting. The same end again, as to a UI that attaches, is not told
     * again; a UI that had heard nothing yet, being new, shows how the last download ended without telling it, unless
     * the map loaded, which the map itself now shows.
     */
    internal fun onMapDownload(message: EngineMessage.MapDownload, resources: Resources, speak: Boolean) {
        val was = download
        download = message
        downloadAsked = false
        val text = endText(message, resources)
        when {
            text == null -> downloadEnd = null

            was.state == MapDownloadState.DOWNLOADING -> {
                downloadEnd = message
                if (speak) Announcer.announce(text, assertive = message.state == MapDownloadState.FAILED)
            }

            // Only NO_DOWNLOAD itself, as no frame the engine sent is that object
            was === NO_DOWNLOAD -> downloadEnd = message.takeIf { it.state != MapDownloadState.LOADED }
        }
    }

    /** Downloads the map the game offers, in place of the profile's. False as for [ProfileSession.send]. */
    fun downloadMap(): Boolean {
        if (downloadAsked) return true
        refused = null
        downloadEnd = null
        downloadAsked = session.send(Requests.downloadMap())
        return downloadAsked
    }

    /** Stops the download, while [download] says it can be. False as for [ProfileSession.send]. */
    fun cancelMapDownload(): Boolean {
        refused = null
        return session.send(Requests.cancelMapDownload())
    }

    /** The player read [end], or it was shown long enough; a later end stays. */
    fun dismissDownloadEnd(end: EngineMessage.MapDownload) {
        if (downloadEnd === end) downloadEnd = null
    }

    /** The player closed the search's results. */
    fun closeSearch() {
        pendingSearch = null
        search = null
    }

    companion object {
        /** What [download] holds until the engine says otherwise: no map offered and none downloading. */
        val NO_DOWNLOAD = EngineMessage.MapDownload(
            offered = false,
            host = "",
            state = MapDownloadState.NONE,
            label = "",
            value = 0,
            maximum = 0,
            cancelable = false,
            error = "",
        )

        // What has the map, whichever profile is the front: the game screen's, which the front's map follows
        private val wanted = EnumMap<Owner, Size?>(Owner::class.java)

        // One thread, under the others: a cut-out ahead of the swap is worth no stutter of what is on the screen
        private val cutter = Executors.newSingleThreadExecutor {
            Thread(it, "AppMapCutOut").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        /** [owner] has the front profile's map at [size] until [hide]; null until it knows its size (see [update]). */
        fun show(owner: Owner, size: Size?) {
            wanted[owner] = size
            EngineClient.front.appMap.update()
        }

        fun hide(owner: Owner) {
            wanted.remove(owner)
            EngineClient.front.appMap.update()
        }

        /**
         * Whether the map in front shows the text behind it, and so wants each frame [decode]s cut out: set on the main
         * thread, read on the reader's.
         */
        @Volatile
        var textBehind = false

        /**
         * Whether the map in front would show the text behind it, while it is behind the text itself: the front profile's
         * frame is then cut out ahead of the swap, so the map comes to the front with the text behind it at once, not
         * once the engine has drawn it anew. Main thread only.
         */
        var cutOutAhead = false
            set(value) {
                field = value
                if (value) EngineClient.front.appMap.cutOut()
            }

        /**
         * [message]'s PNG, decoded off the main thread, and its colours sampled there too, and cut out while
         * [textBehind]; a null bitmap for none, or one that does not decode.
         */
        internal fun decode(message: EngineMessage.AppMap): Decoded {
            val bitmap = if (message.png.isEmpty()) null else BitmapFactory.decodeByteArray(message.png, 0, message.png.size)
            val tones = bitmap?.let { MapBehindText.tones(it.width, it.height) { y, row -> it.getPixels(row, 0, it.width, 0, y, it.width, 1) } }
            return Decoded(bitmap?.asImageBitmap(), tones, bitmap?.takeIf { textBehind }?.let(::overText))
        }

        internal fun overText(bitmap: Bitmap): OverText? {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            // A decoded frame's pixels are copied as the bitmap keeps them, and the cut frame's copied back: getPixels
            // and createBitmap(pixels) convert each one, most of the work of a frame. A bitmap that keeps anything but
            // sRGB colours, a pixel to an Int, is converted
            val kept = bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == true
            val layout = if (kept) TextBehindMap.Layout.ABGR else TextBehindMap.Layout.ARGB
            if (kept) bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels)) else bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val ground = TextBehindMap.ground(pixels, width, height, layout) ?: return null
            val background = ground.background ?: return OverText(ground, bitmap.asImageBitmap())
            TextBehindMap.cutOut(pixels, background, layout)
            val frame = if (kept) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(pixels)) }
            } else {
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            }
            return OverText(ground, frame.asImageBitmap())
        }

        /** What [end] says to the player of how a download ended; null while none has ended. */
        internal fun endText(end: EngineMessage.MapDownload, resources: Resources): String? = when (end.state) {
            MapDownloadState.NONE, MapDownloadState.DOWNLOADING -> null
            MapDownloadState.LOADED -> resources.getString(R.string.map_download_loaded, end.host)
            MapDownloadState.CANCELLED -> resources.getString(R.string.map_download_cancelled)
            MapDownloadState.FAILED -> resources.getString(R.string.map_download_failed, end.error)
        }

        /** As the engine takes `setAppMapSize`'s scale and sizes: IEngine::maxAppMapScale and PngImage::maxSide. */
        const val MAX_SCALE = 4.0
        const val MAX_SIDE = 4096

        private const val TAG = "AppMap"
    }
}
