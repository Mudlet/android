package org.mudlet.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * The app's own settings, kept in SharedPreferences. Each one is Compose state, read and set on the main thread. A new
 * setting is a property here, in [all] so that it loads, and a row on the settings screen.
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

    private val all = listOf(textSize, readNewText, advertiseScreenReader, blankLines, screenReaderOffered)

    /**
     * The settings Mudlet keeps for each profile too. The app keeps its own copy, as `runLua` returns no values, and
     * gives each profile that loads the app's values.
     */
    val mudletSettings = all.filter { it.mudlet }

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
     * name of the `setConfig()` key it sets in Mudlet.
     */
    abstract class Setting<T>(protected val key: String, val default: T, val mudlet: Boolean = false) {
        var value by mutableStateOf(default)
            private set

        fun set(new: T) {
            val v = normalise(new)
            if (v == value) return
            value = v
            prefs?.edit { write(this, v) }
        }

        /** The Lua that gives Mudlet's profile this setting's value. */
        fun setConfigLua(): String = "setConfig(\"$key\", ${lua(value)})"

        internal fun load(prefs: SharedPreferences) {
            value = normalise(read(prefs))
        }

        protected open fun normalise(v: T): T = v

        protected open fun lua(v: T): String = throw UnsupportedOperationException("$key is not one of Mudlet's settings")

        protected abstract fun read(prefs: SharedPreferences): T

        protected abstract fun write(editor: SharedPreferences.Editor, v: T)
    }

    /** A whole number within [range]: a value outside it is taken as the nearest end. */
    class IntSetting(key: String, default: Int, val range: IntRange) : Setting<Int>(key, default) {
        override fun normalise(v: Int) = v.coerceIn(range)

        override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)

        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    class BoolSetting(key: String, default: Boolean, mudlet: Boolean = false) : Setting<Boolean>(key, default, mudlet) {
        override fun lua(v: Boolean) = v.toString()

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
        // The values are fixed strings of letters, which need no escaping
        override fun lua(v: E) = "\"${mudletValue(v)}\""

        override fun read(prefs: SharedPreferences) = prefs.getString(key, null)?.let { name -> entries.firstOrNull { it.name == name } } ?: default

        override fun write(editor: SharedPreferences.Editor, v: E) {
            editor.putString(key, v.name)
        }
    }
}
