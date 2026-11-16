package org.mudlet.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import org.mudlet.android.protocol.AddonCommand
import org.mudlet.android.protocol.ButtonBar
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LabelLink
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.MudletSize
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Placement
import org.mudlet.android.protocol.PointerType
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.ScriptWindows

/**
 * The script windows' part of [EngineClient] (docs/phase-f.md): the windows, button bars, commands and map the engine
 * describes, for the game screen to draw, and the requests about them. Main thread only, as EngineClient's state;
 * EngineClient hands it the engine's messages about them and tells it of each attach.
 */
object ScriptWindowsClient {
    var state by mutableStateOf(ScriptWindows())
        private set

    /** The profile's button bars and its packages' commands, each as the engine last sent them whole. */
    var buttonBars by mutableStateOf(emptyList<ButtonBar>())
        private set
    var commands by mutableStateOf(emptyList<AddonCommand>())
        private set

    /** The map as the engine last drew it (docs/protocol.md, "The map"); null until it first does, for each engine instance. */
    var map by mutableStateOf<MapImage?>(null)
        private set

    /** Why the engine refused the last map request, until the next one is sent or the player dismisses it. */
    var mapRefusal by mutableStateOf<String?>(null)
        private set

    /** Fitted or stacked, or by the game area's width: the setting docs/phase-f.md's "Layout" offers the player. */
    var layoutMode by mutableStateOf(LayoutMode.AUTO)

    // The size the game screen wants Mudlet's main window to be, and the one this engine instance last got
    private var wantedSize: MudletSize? = null
    private var sentSize: MudletSize? = null

    // Where this engine instance was last told each user window is
    private val sentPlacements = HashMap<String, Placement>()

    /** An image file of the engine's, as a label shows it: `getImage`'s fields. */
    data class ImageRequest(val path: String, val w: Int? = null, val h: Int? = null, val frame: Int? = null) {
        init {
            require((w == null) == (h == null)) { "an image is asked for at a size with both w and h, or neither" }
        }
    }

    /**
     * A `map` or `appMap` frame: [w] by [h] its size in the map's pixels, and [bitmap] its PNG, null if that did not
     * decode, of as many pixels for `map` and larger by the scale asked for `appMap`; [viewOnly] false while the map is
     * edited.
     */
    class MapImage(val bitmap: ImageBitmap?, val w: Int, val h: Int, val viewOnly: Boolean)

    /** The images the engine sent, by what was asked; one not there yet is asked for with [requestImage]. */
    val images = mutableStateMapOf<ImageRequest, ImageBitmap>()

    // What was asked and not yet answered, by request id, and what of this engine instance's is kept or awaited
    private val pendingImages = HashMap<Long, ImageRequest>()
    private val askedImages = HashSet<ImageRequest>()

    // How many of the labels drawn show each image; one none shows is let go first, the oldest first
    private val shownImages = HashMap<ImageRequest, Int>()
    private val imageOrder = LinkedHashSet<ImageRequest>()

    // The links of each console asked about since its text last came, answered or not
    private val askedLinks = HashMap<String, HashSet<Int>>()

    // The label the player last touched, which is told when another is
    private var touchedLabel: String? = null

    internal fun onMessage(message: EngineMessage) {
        when (message) {
            is EngineMessage.ButtonBars -> buttonBars = message.bars

            is EngineMessage.Commands -> commands = message.commands

            else -> {
                // New text may have changed the console's links, so they are asked about again
                if (message is EngineMessage.WindowText && message.replyTo == null) askedLinks.remove(message.window)
                state = state.after(message)
            }
        }
    }

    /** [message]'s PNG, decoded off the main thread; null for one that does not decode. */
    internal fun decode(message: EngineMessage.Image): ImageBitmap? = decodePng(message.png)

    /** [message]'s PNG, decoded off the main thread; null for one that does not decode. */
    internal fun decode(message: EngineMessage.Map): ImageBitmap? = decodePng(message.png)

