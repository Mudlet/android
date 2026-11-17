package org.mudlet.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mudlet.android.protocol.ServerEncodings
import java.io.File

/**
 * The app's own settings, kept in SharedPreferences. Each one is Compose state, read and set on the main thread. A new
 * setting is a property here, in [all] so that it loads, and an entry of the settings screen's catalogue
 * (ui/settings/SettingsCatalogue.kt). A [Setting.mudlet] one is each game's own, kept by Mudlet with its profile: here
 * it is only what a game added in the app is given as it first loads (ui/ProfileSession.kt reads the game's).
 */
object Settings {
    private const val PREFS = "settings"

    // Where the engine keeps each profile, in a directory of its name under the app's files (docs/architecture.md)
    private const val PROFILES_DIR = "mudlet/profiles"

    private var prefs: SharedPreferences? = null

    /** The console's text size in sp, so the system font scale scales it too. */
    val textSize = IntSetting("textSizeSp", default = 14, range = 8..32)

    /** "Read new text aloud": new game lines are announced while TalkBack is on, as Mudlet's own setting has it. */
    val readNewText = BoolSetting("announceIncomingText", default = true, mudlet = true)

    /** "Tell games I use a screen reader", in telnet's MTTS and NEW-ENVIRON. */
    val advertiseScreenReader = BoolSetting("advertiseScreenReader", default = false, mudlet = true)

    /** What becomes of the blank lines a game sends. */
    val blankLines = EnumSetting("blankLinesBehaviour", BlankLines.SHOW, BlankLines.entries, mudlet = true) { it.mudletValue }

    /** Whether to join the lines a game wraps itself, at [undoWrapWidth], so triggers see them whole. */
    val undoWrap = BoolSetting("undoServerWrap", default = false, mudlet = true)

    /** The column a game wraps its lines at, for [undoWrap]. */
    val undoWrapWidth = IntSetting("undoServerWrapWidth", default = 80, range = 20..500, mudlet = true)

    /** Which of the commands the player sends show in the game's text. */
    val showSentText = EnumSetting("showSentText", SentText.SCRIPT, SentText.entries, mudlet = true) { it.mudletValue }

    /** Whether each line sent ends with a line feed alone rather than a carriage return and a line feed. */
    val unixLineEndings = BoolSetting("inputLineStrictUnixEndings", default = false, mudlet = true)

    /**
     * Desktop Mudlet's "Auto clear the input line after you sent text": off, the command sent stays on the input line,
     * selected. Mudlet starts a profile with it off; the app starts a game with it on (docs/divergences.md).
     */
    val clearInputAfterSend = BoolSetting("autoClearInputLine", default = true, mudlet = true, mudletDefault = false)

    /** The text a sent line is split into commands at, as Mudlet's ";;"; none splits nothing. */
    val commandSeparator = TextSetting("commandSeparator", default = ";;", mudlet = true, blankAllowed = true)

    /** Whether a command brought back from the history is selected whole, so what is typed next replaces it. */
    val highlightHistory = BoolSetting("highlightHistory", default = true, mudlet = true)

    /** "Show passwords as you type them": no masked field when the game hides what is typed, as some hide everything. */
    val showPasswords = BoolSetting("disablePasswordMasking", default = false, mudlet = true)

    /** Whether Mudlet asks to switch to a secure connection when a game offers one. */
    val offerSecureConnection = BoolSetting("askTlsAvailable", default = true, mudlet = true)

    /** "Server data encoding": the encoding Mudlet reads the game's text in and sends in, by Mudlet's name for it. */
    val serverEncoding = TextSetting("serverEncoding", default = ServerEncodings.DEFAULT, mudlet = true)

