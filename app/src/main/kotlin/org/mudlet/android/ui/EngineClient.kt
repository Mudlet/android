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
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineService
import org.mudlet.android.engine.EngineSession
import org.mudlet.android.engine.EngineSocket
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.EngineRestore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.Transport
import org.mudlet.android.ui.game.Announcer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
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

    private const val TAG = "EngineClient"

    // About 300 ms of failed connects at EngineLink's fast retry interval
    private const val RESTART_AFTER_FAILED_ATTEMPTS = 6

    private const val PREF_LAST_HOST = "lastHost"
    private const val PREF_LAST_PORT = "lastPort"
    private const val PREF_LAST_TLS = "lastTls"

    // How long after a send the engine's emptying of its line (an empty commandLine) is taken as that send's
    private const val SEND_CLEAR_WINDOW_MS = 3000L

    // How long after a send, sendHidden or dismissHidden a status sendError is taken as its answer
    private const val SEND_ERROR_WINDOW_MS = 5000L

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

    /**
     * The engine took what the player typed ahead of the game hiding input: the input line is to move its text into the
     * masked field that [EngineMessage.HiddenInput] opens next, empty itself and call [typedLineMoved].
     */
    var moveTypedLine by mutableStateOf(false)
        private set

    /** Why the engine could not send the last input request (status `sendError`), until the screen shows it and calls [sendErrorShown]. */
    var sendError by mutableStateOf<String?>(null)
        private set

    /** The engine's profiles, the last opened first; null until it has listed them. */
    var profiles by mutableStateOf<List<Profile>?>(null)
        private set

    // The profile requests waiting for their answer, by id; main thread only
    private val profileAnswers = HashMap<Long, (String?) -> Unit>()

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

    /** The console's columns and rows, as [setScreenSize] last had them; null until the UI has measured it. */
    @Volatile
    var screenSize: Pair<Int, Int>? = null
        private set

    // When an input request last went out; the engine keeps sendError in its status, which it sends again on attach
    @Volatile private var inputSentAt = 0L

    // Reader thread only: from hello to synced, the engine resends what it holds, which is not news
    private var replaying = false
    private var startRequestedAt = 0L
    private var detachStartedAt = 0L
    private var lostAt = 0L
    private var restartRequestedForThisOutage = false

    // Main thread only
    private val completions = HashMap<Long, (String) -> Unit>()
    private val sendsAwaitingClear = ArrayDeque<Long>()

    // The ids of the runLua requests that carry the app's Mudlet settings, whose luaResult is no script's
    private val settingsRequests: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // Reader-thread batches, drained on the main thread
    private val pendingLock = Any()
    private var pendingLines = ArrayList<UiLine>()

    // The text of the new lines, not those replayed, for the Announcer
    private var pendingNews = ArrayList<String>()
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

    /** The `id` for a request to the engine, whoever sends it: see [EngineLink.newRequestId]. */
    fun newRequestId(): Long = link.newRequestId()

    /** False when the link is down and the command was dropped. */
    fun send(message: org.json.JSONObject): Boolean = sendOn(message) != 0

    /** As [send], returning the number of the link's connection the command went out on, or 0 when it was dropped. */
    fun sendOn(message: org.json.JSONObject): Int {
        val connection = link.sendOn(message)
        if (connection == 0) linkState = "not attached: command dropped"
        return connection
    }

    // A request the engine answers with status sendError when it cannot carry it out
    private fun sendInput(message: org.json.JSONObject): Boolean {
        if (!send(message)) return false
        inputSentAt = SystemClock.elapsedRealtime()
        return true
    }

    /** A command typed on the input line: sent, and put first in [history]. False as for [send]. */
    fun sendCommand(line: String): Boolean {
        // A script the command runs reads what the player typed in the text boxes
        ScriptWindowsClient.flushTyping()
        if (!sendInput(Requests.send(line))) return false
        val now = SystemClock.elapsedRealtime()
        dropExpiredSends(now)
        sendsAwaitingClear.addLast(now)
        if (line.isNotBlank()) history = listOf(line) + history.filter { it != line }
        return true
    }

    /** What the player typed while [hiddenInput] holds. False as for [send]. */
    fun sendHidden(text: String): Boolean = sendInput(Requests.sendHidden(text))

    fun dismissHidden(): Boolean = sendInput(Requests.dismissHidden())

    /** Asks the engine to complete [text]; [onResult] gets the completed line, or empty for none. False as for [send]. */
    fun complete(text: String, onResult: (String) -> Unit): Boolean {
        val id = newRequestId()
        if (!send(Requests.complete(text, false, id))) return false
        completions[id] = onResult
        return true
    }

    fun commandLineShown() {
        commandLine = null
    }

    fun sendErrorShown() {
        sendError = null
    }

    /** Connects the engine to a game and remembers the address for [reconnect]. False as for [send]. */
    fun connect(host: String, port: Int, tls: Boolean): Boolean {
        rememberAddress(GameAddress(host, port, tls))
        return send(Requests.connect(host, port, tls))
    }

    private fun rememberAddress(address: GameAddress) {
        lastAddress = address
        prefs().edit {
            putString(PREF_LAST_HOST, address.host)
            putInt(PREF_LAST_PORT, address.port)
            putBoolean(PREF_LAST_TLS, address.tls)
        }
    }

    /** Connects to [lastAddress] again; false when there is none or the request was dropped. */
    fun reconnect(): Boolean {
        val address = lastAddress ?: return false
        return send(Requests.connect(address.host, address.port, address.tls))
    }

    fun disconnect(): Boolean = send(Requests.disconnect())

    fun typedLineMoved() {
        moveTypedLine = false
    }

    /** Changes [setting] and, if it is one of [Settings.mudletSettings], gives the profile that is loaded the new value. */
    fun <T> setMudletSetting(setting: Settings.Setting<T>, value: T) {
        val old = setting.value
        setting.set(value)
        if (setting.mudlet && setting.value != old) sendMudletSettings(listOf(setting))
    }

    // Lost when no profile is loaded or the link is down: the next profile to load, or the next attach, gets them all
    private fun sendMudletSettings(settings: List<Settings.Setting<*>>) {
        if (status["profile"]?.endsWith(" loaded") != true) return
        val id = newRequestId()
        settingsRequests.add(id)
        if (!link.send(Requests.runLua(settings.joinToString("\n") { it.setConfigLua() }, id))) settingsRequests.remove(id)
    }

    fun setScreenSize(columns: Int, rows: Int) {
        val size = columns to rows
        if (size == screenSize) return
        screenSize = size
        if (attached) link.send(Requests.setScreenSize(columns, rows))
    }

    // Each answer comes to [onAnswer] on the main thread: null once the engine has done it, else why not
    fun saveProfile(profile: Profile, onAnswer: (error: String?) -> Unit) = profileRequest(onAnswer) { Requests.saveProfile(profile, it) }

    fun deleteProfile(name: String, onAnswer: (error: String?) -> Unit) = profileRequest(onAnswer) { Requests.deleteProfile(name, it) }

    /** Opens and connects the profile [name]; once the engine has, Reconnect goes to its address. */
    fun openProfile(name: String, password: String?, onAnswer: (error: String?) -> Unit) = profileRequest({ error ->
        if (error == null) {
            profiles?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { rememberAddress(GameAddress(it.host, it.port, it.tls)) }
        }
        onAnswer(error)
    }) { Requests.openProfile(name, password, true, it) }

    /** Saves [profile], then opens it if the engine took it. */
    fun saveAndOpenProfile(profile: Profile, password: String?, onAnswer: (error: String?) -> Unit) = saveProfile(profile) { error ->
        if (error != null) onAnswer(error) else openProfile(profile.name, password, onAnswer)
    }

    private fun profileRequest(onAnswer: (String?) -> Unit, request: (Long) -> JSONObject) {
        val id = newRequestId()
        profileAnswers[id] = onAnswer
        if (!send(request(id))) {
            profileAnswers.remove(id)
            onAnswer(app.getString(R.string.profiles_error_not_attached))
        }
    }

    // False when [replyTo] is no profile request's id
    private fun answerProfileRequest(replyTo: Any?, error: String?): Boolean {
        val id = (replyTo as? Number)?.toLong() ?: return false
        val onAnswer = profileAnswers.remove(id) ?: return false
        onAnswer(error)
        return true
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
            replaying = true
            // Read here, as the link has it now: what is sent before the post runs goes out on this connection
            val connection = link.connection
            // The engine forgets nothing across a reattach, but a new engine instance needs the size again
            screenSize?.let { (columns, rows) -> link.send(Requests.setScreenSize(columns, rows)) }
            // Another UI or a restore may have changed them meanwhile
            link.send(Requests.listProfiles(newRequestId()))
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
                    moveTypedLine = false
                }
                linkState = "attached to pid ${hello.pid} (${if (sameInstance) "same engine, delta replay" else "new engine instance"})"
                if (!sameInstance) tail = null
                if (!sameInstance && lines.isNotEmpty()) {
                    addLines(listOf(UiLine(app.getString(R.string.marker_new_engine, hello.instance), IntArray(0), false, marker = true)))
                }
                ScriptWindowsClient.onAttached(sameInstance, connection)
            }
        }

        override fun onMessage(message: EngineMessage) {
            when (message) {
                is EngineMessage.Line -> synchronized(pendingLock) {
                    pendingLines.add(UiLine(message.text, message.runs, message.prompt))
                    if (!message.replay) pendingNews.add(message.text)
                    scheduleFlush()
                }

                is EngineMessage.Clear -> synchronized(pendingLock) {
                    pendingLines.clear()
                    pendingNews.clear()
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

                is EngineMessage.Synced -> replaying = false

                is EngineMessage.Status -> if (message.key == "completion") {
                    val id = (message.replyTo as? Number)?.toLong()
                    if (id != null) post { completions.remove(id)?.invoke(message.value) }
                } else if (message.key == "luaResult" && (message.replyTo as? Number)?.toLong()?.let(settingsRequests::remove) == true) {
                    // libmudlet answers "ran", the stand-in nothing, when the chunk ran
                    if (message.value != "ran" && message.value.isNotEmpty()) Log.w(TAG, "Mudlet did not take the app's settings: ${message.value}")
                } else {
                    if (message.key == "sendError" && !replaying && inputSentAt > 0 && SystemClock.elapsedRealtime() - inputSentAt < SEND_ERROR_WINDOW_MS) {
                        post { sendError = message.value }
                    }
                    synchronized(pendingLock) {
                        pendingStatus[message.key] = message.value
                        scheduleFlush()
                    }
                }

                is EngineMessage.HiddenInput -> post { hiddenInput = message.wanted }

                is EngineMessage.History -> post { history = message.lines }

                is EngineMessage.CommandLine -> post { onCommandLine(message.text) }

                is EngineMessage.Announce -> post { Announcer.announce(message.text, message.assertive) }

                is EngineMessage.Windows, is EngineMessage.WindowText, is EngineMessage.MainArea, is EngineMessage.WindowCommandLine, is EngineMessage.Label,
                is EngineMessage.TextBox, is EngineMessage.ButtonBars, is EngineMessage.Commands, is EngineMessage.Link,
                -> post { ScriptWindowsClient.onMessage(message) }

                is EngineMessage.Image -> {
                    val bitmap = ScriptWindowsClient.decode(message)
                    post { ScriptWindowsClient.onImage(message, bitmap) }
                }

                is EngineMessage.Map -> {
                    val bitmap = ScriptWindowsClient.decode(message)
                    post { ScriptWindowsClient.onMap(message, bitmap) }
                }

                is EngineMessage.AppMap -> {
                    val bitmap = AppMapClient.decode(message)
                    post { AppMapClient.onAppMap(message, bitmap) }
                }

                is EngineMessage.Rooms -> post { AppMapClient.onRooms(message) }

                is EngineMessage.MapDownload -> post { AppMapClient.onMapDownload(message, app.resources) }

                is EngineMessage.Notify -> post { notifyCount++ }

                is EngineMessage.MoveTypedLine -> post { moveTypedLine = true }

                is EngineMessage.Gap -> post {
                    gapLines += message.skipped
                    synchronized(pendingLock) {
                        val skipped = message.skipped
                        val text = app.resources.getQuantityString(R.plurals.marker_lines_skipped, skipped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), skipped)
                        pendingLines.add(UiLine(text, IntArray(0), false, true))
                        scheduleFlush()
                    }
                }

                is EngineMessage.Error -> post {
                    if (!answerProfileRequest(message.replyTo, message.message)) linkState = "engine error: ${message.message}"
                    ScriptWindowsClient.onError(message)
                }

                is EngineMessage.Profiles -> post {
                    profiles = message.profiles
                    answerProfileRequest(message.replyTo, null)
                }

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
                // Their answers went with the connection
                val unanswered = profileAnswers.values.toList()
                profileAnswers.clear()
                unanswered.forEach { it(app.getString(R.string.profiles_error_engine_lost)) }
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
        val news: List<String>
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
            news = pendingNews
            pendingNews = ArrayList()
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
        if (news.isNotEmpty()) Announcer.newLines(news)
        // Also when an attach resends the profile status: a setting may have changed while the link was down
        if (newStatus["profile"]?.endsWith(" loaded") == true) sendMudletSettings(Settings.mudletSettings)
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
