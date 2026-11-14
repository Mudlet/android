package org.mudlet.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// Wire format shared with native/enginehost/SocketBridge.cpp, specified in docs/protocol.md: a 4-byte
// big-endian length, then one UTF-8 JSON object whose "t" field is the message type.
const val PROTOCOL_VERSION = 4
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

    /** What a script asks screen readers to say; [assertive] interrupts what they are saying. */
    data class Announce(val text: String, val assertive: Boolean) : EngineMessage
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

    /** The console's size in characters, which a game that asks is told (NAWS); each from 1 to 65535. */
    fun setScreenSize(columns: Int, rows: Int): JSONObject = JSONObject().put("t", "setScreenSize").put("columns", columns).put("rows", rows)
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

            else -> EngineMessage.Unknown(t)
        }

        private fun parseProfiles(profiles: JSONArray?): List<Profile> {
            if (profiles == null) return emptyList()
            return List(profiles.length()) { i ->
                val p = profiles.getJSONObject(i)
                Profile(p.getString("name"), p.optString("host"), p.optInt("port", 23), p.optBoolean("tls"), p.optString("login"), p.optBoolean("autoReconnect"))
            }
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