    // The game protocols Mudlet speaks, with Mudlet's defaults
    val gmcp = BoolSetting("enableGMCP", default = true, mudlet = true)
    val msdp = BoolSetting("enableMSDP", default = false, mudlet = true)
    val mssp = BoolSetting("enableMSSP", default = true, mudlet = true)
    val msp = BoolSetting("enableMSP", default = true, mudlet = true)
    val mxp = BoolSetting("enableMXP", default = true, mudlet = true)
    val mtts = BoolSetting("enableMTTS", default = true, mudlet = true)
    val mnes = BoolSetting("enableMNES", default = false, mudlet = true)
    val naws = BoolSetting("enableNAWS", default = true, mudlet = true)
    val charset = BoolSetting("enableCHARSET", default = true, mudlet = true)
    val newEnviron = BoolSetting("enableNEWENVIRON", default = true, mudlet = true)

    /** Whether the sounds, music and videos a game plays are named in its text as they start and stop. */
    val closedCaptions = BoolSetting("enableClosedCaption", default = false, mudlet = true)

    // The workarounds desktop Mudlet has under "Special options", for games that need them
    val forceCompressionOff = BoolSetting("specialForceCompressionOff", default = false, mudlet = true)
    val forceGaOff = BoolSetting("specialForceGAOff", default = false, mudlet = true)

    /**
     * Desktop Mudlet's "Fix unnecessary linebreaks on GA servers": the line feed some games (the IRE ones) send ahead of
     * the text after a prompt is taken out. Mudlet turns it on for those as it first loads their profile.
     */
    val fixLinebreaks = BoolSetting("fixUnnecessaryLinebreaks", default = false, mudlet = true)
    val versionInTerminalType = BoolSetting("versionInTTYPE", default = false, mudlet = true)
    val forceMxpOn = BoolSetting("specialForceMXPProcessorOn", default = false, mudlet = true)
    val lazyTriggerVariables = BoolSetting("lazyCaptureGlobals", default = true, mudlet = true)

    /** Whether a script's errors are also shown in the game's text, as the app has no error console. */
    val showScriptErrors = BoolSetting("echoLuaErrors", default = false, mudlet = true)

    // The certificate errors a secure connection lets past, under "TLS/SSL secure connection" in desktop Mudlet
    val acceptExpiredCertificate = BoolSetting("sslIgnoreExpired", default = false, mudlet = true)
    val acceptSelfSignedCertificate = BoolSetting("sslIgnoreSelfSigned", default = false, mudlet = true)
    val acceptAnyCertificate = BoolSetting("sslIgnoreAll", default = false, mudlet = true)

    // Whether the game may install its own packages (its interface) and play its own sounds and music over GMCP
    val gameInstallsPackages = BoolSetting("acceptServerGUI", default = true, mudlet = true)
    val gamePlaysMedia = BoolSetting("acceptServerMedia", default = true, mudlet = true)

    // Desktop Mudlet's mutes, under its toolbar's Sound button: Mudlet's own, so every open game shares them
    val muteScripts = BoolSetting("muteMediaAPI", default = false, mudlet = true)
    val muteGame = BoolSetting("muteMediaGame", default = false, mudlet = true)

    /** Whether the first run with TalkBack on has asked about [advertiseScreenReader] yet. */
    val screenReaderOffered = BoolSetting("screenReaderOffered", default = false)

    /** "Map behind the text": the profile's map, faint, behind the game's text (docs/architecture.md, "The map behind the text"). */
    val mapBehindText = BoolSetting("mapBehindText", default = true)

    /** "Colours from my wallpaper": the app's colours from the wallpaper (Material You), on Android 12 and later. */
    val wallpaperColours = BoolSetting("wallpaperColours", default = true)

    private val all = listOf(
        textSize,
        readNewText,
        advertiseScreenReader,
        blankLines,
        undoWrap,
        undoWrapWidth,
        showSentText,
        unixLineEndings,
        clearInputAfterSend,
        commandSeparator,
        highlightHistory,
        showPasswords,
        offerSecureConnection,
        serverEncoding,
        gmcp,
        msdp,
        mssp,
        msp,
        mxp,
        mtts,
        mnes,
        naws,
        charset,
        newEnviron,
        closedCaptions,
        forceCompressionOff,
        forceGaOff,
        fixLinebreaks,
        versionInTerminalType,
        forceMxpOn,
        lazyTriggerVariables,
        showScriptErrors,
        acceptExpiredCertificate,
        acceptSelfSignedCertificate,
        acceptAnyCertificate,
        gameInstallsPackages,
        gamePlaysMedia,
        muteScripts,
        muteGame,
        screenReaderOffered,
        mapBehindText,
        wallpaperColours,
    )

