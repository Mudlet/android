package org.mudlet.proto.c.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mudlet.proto.c.engine.EngineService
import org.mudlet.proto.c.engine.EngineSocket
import org.mudlet.proto.c.protocol.EngineLink
import org.mudlet.proto.c.protocol.EngineMessage
import org.mudlet.proto.c.protocol.Requests
import org.mudlet.proto.c.protocol.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

class UiLine(val text: String, val runs: IntArray, val prompt: Boolean, val marker: Boolean = false)

/**
 * The UI process's view of the engine. Lives for the UI process; attaches while an
 * Activity is started, so a backgrounded (and later frozen) UI holds no connection.
 * State is Compose snapshot state, written only on the main thread.
 */
object EngineClient {
    const val MAX_LINES = 2000

    val lines = mutableStateListOf<UiLine>()
    val status = mutableStateMapOf<String, String>()
    var linkState by mutableStateOf("not attached")
    var enginePid by mutableStateOf(0)
    var fgsState by mutableStateOf("?")
    var notifyCount by mutableStateOf(0)
    var gapLines by mutableStateOf(0L)
    var attachCount by mutableStateOf(0)
    var engineLosses by mutableStateOf(0)
    var lastAttachMs by mutableStateOf(-1L)
    var startToHelloMs by mutableStateOf(-1L)
    var lastLossToAttachMs by mutableStateOf(-1L)
    var pingResult by mutableStateOf("")
    var lastLuaResult by mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    // One link for the UI process's life: it remembers the engine instance and last seq,
    // so reattaching after onStop/onStart (or rotation) replays only the missed lines
    private val link: EngineLink by lazy { EngineLink(::connectLocal, listener) }
    @Volatile private var attached = false
    @Volatile private var viewWidthPx = 0
    private var startRequestedAt = 0L
    private var detachStartedAt = 0L
    private var lostAt = 0L
    private var restartRequestedForThisOutage = false

    // Reader-thread batches, drained on the main thread
    private val pendingLock = Any()
    private var pendingLines = ArrayList<UiLine>()
    private var pendingStatus = HashMap<String, String>()
    private var flushPosted = false

    var engineWanted: Boolean
        get() = prefs().getBoolean("wanted", true)
        set(value) = prefs().edit().putBoolean("wanted", value).apply()

    private fun prefs() = app.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun startEngine() {
        engineWanted = true
        startRequestedAt = SystemClock.elapsedRealtime()
        app.startForegroundService(Intent(app, EngineService::class.java))
    }

    fun stopEngine() {
        engineWanted = false
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

    fun send(message: org.json.JSONObject) {
        if (!link.send(message)) linkState = "not attached: command dropped"
    }

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
            val text = if (us.isEmpty()) "no replies" else
                "n=${us.size} median=${us[us.size / 2]}us p99=${us[(us.size * 99) / 100]}us max=${us.last()}us"
            main.post { pingResult = text }
        }
    }

    fun refreshServiceState() {
        val am = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val info = am.getRunningServices(50).firstOrNull { it.service.className == EngineService::class.java.name }
        fgsState = if (info == null) "not running" else
            "foreground=${info.foreground} pid=${info.pid} process=${info.process} started=${info.started}"
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
                linkState = "attached to pid ${hello.pid} (${if (sameInstance) "same engine, delta replay" else "new engine instance"})"
                if (!sameInstance && lines.isNotEmpty()) {
                    lines.add(UiLine("--- new engine instance ${hello.instance} ---", IntArray(0), false, marker = true))
                }
            }
        }

        override fun onMessage(message: EngineMessage) {
            when (message) {
                is EngineMessage.Line -> synchronized(pendingLock) {
                    pendingLines.add(UiLine(message.text, message.runs, message.prompt))
                    scheduleFlush()
                }
                is EngineMessage.Status -> synchronized(pendingLock) {
                    pendingStatus[message.key] = message.value
                    scheduleFlush()
                }
                is EngineMessage.Notify -> post { notifyCount++ }
                is EngineMessage.Gap -> post {
                    gapLines += message.skipped
                    synchronized(pendingLock) {
                        pendingLines.add(UiLine("--- ${message.skipped} lines skipped while the UI lagged ---", IntArray(0), false, true))
                        scheduleFlush()
                    }
                }
                is EngineMessage.Error -> post { linkState = "engine error: ${message.message}" }
                else -> {}
            }
        }

        override fun onDetached(reason: String) {
            post {
                engineLosses++
                lostAt = SystemClock.elapsedRealtime()
                detachStartedAt = lostAt
                enginePid = 0
                linkState = "engine connection lost ($reason); reconnecting"
            }
        }

        override fun onConnectFailed(attempt: Int, error: IOException) {
            post {
                linkState = "engine not reachable (attempt $attempt: ${error.message})"
                // The engine is gone (stopped from its notification, crashed, or killed). While a
                // UI is started we may start a foreground service again.
                if (attempt >= 3 && engineWanted && !restartRequestedForThisOutage && attached) {
                    restartRequestedForThisOutage = true
                    startEngine()
                }
            }
        }
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
        synchronized(pendingLock) {
            newLines = pendingLines
            newStatus = pendingStatus
            pendingLines = ArrayList()
            pendingStatus = HashMap()
            flushPosted = false
        }
        status.putAll(newStatus)
        newStatus["luaResult"]?.let { lastLuaResult = it }
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
