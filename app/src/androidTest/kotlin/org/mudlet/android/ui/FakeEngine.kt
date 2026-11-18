package org.mudlet.android.ui

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.protocol.FrameWriter
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.protocol.Transport
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An engine for the UI to attach to, in the test's own process: it serves the control socket and a socket for each
 * profile it has open (docs/protocol.md, "Several profiles"), answers each hello as the engine would ("Attaching"),
 * and then sends what a test tells it to. EngineClient reaches it through [EngineClient.connector], over local sockets
 * as it reaches the engine, but in the abstract namespace. The profiles in [open] are open from the start, the last in
 * front; an `openProfile` that a test answers with [Session.profiles] opens another.
 */
class FakeEngine(open: List<String> = emptyList()) : AutoCloseable {
    private val base = "$SOCKET_PREFIX${UUID.randomUUID()}"

    // The profiles open, in the order opened, the front last; guarded by itself
    private val games = ArrayList<Server>()
    private var opened = 0

    @Volatile private var closed = false

    /** The instance the next connection says hello with: a new one is a new engine, as after the old one was killed. */
    @Volatile var instance = FIRST_INSTANCE

    private val control = Server(base, null)

    /** A profile's socket path that this fake serves nothing on, as a profile's once it has closed. */
    val unservedPath = "$base-closed"

    init {
        open.forEach { openProfile(it, alongside = true) }
    }

    /** For [EngineClient.connector]: [path] is a profile's socket from [openProfilesMessage], else the control socket. */
    fun connect(path: String): Transport {
        // A profile that closed, or one of an earlier test's fake, has no socket, as a profile's goes when it closes
        val server = if (path.startsWith(SOCKET_PREFIX)) synchronized(games) { games.firstOrNull { it.path == path } } else control
        val socket = localSocket(server?.path ?: throw IOException("no profile is served on $path"))
        return object : Transport {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() = socket.close()
        }
    }

    /**
     * The next UI that attached, once it has said hello on the control socket and been answered, and, while a profile is
     * open, on the front profile's socket too.
     */
    fun nextSession(timeoutMs: Long = 10_000): Session {
        val connection = control.next(timeoutMs)
        val front = synchronized(games) { games.lastOrNull() }
        return Session(connection, front?.next(timeoutMs))
    }

    override fun close() {
        closed = true
        control.stop()
        synchronized(games) { games.toList() }.forEach(Server::stop)
    }

    /** Every open profile as `openProfiles` gives them, with [re] the id of the request it answers. */
    fun openProfilesMessage(re: Any? = null): JSONObject {
        val open = synchronized(games) { games.toList() }
        val profiles = open.mapIndexed { i, game ->
            JSONObject().put("name", game.profile).put("slot", game.slot).put("socket", game.path).put("state", "loaded")
                .put("passwordGiven", game.passwordGiven)
                .put("connection", "").put("lastSeq", game.lastSeq).put("unread", game.unread).put("attention", game.attention)
                .put("front", i == open.lastIndex)
        }
        return JSONObject().put("t", "openProfiles").put("profiles", JSONArray(profiles)).putOpt("re", re)
    }

    // Opens the profile [name] in front, closing the others unless [alongside], as the engine's openProfile does;
    // opening one that is open leaves it open
    private fun openProfile(name: String, alongside: Boolean): Server {
        val (game, closing) = synchronized(games) {
            val running = games.firstOrNull { it.profile.equals(name, ignoreCase = true) }
            val closing = if (alongside) emptyList() else games.filter { it !== running }
            games.removeAll(closing.toSet())
            val game = running ?: Server("$base-p${++opened}", name, slot = (1..games.size + 1).first { s -> games.none { it.slot == s } })
            games.remove(game)
            games.add(game)
            game to closing
        }
        closing.forEach(Server::stop)
        return game
    }

    /** Opens the profile [name] in front, on a socket of its own, leaving the others open; [Session.openProfiles] tells the UI. */
    fun openAlongside(name: String) {
        openProfile(name, alongside = true)
    }

    /**
     * What [openProfilesMessage] says of the open profile [name]'s lines: the last one's seq, how many the player has not
     * seen and whether it wants attention; [Session.openProfiles] tells the UI.
     */
    fun counts(name: String, lastSeq: Long, unread: Long, attention: Boolean) {
        val game = server(name)
        game.lastSeq = lastSeq
        game.unread = unread
        game.attention = attention
    }

