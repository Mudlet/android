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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.mudlet.android.protocol.AddonCommand
import org.mudlet.android.protocol.ButtonBar
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyPressResult
import org.mudlet.android.protocol.LabelLink
import org.mudlet.android.protocol.LabelStyle
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.MainWindowDevice
import org.mudlet.android.protocol.MapInfo
import org.mudlet.android.protocol.MapPoint
import org.mudlet.android.protocol.MapPointerType
import org.mudlet.android.protocol.MapTarget
import org.mudlet.android.protocol.MudletSize
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Placement
import org.mudlet.android.protocol.PointerType
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.RichText
import org.mudlet.android.protocol.ScriptWindows
import org.mudlet.android.protocol.TextBoxContent

/**
 * The script windows' part of a [ProfileSession] (docs/phase-f.md): the windows, button bars, commands and map the
 * profile's engine describes, for the game screen to draw, and the requests about them. Main thread only, as the
 * session's state; the session hands it the messages about them and tells it of each attach.
 */
class ScriptWindowsClient internal constructor(private val session: ProfileSession) {
    var state by mutableStateOf(ScriptWindows())
        private set

    /** The profile's button bars and its packages' commands, each as the engine last sent them whole. */
    var buttonBars by mutableStateOf(emptyList<ButtonBar>())
        private set
    var commands by mutableStateOf(emptyList<AddonCommand>())
        private set

    /** The keys whose press goes to a key binding of the profile, as the engine last sent them (docs/protocol.md, "Key bindings"). */
    var keyBindings by mutableStateOf(emptySet<KeyBinding>())
        private set

    /** The script text box the player is typing in, if any: the keys typed there are the box's, not the bindings'. */
    internal var focusedTextBox: String? = null

    /** The map as the engine last drew it (docs/protocol.md, "The map"); null until it first does, for each engine instance. */
    var map by mutableStateOf<MapImage?>(null)
        private set

    /** Why the engine refused the last map request, until the next one is sent or the player dismisses it. */
    var mapRefusal by mutableStateOf<String?>(null)
        private set

    /** The map's moves by touch. */
    internal val mapMover = MapMover(MapTarget.MAPPER) {
        mapRefusal = null
        session.send(it)
    }

    /** The map's rooms and areas the player asks about. */
    internal val mapRooms = MapRooms(MapTarget.MAPPER, mapMover, session::newRequestId) {
        mapRefusal = null
        session.appMap.roomsAskedByMapper()
        session.send(it)
    }

    /**
     * A `prompt:` link in a label was sent to the engine to follow, or the caret key left the game's lines
     * ([focusInputLine]): the input line is to take the focus and call [inputFocusTaken].
     */
    var focusInput by mutableStateOf(false)
        private set

    // The size the game screen wants Mudlet's main window to be, and the one this engine instance last got
    private var wantedSize: MudletSize? = null
    private var sentSize: MudletSize? = null

    // The same for what the main window is drawn on
    private var wantedDevice: MainWindowDevice? = null
    private var sentDevice: MainWindowDevice? = null

    // The scale and density the mapper window's map is shown at, and those the engine draws it at since the UI attached:
    // 1 until asked, as the engine goes back to 1 when a UI goes; null when they could not be sent
    private var wantedMapScale = MapScale(1.0, 1.0)
    private var sentMapScale: MapScale? = MapScale(1.0, 1.0)

    // Where this engine instance was last told each user window is
    private val sentPlacements = HashMap<String, Placement>()

    /**
     * An image file of the engine's, as a label shows it: `getImage`'s fields; or, [decoded], the image of the `data:`
     * URL [path] in a label's text, which the app decodes itself ([decodeImage]) and the engine is never asked for, so
     * a style sheet's `url()` of the same text, which the engine refuses as Qt's style sheet draws none, is apart.
     */
    data class ImageRequest(val path: String, val w: Int? = null, val h: Int? = null, val frame: Int? = null, val decoded: Boolean = false) {
        init {
            require((w == null) == (h == null)) { "an image is asked for at a size with both w and h, or neither" }
        }
    }

    /**
     * A `map` or `appMap` frame: [w] by [h] its size in the map's pixels, and [bitmap] its PNG, null if that did not
     * decode, larger by the scale asked for with [setMapScale] or `setAppMapSize`; [viewOnly] false while the map is
     * edited; [info] its map info box, which the picture leaves out, null with none.
     */
    class MapImage(val bitmap: ImageBitmap?, val w: Int, val h: Int, val viewOnly: Boolean, val info: MapInfo? = null)

