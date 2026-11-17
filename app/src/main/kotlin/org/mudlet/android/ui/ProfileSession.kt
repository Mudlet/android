package org.mudlet.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.OpenProfile
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.protocol.Requests
import org.mudlet.android.ui.game.Announcer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One open profile's part of the UI process (docs/multi-profile.md, "Per-profile UI state"): the link to the profile's
 * own socket and what came over it, its console's lines and statuses, the input line's state, its script windows
 * ([windows]) and its map ([appMap]). [EngineClient] keeps one for each profile the engine has open, and one with no
 * socket ([name] empty) for the game screen while none is. State is Compose snapshot state, written only on the main
 * thread.
 */
class ProfileSession internal constructor(
    /** The profile's name, as the engine's `openProfiles` gives it. */
    val name: String,
    socket: String?,
    private val app: Context,
) {
    val lines = mutableStateListOf<UiLine>()

    /**
     * How many lines have been added to [lines] in this process, markers included: `lines[i]` is line number
     * `linesAdded - lines.size + i`, a number that stays with the line while older ones are dropped.
     */
    var linesAdded by mutableLongStateOf(0L)
        private set

    /** How many lines came after the player last saw this game, as the engine counts them (`openProfiles`). */
    var unread by mutableLongStateOf(0L)
        private set

    /** The game asked for the player's attention, by a script's `alert()` or the bell, and has not been seen since. */
    var attention by mutableStateOf(false)
        private set

    // The engine's last line of this profile, as the last `openProfiles` gave it: what a markSeen sees up to
    internal var engineLastSeq = 0L
        private set

    // Whether "New activity in" has been said for the attention it wants now
    private var attentionSaid = false

    /**
     * What Reconnect connects to: the last address [connect] was given, kept across UI process restarts, else the
     * profile's own as the engine lists it.
     */
    val lastAddress: GameAddress?
        get() = rememberedAddress ?: EngineClient.profiles?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { GameAddress(it.host, it.port, it.tls) }

    private var rememberedAddress by mutableStateOf<GameAddress?>(null)

    /**
     * The connection ended without the player's Disconnect, as when the game or the network dropped it, until it is
     * connected again or the player connects or disconnects. A script's `disconnect()` reads as a drop too.
     */
    var dropped by mutableStateOf(false)
        private set

    // The player asked to disconnect, so the end of the connection that follows is no drop; main thread only
    private var disconnectAsked = false

    /** The profile's "Reconnect automatically", as the engine lists it: Mudlet dials again by itself after a drop. */
    val autoReconnect: Boolean
        get() = EngineClient.profiles?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.autoReconnect == true

    // The engine's line still being written, such as a prompt, shown below the others
    var tail by mutableStateOf<UiLine?>(null)
        private set

    /** The profile's statuses, as its socket last gave them; the engine's own are [EngineClient.engineStatus]. */
    val status = mutableStateMapOf<String, String>()

    /** The profile's settings, by Mudlet's `getConfig()` keys, as the engine last gave them; [setting] reads one. */
    val settings = mutableStateMapOf<String, Any>()

    /**
     * A setting changed while the game is connected waits for its next connection ([Settings.readAsItConnects]), until
     * the settings screen has said so and calls [settingAwaitsConnectionShown], or the connection ends.
     */
    var settingAwaitsConnection by mutableStateOf(false)
        private set

    /** How this session's link to its socket stands, for the smoke test's errors and the tests. */
    var linkState by mutableStateOf("not attached")
        private set

    /** How many times the link has attached: what was sent to the engine before is sent again after each. */
    var attaches by mutableIntStateOf(0)
        private set

    var lastLuaResult by mutableStateOf("")
        private set

    /** The game hides what the player types, as for a password: the input line masks it and sends it with [sendHidden]. */
    var hiddenInput by mutableStateOf(false)
        private set

    /** The game offers its secure port and waits for [answerSecureOffer], as desktop Mudlet asks. */
    var secureOffer by mutableStateOf(false)
        private set

    /** The player put that offer off without answering: not asked again until the game offers it again. */
    var secureOfferPutOff by mutableStateOf(false)
        private set

    /** Why the engine did not take the last [answerSecureOffer], until [secureOfferRefusalShown]. */
    var secureOfferRefusal by mutableStateOf<String?>(null)
        private set

    /** The game's certificate the last secure connection was refused for, as the engine says, until the next is tried. */
    var refusedCertificate by mutableStateOf<RefusedCertificate?>(null)
        private set

    /** The player answered or put off [refusedCertificate]: not asked again until another is refused. */
    var refusedCertificatePutOff by mutableStateOf(false)
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

    /** The profile whose suggestions the input line shows: this one, unless the engine's status `profile` named another as loaded. */
    var suggestionsProfile by mutableStateOf(name.ifEmpty { NO_PROFILE_SUGGESTIONS })
        private set

    /** The windows, button bars, commands and map window its scripts draw. */
    val windows = ScriptWindowsClient(this)

    /** The app's own map of this profile. */
    internal val appMap = AppMapClient(this)

    /** Its triggers, aliases, scripts, timers, keys and buttons, as the script library shows and edits them. */
    internal val scripts = ScriptItemsClient(::newRequestId, ::send)

    /** The text sizes the player pinched this profile's script consoles to. */
    internal val consoleZoom = ConsoleZoomClient(name, ::prefs, ::runAppLua)

    // The socket the link connects to, which a profile reopened in another slot changes; null for none
    @Volatile private var socket: String? = socket

    private val main = Handler(Looper.getMainLooper())
    private val link: EngineLink? = if (socket == null) null else EngineLink({ EngineClient.connector(this.socket!!) }, listener())

    // When an input request last went out; the engine keeps sendError in its status, which it sends again on attach
    @Volatile private var inputSentAt = 0L

    // Reader thread only: from hello to synced, the engine resends what it holds, which is not news
    private var replaying = false

    // Set while the socket answers for another profile, as one whose slot another took before the control socket said
    @Volatile private var misdirected = false

    // Main thread only
    private val completions = HashMap<Long, (String) -> Unit>()
    private val sendsAwaitingClear = ArrayDeque<Long>()

    // The number (linesAdded) after the last console line given to the suggestions, so none is given twice
    internal var linesSuggested = 0L

    // The ids of the runLua requests that carry the app's own Lua, such as a script console's pinched size, whose luaResult is no script's
    private val appLuaRequests: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // The ids of the getSettings and setSettings requests not yet answered, and of the setSettings among them that give
    // the game the app's values (or the input line's clearing), which it has once that is answered
    private val settingsRequests: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val defaultsRequests: MutableSet<Long> = ConcurrentHashMap.newKeySet()

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

    init {
        val p = prefs()
        val host = p.getString(PREF_LAST_HOST + name, null)
        if (host != null) rememberedAddress = GameAddress(host, p.getInt(PREF_LAST_PORT + name, 23), p.getBoolean(PREF_LAST_TLS + name, false))
    }

    private fun prefs() = app.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)

    internal fun start() {
        link?.start()
        linkState = "connecting"
    }

    internal fun stop() {
        link?.stop()
        linkState = "detached"
    }

    /** Connects at once if the link is waiting to try again. */
    internal fun wake() {
        link?.wake()
    }

    /** The profile is now served on [path]: a link to the old socket is dropped, and one that runs reconnects there. */
    internal fun moveTo(path: String, running: Boolean) {
        if (path == socket) return
        // Stopped first: the link's thread reads the socket at each try, and one still trying would say hello on the new one
        if (running) stop()
        socket = path
        misdirected = false
        if (running) start()
    }

    /** The `id` for a request on this profile's socket, whoever sends it: see [EngineLink.newRequestId]. */
    fun newRequestId(): Long = link?.newRequestId() ?: 0L

    /** False when the link is down and the request was dropped. */
    fun send(message: JSONObject): Boolean = sendOn(message) != 0

    /** As [send], returning the number of the link's connection the request went out on, or 0 when it was dropped. */
    fun sendOn(message: JSONObject): Int {
        val connection = if (misdirected) 0 else link?.sendOn(message) ?: 0
        if (connection == 0) linkState = "not attached: command dropped"
        return connection
    }

    // A request the engine answers with status sendError when it cannot carry it out
    private fun sendInput(message: JSONObject): Boolean {
        if (!send(message)) return false
        inputSentAt = SystemClock.elapsedRealtime()
        return true
    }

    /** A command typed on the input line: sent, and put first in [history]. False as for [send]. */
    fun sendCommand(line: String): Boolean {
        // A script the command runs reads what the player typed in the text boxes
        windows.flushTyping()
        if (!sendInput(Requests.send(line))) return false
        // Mudlet empties its line after a send only for a profile that has it cleared
        if (setting(Settings.clearInputAfterSend)) {
            val now = SystemClock.elapsedRealtime()
            dropExpiredSends(now)
            sendsAwaitingClear.addLast(now)
        }
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

    fun typedLineMoved() {
        moveTypedLine = false
    }

    /**
     * Answers [secureOffer] as desktop Mudlet's Yes and No do. Either, once taken, closes the connection, which is the player's
     * doing, not a drop; a yes moves the profile to the secure port, which Reconnect then goes to. False as for [send].
     */
    fun answerSecureOffer(accept: Boolean): Boolean {
        if (!sendConnection(Requests.answerSecureOffer(accept), disconnecting = true)) return false
        if (accept) forgetAddress()
        return true
    }

    fun putOffSecureOffer() {
        secureOfferPutOff = true
    }

    fun secureOfferRefusalShown() {
        secureOfferRefusal = null
    }

    /**
     * Lets past, for this game, what [refusedCertificate] was refused for, as desktop Mudlet's "Accept expired
     * certificates" and "Accept self-signed certificates" do; they stay on in the game's settings until the player turns
     * them off. False, changing nothing more, for a certificate with [RefusedCertificate.others] or as [changeSetting].
     */
    fun acceptRefusedCertificate(): Boolean {
        val certificate = refusedCertificate ?: return false
        if (certificate.others) return false
        if (certificate.expired && !changeSetting(Settings.acceptExpiredCertificate, true)) return false
        if (certificate.selfSigned && !changeSetting(Settings.acceptSelfSignedCertificate, true)) return false
        refusedCertificatePutOff = true
        return true
    }

    fun putOffRefusedCertificate() {
        refusedCertificatePutOff = true
    }

    /** Connects the profile to a game and remembers the address for [reconnect]. False as for [send]. */
    fun connect(host: String, port: Int, tls: Boolean): Boolean {
        rememberAddress(GameAddress(host, port, tls))
        return sendConnection(Requests.connect(host, port, tls), disconnecting = false)
    }

    private fun rememberAddress(address: GameAddress) {
        rememberedAddress = address
        prefs().edit {
            putString(PREF_LAST_HOST + name, address.host)
            putInt(PREF_LAST_PORT + name, address.port)
            putBoolean(PREF_LAST_TLS + name, address.tls)
        }
    }

    /** Forgets the address [connect] was given, so that Reconnect goes to the profile's own. */
    internal fun forgetAddress() {
        rememberedAddress = null
        forgetAddress(prefs(), name)
    }

    /** Connects to [lastAddress] again; false when there is none or the request was dropped. */
    fun reconnect(): Boolean {
        val address = lastAddress ?: return false
        return sendConnection(Requests.connect(address.host, address.port, address.tls), disconnecting = false)
    }

    fun disconnect(): Boolean = sendConnection(Requests.disconnect(), disconnecting = true)

    // The player has acted on the connection, which a drop's state gives way to
    private fun sendConnection(message: JSONObject, disconnecting: Boolean): Boolean {
        if (!send(message)) return false
        dropped = false
        disconnectAsked = disconnecting
        return true
    }

    // Only a change seen as it happens counts: an attach resends the status from scratch, which is no drop
    private fun connectionChanged(was: String?, now: String) {
        if (now == CONNECTED) {
            dropped = false
            disconnectAsked = false
            return
        }
        settingAwaitsConnection = false
        if (was == CONNECTED) {
            dropped = !disconnectAsked
            disconnectAsked = false
        }
    }

    /** Runs Lua of the app's own in the profile, whose luaResult is no script's; false when it could not go. */
    internal fun runAppLua(code: String): Boolean {
        val id = newRequestId()
        appLuaRequests.add(id)
        if (link?.send(Requests.runLua(code, id)) == true) return true
        appLuaRequests.remove(id)
        return false
    }

    /** This game's value of [setting], one of [Settings.mudletSettings], as Mudlet last gave it; until then, the app's. */
    fun <T> setting(setting: Settings.Setting<T>): T = settings[setting.key]?.let(setting::fromMudlet) ?: setting.value

    /**
     * Changes this game's [setting] to [value], shown at once and put right by the engine's answer; false, changing
     * nothing, while its profile has not loaded or the link is down.
     */
    fun <T> changeSetting(setting: Settings.Setting<T>, value: T): Boolean {
        if (!settingsAvailable) return false
        val mudletValue = setting.toMudlet(value)
        if (!sendSettings(Requests.setSettings(mapOf(setting.key to mudletValue), newRequestId()))) return false
        settings[setting.key] = mudletValue
        if (setting in Settings.readAsItConnects && status["connection"] == CONNECTED) settingAwaitsConnection = true
        return true
    }

    fun settingAwaitsConnectionShown() {
        settingAwaitsConnection = false
    }

    /** Asks Mudlet for this game's settings again, as a script may have changed them; nothing while it has not loaded. */
    fun refreshSettings() {
        if (settingsAvailable) sendSettings(Requests.getSettings(Settings.mudletSettings.map { it.key }, newRequestId()))
    }

    /** Whether this game's settings can be read and changed: once its profile has loaded. */
    val settingsAvailable: Boolean get() = status["profile"]?.endsWith(LOADED) == true

    // Each game is asked for its settings as it loads, a game just added in the app first given its defaults, and one
    // that was there before the input line could keep a sent command told to go on clearing it, as the app did
    private fun onLoaded() {
        when {
            Settings.awaitsDefaults(name) -> giveDefaults()
            Settings.awaitsInputClear(name) -> give(mapOf(Settings.clearInputAfterSend.key to true))
        }
        refreshSettings()
    }

    // The app's values that are not what Mudlet starts a profile with, which then are the game's own: those the player
    // changed, and the app's own default where that is another. For the rest the game keeps Mudlet's, some of which
    // Mudlet picks for the game
    private fun giveDefaults() {
        val own = Settings.mudletSettings.filter { it.differsFromMudlets }
        if (own.isEmpty()) given() else give(own.associate { it.key to it.valueToMudlet() })
    }

    // The game has [values] as its own once Mudlet answers
    private fun give(values: Map<String, Any>) {
        val id = newRequestId()
        defaultsRequests.add(id)
        if (!sendSettings(Requests.setSettings(values, id))) defaultsRequests.remove(id)
    }

    private fun given() {
        Settings.setAwaitsDefaults(name, false)
        Settings.setAwaitsInputClear(name, false)
    }

    private fun sendSettings(request: JSONObject): Boolean {
        val id = request.getLong("id")
        settingsRequests.add(id)
        val sent = link?.send(request) == true
        if (!sent) settingsRequests.remove(id)
        return sent
    }

    private fun onSettings(message: EngineMessage.SettingValues) {
        val id = (message.replyTo as? Number)?.toLong()
        if (id == null || !settingsRequests.remove(id)) return
        settings.putAll(message.values)
        if (message.refused.isNotEmpty()) Log.w(TAG, "Mudlet did not take these settings: ${message.refused}")
        if (defaultsRequests.remove(id)) given()
    }

    internal fun sendScreenSize(size: Pair<Int, Int>) {
        link?.send(Requests.setScreenSize(size.first, size.second))
    }

    /** A line of the app's own, such as how a restore of the engine went. */
    internal fun addMarker(text: String) = addLines(listOf(UiLine(text, IntArray(0), false, marker = true)))

    private fun front() = EngineClient.front === this

    /**
     * The engine's count of this profile's lines from [open]. A game in the background that wants attention has TalkBack
     * say so, once until it is seen.
     */
    internal fun counted(open: OpenProfile) {
        unread = open.unread
        attention = open.attention
        engineLastSeq = open.lastSeq
        if (!attention) {
            attentionSaid = false
        } else if (!attentionSaid && !front()) {
            attentionSaid = true
            Announcer.announce(app.getString(R.string.game_attention_announce, name), assertive = false)
        }
    }

    /** The player has seen this game's lines, which a markSeen up to [engineLastSeq] tells the engine. */
    internal fun seen() {
        unread = 0
        attention = false
        attentionSaid = false
    }

    private fun listener() = object : EngineLink.Listener {
        override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) {
            misdirected = hello.profile != null && hello.profile != name
            if (misdirected) {
                post { linkState = "the socket serves ${hello.profile}, not $name" }
                return
            }
            replaying = true
            // Read here, as the link has it now: what is sent before the post runs goes out on this connection
            val connection = link?.connection ?: 0
            // The engine forgets nothing across a reattach, but a new engine instance needs the size again
            EngineClient.screenSize?.let(::sendScreenSize)
            // The engine resends its whole status after hello, so the old instance's values must go first
            if (!sameInstance) resetStatus()
            post {
                attaches++
                // The engine's answers to sends on the old connection went with it
                sendsAwaitingClear.clear()
                if (!sameInstance) {
                    lastLuaResult = ""
                    // A new engine restores the connection itself (status restore); the old one's drop is not this one's
                    dropped = false
                    disconnectAsked = false
                    moveTypedLine = false
                    tail = null
                    if (lines.isNotEmpty()) addMarker(app.getString(R.string.marker_new_engine, hello.instance))
                }
                linkState = "attached (${if (sameInstance) "same engine, delta replay" else "new engine instance"})"
                windows.onAttached(sameInstance, connection)
                scripts.onAttached(sameInstance)
            }
        }

        override fun onMessage(message: EngineMessage) {
            if (misdirected) return
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
                } else if (message.key == "luaResult" && (message.replyTo as? Number)?.toLong()?.let(appLuaRequests::remove) == true) {
                    if (message.value != "ran") Log.w(TAG, "Mudlet did not run the app's Lua: ${message.value}")
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

                is EngineMessage.SecureOffer -> post {
                    if (message.offered && !secureOffer) secureOfferPutOff = false
                    secureOffer = message.offered
                }

                is EngineMessage.CertificateRefused -> post {
                    if (message.certificate != refusedCertificate) refusedCertificatePutOff = false
                    refusedCertificate = message.certificate
                }

                is EngineMessage.History -> post { history = message.lines }

                is EngineMessage.CommandLine -> post { onCommandLine(message.text) }

                // One in the background says whose it is, and does not interrupt what the front one is saying
                is EngineMessage.Announce -> post {
                    if (front()) {
                        Announcer.announce(message.text, message.assertive)
                    } else {
                        Announcer.announce(app.getString(R.string.game_announce, name, message.text), assertive = false)
                    }
                }

                is EngineMessage.Windows, is EngineMessage.WindowText, is EngineMessage.MainArea, is EngineMessage.WindowCommandLine, is EngineMessage.Label,
                is EngineMessage.TextBox, is EngineMessage.ButtonBars, is EngineMessage.Commands, is EngineMessage.Link,
                -> post { windows.onMessage(message) }

                is EngineMessage.Image -> {
                    val bitmap = ScriptWindowsClient.decode(message)
                    post { windows.onImage(message, bitmap) }
                }

                is EngineMessage.Map -> {
                    val bitmap = ScriptWindowsClient.decode(message)
                    post { windows.onMap(message, bitmap) }
                }

                is EngineMessage.AppMap -> {
                    val frame = AppMapClient.decode(message)
                    post { appMap.onAppMap(message, frame) }
                }

                is EngineMessage.Rooms -> post { appMap.onRooms(message) }

                is EngineMessage.SettingValues -> post { onSettings(message) }

                // Each map asks with its own ids, so only the one that asked takes the answer
                is EngineMessage.Room -> post { appMap.rooms.onRoom(message) || windows.mapRooms.onRoom(message) }

                is EngineMessage.Areas -> post { appMap.rooms.onAreas(message) || windows.mapRooms.onAreas(message) }

                is EngineMessage.Envs -> post { appMap.rooms.onEnvs(message) || windows.mapRooms.onEnvs(message) }

                is EngineMessage.ScriptItems -> post { scripts.onItems(message) }

                is EngineMessage.ScriptItemFields -> post { scripts.onItem(message) }

                is EngineMessage.LuaCheck -> post { scripts.onLuaCheck(message) }

                is EngineMessage.MapDownload -> post { appMap.onMapDownload(message, app.resources, front()) }

                is EngineMessage.Notify -> post { EngineClient.notifyCount++ }

                is EngineMessage.MoveTypedLine -> post { moveTypedLine = true }

                is EngineMessage.Gap -> post {
                    EngineClient.gapLines += message.skipped
                    synchronized(pendingLock) {
                        val skipped = message.skipped
                        val text = app.resources.getQuantityString(R.plurals.marker_lines_skipped, skipped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), skipped)
                        pendingLines.add(UiLine(text, IntArray(0), false, true))
                        scheduleFlush()
                    }
                }

                is EngineMessage.Error -> post {
                    linkState = "engine error: ${message.message}"
                    val id = (message.replyTo as? Number)?.toLong()
                    val request = message.message.substringBefore(':')
                    val reason = message.message.substringAfter(':').trim()
                    if (id != null && settingsRequests.remove(id)) {
                        // The settings shown stay as they were; the next load, attach or page asks again
                        defaultsRequests.remove(id)
                        Log.w(TAG, "Mudlet's settings: ${message.message}")
                    } else if (request == "answerSecureOffer") {
                        // The connection stays as it was, so a drop after this is no answer's doing
                        disconnectAsked = false
                        secureOfferRefusal = reason
                    } else if (request in ScriptItemsClient.REQUESTS) {
                        scripts.onRefused(request, reason, message.replyTo)
                    } else {
                        windows.onError(message)
                    }
                }

                else -> {}
            }
        }

        override fun onDetached(reason: String) {
            resetStatus()
            post {
                completions.clear()
                linkState = "connection lost ($reason); reconnecting"
            }
        }

        // The control socket's link restarts an engine that is gone; this one only keeps trying
        override fun onConnectFailed(attempt: Int, error: IOException) {
            post { linkState = "not reachable (attempt $attempt: ${error.message})" }
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

    internal fun post(block: () -> Unit) {
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
        val wasConnection = status["connection"]
        status.putAll(newStatus)
        newStatus["connection"]?.let { connectionChanged(wasConnection, it) }
        newStatus["luaResult"]?.let { lastLuaResult = it }
        newStatus["profile"]?.let(::profileStatus)
        if (clear) lines.clear()
        addLines(newLines)
        if (tailSet) tail = newTail
        // Only the front profile's new lines are read out, as desktop Mudlet reads only the profile in front
        if (news.isNotEmpty() && front()) Announcer.newLines(news)
        // Also when an attach resends the profile status: a setting may have changed while the link was down
        if (newStatus["profile"]?.endsWith(LOADED) == true) onLoaded()
    }

    // Takes the profile from the engine's status `profile` ("<name> loaded"); its other values change nothing
    private fun profileStatus(value: String) {
        if (value.endsWith(LOADED) && value.length > LOADED.length) suggestionsProfile = value.removeSuffix(LOADED)
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

    companion object {
        const val MAX_LINES = 2000

        /** As [ProfileSession.forgetAddress], for the profile [name] while it has no session. */
        internal fun forgetAddress(prefs: SharedPreferences, name: String) = prefs.edit {
            remove(PREF_LAST_HOST + name)
            remove(PREF_LAST_PORT + name)
            remove(PREF_LAST_TLS + name)
        }

        private const val TAG = "ProfileSession"

        // The profile libmudlet starts when none was opened (MUDLET_PROFILE), whose suggestions the input line has
        // shown while none is
        private const val NO_PROFILE_SUGGESTIONS = "Android"
        private const val LOADED = " loaded"
        private const val CONNECTED = "connected"

        // Each followed by the profile's name
        private const val PREF_LAST_HOST = "lastHost:"
        private const val PREF_LAST_PORT = "lastPort:"
        private const val PREF_LAST_TLS = "lastTls:"

        // How long after a send the engine's emptying of its line (an empty commandLine) is taken as that send's
        private const val SEND_CLEAR_WINDOW_MS = 3000L

        // How long after a send, sendHidden or dismissHidden a status sendError is taken as its answer
        private const val SEND_ERROR_WINDOW_MS = 5000L
    }
}