    /**
     * Whether [openProfilesMessage] says the open profile [name] holds a password, which one the engine brought back
     * after it was killed does not; [Session.openProfiles] tells the UI.
     */
    fun passwordGiven(name: String, given: Boolean) {
        server(name).passwordGiven = given
    }

    /** Closes the open profile [name], whose socket goes; [Session.openProfiles] tells the UI. */
    fun closeProfile(name: String) {
        val game = server(name)
        synchronized(games) { games.remove(game) }
        game.stop()
    }

    private fun server(name: String): Server = synchronized(games) { games.firstOrNull { it.profile == name } } ?: throw AssertionError("the fake engine has no profile $name open")

    private fun localSocket(path: String): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    // A socket the engine listens on: the control socket ([profile] null) or a profile's
    private inner class Server(val path: String, val profile: String?, val slot: Int = 0) {
        private val socket = LocalServerSocket(path)
        private val attached = LinkedBlockingQueue<Connection>()
        private val live = CopyOnWriteArrayList<Connection>()

        @Volatile private var stopped = false

        @Volatile var lastSeq = 0L

        @Volatile var unread = 0L

        @Volatile var attention = false

        @Volatile var passwordGiven = true

        init {
            thread(name = "fake-engine-${profile ?: "control"}", isDaemon = true) {
                while (true) {
                    val accepted = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    if (stopped || closed) {
                        accepted.close()
                        break
                    }
                    try {
                        val connection = Connection(accepted, instance, profile, ::greet)
                        live.add(connection)
                        attached.add(connection)
                    } catch (_: IOException) {
                        // The UI gave up on this connection before its hello
                        accepted.close()
                    }
                }
            }
        }

        // What the engine sends after its hello: on the control socket the open profiles, on a profile's what it
        // follows its hello with, so state an earlier test left in EngineClient goes
        private fun greet(connection: Connection) {
            if (profile == null) {
                connection.send(openProfilesMessage())
                connection.send(JSONObject().put("t", "synced").put("seq", 0))
            } else {
                connection.send(JSONObject().put("t", "hiddenInput").put("wanted", false))
                connection.send(JSONObject().put("t", "history").put("lines", JSONArray()))
            }
        }

        /** The next connection the UI made to this socket. */
        fun next(timeoutMs: Long): Connection = attached.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("the UI did not attach to ${profile ?: "the control socket"} of the fake engine within $timeoutMs ms")

        /** As the engine process dying: every connection ends, and the socket is there again for the next engine. */
        fun dropConnections() = takeConnections().forEach(Connection::close)

        // The connections made so far, which [nextSession] no longer hands out
        fun takeConnections(): List<Connection> {
            val taken = live.toList()
            live.removeAll(taken.toSet())
            attached.removeAll(taken.toSet())
            return taken
        }

        fun stop() {
            stopped = true
            // Closing a LocalServerSocket does not wake its accept(): a last connection does
            try {
                localSocket(path).close()
            } catch (_: IOException) {
            }
            socket.close()
            dropConnections()
            attached.clear()
        }
    }

