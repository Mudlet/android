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
import org.json.JSONObject
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineService
import org.mudlet.android.engine.EngineSession
import org.mudlet.android.engine.EngineSocket
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.EngineRestore
import org.mudlet.android.protocol.OpenProfile
import org.mudlet.android.protocol.Profile
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
 * The UI process's view of the engine: the link to its control socket, with the engine's own statuses and its
 * profiles, and a [ProfileSession] for each profile it has open (docs/multi-profile.md, "Per-profile UI state"), each
 * on that profile's socket. Lives for the UI process; attaches while an Activity is started, so a backgrounded (and
 * later frozen) UI holds no connection. State is Compose snapshot state, written only on the main thread.
 */
// It holds only the application's Context (app), which leaks nothing
@SuppressLint("StaticFieldLeak")
object EngineClient {
    // About 300 ms of failed connects at EngineLink's fast retry interval
    private const val RESTART_AFTER_FAILED_ATTEMPTS = 6

    private const val PREF_LAST_PROFILE = "lastProfile"

    /** The engine's own statuses, from its control socket; each profile's are its [ProfileSession.status]. */
    val engineStatus = mutableStateMapOf<String, String>()

    /** The profile last opened, kept across UI process restarts: what Reconnect opens again while none is open. */
    var lastProfile by mutableStateOf<String?>(null)
        private set

    /** Whether Reconnect has something to do: [front]'s address, or, while no profile is open, [lastProfile]. */
    val canReconnect: Boolean get() = frontSession?.let { it.lastAddress != null } ?: (lastProfile != null)
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

    /** The engine's profiles, the last opened first; null until it has listed them. */
    var profiles by mutableStateOf<List<Profile>?>(null)
        private set

    /** A session for each profile the engine has open, in the order they were opened. */
    val sessions: List<ProfileSession> get() = openSessions

    private val openSessions = mutableStateListOf<ProfileSession>()

    /**
     * The profile the player is looking at: the engine's front, or the one [setFront] asked for until the engine says
     * so; while no profile is open, a session with no socket, which holds nothing.
     */
    val front: ProfileSession get() = frontSession ?: noProfile

    private var frontSession by mutableStateOf<ProfileSession?>(null)
    private val noProfile by lazy { ProfileSession("", null, app) }

    // Set by setFront until an openProfiles names it the front, so one sent before the engine had it does not undo it
    private var requestedFront: String? = null

    // A profile showProfile could not bring to the front yet, with what to do once it has, until the next openProfiles
    private var wantedFront: Pair<String, () -> Unit>? = null

    // Whether sessions is the engine's word on what is open: from its openProfiles until the link goes down
    private var openProfilesKnown = false

    // The app's own lines that came while no profile was open, for the first to open
    private val heldMarkers = ArrayList<String>()

    // The profile requests waiting for their answer, by id; main thread only
    private val profileAnswers = HashMap<Long, (String?) -> Unit>()

    private val main = Handler(Looper.getMainLooper())

    // The application's Context, which lives as long as the process
    private lateinit var app: Context

    /**
     * How a link reaches the socket at a path: the control socket's, [EngineSocket.path], or a profile's. The
     * instrumented tests (src/androidTest) put a fake engine here.
     */
    @VisibleForTesting
    internal var connector: (path: String) -> Transport = ::connectLocal

    // One link for the UI process's life: it remembers the engine instance, so a reattach after onStop/onStart (or
    // rotation) is to the same engine or to a new one
    private val link: EngineLink by lazy { EngineLink({ connector(EngineSocket.path(app)) }, listener, profileSockets = true) }

    @Volatile private var attached = false

    /** The console's columns and rows, as [setScreenSize] last had them; null until the UI has measured it. */
    @Volatile
    var screenSize: Pair<Int, Int>? = null
        private set

    // Main thread only
    private var startRequestedAt = 0L
    private var detachStartedAt = 0L
    private var lostAt = 0L
    private var restartRequestedForThisOutage = false

    var engineWanted: Boolean
        get() = prefs().getBoolean("wanted", true)
        set(value) = prefs().edit { putBoolean("wanted", value) }