    /** The settings Mudlet keeps with each profile, whose app values a game added in the app is given. */
    val mudletSettings = all.filter { it.mudlet }

    /**
     * Those of [mudletSettings] that Mudlet reads only as a game connects (as it negotiates telnet's options, or makes
     * a secure connection), so that one changed while the game is connected waits for its next connection.
     */
    val readAsItConnects: Set<Setting<*>> = setOf(
        offerSecureConnection,
        gmcp,
        msdp,
        mssp,
        msp,
        mxp,
        mtts,
        mnes,
        naws,
        charset,
        newEnviron,
        forceCompressionOff,
        forceGaOff,
        versionInTerminalType,
        acceptExpiredCertificate,
        acceptSelfSignedCertificate,
        acceptAnyCertificate,
    )

    // The games added in the app that have not had the app's values of mudletSettings yet, by name. A game the app did
    // not add, such as one copied from desktop Mudlet, keeps its own from the start.
    private const val GAMES_AWAITING_DEFAULTS = "gamesAwaitingDefaults"

    // The games that were there before the app had [clearInputAfterSend], by name: the app emptied the input line of
    // each whatever its profile said, which its profile is made to say as it next loads
    private const val GAMES_AWAITING_INPUT_CLEAR = "gamesAwaitingInputClear"

    /** Whether the game [name] is to be given the app's values of [mudletSettings] as it loads. */
    fun awaitsDefaults(name: String): Boolean = name in games(GAMES_AWAITING_DEFAULTS)

    /** Marks the game [name] as to be given them, as when it is added, or not, once it has them or is deleted. */
    fun setAwaitsDefaults(name: String, awaits: Boolean) = setGame(GAMES_AWAITING_DEFAULTS, name, awaits)

    /** Whether the game [name], there before the input line could keep a sent command, is yet to be told to clear it. */
    fun awaitsInputClear(name: String): Boolean = name in games(GAMES_AWAITING_INPUT_CLEAR)

    /** Marks the game [name] as yet to be told, or not, once it is, has its own value or is deleted. */
    fun setAwaitsInputClear(name: String, awaits: Boolean) = setGame(GAMES_AWAITING_INPUT_CLEAR, name, awaits)

    private fun games(key: String): Set<String> = prefs?.getStringSet(key, null).orEmpty()

    private fun setGame(key: String, name: String, among: Boolean) {
        val p = prefs ?: return
        val games = games(key)
        if ((name in games) == among) return
        p.edit { putStringSet(key, if (among) games + name else games - name) }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        all.forEach { it.load(p) }
        noteGamesBeforeInputClear(p, File(context.filesDir, PROFILES_DIR))
    }

    /**
     * Once, as the app first runs with [clearInputAfterSend]: the games whose profiles are in [profiles] are those
     * the app has emptied the input line of, and a game that comes later starts with its own value.
     */
    @VisibleForTesting
    internal fun noteGamesBeforeInputClear(prefs: SharedPreferences, profiles: File) {
        if (prefs.contains(GAMES_AWAITING_INPUT_CLEAR)) return
        prefs.edit { putStringSet(GAMES_AWAITING_INPUT_CLEAR, profiles.list()?.toSet().orEmpty()) }
    }

    /** The values upstream Mudlet's `setConfig("blankLinesBehaviour", ...)` takes. */
    enum class BlankLines(val mudletValue: String) {
        SHOW("show"),
        HIDE("hide"),
        SPACE("replacewithspace"),
    }