    private fun decodePng(png: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()

    /** A new `map`, with what [decode] made of it. */
    internal fun onMap(message: EngineMessage.Map, bitmap: ImageBitmap?) {
        if (bitmap == null) Log.w(TAG, "the engine's map, ${message.w} by ${message.h}, did not decode")
        map = MapImage(bitmap, message.w, message.h, message.viewOnly)
    }

    /**
     * An `error` from the engine: the refusal of a map request is shown with the map that asked, this or
     * [AppMapClient]'s. A link's is only logged, as a link a script let go of or disabled, or one the player tapped as it
     * went, is no fault of theirs; so is a text box's, which a script made read-only or hid as the player typed.
     */
    internal fun onError(message: EngineMessage.Error) {
        val request = message.message.substringBefore(':')
        val reason = message.message.substringAfter(':').trim()
        if (AppMapClient.onRefused(request, reason)) return
        if (request == "mapPointer" || request == "setMapViewOnly") mapRefusal = reason
        if (request == "getLink" || request == "followLink") Log.w(TAG, "a script window's link: ${message.message}")
        // The box's own textBox follows, which puts back what it holds
        if (request == "setTextBoxText") Log.w(TAG, "a text box's typing: ${message.message}")
    }

    /** An answer to [requestImage], with what [decode] made of it. */
    internal fun onImage(message: EngineMessage.Image, bitmap: ImageBitmap?) {
        val request = pendingImages.remove((message.replyTo as? Number)?.toLong()) ?: return
        if (bitmap == null) {
            // Not asked again: the same file would not decode better
            Log.w(TAG, "the engine's image ${request.path} did not decode")
            return
        }
        images[request] = bitmap
        imageOrder.add(request)
        trimImages()
    }

    /** A label draws [request] until [imageHidden]: it is not let go while one does. */
    fun imageShown(request: ImageRequest) {
        shownImages[request] = (shownImages[request] ?: 0) + 1
    }

    fun imageHidden(request: ImageRequest) {
        val count = (shownImages[request] ?: return) - 1
        if (count > 0) shownImages[request] = count else shownImages.remove(request)
        trimImages()
    }

    // Lets go of the oldest images no label shows while there are more than MAX_IMAGES; one let go is asked again
    // when a label shows it
    private fun trimImages() {
        val unshown = imageOrder.iterator()
        while (images.size > MAX_IMAGES && unshown.hasNext()) {
            val request = unshown.next()
            if (request in shownImages) continue
            unshown.remove()
            images.remove(request)
            askedImages.remove(request)
        }
    }

    /** A new engine instance knows none of the windows, nor the size or placements the last one was given. */
    internal fun onAttached(sameInstance: Boolean) {
        if (!sameInstance) {
            state = ScriptWindows()
            buttonBars = emptyList()
            commands = emptyList()
            map = null
            mapRefusal = null
            sentSize = null
            sentPlacements.clear()
            touchedLabel = null
            // Its files may not be the last one's
            images.clear()
            imageOrder.clear()
            askedImages.clear()
        }
        // What the last link did not answer is asked again
        pendingImages.clear()
        askedLinks.clear()
        askedImages.retainAll(images.keys)
        // Also a size that could not be sent while the link was down
        wantedSize?.let(::setMainWindowSize)
        AppMapClient.onAttached(sameInstance)
    }

    /** Gives Mudlet's main window [size], unless this engine instance has it already; again to a new one. */
    fun setMainWindowSize(size: MudletSize) {
        wantedSize = size
        if (size == sentSize) return
        sentSize = if (EngineClient.send(Requests.setMainWindowSize(size.w, size.h))) size else null
    }

    /** Tells the engine where the user window [name] is, unless it was told already. */
    fun placeUserWindow(name: String, placement: Placement) {
        if (sentPlacements[name] == placement) return
        val sent = EngineClient.send(Requests.placeUserWindow(name, placement.x, placement.y, placement.w, placement.h, placement.floating))
        if (sent) sentPlacements[name] = placement else sentPlacements.remove(name)
    }

    /** The close button of a user window: the engine hides it. */
    fun closeUserWindow(name: String) {
        EngineClient.send(Requests.closeUserWindow(name))
    }

    /** Enter on the command line [name]. False as for [EngineClient.send]. */
    fun sendWindowCommandLine(name: String, text: String): Boolean = EngineClient.send(Requests.sendWindowCommandLine(name, text))

    fun commandLineShown(name: String) {
        state = state.commandLineShown(name)
    }

    /**
     * What the player has typed in the text box [name], all of it: the engine sets the box to it, and [state] holds it
     * at once, as the engine does not echo it. False as for [EngineClient.send], and then [state] keeps what it had.
     */
    fun setTextBoxText(name: String, text: String): Boolean {
        val connection = EngineClient.sendOn(Requests.setTextBoxText(name, text))
        if (connection != 0) state = state.textBoxTyped(name, text, connection)
        return connection != 0
    }

    /**
     * A tap on the button or menu entry [button] of [buttonBars]: its command and script run, and a push-down one goes
     * down or up, which the next [buttonBars] shows. False as for [EngineClient.send].
     */
    fun pressButton(button: Int): Boolean = EngineClient.send(Requests.pressButton(button))

    /** A tap on the command [command] of [commands]: a checkable one is ticked or unticked. False as for [EngineClient.send]. */
    fun activateCommand(command: Int): Boolean = EngineClient.send(Requests.activateCommand(command))

    /**
     * What the player did on the map at [at], in the pixels of the last [map]: a tap selects the room there ([type]
     * press), a double tap walks to it, and a wheel turn of [wheelY] zooms about it. False as for [EngineClient.send].
     */
    fun mapPointer(type: MapPointerType, at: MapPoint, wheelY: Int = 0): Boolean {
        mapRefusal = null
        val button = if (type == MapPointerType.WHEEL) 0 else POINTER_LEFT
        return EngineClient.send(Requests.mapPointer(type, at.x, at.y, button, 0, wheelY))
    }

    /** The map viewed only, or edited; the next [map] says which it is. False as for [EngineClient.send]. */
    fun setMapViewOnly(viewOnly: Boolean): Boolean {
        mapRefusal = null
        AppMapClient.viewOnlyAskedByMapper()
        return EngineClient.send(Requests.setMapViewOnly(viewOnly))
    }

    /** The player read [mapRefusal]. */
    fun dismissMapRefusal() {
        mapRefusal = null
    }

    /** Asks the engine what the link [link] of the console [window] offers, unless it was asked since its text came; [state] gets it. */
    fun requestLink(window: String, link: Int) {
        val asked = askedLinks.getOrPut(window) { HashSet() }
        if (!asked.add(link)) return
        if (!EngineClient.send(Requests.getLink(link, window, EngineClient.newRequestId()))) asked.remove(link)
    }

    /**
     * A tap on the link [link] of the console [window], or with [item], that item picked from its menu: what it runs
     * changes the console, whose next text shows it. False as for [EngineClient.send].
     */
    fun followLink(window: String, link: Int, item: Int? = null): Boolean = EngineClient.send(Requests.followLink(link, item, window, EngineClient.newRequestId()))

    /** Asks the engine for [request], unless it was asked already; [images] gets it. */
    fun requestImage(request: ImageRequest) {
        if (request in images || request in askedImages) return
        val id = EngineClient.newRequestId()
        if (EngineClient.send(Requests.getImage(request.path, request.w, request.h, request.frame, id))) {
            askedImages.add(request)
            pendingImages[id] = request
        }
    }

    /**
     * What the player's finger did on the label [label], at [x], [y] in its pixels: `pointer`, with [button] that
     * changed and [buttons] held after it. The label touched before this one is told the pointer left it.
     */
    fun pointer(label: String, type: PointerType, x: Double, y: Double, button: Int = 0, buttons: Int = 0) {
        if (type == PointerType.ENTER) {
            if (touchedLabel == label) return
            touchedLabel?.let { EngineClient.send(Requests.pointer(it, PointerType.LEAVE, 0.0, 0.0, 0, 0, 0, 0)) }
            touchedLabel = label
        }
        EngineClient.send(Requests.pointer(label, type, x, y, button, buttons, 0, 0))
    }

    /** Follows a link the player tapped in a label's text, as [LabelLink] says. */
    fun followLabelLink(context: Context, href: String) {
        when (val link = LabelLink.of(href)) {
            is LabelLink.Web -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no app opens the label link ${link.url}", e)
            }

            is LabelLink.NotRun -> Log.w(TAG, "the label link \"$href\" was not run: ${link.scheme}: is not a scheme Mudlet handles, and the link is not a Lua command")

            else -> link.request(EngineClient.newRequestId())?.let(EngineClient::send)
        }
    }

    private const val TAG = "ScriptWindows"

    // The images kept beyond those shown: few enough that sized ones asked again as labels resize do not pile up
    private const val MAX_IMAGES = 64
}
