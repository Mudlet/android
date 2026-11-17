package org.mudlet.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.math.roundToInt

// Wire format shared with native/enginehost/SocketBridge.cpp, specified in docs/protocol.md: a 4-byte
// big-endian length, then one UTF-8 JSON object whose "t" field is the message type.
const val PROTOCOL_VERSION = 21
private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

// A run's colours: -1 default, 0-15 ANSI palette, RUN_RGB | 0xRRGGBB exact
// Flattened runs: start, length, fg, bg, attributes, link (0 for none)
const val RUN_STRIDE = 6
const val RUN_RGB = 0x1000000
const val RUN_BOLD = 1
const val RUN_ITALIC = 2
const val RUN_UNDERLINE = 4
const val RUN_STRIKE = 8
const val RUN_REVERSE = 16
const val RUN_OVERLINE = 32
const val RUN_BLINK = 64
const val RUN_FAST_BLINK = 128
const val RUN_CONCEALED = 256

// Qt's mouse buttons and keyboard modifiers, as Requests.pointer takes them
const val POINTER_LEFT = 1
const val POINTER_RIGHT = 2
const val POINTER_MIDDLE = 4
const val MODIFIER_SHIFT = 0x02000000
const val MODIFIER_CONTROL = 0x04000000
const val MODIFIER_ALT = 0x08000000
const val MODIFIER_META = 0x10000000

/** A profile as the engine lists it; the password is never part of one. */
data class Profile(val name: String, val host: String, val port: Int, val tls: Boolean, val login: String, val autoReconnect: Boolean)

/** Where an open profile is in its life (docs/protocol.md, `openProfiles`); a state this client does not know is [LOADED]. */
enum class OpenProfileState(val wire: String) {
    STARTING("starting"),
    RESTORING("restoring"),
    LOADED("loaded"),
    CLOSING("closing"),
    ;

    companion object {
        fun fromWire(wire: String): OpenProfileState = entries.firstOrNull { it.wire == wire } ?: LOADED
    }
}

/**
 * An open profile, served on its own [socket] (docs/protocol.md, "Several profiles"). [connection] is its last
 * `connection` status; [unread] counts its lines after the last Requests.markSeen, and [attention] says it asked for
 * the player since.
 */
data class OpenProfile(
    val name: String,
    val slot: Int,
    val socket: String,
    val state: OpenProfileState,
    val connection: String,
    val lastSeq: Long,
    val unread: Long,
    val attention: Boolean,
    val front: Boolean,
)

/** An item of a link's menu; a [separator] has no label. */
data class LinkItem(val label: String, val enabled: Boolean, val separator: Boolean)

/** A script window's kind, as upstream names it on the wire. */
enum class WindowKind(val wire: String) {
    MINI_CONSOLE("miniConsole"),
    USER_WINDOW("userWindow"),
    LABEL("label"),
    COMMAND_LINE("commandLine"),
    TEXT_BOX("textBox"),
    SCROLL_BOX("scrollBox"),
    MAPPER("mapper"),
    MXP_FRAME("mxpFrame"),
    ;