    /**
     * The images the engine sent, and those the app decoded of `data:` URLs ([decodeImage]), by what was asked; one not
     * there yet is asked for with [requestImage].
     */
    val images = mutableStateMapOf<ImageRequest, ImageBitmap>()

    // What was asked and not yet answered, by request id, and what of this engine instance's is kept or awaited, or
    // being decoded
    private val pendingImages = HashMap<Long, ImageRequest>()
    private val askedImages = HashSet<ImageRequest>()

    // How many of the labels drawn show each image; one none shows is let go first, the oldest first
    private val shownImages = HashMap<ImageRequest, Int>()
    private val imageOrder = LinkedHashSet<ImageRequest>()

    // The links of each console asked about since its text last came, answered or not
    private val askedLinks = HashMap<String, HashSet<Int>>()

    // The label the player last touched, which is told when another is
    private var touchedLabel: String? = null

    // What the player has typed in each text box that the engine has not been sent, as the box last drew it
    private val typing = HashMap<String, String>()

    internal fun onMessage(message: EngineMessage) {
        when (message) {
            is EngineMessage.ButtonBars -> buttonBars = message.bars

            is EngineMessage.Commands -> commands = message.commands

            is EngineMessage.KeyBindings -> keyBindings = message.keys.toSet()

            // Only for a key judged by a list the bindings have since changed from, or whose text hidden input keeps;
            // the key is then dropped
            is EngineMessage.KeyPressed -> if (message.result == KeyPressResult.NO_BINDING || message.result == KeyPressResult.KEY_KEPT) {
                Log.i(TAG, "a key sent to the bindings was not theirs: ${message.result}")
            }

            else -> {
                // New text may have changed the console's links, so they are asked about again
                if (message is EngineMessage.WindowText && message.replyTo == null) askedLinks.remove(message.window)
                state = state.after(message)
            }
        }
    }

    /** A new `map`, with what [decode] made of it. */
    internal fun onMap(message: EngineMessage.Map, bitmap: ImageBitmap?) {
        if (bitmap == null) Log.w(TAG, "the engine's map, ${message.w} by ${message.h}, did not decode")
        map = MapImage(bitmap, message.w, message.h, message.viewOnly, message.info)
        mapMover.onFrame(message.view)
        mapRooms.onFrame(message.view)
    }

    /**
     * An `error` from the engine: the refusal of a map request is shown with the map that asked, this or the session's
     * [AppMapClient]'s; that of [setMapScale] is only logged. A link's is only logged too, as a link a script let go of
     * or disabled, or one the player tapped as it went, is no fault of theirs; so is a text box's, which a script made
     * read-only or hid as the player typed, and an MXP frame tab's, whose page the game closed or whose frame it hid as
     * the player tapped, and a label link's, which the engine tried, which the label no longer has, or which is a
     * `prompt:` link while the game hides input. Those logs leave out the engine's reason, which can quote a link, a
     * label, a frame or a box that the game or a script made, text a problem report is not to carry.
     */
    internal fun onError(message: EngineMessage.Error) {
        val request = message.message.substringBefore(':')
        val reason = message.message.substringAfter(':').trim()
        val replyTo = (message.replyTo as? Number)?.toLong()
        if (session.appMap.onRefused(request, reason, replyTo)) return
        // One to a search asked again since is not shown, as its answer would not be
        if (request == "findRooms" && !mapRooms.searching(replyTo)) return
        if (request == "moveMap" || request == "followPlayer") mapMover.clear()
        if (request in MapRooms.REQUESTS) mapRooms.onRefused(request)
        if (request in MAP_REQUESTS) mapRefusal = reason
        if (request == "getLink" || request == "followLink") Log.w(TAG, "a script window's link was refused")
        // The box's own textBox follows, which puts back what it holds
        if (request == "setTextBoxText") Log.w(TAG, "a text box's typing was refused")
        // The map stays at the scale the engine last took
        if (request == "setMapScale") Log.w(TAG, "the map's scale: ${message.message}")
        // The tabs follow the engine's windows, so they still show the page in front
        if (request == "selectFrameTab") Log.w(TAG, "an MXP frame's tab was refused")
        // The key is lost, as when the profile closes under it
        if (request == "pressKey") Log.w(TAG, "a key binding's key was refused")
        // The engine tried the link, the label no longer has it, or it is a prompt: link while the game hides input:
        // following it here would run it twice, or do what the engine refused
        if (request == "followLabelLink") Log.w(TAG, "a label's link was refused")
    }