    /** The values `setConfig("showSentText", ...)` takes; SCRIPT shows them unless a script sends them hidden. */
    enum class SentText(val mudletValue: String) {
        SCRIPT("script"),
        ALWAYS("always"),
        NEVER("never"),
    }

    /**
     * One setting: [value] is the current one, [set] changes and stores it. A [mudlet] setting's [key] is also the
     * name of the `getConfig()` and `setConfig()` key it has in Mudlet, which starts a profile with [mudletDefault].
     */
    abstract class Setting<T>(val key: String, val default: T, val mudlet: Boolean = false, val mudletDefault: T = default) {
        var value by mutableStateOf(default)
            private set

        fun set(new: T) {
            val v = normalise(new)
            if (v == value) return
            value = v
            prefs?.edit { write(this, v) }
        }

        @VisibleForTesting
        fun reset() = set(default)

        /** [v] as Mudlet's `setConfig()` takes it and `getConfig()` gives it. */
        open fun toMudlet(v: T): Any = v as Any

        /** [value] as Mudlet's `setConfig()` takes it. */
        fun valueToMudlet(): Any = toMudlet(value)

        /** Whether [value] is other than what Mudlet starts a profile with. */
        val differsFromMudlets get() = value != mudletDefault

        /** What `getConfig()` gave, or null for a value this setting cannot have. */
        abstract fun fromMudlet(v: Any): T?

        internal fun load(prefs: SharedPreferences) {
            value = normalise(read(prefs))
        }

        protected open fun normalise(v: T): T = v

        protected abstract fun read(prefs: SharedPreferences): T

        protected abstract fun write(editor: SharedPreferences.Editor, v: T)
    }

    /** A whole number within [range]: a value outside it is taken as the nearest end. */
    class IntSetting(key: String, default: Int, val range: IntRange, mudlet: Boolean = false) : Setting<Int>(key, default, mudlet) {
        override fun normalise(v: Int) = v.coerceIn(range)

        override fun fromMudlet(v: Any) = (v as? Number)?.toInt()?.coerceIn(range)

        override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    class BoolSetting(key: String, default: Boolean, mudlet: Boolean = false, mudletDefault: Boolean = default) : Setting<Boolean>(key, default, mudlet, mudletDefault) {
        override fun fromMudlet(v: Any) = v as? Boolean

        override fun read(prefs: SharedPreferences) = prefs.getBoolean(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Boolean) {
            editor.putBoolean(key, v)
        }
    }

    /** Some text, such as one of Mudlet's names for an option of many; a blank one is taken as [default] unless [blankAllowed]. */
    class TextSetting(key: String, default: String, mudlet: Boolean = false, private val blankAllowed: Boolean = false) : Setting<String>(key, default, mudlet) {
        override fun normalise(v: String) = if (blankAllowed) v else v.ifBlank { default }

        override fun fromMudlet(v: Any) = (v as? String)?.takeIf { blankAllowed || it.isNotBlank() }

        override fun read(prefs: SharedPreferences) = prefs.getString(key, null) ?: default

        override fun write(editor: SharedPreferences.Editor, v: String) {
            editor.putString(key, v)
        }
    }

    /** One of [entries], stored by name; [mudletValue] is the string Mudlet's `setConfig()` takes for each. */
    class EnumSetting<E : Enum<E>>(
        key: String,
        default: E,
        val entries: List<E>,
        mudlet: Boolean = false,
        private val mudletValue: (E) -> String,
    ) : Setting<E>(key, default, mudlet) {
        override fun toMudlet(v: E): Any = mudletValue(v)

        override fun fromMudlet(v: Any) = entries.firstOrNull { mudletValue(it) == v }

        override fun read(prefs: SharedPreferences) = prefs.getString(key, null)?.let { name -> entries.firstOrNull { it.name == name } } ?: default

        override fun write(editor: SharedPreferences.Editor, v: E) {
            editor.putString(key, v.name)
        }
    }
}
