package org.mudlet.android.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mudlet.android.R
import org.mudlet.android.engine.EngineService
import org.mudlet.android.engine.EngineSession
import org.mudlet.android.engine.EngineSocket
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.EngineRestore
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

class UiLine(val text: String, val runs: IntArray, val prompt: Boolean, val marker: Boolean = false)

/** Where a `connect` request sent the engine. */
data class GameAddress(val host: String, val port: Int, val tls: Boolean)

/**
 * The UI process's view of the engine. Lives for the UI process; attaches while an
 * Activity is started, so a backgrounded (and later frozen) UI holds no connection.
 * State is Compose snapshot state, written only on the main thread.
 */
// It holds only the application's Context (app), which leaks nothing
@SuppressLint("StaticFieldLeak")
object EngineClient {
    const val MAX_LINES = 2000

    // About 300 ms of failed connects at EngineLink's fast retry interval
    private const val RESTART_AFTER_FAILED_ATTEMPTS = 6

    private const val PREF_LAST_HOST = "lastHost"
    private const val PREF_LAST_PORT = "lastPort"
    private const val PREF_LAST_TLS = "lastTls"

    // How long after a send the engine's emptying of its line (an empty commandLine) is taken as that send's
    private const val SEND_CLEAR_WINDOW_MS = 3000L

    val lines = mutableStateListOf<UiLine>()

    /**
     * How many lines have been added to [lines] in this process, markers included: `lines[i]` is line number
     * `linesAdded - lines.size + i`, a number that stays with the line while older ones are dropped.
     */
    var linesAdded by mutableLongStateOf(0L)
        private set

    /** The last address [connect] was given, kept across UI process restarts: what Reconnect connects to. */
    var lastAddress by mutableStateOf<GameAddress?>(null)
        private set

    // The engine's line still being written, such as a prompt, shown below the others
    var tail by mutableStateOf<UiLine?>(null)
    val status = mutableStateMapOf<String, String>()
    var linkState by mutableStateOf("not attached")
    var enginePid by mutableIntStateOf(0)
    var fgsState by mutableStateOf("?")
    var notifyCount by mutableIntStateOf(0)
    var gapLines by mutableLongStateOf(0L)
    var attachCount by mutableIntStateOf(0)
    var engineLosses by mutableIntStateOf(0)
    var lastAttachMs by mutableLongStateOf(-1L)
    var startToHelloMs by mutableLongStateOf(-1L)
    var lastLossToAttachMs by mutableLongStateOf(-1L)
    var pingResult by mutableStateOf("")
    var lastLuaResult by mutableStateOf("")

    /** The game hides what the player types, as for a password: the input line masks it and sends it with [sendHidden]. */
    var hiddenInput by mutableStateOf(false)
        private set

    /** The input line's history, newest first: the engine's, with the commands [sendCommand] sent since it last said. */
    var history by mutableStateOf<List<String>>(emptyList())
        private set

    /** What a script set the input line to, until the line shows it and calls [commandLineShown]. */
    var commandLine by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    // The application's Context, which lives as long as the process
    private lateinit var app: Context

    /** How the link reaches the engine: its local socket. The instrumented tests (src/androidTest) put a fake engine here. */
    @VisibleForTesting
    internal var connector: () -> Transport = ::connectLocal

    // One link for the UI process's life: it remembers the engine instance and last seq,
    // so reattaching after onStop/onStart (or rotation) replays only the missed lines
    private val link: EngineLink by lazy { EngineLink({ connector() }, listener) }

    @Volatile private var attached = false

    @Volatile private var viewWidthPx = 0
    private var startRequestedAt = 0L
    private var detachStartedAt = 0L
    private var lostAt = 0L
    private var restartRequestedForThisOutage = false

    // Main thread only
    private var nextRequestId = 1L
    private val completions = HashMap<Long, (String) -> Unit>()
    private val sendsAwaitingClear = ArrayDeque<Long>()