    /** An answer to [requestImage], with what [decode] made of it. */
    internal fun onImage(message: EngineMessage.Image, bitmap: ImageBitmap?) {
        val request = pendingImages.remove((message.replyTo as? Number)?.toLong()) ?: return
        if (bitmap == null) {
            // Not asked again: the same file would not decode better
            Log.w(TAG, "an image of the engine's did not decode")
            return
        }
        keep(request, bitmap)
    }

    /**
     * Decodes [request]'s `data:` URL, [ImageRequest.decoded], off the main thread into [images], where the engine's
     * files are kept and let go: once, as [requestImage] asks once, and to its end, so each label that shows it gets it.
     * One that does not decode is forgotten, so script-made URLs that fail do not pile up; it is decoded again only when
     * a label shows it afresh.
     */
    suspend fun decodeImage(request: ImageRequest) {
        if (request in images || !askedImages.add(request)) return
        withContext(NonCancellable) {
            val bitmap = withContext(Dispatchers.Default) { decodeData(request.path) }
            if (bitmap != null) keep(request, bitmap) else askedImages.remove(request)
        }
    }

    private fun keep(request: ImageRequest, bitmap: ImageBitmap) {
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

    // Lets go of the oldest images no label shows while there are more than MAX_IMAGES, or those no label shows hold
    // more than MAX_IMAGE_BYTES; one let go is asked again when a label shows it
    private fun trimImages() {
        var bytes = images.entries.sumOf { (request, image) -> if (request in shownImages) 0L else image.width * image.height * 4L }
        val unshown = imageOrder.iterator()
        while ((images.size > MAX_IMAGES || bytes > MAX_IMAGE_BYTES) && unshown.hasNext()) {
            val request = unshown.next()
            if (request in shownImages) continue
            unshown.remove()
            images.remove(request)?.let { bytes -= it.width * it.height * 4L }
            askedImages.remove(request)
        }
    }

    /** A new engine instance knows none of the windows, nor the size or placements the last one was given. */
    internal fun onAttached(sameInstance: Boolean, connection: Int) {
        if (!sameInstance) {
            // Typing already sent on this connection stays counted, as below
            state = ScriptWindows(textBoxesTyped = state.textBoxesTyped, textBoxesTypedOn = state.textBoxesTypedOn)
            buttonBars = emptyList()
            commands = emptyList()
            keyBindings = emptySet()
            map = null
            mapMover.onFrame(null)
            mapRefusal = null
            sentSize = null
            sentDevice = null
            sentPlacements.clear()
            touchedLabel = null
            // Its files may not be the last one's
            images.clear()
            imageOrder.clear()
            askedImages.clear()
        }
        // Each setTextBoxText is counted afresh on this connection, as the engine counts them after its hello. Typing
        // may have gone out on it already, from this thread before this post ran, and stays counted
        state = state.attached(connection)
        // What was moved and not drawn may not have reached the engine
        mapMover.clear()
        // What the last link did not answer is asked again
        pendingImages.clear()
        askedLinks.clear()
        askedImages.retainAll(images.keys)
        // Also a size that could not be sent while the link was down
        wantedSize?.let(::setMainWindowSize)
        wantedDevice?.let(::setMainWindowDevice)
        mapRooms.onAttached(sameInstance)
        sentMapScale = MapScale(1.0, 1.0)
        setMapScale(wantedMapScale)
        session.appMap.onAttached(sameInstance)
    }

    /** Gives Mudlet's main window [size], unless this engine instance has it already; again to a new one. */
    fun setMainWindowSize(size: MudletSize) {
        wantedSize = size
        if (size == sentSize) return
        sentSize = if (session.send(Requests.setMainWindowSize(size.w, size.h))) size else null
    }

    /** Tells Mudlet what its main window is drawn on, unless this engine instance has it already; again to a new one. */
    fun setMainWindowDevice(device: MainWindowDevice) {
        wantedDevice = device
        if (device == sentDevice) return
        sentDevice = if (session.send(Requests.setMainWindowDevice(device))) device else null
    }

    /**
     * Has the engine draw [map] at [scale] device pixels to each of the mapper window's, laid out as the app's own map
     * is at [density] device pixels to the dp, unless it does already.
     */
    fun setMapScale(scale: Double, density: Float) = setMapScale(MapScale(scale, density.toDouble().coerceIn(1.0, AppMapClient.MAX_SCALE)))

    private fun setMapScale(wanted: MapScale) {
        wantedMapScale = wanted
        if (wanted == sentMapScale) return
        sentMapScale = if (session.send(Requests.setMapScale(wanted.scale, wanted.density))) wanted else null
    }

    private data class MapScale(val scale: Double, val density: Double)

    /** Tells the engine where the user window [name] is, unless it was told already. */
    fun placeUserWindow(name: String, placement: Placement) {
        if (sentPlacements[name] == placement) return
        val sent = session.send(Requests.placeUserWindow(name, placement.x, placement.y, placement.w, placement.h, placement.floating))
        if (sent) sentPlacements[name] = placement else sentPlacements.remove(name)
    }

    /** The close button of a user window: the engine hides it. */
    fun closeUserWindow(name: String) {
        session.send(Requests.closeUserWindow(name))
    }

    /** A tap on the tab of an MXP frame's page [name]: the engine brings it forward. */
    fun selectFrameTab(name: String) {
        session.send(Requests.selectFrameTab(name))
    }

    /** Enter on the command line [name]. False as for [ProfileSession.send]. */
    fun sendWindowCommandLine(name: String, text: String): Boolean {
        flushTyping()
        return session.send(Requests.sendWindowCommandLine(name, text))
    }

    fun commandLineShown(name: String) {
        state = state.commandLineShown(name)
    }

    fun inputFocusTaken() {
        focusInput = false
    }

    fun focusInputLine() {
        focusInput = true
    }

    /** The text box [name] has [text] typed in it that the engine has not been sent, or nothing for null, which [flushTyping] sends. */
    fun typing(name: String, text: String?) {
        if (text == null) typing.remove(name) else typing[name] = text
    }

    /**
     * Sends what the player has typed in the text box [name], all of it, unless it was sent: the engine sets the box to
     * it, as plain text as it keeps it, and [state] holds it at once, as the engine does not send it back. Kept to send
     * again while the link is down; dropped for a box the engine no longer has.
     */
    fun flushTyping(name: String) {
        val text = typing[name] ?: return
        if (name !in state.textBoxes) {
            typing.remove(name)
            return
        }
        val plain = TextBoxContent.plain(text)
        val connection = session.sendOn(Requests.setTextBoxText(name, plain))
        if (connection == 0) return
        typing.remove(name)
        state = state.textBoxTyped(name, plain, connection)
    }

    /** Sends what the player has typed in every text box, before what may run a script that reads them. */
    fun flushTyping() {
        typing.keys.toList().forEach(::flushTyping)
    }

    /**
     * A tap on the button or menu entry [button] of [buttonBars]: its command and script run, and a push-down one goes
     * down or up, which the next [buttonBars] shows. False as for [ProfileSession.send].
     */
    fun pressButton(button: Int): Boolean {
        flushTyping()
        return session.send(Requests.pressButton(button))
    }

    /** A press of [key], one of [keyBindings], which typed [text], empty for nothing: its binding runs. False as for [ProfileSession.send]. */
    fun pressKey(key: KeyBinding, text: String): Boolean {
        flushTyping()
        return session.send(Requests.pressKey(key.key, key.modifiers, text, session.newRequestId()))
    }

    /** A tap on the command [command] of [commands]: a checkable one is ticked or unticked. False as for [ProfileSession.send]. */
    fun activateCommand(command: Int): Boolean {
        flushTyping()
        return session.send(Requests.activateCommand(command))
    }

    /**
     * What the player did on the map at [at], in the pixels of the last [map]: a tap selects the room there ([type]
     * press), a double tap walks to it, and a wheel turn of [wheelY] zooms about it. False as for [ProfileSession.send].
     */
    fun mapPointer(type: MapPointerType, at: MapPoint, wheelY: Int = 0): Boolean {
        mapRefusal = null
        flushTyping()
        val button = if (type == MapPointerType.WHEEL) 0 else POINTER_LEFT
        return session.send(Requests.mapPointer(type, at.x, at.y, button, 0, wheelY))
    }

    /** The map viewed only, or edited; the next [map] says which it is. False as for [ProfileSession.send]. */
    fun setMapViewOnly(viewOnly: Boolean): Boolean {
        mapRefusal = null
        session.appMap.viewOnlyAskedByMapper()
        return session.send(Requests.setMapViewOnly(viewOnly))
    }

    /** The player read [mapRefusal]. */
    fun dismissMapRefusal() {
        mapRefusal = null
    }

    /** Asks the engine what the link [link] of the console [window] offers, unless it was asked since its text came; [state] gets it. */
    fun requestLink(window: String, link: Int) {
        val asked = askedLinks.getOrPut(window) { HashSet() }
        if (!asked.add(link)) return
        if (!session.send(Requests.getLink(link, window, session.newRequestId()))) asked.remove(link)
    }

    /**
     * A tap on the link [link] of the console [window], or with [item], that item picked from its menu: what it runs
     * changes the console, whose next text shows it. False as for [ProfileSession.send].
     */
    fun followLink(window: String, link: Int, item: Int? = null): Boolean {
        flushTyping()
        return session.send(Requests.followLink(link, item, window, session.newRequestId()))
    }

    /** Asks the engine for [request], unless it was asked already; [images] gets it. */
    fun requestImage(request: ImageRequest) {
        if (request in images || request in askedImages) return
        val id = session.newRequestId()
        if (session.send(Requests.getImage(request.path, request.w, request.h, request.frame, id))) {
            askedImages.add(request)
            pendingImages[id] = request
        }
    }

    /**
     * What the player's finger or mouse did on the label [label], at [x], [y] in its pixels: `pointer`, with [button]
     * that changed and [buttons] held after it. The label touched before this one is told the pointer left it, and a
     * label that was not touched last is not told so again.
     */
    fun pointer(label: String, type: PointerType, x: Double, y: Double, button: Int = 0, buttons: Int = 0) {
        flushTyping()
        if (type == PointerType.ENTER) {
            if (touchedLabel == label) return
            touchedLabel?.let { session.send(Requests.pointer(it, PointerType.LEAVE, 0.0, 0.0, 0, 0, 0, 0)) }
            touchedLabel = label
        }
        if (type == PointerType.LEAVE) {
            if (touchedLabel != label) return
        }
        session.send(Requests.pointer(label, type, x, y, button, buttons, 0, 0))
    }

    /**
     * Follows the link [href] the player tapped in the text of the label [label], as [LabelLink] says: the engine does,
     * but for a web link. A `prompt:` link gives the input line the focus, as Mudlet's label gives its command line the
     * focus, for the player to finish the text the engine puts there.
     */
    fun followLabelLink(context: Context, label: String, href: String) {
        when (val link = LabelLink.of(href)) {
            is LabelLink.Web -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no app opens a label's web link", e)
            }

            is LabelLink.Engine -> {
                flushTyping()
                if (session.send(Requests.followLabelLink(label, link.href, session.newRequestId())) && link.prompts) focusInput = true
            }
        }
    }

