package org.mudlet.android.ui

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.protocol.FrameWriter
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.Transport
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An engine for the UI to attach to, in the test's own process: it answers EngineClient's hello as the engine would
 * (docs/protocol.md, "Attaching") and then sends what a test tells it to. EngineClient reaches it through
 * [EngineClient.connector], over a local socket as it reaches the engine, but in the abstract namespace.
 */
class FakeEngine : AutoCloseable {
    private val name = "mudlet-fake-engine-${UUID.randomUUID()}"
    private val server = LocalServerSocket(name)
    private val sessions = LinkedBlockingQueue<Session>()

    @Volatile private var closed = false

    /** The instance the next session says hello with: a new one is a new engine, as after the old one was killed. */
    @Volatile var instance = FIRST_INSTANCE

    init {
        thread(name = "fake-engine", isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (closed) {
                    socket.close()
                    break
                }
                try {
                    sessions.add(Session(socket, instance))
                } catch (_: IOException) {
                    // The UI gave up on this connection before its hello
                    socket.close()
                }
            }
        }
    }

    /** For [EngineClient.connector]. */
    fun connect(): Transport {
        val socket = localSocket()
        return object : Transport {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() = socket.close()
        }
    }

    /** The next UI that attached, once it has said hello and been answered. */
    fun nextSession(timeoutMs: Long = 10_000): Session = sessions.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: throw AssertionError("the UI did not attach to the fake engine within $timeoutMs ms")

    override fun close() {
        closed = true
        // Closing a LocalServerSocket does not wake its accept(): a last connection does
        try {
            localSocket().close()
        } catch (_: IOException) {
        }
        server.close()
        while (true) sessions.poll()?.close() ?: break
    }

    private fun localSocket(): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    class Session(private val socket: LocalSocket, instance: String) : AutoCloseable {
        private val writer = FrameWriter(socket.outputStream)
        private val input = DataInputStream(socket.inputStream)

        /** Every request [receive] has read, the skipped ones included. */
        val received = mutableListOf<JSONObject>()

        /** The UI's hello: "instance" and "lastSeq" when it was attached to an engine before. */
        val hello: JSONObject

        init {
            socket.soTimeout = READ_TIMEOUT_MS
            hello = readFrame(input)
            if (hello.optString("t") != "hello") throw IOException("the UI began with $hello")
            send(JSONObject().put("t", "hello").put("v", 1).put("pid", FAKE_PID).put("instance", instance))
            // As the engine follows its hello, so state an earlier test left in EngineClient goes
            hiddenInput(false)
            history(emptyList())
        }

        fun send(message: JSONObject) = writer.write(message)

        /** The next request of type [type] the UI sent, skipping the others; fails after [timeoutMs]. */
        fun receive(type: String, timeoutMs: Long = 10_000): JSONObject {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // A read times out after soTimeout, or fails when the UI has closed the connection
                val frame = try {
                    readFrame(input)
                } catch (e: IOException) {
                    throw AssertionError("the UI sent no '$type': $e", e)
                }
                received.add(frame)
                if (frame.optString("t") == type) return frame
            }
            throw AssertionError("the UI sent no '$type' within $timeoutMs ms")
        }

        /** Fails if the UI sends a request of type [type] within [ms]; the others it skips. */
        fun assertNoRequest(type: String, ms: Int) {
            val deadline = System.currentTimeMillis() + ms
            try {
                while (true) {
                    socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val frame = readFrame(input)
                    if (frame.optString("t") == type) throw AssertionError("the UI sent $frame")
                }
            } catch (_: IOException) {
                // A LocalSocket's read timeout is a plain IOException
            } finally {
                socket.soTimeout = 10_000
            }
        }

        /** The answer to a profile request, as the engine gives it: every profile, and the request's id as "re". */
        fun profiles(profiles: List<Profile>, request: JSONObject? = null) = send(
            JSONObject().put("t", "profiles").put("re", request?.opt("id"))
                .put("profiles", JSONArray(profiles.map { it.toJson() })),
        )

        /** The engine refusing [request]. */
        fun refuse(request: JSONObject, why: String) = send(
            JSONObject().put("t", "error").put("re", request.opt("id")).put("message", "${request.optString("t")}: $why"),
        )

        /**
         * The last request of type [type] the UI sent before it went quiet for [quietMs]: what it settled on, where it
         * may first have sent what it held from before, as ScriptWindowsClient sends a new engine the last size. With
         * [readPast], one already read past counts: ScriptWindowsClient sends no size twice, and connecting to a
         * profile reads past the one it sends as it attaches.
         */
        fun receiveLast(type: String, quietMs: Int = 1_000, readPast: Boolean = false): JSONObject {
            var last = (if (readPast) received.lastOrNull { it.optString("t") == type } else null) ?: receive(type)
            socket.soTimeout = quietMs
            try {
                while (true) {
                    val frame = try {
                        readFrame(input)
                    } catch (_: IOException) {
                        break
                    }
                    received.add(frame)
                    if (frame.optString("t") == type) last = frame
                }
            } finally {
                socket.soTimeout = READ_TIMEOUT_MS
            }
            return last
        }

        fun clear() = send(JSONObject().put("t", "clear"))

        /**
         * [runs]: each [start, length, fg, bg, attributes], as docs/protocol.md's "Runs" says. [replay]: one of the
         * lines an engine sends again to a UI that attached.
         */
        fun line(seq: Long, text: String, runs: List<List<Int>> = emptyList(), prompt: Boolean = false, replay: Boolean = false) = send(
            JSONObject().put("t", "line").put("seq", seq).put("text", text).put("prompt", prompt).put("replay", replay)
                .put("runs", JSONArray(runs.map { JSONArray(it) })),
        )

        fun tail(text: String, prompt: Boolean = true) = send(JSONObject().put("t", "tail").put("text", text).put("prompt", prompt))

        fun status(key: String, value: String) = send(JSONObject().put("t", "status").put("key", key).put("value", value))

        /** A reply to the request whose "id" was [re]. */
        fun reply(key: String, value: String, re: Any) = send(JSONObject().put("t", "status").put("key", key).put("value", value).put("re", re))

        fun hiddenInput(wanted: Boolean) = send(JSONObject().put("t", "hiddenInput").put("wanted", wanted))

        /** [lines]: newest first. */
        fun history(lines: List<String>) = send(JSONObject().put("t", "history").put("lines", JSONArray(lines)))

        fun commandLine(text: String) = send(JSONObject().put("t", "commandLine").put("text", text))

        fun announce(text: String, assertive: Boolean) = send(JSONObject().put("t", "announce").put("text", text).put("assertive", assertive))

        fun synced(seq: Long) = send(JSONObject().put("t", "synced").put("seq", seq))

        /** [windows] as [window] makes them; [removed]: each the name and the kind of a window that went. */
        fun windows(windows: List<JSONObject>, full: Boolean = false, removed: List<Pair<String, String>> = emptyList()) = send(
            JSONObject().put("t", "windows").put("full", full).put("windows", JSONArray(windows))
                .put("removed", JSONArray(removed.map { (name, kind) -> JSONObject().put("name", name).put("kind", kind) })),
        )

        /** The last rows of the console [window], [rows] of them up to its last row, without colours. */
        fun windowText(window: String, rows: List<String>, lineCount: Int = rows.size) = send(
            JSONObject().put("t", "windowText").put("window", window).put("lineCount", lineCount).put("first", lineCount - rows.size)
                .put("lines", JSONArray(rows.map { JSONObject().put("text", it).put("runs", JSONArray()).put("prompt", false).put("wrapped", false) })),
        )

        fun mainArea(w: Int, h: Int, columns: Int, rows: Int, textX: Int, textY: Int, textW: Int, textH: Int) = send(
            JSONObject().put("t", "mainArea").put("w", w).put("h", h).put("columns", columns).put("rows", rows)
                .put("textX", textX).put("textY", textY).put("textW", textW).put("textH", textH),
        )

        fun windowCommandLine(name: String, text: String) = send(JSONObject().put("t", "windowCommandLine").put("name", name).put("text", text))

        /** What the label [name] shows (docs/protocol.md, `label`): [text] as rich text, or the file [image]; [value] from [valueMin] to [valueMax]. */
        fun label(
            name: String,
            text: String = "",
            image: String = "",
            toolTip: String = "",
            accessibleName: String = "",
            accessibleButton: Boolean = false,
            styleSheet: String = "",
            liveRegion: String? = null,
            value: Double? = null,
            valueMin: Double? = null,
            valueMax: Double? = null,
        ) = send(
            JSONObject().put("t", "label").put("name", name).put("text", text).put("image", image).put("svg", false)
                .put("movie", "").put("moviePaused", false).put("movieFrame", -1).put("movieSpeed", 100).put("toolTip", toolTip)
                .put("clickThrough", false).put("accessibleName", accessibleName).put("accessibleButton", accessibleButton)
                .put("styleSheet", styleSheet).putOpt("liveRegion", liveRegion)
                .putOpt("value", value).putOpt("valueMin", valueMin).putOpt("valueMax", valueMax),
        )

        /**
         * What the text box [name] holds (docs/protocol.md, `textBox`), [typed] after as many `setTextBoxText` for it, by
         * default those [received] has, as the engine counts those it read.
         */
        fun textBox(
            name: String,
            text: String = "",
            readOnly: Boolean = false,
            placeholder: String = "",
            styleSheet: String = "",
            tabMovesFocus: Boolean = false,
            typed: Int = received.count { it.optString("t") == "setTextBoxText" && it.optString("name") == name },
        ) = send(
            JSONObject().put("t", "textBox").put("name", name).put("text", text).put("readOnly", readOnly).put("placeholder", placeholder)
                .put("styleSheet", styleSheet).put("tabMovesFocus", tabMovesFocus).put("typed", typed),
        )

        /** The answer to the `getImage` whose id was [re]: [png], a PNG of [w] by [h] pixels. */
        fun image(path: String, png: ByteArray, w: Int, h: Int, re: Any) = send(
            JSONObject().put("t", "image").put("path", path).put("png", Base64.getEncoder().encodeToString(png)).put("w", w).put("h", h).put("re", re),
        )

        /** As the engine process dying: the UI sees the connection end. */
        override fun close() {
            // shutdownInput wakes the UI's reader at once, where close alone may leave it blocked
            try {
                socket.shutdownInput()
                socket.shutdownOutput()
            } catch (_: IOException) {
            }
            socket.close()
        }
    }

    companion object {
        const val FAKE_PID = 4242
        const val FIRST_INSTANCE = "fake-1"

        private fun Profile.toJson() = JSONObject()
            .put("name", name)
            .put("host", host)
            .put("port", port)
            .put("tls", tls)
            .put("login", login)
            .put("autoReconnect", autoReconnect)

        // How long a read waits for the UI
        const val READ_TIMEOUT_MS = 10_000

        /**
         * A shown window of a `windows` message (docs/protocol.md): a mini console, unless told, in the main window at
         * [x], [y] in Mudlet's pixels. A console has [columns] and [rows]; [dockArea] puts a user window in a dock.
         */
        fun window(
            name: String,
            kind: String = "miniConsole",
            x: Int = 0,
            y: Int = 0,
            w: Int = 100,
            h: Int = 100,
            columns: Int = 40,
            rows: Int = 10,
            parent: String = "",
            parentKind: String = "",
            dockArea: String? = null,
        ): JSONObject {
            val window = JSONObject().put("name", name).put("kind", kind).put("parent", parent).put("parentKind", parentKind)
                .put("x", x).put("y", y).put("w", w).put("h", h).put("visible", true).put("shown", true).put("stack", 0)
            if (kind == "miniConsole" || kind == "userWindow") window.put("columns", columns).put("rows", rows)
            if (dockArea != null) window.put("inDock", true).put("dockArea", dockArea).put("dockFloating", false).put("dockTitle", name)
            return window
        }

        private fun readFrame(input: DataInputStream): JSONObject {
            val n = input.readInt()
            val payload = ByteArray(n)
            input.readFully(payload)
            return JSONObject(String(payload, Charsets.UTF_8))
        }
    }
}
