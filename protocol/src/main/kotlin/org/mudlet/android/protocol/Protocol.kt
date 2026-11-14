package org.mudlet.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// Wire format shared with native/enginehost/SocketBridge.cpp, specified in docs/protocol.md: a 4-byte
// big-endian length, then one UTF-8 JSON object whose "t" field is the message type.
const val PROTOCOL_VERSION = 7
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

/** A profile as the engine lists it; the password is never part of one. */
data class Profile(val name: String, val host: String, val port: Int, val tls: Boolean, val login: String, val autoReconnect: Boolean)

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
    val fontFamily: String?,
    /** In points; 0 for none. */
    val fontSize: Double,
    val inDock: Boolean,
    /** A user window's. */
    val dock: Dock?,
    /** A mini console's, user window's or MXP frame's. */
    val console: Console?,
) {
    val key get() = WindowKey(name, kind)

    /** [area] is left, right, top or bottom. */
    data class Dock(val autoDock: Boolean, val area: String, val floating: Boolean, val title: String)

    /** [scrolledBackTo] is the row it is scrolled back to, null while it follows new lines. */
    data class Console(val scrolledBackTo: Int?, val columns: Int, val rows: Int, val scrollBar: Boolean, val timeStamps: Boolean)
}

/** A row of a script window's console: [runs] as [EngineMessage.Line]'s, but a bg of -1 is the window's bg. */
data class WindowRow(val text: String, val runs: IntArray, val prompt: Boolean, val wrapped: Boolean)

sealed interface EngineMessage {
    data class Hello(val version: Int, val pid: Int, val instance: String) : EngineMessage
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

    /** The game hides what the player types, as for a password ([wanted]), or has stopped. */
    data class HiddenInput(val wanted: Boolean) : EngineMessage

    /** The input line's history, newest first. */
    data class History(val lines: List<String>) : EngineMessage

    /** A script set the whole input line to [text]. */
    data class CommandLine(val text: String) : EngineMessage

    /** The script windows: with [full], all of them; otherwise those that changed, and the keys of those [removed]. */
    data class Windows(val full: Boolean, val windows: List<ScriptWindow>, val removed: List<WindowKey>) : EngineMessage

    /** Rows of a script window's console from row [first] of its [lineCount]; [replyTo] is the getWindowText id. */
    data class WindowText(val window: String, val lineCount: Int, val first: Int, val lines: List<WindowRow>, val replyTo: Any?) : EngineMessage

    /** The main window's size the UI gave, the main console's grid, and where its text goes, in Mudlet's pixels. */
    data class MainArea(val w: Int, val h: Int, val columns: Int, val rows: Int, val textX: Int, val textY: Int, val textW: Int, val textH: Int) : EngineMessage

    /** A script set the command line [name] of a script window to [text]. */
    data class WindowCommandLine(val name: String, val text: String) : EngineMessage
    data class Unknown(val type: String) : EngineMessage
}

object Requests {
    fun hello(instance: String?, lastSeq: Long): JSONObject = JSONObject()
        .put("t", "hello").put("v", PROTOCOL_VERSION).put("client", "android-compose")
        .apply { if (instance != null) put("instance", instance).put("lastSeq", lastSeq) }

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
    fun setViewWidthPx(px: Int): JSONObject = JSONObject().put("t", "setViewWidthPx").put("px", px)
    fun ping(id: Long): JSONObject = JSONObject().put("t", "ping").put("id", id)

    // The profile requests: [id] comes back as the replyTo of the Profiles or Error that answers each
    fun listProfiles(id: Long): JSONObject = JSONObject().put("t", "listProfiles").put("id", id)
    fun saveProfile(profile: Profile, id: Long): JSONObject = JSONObject().put("t", "saveProfile").put("name", profile.name)
        .put("host", profile.host).put("port", profile.port).put("tls", profile.tls).put("login", profile.login)
        .put("autoReconnect", profile.autoReconnect).put("id", id)

    fun deleteProfile(name: String, id: Long): JSONObject = JSONObject().put("t", "deleteProfile").put("name", name).put("id", id)

    /** [password] is for this run of the profile only; null sends none. */
    fun openProfile(name: String, password: String?, connect: Boolean, id: Long): JSONObject = JSONObject()
        .put("t", "openProfile").put("name", name).put("connect", connect).put("id", id)
        .apply { if (password != null) put("password", password) }

    // The script windows'. A refusal is an Error naming the request.
    fun setMainWindowSize(w: Int, h: Int): JSONObject = JSONObject().put("t", "setMainWindowSize").put("w", w).put("h", h)

    /** Answered by [EngineMessage.WindowText] with [id] as its replyTo; a negative [first] counts back from the end. */
    fun getWindowText(window: String, first: Int, count: Int, id: Long): JSONObject = JSONObject()
        .put("t", "getWindowText").put("window", window).put("first", first).put("count", count).put("id", id)

    fun placeUserWindow(name: String, x: Int, y: Int, w: Int, h: Int, floating: Boolean): JSONObject = JSONObject()
        .put("t", "placeUserWindow").put("name", name).put("x", x).put("y", y).put("w", w).put("h", h).put("floating", floating)

    fun closeUserWindow(name: String): JSONObject = JSONObject().put("t", "closeUserWindow").put("name", name)
    fun sendWindowCommandLine(name: String, text: String): JSONObject = JSONObject().put("t", "sendWindowCommandLine").put("name", name).put("text", text)
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
            "hello" -> EngineMessage.Hello(o.getInt("v"), o.optInt("pid"), o.optString("instance"))

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

            "hiddenInput" -> EngineMessage.HiddenInput(o.optBoolean("wanted"))

            "history" -> EngineMessage.History(parseStrings(o.optJSONArray("lines")))

            "commandLine" -> EngineMessage.CommandLine(o.optString("text"))

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

            else -> EngineMessage.Unknown(t)
        }

        private fun parseProfiles(profiles: JSONArray?): List<Profile> {
            if (profiles == null) return emptyList()
            return List(profiles.length()) { i ->
                val p = profiles.getJSONObject(i)
                Profile(p.getString("name"), p.optString("host"), p.optInt("port", 23), p.optBoolean("tls"), p.optString("login"), p.optBoolean("autoReconnect"))
            }
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
            )
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