    companion object {
        /** Fitted or stacked, or by the game area's width: the setting docs/phase-f.md's "Layout" offers the player. */
        var layoutMode by mutableStateOf(LayoutMode.AUTO)

        /** [message]'s PNG, decoded off the main thread; null for one that does not decode. */
        internal fun decode(message: EngineMessage.Image): ImageBitmap? = decodePng(message.png)

        /** [message]'s PNG, decoded off the main thread; null for one that does not decode. */
        internal fun decode(message: EngineMessage.Map): ImageBitmap? = decodePng(message.png)

        // An image's bytes decoded, null for bytes that do not decode or an image too large for the memory left
        private fun decodePng(png: ByteArray): ImageBitmap? = try {
            BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
        } catch (_: OutOfMemoryError) {
            null
        }

        // A `data:` URL's image, null as for the engine's, and for one wider or higher than the engine sends one
        // (`PngImage::maxSide`), found from its size alone first; its base64 read too within the memory left
        private fun decodeData(path: String): ImageBitmap? = try {
            RichText.Image(path, null, null).data()?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (maxOf(bounds.outWidth, bounds.outHeight) > LabelStyle.MAX_LENGTH) null else decodePng(bytes)
            }
        } catch (_: OutOfMemoryError) {
            null
        }

        private const val TAG = "ScriptWindows"

        // The mapper window's requests whose refusal is shown on its map
        private val MAP_REQUESTS = setOf("mapPointer", "setMapViewOnly", "moveMap", "followPlayer") + MapRooms.REQUESTS

        // The images kept beyond those shown: few enough that sized ones asked again as labels resize do not pile up
        private const val MAX_IMAGES = 64

        // And the bytes those hold at most, as images in a label's text are kept at their own size: one of the
        // largest the engine sends, 4096 pixels a side
        private const val MAX_IMAGE_BYTES = 64L shl 20
    }
}
