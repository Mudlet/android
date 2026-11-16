package org.mudlet.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * The app's own settings, kept in SharedPreferences. Each one is Compose state, read and set on the main thread. A new
 * setting is a property here, in [all] so that it loads, and an entry of the settings screen's catalogue
 * (ui/settings/SettingsCatalogue.kt). A [Setting.mudlet] one is each game's own, kept by Mudlet with its profile: here
 * it is only what a game added in the app is given as it first loads (ui/ProfileSession.kt reads the game's).
 */
object Settings {
    private const val PREFS = "settings"

    private var prefs: SharedPreferences? = null

    /** The console's text size in sp, so the system font scale scales it too. */
    val textSize = IntSetting("textSizeSp", default = 14, range = 8..32)

    /** "Read new text aloud": new game lines are announced while TalkBack is on, as Mudlet's own setting has it. */
    val readNewText = BoolSetting("announceIncomingText", default = true, mudlet = true)

    /** "Tell games I use a screen reader", in telnet's MTTS and NEW-ENVIRON. */
    val advertiseScreenReader = BoolSetting("advertiseScreenReader", default = false, mudlet = true)

    /** What becomes of the blank lines a game sends. */
    val blankLines = EnumSetting("blankLinesBehaviour", BlankLines.SHOW, BlankLines.entries, mudlet = true) { it.mudletValue }

    /** Whether the first run with TalkBack on has asked about [advertiseScreenReader] yet. */
    val screenReaderOffered = BoolSetting("screenReaderOffered", default = false)

    /** "Map behind the text": the profile's map, faint, behind the game's text (docs/architecture.md, "The map behind the text"). */
    val mapBehindText = BoolSetting("mapBehindText", default = true)

    /** "Colours from my wallpaper": the app's colours from the wallpaper (Material You), on Android 12 and later. */
    val wallpaperColours = BoolSetting("wallpaperColours", default = true)

    private val all = listOf(textSize, readNewText, advertiseScreenReader, blankLines, screenReaderOffered, mapBehindText, wallpaperColours)

    /** The settings Mudlet keeps with each profile, whose app values a game added in the app is given. */
    val mudletSettings = all.filter { it.mudlet }

    // The games added in the app that have not had the app's values of mudletSettings yet, by name. A game the app did
    // not add, such as one copied from desktop Mudlet, keeps its own from the start.
    private const val GAMES_AWAITING_DEFAULTS = "gamesAwaitingDefaults"

    /** Whether the game [name] is to be given the app's values of [mudletSettings] as it loads. */
    fun awaitsDefaults(name: String): Boolean = prefs?.getStringSet(GAMES_AWAITING_DEFAULTS, null)?.contains(name) == true

    /** Marks the game [name] as to be given them, as when it is added, or not, once it has them or is deleted. */
    fun setAwaitsDefaults(name: String, awaits: Boolean) {
        val p = prefs ?: return
        val games = p.getStringSet(GAMES_AWAITING_DEFAULTS, null).orEmpty()
        if ((name in games) == awaits) return
        p.edit { putStringSet(GAMES_AWAITING_DEFAULTS, if (awaits) games + name else games - name) }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        all.forEach { it.load(p) }
    }

    /** The values upstream Mudlet's `setConfig("blankLinesBehaviour", ...)` takes. */
    enum class BlankLines(val mudletValue: String) {
        SHOW("show"),
        HIDE("hide"),
        SPACE("replacewithspace"),
    }

    /**
     * One setting: [value] is the current one, [set] changes and stores it. A [mudlet] setting's [key] is also the
     * name of the `getConfig()` and `setConfig()` key it has in Mudlet.
     */
    abstract class Setting<T>(val key: String, val default: T, val mudlet: Boolean = false) {
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
    class IntSetting(key: String, default: Int, val range: IntRange) : Setting<Int>(key, default) {
        override fun normalise(v: Int) = v.coerceIn(range)

        override fun fromMudlet(v: Any) = (v as? Number)?.toInt()?.coerceIn(range)

        override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    class BoolSetting(key: String, default: Boolean, mudlet: Boolean = false) : Setting<Boolean>(key, default, mudlet) {
        override fun fromMudlet(v: Any) = v as? Boolean

        override fun read(prefs: SharedPreferences) = prefs.getBoolean(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Boolean) {
            editor.putBoolean(key, v)
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