    // Reader-thread batches, drained on the main thread
    private val pendingLock = Any()
    private var pendingLines = ArrayList<UiLine>()
    private var pendingTail: UiLine? = null
    private var pendingTailSet = false
    private var pendingClear = false
    private var pendingStatus = HashMap<String, String>()
    private var pendingStatusReset = false
    private var flushPosted = false

    var engineWanted: Boolean
        get() = prefs().getBoolean("wanted", true)
        set(value) = prefs().edit { putBoolean("wanted", value) }

    private fun prefs() = app.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        val host = p.getString(PREF_LAST_HOST, null)
        if (host != null) lastAddress = GameAddress(host, p.getInt(PREF_LAST_PORT, 23), p.getBoolean(PREF_LAST_TLS, false))
    }

    fun startEngine() {
        engineWanted = true
        startRequestedAt = SystemClock.elapsedRealtime()
        app.startForegroundService(Intent(app, EngineService::class.java))
        link.wake()
    }

    fun stopEngine() {
        engineWanted = false
        // Also when the engine is not running: one that died with a profile must not bring it back at the next start
        EngineSession.markStopped(app)
        app.stopService(Intent(app, EngineService::class.java))
    }

    fun killEngineProcess() {
        if (enginePid > 0) android.os.Process.killProcess(enginePid)
    }

    fun attach() {
        if (attached) return
        attached = true
        detachStartedAt = SystemClock.elapsedRealtime()
        link.start()
        linkState = "connecting"
    }

    fun detach() {
        if (!attached) return
        attached = false
        link.stop()
        linkState = "detached (UI stopped)"
    }

    /** False when the link is down and the command was dropped. */
    fun send(message: org.json.JSONObject): Boolean {
        val sent = link.send(message)
        if (!sent) linkState = "not attached: command dropped"
        return sent
    }

    /** A command typed on the input line: sent, and put first in [history]. False as for [send]. */
    fun sendCommand(line: String): Boolean {
        if (!send(Requests.send(line))) return false
        val now = SystemClock.elapsedRealtime()
        dropExpiredSends(now)
        sendsAwaitingClear.addLast(now)
        if (line.isNotBlank()) history = listOf(line) + history.filter { it != line }
        return true
    }

    /** What the player typed while [hiddenInput] holds. False as for [send]. */
    fun sendHidden(text: String): Boolean = send(Requests.sendHidden(text))

    fun dismissHidden(): Boolean = send(Requests.dismissHidden())

    /** Asks the engine to complete [text]; [onResult] gets the completed line, or empty for none. False as for [send]. */
    fun complete(text: String, onResult: (String) -> Unit): Boolean {
        val id = nextRequestId++
        if (!send(Requests.complete(text, false, id))) return false
        completions[id] = onResult
        return true
    }

    fun commandLineShown() {
        commandLine = null
    }

    /** Connects the engine to a game and remembers the address for [reconnect]. False as for [send]. */
    fun connect(host: String, port: Int, tls: Boolean): Boolean {
        lastAddress = GameAddress(host, port, tls)
        prefs().edit {
            putString(PREF_LAST_HOST, host)
            putInt(PREF_LAST_PORT, port)
            putBoolean(PREF_LAST_TLS, tls)
        }
        return send(Requests.connect(host, port, tls))
    }

    /** Connects to [lastAddress] again; false when there is none or the request was dropped. */
    fun reconnect(): Boolean {
        val address = lastAddress ?: return false
        return send(Requests.connect(address.host, address.port, address.tls))
    }

    fun disconnect(): Boolean = send(Requests.disconnect())

    fun setViewWidth(px: Int) {
        viewWidthPx = px
        if (attached) link.send(Requests.setViewWidthPx(px))
    }

    fun runPings(count: Int = 300) {
        if (!attached) return
        val l = link
        pingResult = "running $count pings..."
        thread(name = "ping") {
            val us = (0 until count).mapNotNull { l.ping()?.div(1000) }.drop(20).sorted()
            val text = if (us.isEmpty()) {
                "no replies"
            } else {
                "n=${us.size} median=${us[us.size / 2]}us p99=${us[(us.size * 99) / 100]}us max=${us.last()}us"
            }
            main.post { pingResult = text }
        }
    }

    fun refreshServiceState() {
        val am = app.getSystemService(ActivityManager::class.java)

        @Suppress("DEPRECATION")
        val info = am.getRunningServices(50).firstOrNull { it.service.className == EngineService::class.java.name }
        fgsState = if (info == null) {
            "not running"
        } else {
            "foreground=${info.foreground} pid=${info.pid} process=${info.process} started=${info.started}"
        }
    }

    private fun connectLocal(): Transport {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(EngineSocket.path(app), LocalSocketAddress.Namespace.FILESYSTEM))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return object : Transport {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() {
                try {
                    socket.shutdownInput()
                } catch (_: IOException) {
                }
                socket.close()
            }
        }
    }

    private val listener = object : EngineLink.Listener {
        override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) {
            // The engine forgets nothing across a reattach, but a new engine instance needs the width again
            if (viewWidthPx > 0) link.send(Requests.setViewWidthPx(viewWidthPx))
            // The engine resends its whole status after hello, so the old instance's values must go first
            if (!sameInstance) resetStatus()
            post {
                val now = SystemClock.elapsedRealtime()
                enginePid = hello.pid
                attachCount++
                lastAttachMs = now - detachStartedAt
                if (startRequestedAt > 0) {
                    startToHelloMs = now - startRequestedAt
                    startRequestedAt = 0
                }
                if (lostAt > 0) {
                    lastLossToAttachMs = now - lostAt
                    lostAt = 0
                }
                restartRequestedForThisOutage = false
                // The engine's answers to sends on the old connection went with it
                sendsAwaitingClear.clear()
                if (!sameInstance) {
                    notifyCount = 0
                    lastLuaResult = ""
                    pingResult = ""
                }
                linkState = "attached to pid ${hello.pid} (${if (sameInstance) "same engine, delta replay" else "new engine instance"})"
                if (!sameInstance) tail = null
                if (!sameInstance && lines.isNotEmpty()) {
                    addLines(listOf(UiLine(app.getString(R.string.marker_new_engine, hello.instance), IntArray(0), false, marker = true)))
                }
            }
        }

        override fun onMessage(message: EngineMessage) {
            when (message) {
                is EngineMessage.Line -> synchronized(pendingLock) {
                    pendingLines.add(UiLine(message.text, message.runs, message.prompt))
                    scheduleFlush()
                }

                is EngineMessage.Clear -> synchronized(pendingLock) {
                    pendingLines.clear()
                    pendingClear = true
                    pendingTail = null
                    pendingTailSet = true
                    scheduleFlush()
                }

                is EngineMessage.Tail -> synchronized(pendingLock) {
                    pendingTail = if (message.text.isEmpty()) null else UiLine(message.text, message.runs, message.prompt)
                    pendingTailSet = true
                    scheduleFlush()
                }

                is EngineMessage.Status -> if (message.key == "completion") {
                    val id = (message.replyTo as? Number)?.toLong()
                    if (id != null) post { completions.remove(id)?.invoke(message.value) }
                } else {
                    synchronized(pendingLock) {
                        pendingStatus[message.key] = message.value
                        scheduleFlush()
                    }
                }

                is EngineMessage.HiddenInput -> post { hiddenInput = message.wanted }

                is EngineMessage.History -> post { history = message.lines }

                is EngineMessage.CommandLine -> post { onCommandLine(message.text) }

                is EngineMessage.Notify -> post { notifyCount++ }

                is EngineMessage.Gap -> post {
                    gapLines += message.skipped
                    synchronized(pendingLock) {
                        val skipped = message.skipped
                        val text = app.resources.getQuantityString(R.plurals.marker_lines_skipped, skipped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), skipped)
                        pendingLines.add(UiLine(text, IntArray(0), false, true))
                        scheduleFlush()
                    }
                }

                is EngineMessage.Error -> post { linkState = "engine error: ${message.message}" }

                else -> {}
            }
        }

        override fun onDetached(reason: String) {
            resetStatus()
            post {
                engineLosses++
                lostAt = SystemClock.elapsedRealtime()
                detachStartedAt = lostAt
                enginePid = 0
                completions.clear()
                linkState = "engine connection lost ($reason); reconnecting"
            }
        }

        override fun onConnectFailed(attempt: Int, error: IOException) {
            post {
                linkState = "engine not reachable (attempt $attempt: ${error.message})"
                // The engine is gone (stopped from its notification, crashed, or killed). While a
                // UI is started we may start a foreground service again.
                if (attempt >= RESTART_AFTER_FAILED_ATTEMPTS && engineWanted && !restartRequestedForThisOutage && attached) {
                    restartRequestedForThisOutage = true
                    startEngine()
                }
            }
        }
    }

    private fun onCommandLine(text: String) {
        // Lost while input is hidden, as Mudlet's GUI writes it into its password box
        if (hiddenInput) return
        // The engine empties its line after each send, which the input line did already: by the time that arrives,
        // the player may be typing the next command
        if (text.isEmpty()) {
            dropExpiredSends(SystemClock.elapsedRealtime())
            if (sendsAwaitingClear.removeFirstOrNull() != null) return
        }
        commandLine = text
    }

    // A profile that keeps the line after a send never empties it
    private fun dropExpiredSends(now: Long) {
        while (sendsAwaitingClear.isNotEmpty() && now - sendsAwaitingClear.first() > SEND_CLEAR_WINDOW_MS) sendsAwaitingClear.removeFirst()
    }

    // Runs on the reader thread, ahead of any status the next engine sends
    private fun resetStatus() = synchronized(pendingLock) {
        pendingStatus.clear()
        pendingStatusReset = true
        scheduleFlush()
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun scheduleFlush() {
        if (flushPosted) return
        flushPosted = true
        main.postDelayed(::flush, 16)
    }

    private fun flush() {
        val newLines: List<UiLine>
        val newStatus: Map<String, String>
        val statusReset: Boolean
        val newTail: UiLine?
        val tailSet: Boolean
        val clear: Boolean
        synchronized(pendingLock) {
            clear = pendingClear
            pendingClear = false
            newTail = pendingTail
            tailSet = pendingTailSet
            pendingTail = null
            pendingTailSet = false
            newLines = pendingLines
            newStatus = pendingStatus
            statusReset = pendingStatusReset
            pendingLines = ArrayList()
            pendingStatus = HashMap()
            pendingStatusReset = false
            flushPosted = false
        }
        if (statusReset) status.clear()
        val restore = EngineRestore.marker(status[EngineRestore.STATUS_KEY], newStatus[EngineRestore.STATUS_KEY])
        status.putAll(newStatus)
        newStatus["luaResult"]?.let { lastLuaResult = it }
        if (clear) lines.clear()
        if (restore != null) addLines(listOf(UiLine(restore, IntArray(0), false, marker = true)))
        addLines(newLines)
        if (tailSet) tail = newTail
    }

    private fun addLines(newLines: List<UiLine>) {
        if (newLines.isEmpty()) return
        linesAdded += newLines.size
        if (newLines.size >= MAX_LINES) {
            lines.clear()
            lines.addAll(newLines.subList(newLines.size - MAX_LINES, newLines.size))
        } else {
            lines.addAll(newLines)
            val excess = lines.size - MAX_LINES
            if (excess > 0) lines.removeRange(0, excess)
        }
    }
}