    private fun prefs() = app.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        lastProfile = prefs().getString(PREF_LAST_PROFILE, null)
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
        sessions.forEach(ProfileSession::start)
        linkState = "connecting"
    }

    fun detach() {
        if (!attached) return
        attached = false
        openProfilesKnown = false
        link.stop()
        sessions.forEach(ProfileSession::stop)
        linkState = "detached (UI stopped)"
    }

    /** The open profile named [name], ignoring case as the engine does; null when it is not open. */
    fun session(name: String): ProfileSession? = sessions.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Makes the open profile [name] the front, here and in the engine. False when it is not open or the link is down. */
    fun setFront(name: String): Boolean {
        val session = session(name) ?: return false
        if (!send(Requests.setFront(session.name))) return false
        requestedFront = session.name
        moveFront(session)
        return true
    }

    /**
     * Brings the open profile [name] to the front, as a tap on one of its notifications asks, then calls [onShown].
     * While the link is down, as when the tap started the UI, the engine's next list of open profiles decides: [onShown]
     * is called then if [name] is open, and never if it is not.
     */
    fun showProfile(name: String, onShown: () -> Unit) {
        wantedFront = null
        if (setFront(name)) {
            onShown()
        } else if (!openProfilesKnown) {
            wantedFront = name to onShown
        }
    }

    /**
     * Connects [front] to its address again, on its own socket. While no profile is open, as on an engine started again
     * after the notification's Quit, opens [lastProfile] with [passwordOf] its name instead: a bare connect there would
     * have libmudlet make a profile of MUDLET_PROFILE's name with the address, a copy of the player's. Why it could not
     * goes to [onRefused].
     */
    fun reconnect(passwordOf: (profile: String) -> String?, onRefused: (error: String) -> Unit) {
        val session = frontSession
        if (session != null) {
            if (session.lastAddress != null && !session.reconnect()) onRefused(app.getString(R.string.profiles_error_not_attached))
            return
        }
        val name = lastProfile ?: return
        openProfile(name, passwordOf(name)) { error -> if (error != null) onRefused(error) }
    }

    private fun moveFront(session: ProfileSession?) {
        val was = frontSession
        if (session === was) return
        frontSession = session
        // Only the front profile draws the app's map
        was?.appMap?.update()
        session?.appMap?.update()
        if (session != null && heldMarkers.isNotEmpty()) {
            heldMarkers.forEach(session::addMarker)
            heldMarkers.clear()
        }
    }

    // False when the link is down and the request was dropped
    private fun send(message: JSONObject): Boolean {
        val sent = link.send(message)
        if (!sent) linkState = "not attached: command dropped"
        return sent
    }

    /** Changes [setting] and, if it is one of [Settings.mudletSettings], gives each profile that is loaded the new value. */
    fun <T> setMudletSetting(setting: Settings.Setting<T>, value: T) {
        val old = setting.value
        setting.set(value)
        if (setting.mudlet && setting.value != old) sessions.forEach { it.sendMudletSettings(listOf(setting)) }
    }

    /** The console's size, which every open profile is given: all of them would show on this screen. */
    fun setScreenSize(columns: Int, rows: Int) {
        val size = columns to rows
        if (size == screenSize) return
        screenSize = size
        if (attached) sessions.forEach { it.sendScreenSize(size) }
    }

    // Each answer comes to [onAnswer] on the main thread: null once the engine has done it, else why not
    fun saveProfile(profile: Profile, onAnswer: (error: String?) -> Unit) = profileRequest(onAnswer) { Requests.saveProfile(profile, it) }

    fun deleteProfile(name: String, onAnswer: (error: String?) -> Unit) = profileRequest(onAnswer) { Requests.deleteProfile(name, it) }

    /** Opens and connects the profile [name], closing those open; Reconnect then goes to its address, or opens it again once none is. */
    fun openProfile(name: String, password: String?, onAnswer: (error: String?) -> Unit) = profileRequest({ error ->
        if (error == null) {
            lastProfile = name
            prefs().edit { putString(PREF_LAST_PROFILE, name) }
            session(name)?.forgetAddress() ?: ProfileSession.forgetAddress(prefs(), name)
        }
        onAnswer(error)
    }) { Requests.openProfile(name, password, connect = true, alongside = false, id = it) }

    /** Saves [profile], then opens it if the engine took it. */
    fun saveAndOpenProfile(profile: Profile, password: String?, onAnswer: (error: String?) -> Unit) = saveProfile(profile) { error ->
        if (error != null) onAnswer(error) else openProfile(profile.name, password, onAnswer)
    }

    private fun profileRequest(onAnswer: (String?) -> Unit, request: (Long) -> JSONObject) {
        val id = link.newRequestId()
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

    // A session for each profile in [open], kept for one that stays open, and the front as the engine has it
    private fun onOpenProfiles(open: List<OpenProfile>) {
        openProfilesKnown = true
        val kept = open.map { profile ->
            sessions.firstOrNull { it.name == profile.name }?.also { it.moveTo(profile.socket, attached) }
                ?: ProfileSession(profile.name, profile.socket, app).also { if (attached) it.start() }
        }
        openSessions.filter { it !in kept }.forEach(ProfileSession::stop)
        openSessions.clear()
        openSessions.addAll(kept)
        val engineFront = open.indexOfFirst { it.front }.let { kept.getOrNull(it) }
        val requested = requestedFront?.let { name -> kept.firstOrNull { it.name == name } }
        if (requested == null || requested === engineFront) requestedFront = null
        moveFront(requested ?: engineFront ?: kept.firstOrNull())
        wantedFront?.let { (name, onShown) ->
            wantedFront = null
            if (setFront(name)) onShown()
        }
    }

    // The engine's status restore tells, in the front profile's console, how bringing the profiles back went
    private fun onEngineStatus(key: String, value: String) {
        if (key == EngineRestore.STATUS_KEY) {
            EngineRestore.marker(engineStatus[key], value)?.let { marker ->
                val session = frontSession
                if (session != null) session.addMarker(marker) else heldMarkers.add(marker)
            }
        }
        engineStatus[key] = value
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

    private fun connectLocal(path: String): Transport {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
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
            // Another UI or a restore may have changed them meanwhile
            link.send(Requests.listProfiles(link.newRequestId()))
            main.post {
                // The engine resends its whole status after hello, so the old instance's values must go first
                if (!sameInstance) engineStatus.clear()
                // What it says is in front now stands: it had any setFront sent before
                requestedFront = null
                // A profile's socket that went with the engine may be back: no need to wait out the link's retry
                sessions.forEach(ProfileSession::wake)
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
                if (!sameInstance) {
                    notifyCount = 0
                    pingResult = ""
                }
                linkState = "attached to pid ${hello.pid} (${if (sameInstance) "same engine, delta replay" else "new engine instance"})"
            }
        }

        override fun onMessage(message: EngineMessage) {
            when (message) {
                is EngineMessage.Status -> main.post { onEngineStatus(message.key, message.value) }

                is EngineMessage.Error -> main.post {
                    if (!answerProfileRequest(message.replyTo, message.message)) linkState = "engine error: ${message.message}"
                }

                is EngineMessage.Profiles -> main.post {
                    profiles = message.profiles
                    answerProfileRequest(message.replyTo, null)
                }

                // The sessions first, so that an openProfile's answer finds the profile open
                is EngineMessage.OpenProfiles -> main.post {
                    onOpenProfiles(message.profiles)
                    answerProfileRequest(message.replyTo, null)
                }

                else -> {}
            }
        }

        override fun onDetached(reason: String) {
            main.post {
                openProfilesKnown = false
                engineStatus.clear()
                engineLosses++
                lostAt = SystemClock.elapsedRealtime()
                detachStartedAt = lostAt
                enginePid = 0
                linkState = "engine connection lost ($reason); reconnecting"
                // Their answers went with the connection
                val unanswered = profileAnswers.values.toList()
                profileAnswers.clear()
                unanswered.forEach { it(app.getString(R.string.profiles_error_engine_lost)) }
            }
        }

        override fun onConnectFailed(attempt: Int, error: IOException) {
            main.post {
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
}
