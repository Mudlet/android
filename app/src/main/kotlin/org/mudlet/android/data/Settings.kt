package org.mudlet.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.engine.EngineScrollback
import org.mudlet.android.protocol.ServerEncodings
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

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
    private var appContext: Context? = null

    /** The console's text size in sp, so the system font scale scales it too. */
    val textSize = IntSetting("textSizeSp", default = 14, range = 8..32)

    /** "Read new text aloud": new game lines are announced while TalkBack is on, as Mudlet's own setting has it. */
    val readNewText = BoolSetting("announceIncomingText", default = true, mudlet = true)

    /**
     * "Scripts speak through TalkBack": while TalkBack is on and the app is on screen, what scripts speak with
     * `ttsSpeak()` and `ttsQueue()` is said by TalkBack, after what it is saying, rather than by the text to speech
     * engine over it (docs/architecture.md, "Scripts' speech"). For every game.
     */
    val scriptSpeechThroughTalkBack = BoolSetting("scriptSpeechThroughTalkBack", default = true)

    /** "Tell games I use a screen reader", in telnet's MTTS and NEW-ENVIRON. */
    val advertiseScreenReader = BoolSetting("advertiseScreenReader", default = false, mudlet = true)

    /** What becomes of the blank lines a game sends. */
    val blankLines = EnumSetting("blankLinesBehaviour", BlankLines.SHOW, BlankLines.entries, mudlet = true) { it.mudletValue }

    /** "Switch between input line and main window using": the hardware key that moves between the line and the text. */
    val caretKey = EnumSetting("caretShortcut", CaretKey.NONE, CaretKey.entries, mudlet = true) { it.mudletValue }

    /** Whether to join the lines a game wraps itself, at [undoWrapWidth], so triggers see them whole. */
    val undoWrap = BoolSetting("undoServerWrap", default = false, mudlet = true)

    /** The column a game wraps its lines at, for [undoWrap]. */
    val undoWrapWidth = IntSetting("undoServerWrapWidth", default = 80, range = 20..500, mudlet = true)

    /**
     * The lines of the game's text the profile's main console keeps, which its scripts read, desktop Mudlet's "Main
     * display size". The app gives a game it adds a tenth of Mudlet's (docs/divergences.md). Its most is what this
     * device keeps, which [init] learns.
     */
    val consoleBufferSize = BufferSizeSetting()

    /** Desktop Mudlet's "Use maximum lines possible": the main console keeps the most this device keeps. */
    val useMaxConsoleBufferSize = BoolSetting("useMaxConsoleBufferSize", default = false, mudlet = true)

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

    /** "React to all keybindings on the same key": a key bound more than once runs every binding, not only the first. */
    val runAllKeyBindings = BoolSetting("runAllKeyMatches", default = false, mudlet = true)

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

    /** "Blinking text": text a game sends blinking pulses, as desktop Mudlet draws it; off, it is drawn steady in italics. */
    val blinkText = BoolSetting("enableBlinkText", default = false, mudlet = true)

    /** "Links from the game (OSC 8)": OSC 8 links are shown, and Mudlet tells the game it shows them. */
    val gameLinks = BoolSetting("enableOSC8Hyperlinks", default = true, mudlet = true)

    /**
     * Desktop Mudlet's "Notify on new data": new text while the app is in the background asks for the player's
     * attention, which the app's "new game text" notification shows.
     */
    val alertOnNewData = BoolSetting("alertOnNewData", default = true, mudlet = true)

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

    /** Whether a script's errors are also shown in the game's text, as well as in the error console. */
    val showScriptErrors = BoolSetting("echoLuaErrors", default = false, mudlet = true)

    // Desktop Mudlet's "Log options": what a new log of the game is written as and named
    val logInHtml = BoolSetting("logInHTML", default = false, mudlet = true)
    val logTimestamps = BoolSetting("logTimestamps", default = false, mudlet = true)
    val logNameFormat = EnumSetting("logFileNameFormat", LogNameFormat.DATE_AND_TIME, LogNameFormat.entries, mudlet = true) { it.mudletValue }

    /** The log's name for [LogNameFormat.NAMED], without its extension; none is Mudlet's "logfile". */
    val logFileName = TextSetting("logFileName", default = "", mudlet = true, blankAllowed = true)

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

    // The game's text colours, as 0xRRGGBB, with Mudlet's own: the colour of text a game gives none, the console's
    // background, the colours the commands sent show in, then the sixteen ANSI ones by the names color_table has
    val foregroundColour = ColourSetting("foregroundColor", 0xC0C0C0)
    val backgroundColour = ColourSetting("backgroundColor", 0x000000)
    val commandForegroundColour = ColourSetting("commandForegroundColor", 0x717100)
    val commandBackgroundColour = ColourSetting("commandBackgroundColor", 0x000000)
    val ansiColours = listOf(
        ColourSetting("ansiBlack", 0x000000),
        ColourSetting("ansiRed", 0x800000),
        ColourSetting("ansiGreen", 0x008000),
        ColourSetting("ansiYellow", 0x808000),
        ColourSetting("ansiBlue", 0x000080),
        ColourSetting("ansiMagenta", 0x800080),
        ColourSetting("ansiCyan", 0x008080),
        ColourSetting("ansiWhite", 0xC0C0C0),
        ColourSetting("ansiLightBlack", 0x808080),
        ColourSetting("ansiLightRed", 0xFF0000),
        ColourSetting("ansiLightGreen", 0x00FF00),
        ColourSetting("ansiLightYellow", 0xFFFF00),
        ColourSetting("ansiLightBlue", 0x0000FF),
        ColourSetting("ansiLightMagenta", 0xFF00FF),
        ColourSetting("ansiLightCyan", 0x00FFFF),
        ColourSetting("ansiLightWhite", 0xFFFFFF),
    )

    /** Every colour the colours page has, which its reset puts back to Mudlet's. */
    val colours = listOf(foregroundColour, backgroundColour, commandForegroundColour, commandBackgroundColour) + ansiColours

    /** "Let the game change colours": the game may redefine the sixteen ANSI colours, by OSC P, and reset them, by OSC R. */
    val gameChangesColours = BoolSetting("serverMayRedefineColors", default = false, mudlet = true)

    // The 2D map's view options, as desktop Mudlet's Mapper tab has them and with its defaults
    val mapRoomSize = IntSetting("mapRoomSize", default = 5, range = 1..11, mudlet = true)
    val mapExitSize = PenSizeSetting("mapExitSize")
    val mapRoomBorders = BoolSetting("mapShowRoomBorders", default = true, mudlet = true)
    val mapRoomBorderSize = PenSizeSetting("mapRoomBorderSize")
    val mapLargeAreaExitArrows = BoolSetting("mapLargeAreaExitArrows", default = false, mudlet = true)

    /** How the map marks the player's room, with the marker's sizes in percent and the colours of [PlayerRoomStyle.CUSTOM_RING]. */
    val playerRoomStyle = EnumSetting("mapPlayerRoomStyle", PlayerRoomStyle.ORIGINAL, PlayerRoomStyle.entries, mudlet = true) { it.mudletValue }
    val playerRoomOuterDiameter = IntSetting("mapPlayerRoomOuterDiameter", default = 120, range = 50..200, mudlet = true)
    val playerRoomInnerDiameter = IntSetting("mapPlayerRoomInnerDiameter", default = 70, range = 0..96, mudlet = true)
    val playerRoomOuterColour = ColourSetting("mapPlayerRoomOuterColor", 0xFF0000)
    val playerRoomInnerColour = ColourSetting("mapPlayerRoomInnerColor", 0xFFFFFF)

    // The map's colours, as desktop Mudlet's Mapper colors tab has them: the background, the exits, the room borders,
    // overlapping rooms' borders, the levels below and above, the grid, then the sixteen default environments'
    val mapBackgroundColour = ColourSetting("mapBackgroundColor", 0x000000)
    val mapLinkColour = ColourSetting("mapLinkColor", 0xC0C0C0)
    val mapRoomBorderColour = ColourSetting("mapRoomBorderColor", 0xC0C0C0)
    val mapCollisionBorderColour = ColourSetting("mapRoomCollisionBorderColor", 0xFFFF00)
    val mapLowerLevelColour = ColourSetting("mapLowerLevelColor", 0x808080)
    val mapUpperLevelColour = ColourSetting("mapUpperLevelColor", 0xFFFFFF)
    val mapGridColour = ColourSetting("mapGridColor", 0xD3D3D3)
    val mapAnsiColours = ansiColours.map { ColourSetting("map" + it.key.replaceFirstChar(Char::uppercaseChar), it.default) }

    /** The map colours desktop Mudlet's "Reset all colors to default" on its Mapper colors tab puts back, as the Map page's reset does. */
    val mapColours = listOf(
        mapBackgroundColour,
        mapLinkColour,
        mapRoomBorderColour,
        mapCollisionBorderColour,
        mapLowerLevelColour,
        mapUpperLevelColour,
        mapGridColour,
    ) + mapAnsiColours

    /** Whether the first run with TalkBack on has asked about [advertiseScreenReader] yet. */
    val screenReaderOffered = BoolSetting("screenReaderOffered", default = false)

    /**
     * "See-through map and text": the profile's map, faint, behind the game's text (docs/architecture.md, "The map behind
     * the text"). Off by default; as no default is ever stored, a player who never chose has the default of the app they run.
     */
    val mapBehindText = BoolSetting("mapBehindText", default = false)

    /**
     * "Map in the corner": with [mapBehindText] off, the profile's map small in a corner of the game's text
     * (docs/architecture.md, "The map in the corner"). On by default; the map's own button there turns it off.
     */
    val mapInCorner = BoolSetting("mapInCorner", default = true)

    /** "Check for new test builds": a PTB installed from GitHub asks GitHub once a day for a newer one (docs/ptb.md). */
    val checkTestBuilds = BoolSetting("checkTestBuilds", default = true)

    /** "Movement pad": the game screen's keys for walking with one thumb (docs/architecture.md, "The movement pad"). */
    val movementPad = BoolSetting("movementPad", default = true)

    /**
     * "Movement pad design": the movement pad the game screen has, the Swipe pad unless the player picks the earlier one
     * or another of those tried beside it (docs/architecture.md, "The movement pad's designs"). Stored by the constant's
     * name, so those are stored names.
     */
    val movementPadDesign = EnumSetting("movementPadDesign", MovementPadDesign.CURRENT, MovementPadDesign.entries) { it.name }

    /** "Movement pad side": the edge the Thumb, Swipe and Room exits pads keep to. Stored by the constant's name, as above. */
    val movementPadSide = EnumSetting("movementPadSide", MovementPadSide.RIGHT, MovementPadSide.entries) { it.name }

    /** "Map beside the text": on an expanded screen, the profile's map beside the game's text, as desktop's docked mapper. */
    val mapBesideText = BoolSetting("mapBesideText", default = true)

    /** "Colours from my wallpaper": the app's colours from the wallpaper (Material You), on Android 12 and later. */
    val wallpaperColours = BoolSetting("wallpaperColours", default = true)

    /** "Show game text in notifications": a game's newest line on its "new game text" notification, not only the count. */
    val gameTextInNotifications = MarkerSetting("notificationGameText") { EngineNotifications.gameTextMarker(it) }

    /**
     * "Script editor design": the script editor the library opens as, the current one or one of the three tried beside
     * it (docs/architecture.md, "The script editor's designs"). Stored by the constant's name, so those are stored names.
     */
    val scriptEditorDesign = EnumSetting("scriptEditorDesign", ScriptEditorDesign.CURRENT, ScriptEditorDesign.entries) { it.name }

    private val all = listOf(
        textSize,
        readNewText,
        advertiseScreenReader,
        blankLines,
        caretKey,
        undoWrap,
        undoWrapWidth,
        consoleBufferSize,
        useMaxConsoleBufferSize,
        showSentText,
        unixLineEndings,
        clearInputAfterSend,
        commandSeparator,
        highlightHistory,
        showPasswords,
        runAllKeyBindings,
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
        blinkText,
        gameLinks,
        alertOnNewData,
        forceCompressionOff,
        forceGaOff,
        fixLinebreaks,
        versionInTerminalType,
        forceMxpOn,
        lazyTriggerVariables,
        showScriptErrors,
        logInHtml,
        logTimestamps,
        logNameFormat,
        logFileName,
        acceptExpiredCertificate,
        acceptSelfSignedCertificate,
        acceptAnyCertificate,
        gameInstallsPackages,
        gamePlaysMedia,
        muteScripts,
        muteGame,
        gameChangesColours,
    ) + colours + listOf(
        mapRoomSize,
        mapExitSize,
        mapRoomBorders,
        mapRoomBorderSize,
        mapLargeAreaExitArrows,
        playerRoomStyle,
        playerRoomOuterDiameter,
        playerRoomInnerDiameter,
        playerRoomOuterColour,
        playerRoomInnerColour,
    ) + mapColours + listOf(
        screenReaderOffered,
        scriptSpeechThroughTalkBack,
        mapBehindText,
        mapInCorner,
        checkTestBuilds,
        movementPad,
        movementPadDesign,
        movementPadSide,
        mapBesideText,
        wallpaperColours,
        gameTextInNotifications,
        scriptEditorDesign,
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
        appContext = context.applicationContext
        consoleBufferSize.range = consoleBufferSize.range.first..EngineScrollback.maxLines(context)
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

    /** The values `setConfig("mapPlayerRoomStyle", ...)` takes, in the order of desktop Mudlet's list. */
    enum class PlayerRoomStyle(val mudletValue: String) {
        ORIGINAL("original"),
        RED_RING("redRing"),
        BLUE_YELLOW_RING("blueYellowRing"),
        CUSTOM_RING("customRing"),
    }

    /** The values `setConfig("caretShortcut", ...)` takes. */
    enum class CaretKey(val mudletValue: String) {
        NONE("none"),
        TAB("tab"),
        CTRL_TAB("ctrltab"),
        F6("f6"),
    }

    /** The values `setConfig("showSentText", ...)` takes; SCRIPT shows them unless a script sends them hidden. */
    enum class SentText(val mudletValue: String) {
        SCRIPT("script"),
        ALWAYS("always"),
        NEVER("never"),
    }

    /** The movement pad's designs, in the order the setting lists them. */
    enum class MovementPadDesign {
        CURRENT,
        THUMB,
        SWIPE,
        EXITS,
    }

    /** The edges of the screen, left and right whatever the language's direction, as a thumb is. */
    enum class MovementPadSide {
        LEFT,
        RIGHT,
    }

    /** The script editor's designs, in the order the setting lists them. */
    enum class ScriptEditorDesign {
        CURRENT,
        WORKBENCH,
        ONE_PAGE,
        RECIPES,
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

        /** Back to what Mudlet starts a profile with. */
        fun setMudletDefault() = set(mudletDefault)

        /** [v] as Mudlet's `setConfig()` takes it and `getConfig()` gives it. */
        open fun toMudlet(v: T): Any = v as Any

        /** [value] as Mudlet's `setConfig()` takes it. */
        fun valueToMudlet(): Any = toMudlet(value)

        /** [mudletDefault] as Mudlet's `setConfig()` takes it. */
        fun mudletDefaultToMudlet(): Any = toMudlet(mudletDefault)

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
    open class IntSetting(key: String, default: Int, open val range: IntRange, mudlet: Boolean = false, mudletDefault: Int = default) : Setting<Int>(key, default, mudlet, mudletDefault) {
        override fun normalise(v: Int) = v.coerceIn(range)

        override fun fromMudlet(v: Any) = (v as? Number)?.toInt()?.coerceIn(range)

        override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    /**
     * [consoleBufferSize]: from Mudlet's least, 100, to the most lines this device keeps (EngineScrollback), as desktop
     * Mudlet's preferences go up to what the computer's memory holds; up to a million until [init] learns it.
     */
    class BufferSizeSetting : IntSetting("consoleBufferSize", default = 10_000, range = 100..1_000_000, mudlet = true, mudletDefault = 100_000) {
        override var range = super.range
            internal set
    }

    /**
     * A map line's thickness from 1 to 11, as desktop Mudlet's preferences show it. Mudlet keeps 50 over it, which
     * divides the pen's width, and the preferences turn it back by qRound(50 / size), as [fromMudlet] does.
     */
    class PenSizeSetting(key: String) : IntSetting(key, default = 5, range = 1..11, mudlet = true) {
        override fun toMudlet(v: Int): Any = 50.0 / v

        override fun fromMudlet(v: Any) = (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.let { (50 / it).roundToInt().coerceIn(range) }
    }

    open class BoolSetting(key: String, default: Boolean, mudlet: Boolean = false, mudletDefault: Boolean = default) : Setting<Boolean>(key, default, mudlet, mudletDefault) {
        override fun fromMudlet(v: Any) = v as? Boolean

        override fun read(prefs: SharedPreferences) = prefs.getBoolean(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Boolean) {
            editor.putBoolean(key, v)
        }
    }

    /**
     * A switch, off by default, kept as whether the file [marker] gives is there rather than in the preferences: the
     * :engine process reads it, which cannot read these preferences as they change.
     */
    class MarkerSetting(key: String, private val marker: (Context) -> File) : BoolSetting(key, default = false) {
        override fun read(prefs: SharedPreferences) = appContext?.let { marker(it).exists() } ?: false

        override fun write(editor: SharedPreferences.Editor, v: Boolean) {
            val file = appContext?.let(marker) ?: return
            val stored = try {
                if (v) file.createNewFile() || file.exists() else file.delete() || !file.exists()
            } catch (e: IOException) {
                false
            }
            if (!stored) Log.w("Settings", "$key: could not ${if (v) "make" else "remove"} the file the engine reads")
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

    /** A colour as 0xRRGGBB, which Mudlet's `setConfig()` takes and `getConfig()` gives as "#rrggbb". */
    class ColourSetting(key: String, default: Int) : Setting<Int>(key, default, mudlet = true) {
        override fun normalise(v: Int) = v and 0xFFFFFF

        override fun toMudlet(v: Int): Any = colourName(v)

        override fun fromMudlet(v: Any) = (v as? String)?.let(::parseColourName)

        override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    /** [rgb] as "#rrggbb". */
    fun colourName(rgb: Int): String = "#%06x".format(Locale.ROOT, rgb and 0xFFFFFF)

    /** "#rrggbb" (either case) as 0xRRGGBB, or null for anything else. */
    fun parseColourName(name: String): Int? = name.takeIf { COLOUR_NAME.matches(it) }?.substring(1)?.toInt(16)

    private val COLOUR_NAME = Regex("#[0-9A-Fa-f]{6}")

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