    /**
     * What a test sees of the UI attached to the fake engine: its connection to the control socket, where the profile
     * requests and their answers go, and to the profile it is about ([forProfile]), where everything else goes. What
     * is sent for a profile before one is open is held for the profile the UI opens next, as the lines a profile's
     * engine sends on attaching.
     */
    inner class Session internal constructor(private val control: Connection, game: Connection?) : AutoCloseable {
        @Volatile private var game: Connection? = game
        private val held = ArrayList<JSONObject>()

        /** Every request [receive] has read from the profile's socket, the skipped ones included. */
        val received: List<JSONObject> get() = game().received

        /** The UI's hello on the profile's socket: "instance" and "lastSeq" when it was attached to an engine before. */
        val hello: JSONObject get() = game().hello

        /** To the control socket if [message] is a control message, else to the profile's. */
        fun send(message: JSONObject) {
            if (message.optString("t") in CONTROL_MESSAGES) control.send(message) else toProfile(message)
        }

        private fun toProfile(message: JSONObject) = synchronized(held) {
            val connection = game
            if (connection != null) connection.send(message) else held.add(message)
        }

        private fun game(): Connection = game ?: throw AssertionError("no profile is open on the fake engine")

        private fun connectionFor(type: String) = if (type in CONTROL_REQUESTS) control else game()

        /** The next request of type [type] the UI sent on the socket that carries it, skipping the others; fails after [timeoutMs]. */
        fun receive(type: String, timeoutMs: Long = 10_000): JSONObject = connectionFor(type).receive(type, timeoutMs)

        /** Fails if the UI sends a request of type [type] within [ms]; the others it skips. */
        fun assertNoRequest(type: String, ms: Int) = assertNoRequest(setOf(type), ms)

        /** Fails if the UI sends a request of any of [types], all on one socket, within [ms]; the others it skips. */
        fun assertNoRequest(types: Set<String>, ms: Int) {
            val connection = connectionFor(types.first())
            require(types.all { connectionFor(it) == connection }) { "$types are not all on one socket" }
            connection.assertNoRequest(types, ms)
        }

        /** As [Connection.receiveLast], on the socket that carries [type]. */
        fun receiveLast(type: String, quietMs: Int = 1_000, readPast: Boolean = false): JSONObject = connectionFor(type).receiveLast(type, quietMs, readPast)

        /**
         * The answer to a profile request, as the engine gives it: every profile, and the request's id as "re". An
         * `openProfile` opens the profile, which is answered with `openProfiles`, once [profiles] has said the new order;
         * this session is then about that profile, once the UI has attached to it.
         */
        fun profiles(profiles: List<Profile>, request: JSONObject? = null) {
            val listed = JSONObject().put("t", "profiles").put("profiles", JSONArray(profiles.map { it.toJson() }))
            if (request == null || request.optString("t") != "openProfile") {
                control.send(listed.putOpt("re", request?.opt("id")))
                return
            }
            control.send(listed)
            val opened = openProfile(request.getString("name"), request.optBoolean("alongside"))
            control.send(openProfilesMessage(request.opt("id")))
            if (game?.profile != opened.profile) attach(opened.next(ATTACH_TIMEOUT_MS))
        }

        /** The UI's session on the open profile [name]'s socket, once it has attached there. */
        fun forProfile(name: String): Session = Session(control, server(name).next(ATTACH_TIMEOUT_MS))

        // The profile's socket, to which what was held goes first
        private fun attach(connection: Connection) = synchronized(held) {
            game = connection
            held.forEach(connection::send)
            held.clear()
        }

        /** The engine refusing [request], on the socket that carried it. */
        fun refuse(request: JSONObject, why: String) {
            val error = JSONObject().put("t", "error").put("re", request.opt("id")).put("message", "${request.optString("t")}: $why")
            if (request.optString("t") in CONTROL_REQUESTS) control.send(error) else toProfile(error)
        }

        /** Every open profile, as the engine tells the UI of each change. */
        fun openProfiles() = control.send(openProfilesMessage())

        /** One of the engine's own statuses, which the control socket carries. */
        fun engineStatus(key: String, value: String) = control.send(JSONObject().put("t", "status").put("key", key).put("value", value))

        fun clear() = send(JSONObject().put("t", "clear"))

        /**
         * [runs]: each [start, length, fg, bg, attributes], as docs/protocol.md's "Runs" says. [replay]: one of the
         * lines an engine sends again to a UI that attached. [time]: when it came, in milliseconds since the epoch.
         */
        fun line(seq: Long, text: String, runs: List<List<Int>> = emptyList(), prompt: Boolean = false, replay: Boolean = false, time: Long = 0) = send(
            JSONObject().put("t", "line").put("seq", seq).put("text", text).put("prompt", prompt).put("replay", replay).put("time", time)
                .put("runs", JSONArray(runs.map { JSONArray(it) })),
        )

        /** A script changed the line [seq] to [text] and [runs], or with [deleted], deleted it. */
        fun lineUpdated(seq: Long, text: String = "", runs: List<List<Int>> = emptyList(), deleted: Boolean = false) = send(
            if (deleted) {
                JSONObject().put("t", "lineUpdated").put("seq", seq).put("deleted", true)
            } else {
                JSONObject().put("t", "lineUpdated").put("seq", seq).put("text", text).put("prompt", false).put("runs", JSONArray(runs.map { JSONArray(it) }))
            },
        )

        fun tail(text: String, prompt: Boolean = true) = send(JSONObject().put("t", "tail").put("text", text).put("prompt", prompt))

        fun status(key: String, value: String) = send(JSONObject().put("t", "status").put("key", key).put("value", value))

        /** A reply to the request whose "id" was [re]. */
        fun reply(key: String, value: String, re: Any) = send(JSONObject().put("t", "status").put("key", key).put("value", value).put("re", re))

        /** The answer to a getSettings or setSettings whose "id" was [re]: the profile's [values] of the settings it named. */
        fun settings(values: Map<String, Any>, re: Any) = send(JSONObject().put("t", "settings").put("values", JSONObject(values)).put("re", re))

        fun hiddenInput(wanted: Boolean) = send(JSONObject().put("t", "hiddenInput").put("wanted", wanted))

        fun secureOffer(offered: Boolean) = send(JSONObject().put("t", "secureOffer").put("offered", offered))

        /** The certificate the last secure connection was refused for, or none (null). */
        fun certificateRefused(certificate: RefusedCertificate?) = send(
            JSONObject().put("t", "certificateRefused").put("refused", certificate != null).apply {
                if (certificate != null) {
                    put("issuedTo", certificate.issuedTo).put("issuer", certificate.issuer).put("expires", certificate.expires)
                        .put("serial", certificate.serial).put("expired", certificate.expired).put("selfSigned", certificate.selfSigned)
                        .put("others", certificate.others).put("errors", JSONArray(certificate.errors))
                }
            },
        )

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
            clickThrough: Boolean = false,
            value: Double? = null,
            valueMin: Double? = null,
            valueMax: Double? = null,
        ) = send(
            JSONObject().put("t", "label").put("name", name).put("text", text).put("image", image).put("svg", false)
                .put("movie", "").put("moviePaused", false).put("movieFrame", -1).put("movieSpeed", 100).put("toolTip", toolTip)
                .put("clickThrough", clickThrough).put("accessibleName", accessibleName).put("accessibleButton", accessibleButton)
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

        /** As the engine process dying: the UI sees every connection end, and attaches again to the next. */
        override fun close() {
            // Taken from every socket before any closes: the UI attaches again the moment one does, to the next engine
            val servers = listOf(this@FakeEngine.control) + synchronized(games) { games.toList() }
            val ending = servers.flatMap(Server::takeConnections) + listOfNotNull(control, game)
            ending.toSet().forEach(Connection::close)
        }
    }

