package org.mudlet.proto.b

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OutLine(val id: Long, val text: AnnotatedString, val prompt: Boolean)

/**
 * Process-wide state and the JNI surface. The natives are registered by JNI_OnLoad in
 * libmudengine (qtapp/AndroidBridge.cpp); the on* callbacks arrive on Qt's main thread.
 */
object EngineBridge {
    private const val MAX_LINES = 2000
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    lateinit var app: Application
        private set
    lateinit var hostMode: HostMode
        private set
    private var appCreatedAt = 0L

    // Compose state, touched on the main thread only.
    val status = mutableStateMapOf<String, String>()
    val lines = mutableStateListOf<OutLine>()
    val events = mutableStateListOf<String>()
    var qtState by mutableStateOf("not started")
    var qtStartedAfterMs by mutableStateOf<Long?>(null)
    var qtEverStarted by mutableStateOf(false)
    var qtTerminated by mutableStateOf(false)
    var serviceState by mutableStateOf("not started")
    var notificationsPosted by mutableIntStateOf(0)
    var notificationsDropped by mutableIntStateOf(0)
    var reembedRequested = false
    private var nextLineId = 0L
    private var lastViewWidthPx = 0

    fun init(application: Application, createdAtElapsed: Long) {
        app = application
        appCreatedAt = createdAtElapsed
        hostMode = HostMode.load(application)
        event("Application.onCreate pid=${Process.myPid()} mode=$hostMode")
    }

    fun event(what: String) {
        val line = "${timeFormat.format(Date())} $what"
        if (Looper.myLooper() == Looper.getMainLooper()) addEvent(line) else mainHandler.post { addEvent(line) }
    }

    private fun addEvent(line: String) {
        events.add(line)
        if (events.size > 40) events.removeRange(0, events.size - 40)
    }

    fun noteQtStartRequested(contextName: String) {
        qtState = "starting (QtQuickView with $contextName context)"
        event("QtQuickView created with $contextName context")
    }

    // Kotlin -> C++. False when Qt's libraries are not loaded or the engine is not alive.
    @JvmStatic private external fun nativeConnect(host: String, port: Int): Boolean
    @JvmStatic private external fun nativeSend(line: String): Boolean
    @JvmStatic private external fun nativeDisconnect(): Boolean
    @JvmStatic private external fun nativeRunLua(code: String): Boolean
    @JvmStatic private external fun nativeSetViewWidthPx(px: Int): Boolean
    @JvmStatic private external fun nativeQuit(): Boolean
    @JvmStatic private external fun nativeEngineAlive(): Boolean

    private inline fun call(what: String, block: () -> Boolean): Boolean {
        val ok = try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        if (!ok) event("$what dropped: engine not running")
        return ok
    }

    fun connect(host: String, port: Int) = call("connect") { nativeConnect(host, port) }
    fun send(line: String) = call("send") { nativeSend(line) }
    fun disconnect() = call("disconnect") { nativeDisconnect() }
    fun runLua(code: String) = call("runLua") { nativeRunLua(code) }
    fun quitQt(reason: String) {
        event("asking Qt to quit: $reason")
        call("quit") { nativeQuit() }
    }

    fun engineAlive(): Boolean = try {
        nativeEngineAlive()
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    fun setViewWidthPx(px: Int) {
        lastViewWidthPx = px
        try {
            nativeSetViewWidthPx(px)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    // C++ -> Kotlin, called on Qt's thread.
    @JvmStatic
    fun onLine(text: String, runs: IntArray, isPrompt: Boolean) {
        val styled = buildAnnotatedString {
            append(text)
            var i = 0
            while (i + 2 < runs.size) {
                val fg = runs[i + 2]
                if (fg >= 0) addStyle(SpanStyle(color = Ansi.palette[fg]), runs[i], runs[i] + runs[i + 1])
                i += 3
            }
        }
        mainHandler.post {
            lines.add(OutLine(nextLineId++, styled, isPrompt))
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
        }
    }

    @JvmStatic
    fun onNotify(title: String, text: String) {
        val posted = Notifications.postTell(app, title, text)
        mainHandler.post { if (posted) notificationsPosted++ else notificationsDropped++ }
    }

    @JvmStatic
    fun onStatus(key: String, value: String) {
        mainHandler.post { applyStatus(key, value) }
    }

    private fun applyStatus(key: String, value: String) {
        status[key] = value
        when (key) {
            "qt" -> {
                event("qt: $value")
                if (value == "running") {
                    qtEverStarted = true
                    qtTerminated = false
                    qtStartedAfterMs = SystemClock.elapsedRealtime() - appCreatedAt
                } else if (value.startsWith("main() returned")) {
                    qtTerminated = true
                }
                qtState = value
            }
            "engine" -> if (value == "ready" && lastViewWidthPx > 0) setViewWidthPx(lastViewWidthPx)
            "qt.appState" -> event("Qt applicationState=${appStateName(value)}")
            "conn" -> event("conn: $value")
        }
        if (key == "heartbeat" || key == "conn" || key == "qt") EngineService.refreshNotification()
    }

    private fun appStateName(v: String) = when (v) {
        "0" -> "Suspended"
        "1" -> "Hidden"
        "2" -> "Inactive"
        "4" -> "Active"
        else -> v
    }

    fun summary(): String = buildString {
        append("Qt: ").append(qtState)
        status["conn"]?.let { append("\nconn: ").append(it) }
        status["heartbeat"]?.let { append("\n").append(it) }
    }
}

object Ansi {
    val defaultFg = Color(0xFFC0C0C0)
    val palette = listOf(
        0xFF000000, 0xFFAA0000, 0xFF00AA00, 0xFFAA5500, 0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAAAAAA,
        0xFF555555, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55, 0xFF5555FF, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFFFFF,
    ).map { Color(it) }
}