    companion object {
        /** Null for a kind this app does not know, whose windows it leaves out. */
        fun fromWire(wire: String): WindowKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A script window is known by its name and kind, as a console's own command line has its console's name. */
data class WindowKey(val name: String, val kind: WindowKind)

/** A window a profile's scripts made, its geometry in Mudlet's pixels within its parent's (docs/protocol.md, `windows`). */
data class ScriptWindow(
    val name: String,
    val kind: WindowKind,
    /** Empty for the main window's area, a dock or an external MXP frame, which have no [parentKind]. */
    val parent: String,
    val parentKind: WindowKind?,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val visible: Boolean,
    val shown: Boolean,
    val stack: Int,
    /** As a run's colour; -1 for none. */
    val bg: Int,
    /** [bg]'s alpha, from 0 (transparent) to 255, which a label's may be below. */
    val bgAlpha: Int,
    val fontFamily: String?,
    /** In points; 0 for none. */
    val fontSize: Double,
    val inDock: Boolean,
    /** A user window's. */
    val dock: Dock?,
    /** A mini console's, user window's or MXP frame's. */
    val console: Console?,
    /** An MXP frame's tab header; null for a frame without one, for a page of one and for the other kinds. */
    val frameTabs: FrameTabs? = null,
) {
    val key get() = WindowKey(name, kind)

    /** [area] is left, right, top or bottom. */
    data class Dock(val autoDock: Boolean, val area: String, val floating: Boolean, val title: String)

    /** [scrolledBackTo] is the row it is scrolled back to, null while it follows new lines. */
    data class Console(val scrolledBackTo: Int?, val columns: Int, val rows: Int, val scrollBar: Boolean, val timeStamps: Boolean)

    /**
     * The tabs Mudlet draws along an MXP frame's top, above its console: [pages], never empty, each the MXP frame it
     * shows, the first being this frame's own console, and [current] the index of the one in front, which only is
     * shown. [Requests.selectFrameTab] brings another forward.
     */
    data class FrameTabs(val pages: List<Page>, val current: Int) {
        init {
            require(pages.isNotEmpty() && current in pages.indices) { "no page $current of ${pages.size}" }
        }

        /** [name] is the MXP frame it shows, and [title] the text on its tab. */
        data class Page(val name: String, val title: String)

        val front get() = pages[current]
    }
}

/** What a label shows, as THeadlessSession::labelContent() gives it (docs/protocol.md, `label`). */
data class LabelContent(
    val name: String,
    /** Rich text, as echo() or setLabelText() gave it; empty while an image or movie shows in its place. */
    val text: String,
    /** The file setBackgroundImage() gave it, a raster image or, with [svg], an SVG drawn under the text. */
    val image: String,
    val svg: Boolean,
    /** As a run's colour, -1 for none, with its alpha from 0 to 255. */
    val svgTint: Int,
    val svgTintAlpha: Int,
    /** In degrees. */
    val svgRotation: Double,
    val svgShearX: Double,
    val svgShearY: Double,
    /** The file setMovie() gave it, shown in place of the text. */
    val movie: String,
    val moviePaused: Boolean,
    /** The frame a paused movie shows; -1 while it plays. */
    val movieFrame: Int,
    /** In percent of the file's own speed. */
    val movieSpeed: Int,
    /** The size scaleMovie() gave it; null for the file's own. */
    val movieW: Int?,
    val movieH: Int?,
    val toolTip: String,
    /** The pointer passes through it to what is under it. */
    val clickThrough: Boolean,
    val accessibleName: String,
    /** It has a click or release callback, so a screen reader is told it is a button. */
    val accessibleButton: Boolean,
    /** How a screen reader is told of its new text, as setLabelLiveRegion() gave it. */
    val liveRegion: LiveRegion,
    /** What a screen reader reads it as, as a progress bar's, as setLabelAccessibleValue() gave it; null for none. */
    val accessibleValue: AccessibleValue?,
    /** As setLabelStyleSheet() gave it, which the engine does not interpret. */
    val styleSheet: String,
)

/** What a text box from createTextEdit() holds, as THeadlessSession::textBoxContent() gives it (docs/protocol.md, `textBox`). */
data class TextBoxContent(
    val name: String,
    /** Plain text, lines split by newlines, as getTextEditText() reads it. */
    val text: String,
    /** The player cannot type in it; a script still sets its text. */
    val readOnly: Boolean,
    /** Shown while it is empty. */
    val placeholder: String,
    /** As setTextEditStyleSheet() gave it, which the engine does not interpret. */
    val styleSheet: String,
    /** Tab moves on to the next control rather than typing a tab. */
    val tabMovesFocus: Boolean,
) {
    companion object {
        /**
         * [text] as the engine keeps what the player types, as plain text as the desktop's box does: a non-breaking space
         * as a space, and a carriage return, line or paragraph separator as a newline.
         */
        fun plain(text: String): String = buildString(text.length) { text.replace("\r\n", "\n").forEach { append(PLAIN[it] ?: it) } }

        // As QTextDocument::toPlainText() gives them; U+FDD0 and U+FDD1 are its frames' marks
        private val PLAIN = mapOf('\r' to '\n', '\u2028' to '\n', '\u2029' to '\n', '\uFDD0' to '\n', '\uFDD1' to '\n', '\u00A0' to ' ')
    }
}

/**
 * A label's accessible value: [value], from [min] to [max], [min] less than [max] and [value] between them, the range
 * no wider than a [Double] holds.
 */
data class AccessibleValue(val value: Double, val min: Double, val max: Double) {
    init {
        require(min < max && (max - min).isFinite() && value in min..max) { "$value is not in a range from $min to $max" }
    }

    /** How far [value] is from [min] to [max], as a whole percentage, as a screen reader says it. */
    val percent: Int get() = ((value - min) / (max - min) * 100).roundToInt()
}

/** What a pointer did, as upstream's WindowPointerEvent names it on the wire. */
enum class PointerType(val wire: String) {
    PRESS("press"),
    RELEASE("release"),
    DOUBLE_CLICK("doubleClick"),
    MOVE("move"),
    ENTER("enter"),
    LEAVE("leave"),
    WHEEL("wheel"),
}

/** What a pointer did on the map, as upstream's MapPointerEvent has it; the map takes no drags. */
enum class MapPointerType(val wire: String) {
    PRESS("press"),
    DOUBLE_CLICK("doubleClick"),
    WHEEL("wheel"),
}

/** How findRooms' text matched a room's name (docs/protocol.md, `rooms`); one this client does not know is [CONTAINS]. */
enum class RoomMatchKind(val wire: String) {
    EXACT("exact"),
    PREFIX("prefix"),
    CONTAINS("contains"),

    /** The text is the room's id, and its name does not hold it. */
    ID("id"),
    ;

    companion object {
        fun fromWire(wire: String) = entries.firstOrNull { it.wire == wire } ?: CONTAINS
    }
}

/**
 * A room of the map that findRooms found (docs/protocol.md, `rooms`): its [area]'s id [areaId] and name, how the text
 * matched it, and the fewest [steps] from the player's room to it, -1 for no way there. [id] is what Requests.showRoom takes.
 */
data class RoomMatch(
    val id: Int,
    val name: String,
    val area: String,
    val areaId: Int = 0,
    val match: RoomMatchKind = RoomMatchKind.CONTAINS,
    val steps: Int = -1,
)

/** A way out of a room: one of the twelve directions, "n" to "out", or a special exit's command, to the room [to]. */
data class RoomExit(val direction: String, val to: Int, val toName: String, val locked: Boolean)

/**
 * A room of the map, as Requests.roomInfo asks for it (docs/protocol.md, `room`); [steps] as in [RoomMatch]. What the
 * map's editing sets ([RoomEdit]): the [symbol] drawn on it, its environment [env] and the colour that draws it, [color]
 * (opaque ARGB, null where the engine gave none), its [weight] for the speedwalk and whether the speedwalk avoids it, [locked].
 */
data class RoomInfo(
    val id: Int,
    val name: String,
    val areaId: Int,
    val area: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val steps: Int,
    val exits: List<RoomExit>,
    val symbol: String = "",
    val env: Int = -1,
    val color: Int? = null,
    val weight: Int = 1,
    val locked: Boolean = false,
)

/** What Requests.editRoom sets of a room: each null is kept; [place] is x, y (north up) and z in its area. */
data class RoomEdit(
    val name: String? = null,
    val symbol: String? = null,
    val env: Int? = null,
    val weight: Int? = null,
    val locked: Boolean? = null,
    val place: Triple<Int, Int, Int>? = null,
)

/** An environment a room can be given, with the colour it draws rooms in, opaque ARGB (docs/protocol.md, `envs`). */
data class MapEnv(val env: Int, val color: Int)

/** `#rrggbb` as an opaque ARGB colour; null for anything else. */
fun rgbColor(text: String): Int? = if (text.length == 7 && text[0] == '#') text.substring(1).toIntOrNull(16)?.let { it or OPAQUE } else null

private const val OPAQUE = 0xFF000000.toInt()

/** An area of the map that has rooms (docs/protocol.md, `areas`). */
data class MapArea(val id: Int, val name: String)

/** Which map [Requests.moveMap] and [Requests.followPlayer] act on: the mapper window's ([EngineMessage.Map]) or the app's own ([EngineMessage.AppMap]). */
enum class MapTarget(val wire: String) {
    MAPPER("mapper"),
    APP("app"),
}

/** A room a map frame drew: its middle at [x], [y] in the frame's pixels. */
data class MapRoomPlace(val id: Int, val x: Double, val y: Double, val name: String)

/**
 * What a map frame laid out (docs/protocol.md, "Moving a map"): the [area] and [level] shown; [zoom], the rooms across
 * the frame's narrow side, which [Requests.moveMap] keeps within [minZoom] and [maxZoom] (the least shows the rooms
 * largest); whether it is [following] the [player]'s room (0 for none); a room's cell, [roomSize], in the frame's pixels;
 * the seq of the last moveMap it draws, [moved]; the [rooms] drawn, at most 500, the nearest the middle; and the area's
 * [levels] that have rooms, lowest first, which Requests.showMapLevel takes.
 */
data class MapViewInfo(
    val area: Int,
    val areaName: String,
    val level: Int,
    val zoom: Double,
    val minZoom: Double,
    val maxZoom: Double,
    val following: Boolean,
    val player: Int,
    val roomSize: Double,
    val moved: Int,
    val rooms: List<MapRoomPlace>,
    val levels: List<Int> = emptyList(),
)

/** How the download of the map the game offers goes (docs/protocol.md, `mapDownload`); a state this client does not know is [NONE]. */
enum class MapDownloadState(val wire: String) {
    NONE("none"),
    DOWNLOADING("downloading"),
    LOADED("loaded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(wire: String): MapDownloadState = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** How a label's new text is announced, as ARIA's aria-live (docs/protocol.md, "Labels"); one this client does not know is [OFF]. */
enum class LiveRegion(val wire: String) {
    OFF("off"),
    POLITE("polite"),
    ASSERTIVE("assertive"),
    ;

    companion object {
        fun fromWire(wire: String): LiveRegion = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}

/** Where a button bar goes, as upstream names it on the wire: a strip of the main console, or a floating toolbar. */
enum class BarPlace(val wire: String) {
    TOP("top"),
    LEFT("left"),
    RIGHT("right"),
    FLOATING("floating"),
    ;

    companion object {
        /** Null for a place this app does not know, whose bars it leaves out. */
        fun fromWire(wire: String): BarPlace? = entries.firstOrNull { it.wire == wire }
    }
}

/** A bar of buttons, as THeadlessSession::buttonBars() gives it (docs/protocol.md, `buttonBars`). */
data class ButtonBar(
    val id: Int,
    val name: String,
    /** The bar's, which the engine does not interpret. */
    val styleSheet: String,
    val place: BarPlace,
    val vertical: Boolean,
    /** A horizontal bar fills each column top to bottom with this many buttons, a vertical one each row left to right. */
    val columns: Int,
    /** How many places of that grid are left empty before the first button. */
    val fillerOffset: Int,
    val buttons: List<BarButton>,
)

/** A button on a [ButtonBar], or an entry of a folder's menu; [id] is what Requests.pressButton takes. */
data class BarButton(
    val id: Int,
    val name: String,
    /** The icon's file, for Requests.getImage; empty for none. */
    val icon: String,
    /** Its own, over its bar's; empty for a menu entry. */
    val styleSheet: String,
    /** It stays down, [checked], from one press to the next. */
    val pushDown: Boolean,
    val checked: Boolean,
    /** A folder, whose press opens a menu of its [children]. */
    val menu: Boolean,
    val children: List<BarButton>,
)

/** A command a package's addCommand() added (docs/protocol.md, `commands`); [id] is what Requests.activateCommand takes. */
data class AddonCommand(
    val id: Int,
    /** The package whose code added it; empty for none. */
    val packageName: String,
    val name: String,
    val icon: String,
    val tooltip: String,
    /** The submenus it sits in, separated by '/', and its shortcut as the script wrote it; both empty off the menu. */
    val menuPath: String,
    val shortcut: String,
    val onMenu: Boolean,
    val onToolbar: Boolean,
    val enabled: Boolean,
    /** True once a script's setCommandChecked() made it checkable; it is ticked while [checked]. */
    val checkable: Boolean,
    val checked: Boolean,
    /** The GUI keeps it on the menu whichever profile is in front. */
    val pinned: Boolean,
    /** Its toolbar button flashes. */
    val pulse: Boolean,
)

/** A row of a script window's console: [runs] as [EngineMessage.Line]'s, but a bg of -1 is the window's bg. */
data class WindowRow(val text: String, val runs: IntArray, val prompt: Boolean, val wrapped: Boolean)

sealed interface EngineMessage {
    /** [profile] names the profile a profile's socket serves; null on the control socket. */
    data class Hello(val version: Int, val pid: Int, val instance: String, val profile: String? = null) : EngineMessage
    data class Line(val seq: Long, val text: String, val runs: IntArray, val prompt: Boolean, val replay: Boolean) : EngineMessage
    data class Tail(val text: String, val runs: IntArray, val prompt: Boolean) : EngineMessage

    /** The engine's console was emptied: drop every line and the tail. */
    data object Clear : EngineMessage
    data class Notify(val title: String, val text: String) : EngineMessage
    data class Status(val key: String, val value: String, val replyTo: Any?) : EngineMessage
    data class Pong(val id: Long) : EngineMessage
    data class Synced(val seq: Long) : EngineMessage
    data class Gap(val skipped: Long) : EngineMessage
    data class Error(val message: String, val replyTo: Any? = null) : EngineMessage

    /** The answer to every profile request: the profiles, the last opened first. */
    data class Profiles(val profiles: List<Profile>, val replyTo: Any?) : EngineMessage

    /** Every open profile, in the order opened, to a UI that attached with profileSockets. */
    data class OpenProfiles(val profiles: List<OpenProfile>, val replyTo: Any?) : EngineMessage

    /** The game hides what the player types, as for a password ([wanted]), or has stopped. */
    data class HiddenInput(val wanted: Boolean) : EngineMessage

    /** The input line's history, newest first. */
    data class History(val lines: List<String>) : EngineMessage

    /** A script set the whole input line to [text]. */
    data class CommandLine(val text: String) : EngineMessage

    /** What a script asks screen readers to say; [assertive] interrupts what they are saying. */
    data class Announce(val text: String, val assertive: Boolean) : EngineMessage

    /**
     * What the link [link] of the console [window] offers now, the answer to [Requests.getLink]; [window] is null for
     * the main console. [fg], [bg] and [styles] (run colours and attribute bits) are how it is painted now, null where
     * the run's own apply; [styles] replaces only the run's bold, italic, underline, overline and strike-out.
     */
    data class Link(
        val link: Int,
        val window: String?,
        val tooltip: String,
        val menu: Boolean,
        val menuTitle: String,
        val items: List<LinkItem>,
        val spoiler: Boolean,
        val revealed: Boolean,
        val disabled: Boolean,
        val concealed: Boolean,
        val visited: Boolean,
        val selected: Boolean,
        val fg: Int?,
        val bg: Int?,
        val styles: Int?,
        val replyTo: Any?,
    ) : EngineMessage

    /** What [Link] says of these links may have changed; empty [ids] means any of them. */
    data class LinksChanged(val ids: List<Int>) : EngineMessage

    /** A script's alert() or the game's bell asks for the player's attention for [ms] milliseconds, 0 until they come back. */
    data class Attention(val ms: Int) : EngineMessage

    /** What the player typed ahead of the game hiding input moves from the input line into the masked one, which [HiddenInput] opens next. */
    data object MoveTypedLine : EngineMessage

    /** The script windows: with [full], all of them; otherwise those that changed, and the keys of those [removed]. */
    data class Windows(val full: Boolean, val windows: List<ScriptWindow>, val removed: List<WindowKey>) : EngineMessage

    /** Rows of a script window's console from row [first] of its [lineCount]; [replyTo] is the getWindowText id. */
    data class WindowText(val window: String, val lineCount: Int, val first: Int, val lines: List<WindowRow>, val replyTo: Any?) : EngineMessage

    /** The main window's size the UI gave, the main console's grid, and where its text goes, in Mudlet's pixels. */
    data class MainArea(val w: Int, val h: Int, val columns: Int, val rows: Int, val textX: Int, val textY: Int, val textW: Int, val textH: Int) : EngineMessage

    /** A script set the command line [name] of a script window to [text]. */
    data class WindowCommandLine(val name: String, val text: String) : EngineMessage

    /** A label's content, new or changed; it follows the [Windows] that has the label. */
    data class Label(val label: LabelContent) : EngineMessage

    /**
     * A text box's content, new or changed by a script, or as the engine holds it after a refused setTextBoxText; it
     * follows the [Windows] that has the box. [typed] is how many setTextBoxText for it the engine had from this UI
     * when it sent it: one that has fewer than the UI sent carries text older than the player's.
     */
    data class TextBox(val textBox: TextBoxContent, val typed: Int = 0) : EngineMessage

    /** The answer to getImage: [png] is a PNG of [w] by [h] pixels, empty if it did not decode. */
    data class Image(val path: String, val png: ByteArray, val w: Int, val h: Int, val replyTo: Any?) : EngineMessage

    /** The profile's button bars, whole; empty with no profile open. */
    data class ButtonBars(val bars: List<ButtonBar>) : EngineMessage

    /** The commands the profile's packages added, whole; empty with no profile open. */
    data class Commands(val commands: List<AddonCommand>) : EngineMessage

    /**
     * The 2D map as the mapper window shows it, drawn again: [png] is a PNG of [w] by [h] pixels, the window's size,
     * times the scale [Requests.setMapScale] asked for, empty if it did not decode; [viewOnly] while the map is viewed, not edited; [view] what
     * it laid out, null where it showed no area. Drawn over the mapper only while [w] and [h] are its size; none comes while the mapper hides or
     * has no size.
     */
    data class Map(val png: ByteArray, val w: Int, val h: Int, val viewOnly: Boolean, val view: MapViewInfo? = null) : EngineMessage

    /**
     * The app's own map (Requests.setAppMapSize), drawn again: [png] is a PNG of [w] by [h] of the map's pixels, at the
     * scale asked for, empty if it did not decode; [viewOnly] and [view] as for [Map]. [w] and [h] are 0, and [png] empty, while
     * there is nothing to draw, as with no profile open or a map with no rooms. Sent only while asked for: the engine
     * forgets the size when the UI goes, so one that attaches is sent none until it asks again.
     */
    data class AppMap(val png: ByteArray, val w: Int, val h: Int, val viewOnly: Boolean, val view: MapViewInfo? = null) : EngineMessage

    /** The answer to findRooms for [text]: up to 100 [rooms], best first, and [more] when there were more. */
    data class Rooms(val text: String, val rooms: List<RoomMatch>, val more: Boolean, val replyTo: Any?) : EngineMessage

    /** The answer to Requests.roomInfo. */
    data class Room(val room: RoomInfo, val replyTo: Any?) : EngineMessage

    /** The answer to Requests.mapAreas: the map's areas that have rooms, by name. */
    data class Areas(val areas: List<MapArea>, val replyTo: Any?) : EngineMessage

    /** The answer to Requests.mapEnvs: the environments a room can be given, by number. */
    data class Envs(val envs: List<MapEnv>, val replyTo: Any?) : EngineMessage

    /**
     * The answer to getSettings or setSettings: [values] by Lua's getConfig() keys, each a Boolean, a Double or a
     * String, as the profile holds it now, a key the engine does not know left out; [refused] the keys setSettings did
     * not change, with why.
     */
    data class SettingValues(
        val values: kotlin.collections.Map<String, Any>,
        val refused: kotlin.collections.Map<String, String>,
        val replyTo: Any?,
    ) : EngineMessage

    /**
     * The map the game offers ([offered], from the [host] of its URL, never the rest of it) and how its download
     * (Requests.downloadMap) goes: while [MapDownloadState.DOWNLOADING], Mudlet's progress [label] and [value] of
     * [maximum] (0 until it starts, then Mudlet's guess of the size until the server says it, which [value] can pass),
     * and [cancelable] until the map has arrived; [error] is why it failed, naming no more of a URL than its scheme
     * and host. Only libmudlet sends it, on each change and to each UI that attaches.
     */
    data class MapDownload(
        val offered: Boolean,
        val host: String,
        val state: MapDownloadState,
        val label: String,
        val value: Int,
        val maximum: Int,
        val cancelable: Boolean,
        val error: String,
    ) : EngineMessage
    data class Unknown(val type: String) : EngineMessage
}

object Requests {
    /** With [profileSockets], on the control socket, the UI attaches to each open profile's socket for its traffic. */
    fun hello(instance: String?, lastSeq: Long, profileSockets: Boolean): JSONObject = JSONObject()
        .put("t", "hello").put("v", PROTOCOL_VERSION).put("client", "android-compose")
        .apply {
            if (instance != null) put("instance", instance).put("lastSeq", lastSeq)
            if (profileSockets) put("profileSockets", true)
        }

    fun connect(host: String, port: Int, tls: Boolean): JSONObject = JSONObject().put("t", "connect").put("host", host).put("port", port).put("tls", tls)
    fun send(text: String): JSONObject = JSONObject().put("t", "send").put("text", text)

    /** What the player typed while [EngineMessage.HiddenInput] holds. Never log it, nor the frame. */
    fun sendHidden(text: String): JSONObject = JSONObject().put("t", "sendHidden").put("text", text)
    fun dismissHidden(): JSONObject = JSONObject().put("t", "dismissHidden")

    /** Answered by status `completion` with [id] as its replyTo: the completed line, or empty for none. */
    fun complete(text: String, fromHistory: Boolean, id: Long): JSONObject = JSONObject()
        .put("t", "complete").put("text", text).put("fromHistory", fromHistory).put("id", id)
    fun disconnect(): JSONObject = JSONObject().put("t", "disconnect")
    fun runLua(code: String, id: Long): JSONObject = JSONObject().put("t", "runLua").put("code", code).put("id", id)

    /** The console's size in characters, which a game that asks is told (NAWS); each from 1 to 65535. */
    fun setScreenSize(columns: Int, rows: Int): JSONObject = JSONObject().put("t", "setScreenSize").put("columns", columns).put("rows", rows)

    /**
     * Answered by [EngineMessage.Link], or an Error, with [id] as its replyTo. [link] is a link of the console [window],
     * a script window's as its `windowText` names it, or of the main console where [window] is null.
     */
    fun getLink(link: Int, window: String?, id: Long): JSONObject = JSONObject().put("t", "getLink").put("link", link).put("id", id)
        .apply { if (window != null) put("window", window) }

    /** Follows [link] of [window], as for [getLink], as a tap does, or picks its menu's [item]; only an Error answers, when nothing ran. */
    fun followLink(link: Int, item: Int?, window: String?, id: Long): JSONObject = JSONObject().put("t", "followLink").put("link", link).put("id", id)
        .apply {
            if (item != null) put("item", item)
            if (window != null) put("window", window)
        }

    /** The app in front ([active]) or not, and its window minimized or in the background; leaving the front saves the profile. */
    fun setAppState(active: Boolean, minimized: Boolean): JSONObject = JSONObject().put("t", "setAppState").put("active", active).put("minimized", minimized)

    fun ping(id: Long): JSONObject = JSONObject().put("t", "ping").put("id", id)

    // The profile requests: [id] comes back as the replyTo of the Profiles or Error that answers each
    fun listProfiles(id: Long): JSONObject = JSONObject().put("t", "listProfiles").put("id", id)
    fun saveProfile(profile: Profile, id: Long): JSONObject = JSONObject().put("t", "saveProfile").put("name", profile.name)
        .put("host", profile.host).put("port", profile.port).put("tls", profile.tls).put("login", profile.login)
        .put("autoReconnect", profile.autoReconnect).put("id", id)

    fun deleteProfile(name: String, id: Long): JSONObject = JSONObject().put("t", "deleteProfile").put("name", name).put("id", id)

    /** [password] is for this run of the profile only; null sends none. [alongside] keeps the others open. */
    fun openProfile(name: String, password: String?, connect: Boolean, alongside: Boolean, id: Long): JSONObject = JSONObject()
        .put("t", "openProfile").put("name", name).put("connect", connect).put("id", id)
        .apply {
            if (password != null) put("password", password)
            if (alongside) put("alongside", true)
        }

    /** Answered by [EngineMessage.OpenProfiles], or an Error, with [id] as its replyTo. */
    fun closeProfile(name: String, id: Long): JSONObject = JSONObject().put("t", "closeProfile").put("name", name).put("id", id)
    fun setFront(name: String): JSONObject = JSONObject().put("t", "setFront").put("name", name)

    /** The player has seen [name]'s lines up to [seq]. */
    fun markSeen(name: String, seq: Long): JSONObject = JSONObject().put("t", "markSeen").put("name", name).put("seq", seq)

    // The script windows'. A refusal is an Error naming the request.
    fun setMainWindowSize(w: Int, h: Int): JSONObject = JSONObject().put("t", "setMainWindowSize").put("w", w).put("h", h)

    /** Answered by [EngineMessage.WindowText] with [id] as its replyTo; a negative [first] counts back from the end. */
    fun getWindowText(window: String, first: Int, count: Int, id: Long): JSONObject = JSONObject()
        .put("t", "getWindowText").put("window", window).put("first", first).put("count", count).put("id", id)

    fun placeUserWindow(name: String, x: Int, y: Int, w: Int, h: Int, floating: Boolean): JSONObject = JSONObject()
        .put("t", "placeUserWindow").put("name", name).put("x", x).put("y", y).put("w", w).put("h", h).put("floating", floating)

    fun closeUserWindow(name: String): JSONObject = JSONObject().put("t", "closeUserWindow").put("name", name)
    fun sendWindowCommandLine(name: String, text: String): JSONObject = JSONObject().put("t", "sendWindowCommandLine").put("name", name).put("text", text)

    /** What the player has typed in the text box [name] so far, the whole of its [text]; a refusal is followed by its [EngineMessage.TextBox]. */
    fun setTextBoxText(name: String, text: String): JSONObject = JSONObject().put("t", "setTextBoxText").put("name", name).put("text", text)

    /** The player's tap on the tab of the MXP frame page [name] (ScriptWindow.FrameTabs), which brings it forward. */
    fun selectFrameTab(name: String): JSONObject = JSONObject().put("t", "selectFrameTab").put("name", name)

    /**
     * What the pointer did at [x], [y] in [window]'s pixels ("main" for the main console's area): [button] that
     * changed and [buttons] held after it as POINTER_*, [modifiers] as MODIFIER_*, [wheelY] in eighths of a degree.
     * Each of those is sent only when not 0. Answered only by an Error, when refused.
     */
    fun pointer(window: String, type: PointerType, x: Double, y: Double, button: Int, buttons: Int, modifiers: Int, wheelY: Int): JSONObject = JSONObject()
        .put("t", "pointer").put("window", window).put("type", type.wire).put("x", x).put("y", y)
        .apply {
            if (button != 0) put("button", button)
            if (buttons != 0) put("buttons", buttons)
            if (modifiers != 0) put("modifiers", modifiers)
            if (wheelY != 0) put("wheelY", wheelY)
        }

    /** A click on the [BarButton] [button]: its command and script run. Answered only by an Error, when refused. */
    fun pressButton(button: Int): JSONObject = JSONObject().put("t", "pressButton").put("button", button)

    /** A click on the [AddonCommand] [command]. Answered only by an Error, when refused. */
    fun activateCommand(command: Int): JSONObject = JSONObject().put("t", "activateCommand").put("command", command)

    /**
     * What the pointer did at [x], [y] in the pixels of the last [EngineMessage.Map]: [button] as POINTER_*, with
     * which a press of POINTER_LEFT selects the room there and a double-click speedwalks to it; [modifiers] as
     * MODIFIER_*; [wheelY] in eighths of a degree, which zooms. Each of those is sent only when not 0. Answered only
     * by an Error, when refused.
     */
    fun mapPointer(type: MapPointerType, x: Double, y: Double, button: Int, modifiers: Int, wheelY: Int): JSONObject = JSONObject()
        .put("t", "mapPointer").put("type", type.wire).put("x", x).put("y", y)
        .apply {
            if (button != 0) put("button", button)
            if (modifiers != 0) put("modifiers", modifiers)
            if (wheelY != 0) put("wheelY", wheelY)
        }

    /** The map viewed only, or edited. Answered only by an Error, when refused. */
    fun setMapViewOnly(viewOnly: Boolean): JSONObject = JSONObject().put("t", "setMapViewOnly").put("viewOnly", viewOnly)

    /**
     * The mapper window's [EngineMessage.Map] drawn from now on at [scale] device pixels to each of the window's (1 to
     * [MapFit.MAX_RENDER_SCALE]), its `w` and `h` still the window's. Answered only by an Error, when refused.
     */
    fun setMapScale(scale: Double): JSONObject = JSONObject().put("t", "setMapScale").put("scale", scale)

    /**
     * The app's own map, apart from any a script opened, drawn at [w] by [h] of its pixels with [scale] device pixels
     * to each (1 to 4) and sent as [EngineMessage.AppMap] whenever it changes; 0 by 0 stops it. Answered otherwise
     * only by an Error, when refused.
     */
    fun setAppMapSize(w: Int, h: Int, scale: Double): JSONObject = JSONObject().put("t", "setAppMapSize").put("w", w).put("h", h).put("scale", scale)

    /** As [mapPointer], at a point of the last [EngineMessage.AppMap]. */
    fun appMapPointer(type: MapPointerType, x: Double, y: Double, button: Int, modifiers: Int, wheelY: Int): JSONObject = mapPointer(type, x, y, button, modifiers, wheelY).put("t", "appMapPointer")

    /** Answered by [EngineMessage.Rooms] with [id] as its replyTo: the rooms whose name holds [text], and the room whose id it is. */
    fun findRooms(text: String, id: Long): JSONObject = JSONObject().put("t", "findRooms").put("text", text).put("id", id)

    /** The app's map centred on [room], which it selects, until the player moves. Answered only by an Error, when refused. */
    fun showRoom(room: Int): JSONObject = JSONObject().put("t", "showRoom").put("room", room)

    /**
     * What the last frame of [map] drew moved by [dx], [dy] of its pixels, then zoomed by [zoom] (above 0; 2 shows the rooms twice
     * as large) about its point [x], [y]; the frames drawn after it carry [seq], counted up from 1, as [MapViewInfo.moved]. Stops
     * following the player where it moves the player off the middle. Answered only by an Error, when refused.
     */
    fun moveMap(map: MapTarget, dx: Double, dy: Double, zoom: Double, x: Double, y: Double, seq: Int): JSONObject = JSONObject()
        .put("t", "moveMap").put("map", map.wire).put("dx", dx).put("dy", dy).put("zoom", zoom).put("x", x).put("y", y).put("seq", seq)

    /** [map] centred on the player's room, following the player again. Answered only by an Error, when refused. */
    fun followPlayer(map: MapTarget): JSONObject = JSONObject().put("t", "followPlayer").put("map", map.wire)

    /** [map] showing [level], one of [MapViewInfo.levels], of the area it drew, until the player moves. Answered only by an Error, when refused. */
    fun showMapLevel(map: MapTarget, level: Int): JSONObject = JSONObject().put("t", "showMapLevel").put("map", map.wire).put("level", level)

    /** [map] showing [area], following the player where they are in it. Answered only by an Error, when refused. */
    fun showMapArea(map: MapTarget, area: Int): JSONObject = JSONObject().put("t", "showMapArea").put("map", map.wire).put("area", area)

    /** Answered by [EngineMessage.Room] with [id] as its replyTo, or an Error: the room whose id is [room]. */
    fun roomInfo(room: Int, id: Long): JSONObject = JSONObject().put("t", "roomInfo").put("room", room).put("id", id)

    /** The player walked to [room], as a double tap on it does. Answered only by an Error, when refused. */
    fun walkToRoom(room: Int): JSONObject = JSONObject().put("t", "walkToRoom").put("room", room)

    /** Answered by [EngineMessage.Areas] with [id] as its replyTo, or an Error. */
    fun mapAreas(id: Long): JSONObject = JSONObject().put("t", "mapAreas").put("id", id)

    /** Answered by [EngineMessage.Room], the room as it now is, with [id] as its replyTo, or an Error: [edit] made to [room]. */
    fun editRoom(room: Int, edit: RoomEdit, id: Long): JSONObject = JSONObject().put("t", "editRoom").put("room", room).put("id", id).apply {
        edit.name?.let { put("name", it) }
        edit.symbol?.let { put("symbol", it) }
        edit.env?.let { put("env", it) }
        edit.weight?.let { put("weight", it) }
        edit.locked?.let { put("locked", it) }
        edit.place?.let { (x, y, z) -> put("x", x).put("y", y).put("z", z) }
    }

    /**
     * As [editRoom]: [room]'s exit [direction], one of the twelve as [RoomExit] names them, led to the room [to], or removed for 0;
     * with [bothWays] the exit back too.
     */
    fun setRoomExit(room: Int, direction: String, to: Int, bothWays: Boolean, id: Long): JSONObject = JSONObject()
        .put("t", "setRoomExit").put("room", room).put("direction", direction).put("to", to).put("bothWays", bothWays).put("id", id)

    /** As [editRoom]: [room]'s exit [direction] [locked], so the speedwalk avoids it, or not. */
    fun lockRoomExit(room: Int, direction: String, locked: Boolean, id: Long): JSONObject = JSONObject()
        .put("t", "lockRoomExit").put("room", room).put("direction", direction).put("locked", locked).put("id", id)

    /** Answered by [EngineMessage.Room], the new room, with [id] as its replyTo, or an Error: a room one place from [room] that way, with exits both ways. */
    fun addRoom(room: Int, direction: String, id: Long): JSONObject = JSONObject().put("t", "addRoom").put("room", room).put("direction", direction).put("id", id)

    /** [room] and its exits gone. Answered only by an Error, when refused. */
    fun deleteRoom(room: Int): JSONObject = JSONObject().put("t", "deleteRoom").put("room", room)

    /** The player placed in [room], as a script's centerview() does. Answered only by an Error, when refused. */
    fun setPlayerRoom(room: Int): JSONObject = JSONObject().put("t", "setPlayerRoom").put("room", room)

    /** [area] renamed [name]. Answered only by an Error, when refused. */
    fun renameArea(area: Int, name: String): JSONObject = JSONObject().put("t", "renameArea").put("area", area).put("name", name)

    /** Answered by [EngineMessage.Envs] with [id] as its replyTo, or an Error. */
    fun mapEnvs(id: Long): JSONObject = JSONObject().put("t", "mapEnvs").put("id", id)

    /** Downloads the map the game offers and loads it, as [EngineMessage.MapDownload] follows. Answered only by an Error, when refused. */
    fun downloadMap(): JSONObject = JSONObject().put("t", "downloadMap")

    /** Stops that download while it is cancelable. Answered only by an Error, when refused. */
    fun cancelMapDownload(): JSONObject = JSONObject().put("t", "cancelMapDownload")

    /** Answered by [EngineMessage.SettingValues] with [id] as its replyTo: the profile's settings named in [keys], getConfig()'s keys. */
    fun getSettings(keys: List<String>, id: Long): JSONObject {
        require(keys.isNotEmpty()) { "getSettings takes at least one key" }
        return JSONObject().put("t", "getSettings").put("keys", JSONArray(keys)).put("id", id)
    }

    /**
     * Sets each of [values], by getConfig()'s keys, as setConfig() does: each a Boolean, a number or a String. Answered by
     * [EngineMessage.SettingValues] with [id] as its replyTo.
     */
    fun setSettings(values: Map<String, Any>, id: Long): JSONObject {
        require(values.isNotEmpty()) { "setSettings takes at least one value" }
        require(values.values.all { it is Boolean || it is Number || it is String }) { "a setting is a Boolean, a number or a String" }
        return JSONObject().put("t", "setSettings").put("values", JSONObject(values)).put("id", id)
    }

    /** Answered by [EngineMessage.Image] with [id] as its replyTo: the file at [path], scaled to [w] by [h], given both, or its [frame]. */
    fun getImage(path: String, w: Int?, h: Int?, frame: Int?, id: Long): JSONObject {
        require((w == null) == (h == null)) { "getImage takes w and h together" }
        return JSONObject().put("t", "getImage").put("path", path).put("id", id)
            .apply {
                if (w != null && h != null) put("w", w).put("h", h)
                if (frame != null) put("frame", frame)
            }
    }
}

/**
 * Writes each frame with one write() to [out], which must be unbuffered, as a socket's stream is: it never flushes,
 * because a LocalSocket's flush() waits until the other end has read everything (seen on Android 9), so a UI's send
 * from its main thread would wait for a busy engine.
 */
class FrameWriter(private val out: OutputStream) {
    @Synchronized
    fun write(message: JSONObject) {
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        val n = payload.size
        val frame = ByteArray(4 + n)
        frame[0] = (n ushr 24).toByte()
        frame[1] = (n ushr 16).toByte()
        frame[2] = (n ushr 8).toByte()
        frame[3] = n.toByte()
        System.arraycopy(payload, 0, frame, 4, n)
        out.write(frame)
    }
}

class FrameReader(input: InputStream) {
    private val data = DataInputStream(input.buffered(64 * 1024))

    /** Blocks for the next frame; throws EOFException when the engine goes away. */
    fun read(): EngineMessage {
        val n = data.readInt()
        if (n < 0 || n > MAX_FRAME_BYTES) throw EOFException("bad frame length $n")
        val payload = ByteArray(n)
        data.readFully(payload)
        return parse(JSONObject(String(payload, Charsets.UTF_8)))
    }

    companion object {
        fun parse(o: JSONObject): EngineMessage = when (val t = o.optString("t")) {
            "hello" -> EngineMessage.Hello(o.getInt("v"), o.optInt("pid"), o.optString("instance"), o.optString("profile").ifEmpty { null })

            "line" -> EngineMessage.Line(
                o.getLong("seq"),
                o.getString("text"),
                flattenRuns(o.optJSONArray("runs")),
                o.optBoolean("prompt"),
                o.optBoolean("replay"),
            )

            "tail" -> EngineMessage.Tail(o.optString("text"), flattenRuns(o.optJSONArray("runs")), o.optBoolean("prompt"))

            "clear" -> EngineMessage.Clear

            "notify" -> EngineMessage.Notify(o.optString("title"), o.optString("text"))

            "status" -> EngineMessage.Status(o.getString("key"), o.optString("value"), o.opt("re"))

            "pong" -> EngineMessage.Pong(o.optLong("id"))

            "synced" -> EngineMessage.Synced(o.optLong("seq"))

            "gap" -> EngineMessage.Gap(o.optLong("skipped"))

            "error" -> EngineMessage.Error(o.optString("message"), o.opt("re"))

            "profiles" -> EngineMessage.Profiles(parseProfiles(o.optJSONArray("profiles")), o.opt("re"))

            "openProfiles" -> EngineMessage.OpenProfiles(objects(o.optJSONArray("profiles")).map(::parseOpenProfile), o.opt("re"))

            "hiddenInput" -> EngineMessage.HiddenInput(o.optBoolean("wanted"))

            "history" -> EngineMessage.History(parseStrings(o.optJSONArray("lines")))

            "commandLine" -> EngineMessage.CommandLine(o.optString("text"))

            "announce" -> EngineMessage.Announce(o.optString("text"), o.optBoolean("assertive"))

            "link" -> EngineMessage.Link(
                o.getInt("link"),
                if (o.has("window")) o.optString("window") else null,
                o.optString("tooltip"),
                o.optBoolean("menu"),
                o.optString("menuTitle"),
                parseLinkItems(o.optJSONArray("items")),
                o.optBoolean("spoiler"),
                o.optBoolean("revealed"),
                o.optBoolean("disabled"),
                o.optBoolean("concealed"),
                o.optBoolean("visited"),
                o.optBoolean("selected"),
                optInt(o, "fg"),
                optInt(o, "bg"),
                optInt(o, "styles"),
                o.opt("re"),
            )

            "linksChanged" -> EngineMessage.LinksChanged(o.optJSONArray("ids")?.let { ids -> List(ids.length()) { ids.getInt(it) } } ?: emptyList())

            "attention" -> EngineMessage.Attention(o.optInt("ms"))

            "moveTypedLine" -> EngineMessage.MoveTypedLine

            "windows" -> EngineMessage.Windows(
                o.optBoolean("full"),
                objects(o.optJSONArray("windows")).mapNotNull(::parseWindow),
                objects(o.optJSONArray("removed")).mapNotNull { r -> WindowKind.fromWire(r.optString("kind"))?.let { WindowKey(r.optString("name"), it) } },
            )

            "windowText" -> EngineMessage.WindowText(
                o.optString("window"),
                o.optInt("lineCount"),
                o.optInt("first"),
                objects(o.optJSONArray("lines")).map { WindowRow(it.optString("text"), flattenRuns(it.optJSONArray("runs")), it.optBoolean("prompt"), it.optBoolean("wrapped")) },
                o.opt("re"),
            )

            "mainArea" -> EngineMessage.MainArea(
                o.optInt("w"),
                o.optInt("h"),
                o.optInt("columns"),
                o.optInt("rows"),
                o.optInt("textX"),
                o.optInt("textY"),
                o.optInt("textW"),
                o.optInt("textH"),
            )

            "windowCommandLine" -> EngineMessage.WindowCommandLine(o.optString("name"), o.optString("text"))

            "label" -> EngineMessage.Label(parseLabel(o))

            "textBox" -> EngineMessage.TextBox(
                TextBoxContent(
                    o.optString("name"),
                    o.optString("text"),
                    o.optBoolean("readOnly"),
                    o.optString("placeholder"),
                    o.optString("styleSheet"),
                    o.optBoolean("tabMovesFocus"),
                ),
                o.optInt("typed"),
            )

            "image" -> EngineMessage.Image(o.optString("path"), decodeBase64(o.optString("png")), o.optInt("w"), o.optInt("h"), o.opt("re"))

            "buttonBars" -> EngineMessage.ButtonBars(objects(o.optJSONArray("bars")).mapNotNull(::parseButtonBar))

            "commands" -> EngineMessage.Commands(objects(o.optJSONArray("commands")).map(::parseCommand))

            "map" -> EngineMessage.Map(decodeBase64(o.optString("png")), o.optInt("w"), o.optInt("h"), o.optBoolean("viewOnly", true), o.optJSONObject("view")?.let(::parseMapView))

            "appMap" -> EngineMessage.AppMap(decodeBase64(o.optString("png")), o.optInt("w"), o.optInt("h"), o.optBoolean("viewOnly", true), o.optJSONObject("view")?.let(::parseMapView))

            "rooms" -> EngineMessage.Rooms(
                o.optString("text"),
                objects(o.optJSONArray("rooms")).map {
                    RoomMatch(it.optInt("id"), it.optString("name"), it.optString("area"), it.optInt("areaId"), RoomMatchKind.fromWire(it.optString("match")), it.optInt("steps", -1))
                },
                o.optBoolean("more"),
                o.opt("re"),
            )

            "settings" -> EngineMessage.SettingValues(
                o.optJSONObject("values")?.let { values ->
                    values.keys().asSequence().mapNotNull { key -> settingValue(values.opt(key))?.let { key to it } }.toMap()
                }.orEmpty(),
                o.optJSONObject("refused")?.let { refused -> refused.keys().asSequence().associateWith { refused.optString(it) } }.orEmpty(),
                o.opt("re"),
            )

            "room" -> EngineMessage.Room(
                RoomInfo(
                    o.optInt("id"),
                    o.optString("name"),
                    o.optInt("areaId"),
                    o.optString("area"),
                    o.optInt("x"),
                    o.optInt("y"),
                    o.optInt("z"),
                    o.optInt("steps", -1),
                    objects(o.optJSONArray("exits")).map { RoomExit(it.optString("direction"), it.optInt("to"), it.optString("toName"), it.optBoolean("locked")) },
                    o.optString("symbol"),
                    o.optInt("env", -1),
                    rgbColor(o.optString("color")),
                    o.optInt("weight", 1),
                    o.optBoolean("locked"),
                ),
                o.opt("re"),
            )

            "areas" -> EngineMessage.Areas(objects(o.optJSONArray("areas")).map { MapArea(it.optInt("id"), it.optString("name")) }, o.opt("re"))

            "envs" -> EngineMessage.Envs(
                objects(o.optJSONArray("envs")).mapNotNull { env -> rgbColor(env.optString("color"))?.let { MapEnv(env.optInt("env"), it) } },
                o.opt("re"),
            )

            "mapDownload" -> EngineMessage.MapDownload(
                o.optBoolean("offered"),
                o.optString("host"),
                MapDownloadState.fromWire(o.optString("state")),
                o.optString("label"),
                o.optInt("value"),
                o.optInt("maximum"),
                o.optBoolean("cancelable"),
                o.optString("error"),
            )

            else -> EngineMessage.Unknown(t)
        }

        private fun parseProfiles(profiles: JSONArray?): List<Profile> {
            if (profiles == null) return emptyList()
            return List(profiles.length()) { i ->
                val p = profiles.getJSONObject(i)
                Profile(p.getString("name"), p.optString("host"), p.optInt("port", 23), p.optBoolean("tls"), p.optString("login"), p.optBoolean("autoReconnect"))
            }
        }

        private fun parseOpenProfile(p: JSONObject) = OpenProfile(
            p.optString("name"),
            p.optInt("slot"),
            p.optString("socket"),
            OpenProfileState.fromWire(p.optString("state")),
            p.optString("connection"),
            p.optLong("lastSeq"),
            p.optLong("unread"),
            p.optBoolean("attention"),
            p.optBoolean("front"),
        )

        private fun parseLinkItems(items: JSONArray?): List<LinkItem> {
            if (items == null) return emptyList()
            return List(items.length()) { i ->
                val item = items.getJSONObject(i)
                LinkItem(item.optString("label"), item.optBoolean("enabled", true), item.optBoolean("separator"))
            }
        }

        private fun optInt(o: JSONObject, key: String): Int? = if (o.has(key)) o.getInt(key) else null

        // A whole number as a Double too, so a setting reads the same however the engine wrote it
        private fun settingValue(value: Any?): Any? = when (value) {
            is Boolean, is String -> value
            is Number -> value.toDouble()
            else -> null
        }

        private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else List(array.length()) { array.optJSONObject(it) }.filterNotNull()

        // Null for a window of a kind this app does not know
        private fun parseWindow(w: JSONObject): ScriptWindow? {
            val kind = WindowKind.fromWire(w.optString("kind")) ?: return null
            return ScriptWindow(
                name = w.optString("name"),
                kind = kind,
                parent = w.optString("parent"),
                parentKind = WindowKind.fromWire(w.optString("parentKind")),
                x = w.optInt("x"),
                y = w.optInt("y"),
                w = w.optInt("w"),
                h = w.optInt("h"),
                visible = w.optBoolean("visible"),
                shown = w.optBoolean("shown"),
                stack = w.optInt("stack"),
                bg = w.optInt("bg", -1),
                bgAlpha = w.optInt("bgAlpha", 255),
                fontFamily = w.optString("fontFamily").ifEmpty { null },
                fontSize = w.optDouble("fontSize", 0.0),
                inDock = w.optBoolean("inDock"),
                dock = if (w.has("dockArea")) {
                    ScriptWindow.Dock(w.optBoolean("autoDock", true), w.optString("dockArea"), w.optBoolean("dockFloating"), w.optString("dockTitle"))
                } else {
                    null
                },
                console = if (w.has("columns")) {
                    val scrolledBackTo = if (w.has("scrolledBackTo")) w.optInt("scrolledBackTo") else null
                    ScriptWindow.Console(scrolledBackTo, w.optInt("columns"), w.optInt("rows"), w.optBoolean("scrollBar"), w.optBoolean("timeStamps"))
                } else {
                    null
                },
                frameTabs = parseFrameTabs(w),
            )
        }

        // Null for none, or for tabs that cannot be read; a missing title is the page's name, as Mudlet's tab shows it
        private fun parseFrameTabs(w: JSONObject): ScriptWindow.FrameTabs? {
            val names = w.optJSONArray("tabs") ?: return null
            val titles = w.optJSONArray("tabTitles")
            val pages = List(names.length()) { i ->
                val name = names.optString(i)
                ScriptWindow.FrameTabs.Page(name, titles?.optString(i).orEmpty().ifEmpty { name })
            }
            val current = w.optInt("tabCurrent", -1)
            return if (pages.isNotEmpty() && current in pages.indices) ScriptWindow.FrameTabs(pages, current) else null
        }

        private fun parseLabel(l: JSONObject) = LabelContent(
            name = l.optString("name"),
            text = l.optString("text"),
            image = l.optString("image"),
            svg = l.optBoolean("svg"),
            svgTint = l.optInt("svgTint", -1),
            svgTintAlpha = l.optInt("svgTintAlpha", 255),
            svgRotation = l.optDouble("svgRotation", 0.0),
            svgShearX = l.optDouble("svgShearX", 0.0),
            svgShearY = l.optDouble("svgShearY", 0.0),
            movie = l.optString("movie"),
            moviePaused = l.optBoolean("moviePaused"),
            movieFrame = l.optInt("movieFrame", -1),
            movieSpeed = l.optInt("movieSpeed", 100),
            movieW = if (l.has("movieW")) l.optInt("movieW") else null,
            movieH = if (l.has("movieH")) l.optInt("movieH") else null,
            toolTip = l.optString("toolTip"),
            clickThrough = l.optBoolean("clickThrough"),
            accessibleName = l.optString("accessibleName"),
            accessibleButton = l.optBoolean("accessibleButton"),
            liveRegion = LiveRegion.fromWire(l.optString("liveRegion")),
            accessibleValue = parseAccessibleValue(l),
            styleSheet = l.optString("styleSheet"),
        )

        // None without a value, or for one with a field that is not a finite number (a string is not, though org.json
        // would read a numeric one) or with an empty range, or one too wide for a double, whose percentage could not
        // be worked out; a value past either end is that end
        private fun parseAccessibleValue(l: JSONObject): AccessibleValue? {
            fun number(key: String, absent: Double) = when (val field = l.opt(key)) {
                null -> absent
                is Number -> field.toDouble()
                else -> Double.NaN
            }
            val value = number("value", Double.NaN)
            val min = number("valueMin", 0.0)
            val max = number("valueMax", 100.0)
            if (!value.isFinite() || !min.isFinite() || !max.isFinite() || min >= max || !(max - min).isFinite()) return null
            return AccessibleValue(value.coerceIn(min, max), min, max)
        }

        // Null for a bar in a place this app does not know
        private fun parseButtonBar(b: JSONObject): ButtonBar? {
            val place = BarPlace.fromWire(b.optString("place")) ?: return null
            return ButtonBar(
                id = b.optInt("id"),
                name = b.optString("name"),
                styleSheet = b.optString("styleSheet"),
                place = place,
                vertical = b.optBoolean("vertical"),
                columns = b.optInt("columns", 1),
                fillerOffset = b.optInt("fillerOffset"),
                buttons = objects(b.optJSONArray("buttons")).map(::parseButton),
            )
        }

        private fun parseButton(b: JSONObject): BarButton = BarButton(
            id = b.optInt("id"),
            name = b.optString("name"),
            icon = b.optString("icon"),
            styleSheet = b.optString("styleSheet"),
            pushDown = b.optBoolean("pushDown"),
            checked = b.optBoolean("checked"),
            menu = b.optBoolean("menu"),
            children = objects(b.optJSONArray("children")).map(::parseButton),
        )

        private fun parseMapView(v: JSONObject) = MapViewInfo(
            v.optInt("area"),
            v.optString("areaName"),
            v.optInt("level"),
            v.optDouble("zoom", 0.0),
            v.optDouble("minZoom", 0.0),
            v.optDouble("maxZoom", 0.0),
            v.optBoolean("following", true),
            v.optInt("player"),
            v.optDouble("roomSize", 0.0),
            v.optInt("moved"),
            objects(v.optJSONArray("rooms")).map { MapRoomPlace(it.optInt("id"), it.optDouble("x", 0.0), it.optDouble("y", 0.0), it.optString("name")) },
            v.optJSONArray("levels")?.let { levels -> List(levels.length()) { levels.optInt(it) } }.orEmpty(),
        )

        private fun parseCommand(c: JSONObject) = AddonCommand(
            id = c.optInt("id"),
            packageName = c.optString("package"),
            name = c.optString("name"),
            icon = c.optString("icon"),
            tooltip = c.optString("tooltip"),
            menuPath = c.optString("menuPath"),
            shortcut = c.optString("shortcut"),
            onMenu = c.optBoolean("onMenu"),
            onToolbar = c.optBoolean("onToolbar"),
            enabled = c.optBoolean("enabled", true),
            checkable = c.optBoolean("checkable"),
            checked = c.optBoolean("checked"),
            pinned = c.optBoolean("pinned"),
            pulse = c.optBoolean("pulse"),
        )

        // Empty for what is not base64, as an engine's bad frame should not end the link
        private fun decodeBase64(text: String): ByteArray = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }

        private fun parseStrings(strings: JSONArray?): List<String> = if (strings == null) emptyList() else List(strings.length()) { strings.optString(it) }

        // [[start, length, fg(, bg, attributes(, link))], ...] -> [start0, length0, fg0, bg0, attributes0, link0, start1, ...]
        private fun flattenRuns(runs: JSONArray?): IntArray {
            if (runs == null) return IntArray(0)
            val flat = IntArray(runs.length() * RUN_STRIDE)
            for (i in 0 until runs.length()) {
                val run = runs.getJSONArray(i)
                val at = i * RUN_STRIDE
                flat[at] = run.getInt(0)
                flat[at + 1] = run.getInt(1)
                flat[at + 2] = run.getInt(2)
                flat[at + 3] = run.optInt(3, -1)
                flat[at + 4] = run.optInt(4, 0)
                flat[at + 5] = run.optInt(5, 0)
            }
            return flat
        }
    }
}