    /** One connection the UI made: its hello, and the requests it sent on it. */
    class Connection internal constructor(private val socket: LocalSocket, instance: String, val profile: String?, greet: (Connection) -> Unit) : AutoCloseable {
        private val writer = FrameWriter(socket.outputStream)
        private val input = DataInputStream(socket.inputStream)

        /** Every request [receive] has read, the skipped ones included. */
        val received: MutableList<JSONObject> = CopyOnWriteArrayList()

        /** The UI's hello: "instance" and "lastSeq" when it was attached to an engine before. */
        val hello: JSONObject

        init {
            socket.soTimeout = READ_TIMEOUT_MS
            hello = readFrame(input)
            if (hello.optString("t") != "hello") throw IOException("the UI began with $hello")
            send(JSONObject().put("t", "hello").put("v", 1).put("pid", FAKE_PID).put("instance", instance).putOpt("profile", profile))
            greet(this)
        }

        @Synchronized
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

        /** Fails if the UI sends a request of any of [types] within [ms]; the others it skips. */
        fun assertNoRequest(types: Set<String>, ms: Int) {
            val deadline = System.currentTimeMillis() + ms
            try {
                while (true) {
                    socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val frame = readFrame(input)
                    if (frame.optString("t") in types) throw AssertionError("the UI sent $frame")
                }
            } catch (_: IOException) {
                // A LocalSocket's read timeout is a plain IOException
            } finally {
                socket.soTimeout = 10_000
            }
        }

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

        // Each fake's sockets' names begin with it, so the UI's link to an earlier test's profile finds none
        private const val SOCKET_PREFIX = "mudlet-fake-engine-"

        // The requests the control socket carries (docs/protocol.md, "Several profiles"), and the messages
        private val CONTROL_REQUESTS = setOf("listProfiles", "saveProfile", "deleteProfile", "openProfile", "closeProfile", "setFront", "markSeen", "setAppState", "answerClipboardRead", "answerPickFile", "answerOpenUrl")
        private val CONTROL_MESSAGES = setOf("profiles", "openProfiles", "clipboardRead", "clipboardWrite", "pickFile", "openUrl")

        // How long a session waits for the UI to attach to a profile it opened
        private const val ATTACH_TIMEOUT_MS = 10_000L

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
         * [fontSize], in points, is what it draws its text in.
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
            fontSize: Double? = null,
        ): JSONObject {
            val window = JSONObject().put("name", name).put("kind", kind).put("parent", parent).put("parentKind", parentKind)
                .put("x", x).put("y", y).put("w", w).put("h", h).put("visible", true).put("shown", true).put("stack", 0)
            if (kind == "miniConsole" || kind == "userWindow") window.put("columns", columns).put("rows", rows)
            if (fontSize != null) window.put("fontFamily", "Ubuntu Mono").put("fontSize", fontSize)
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
