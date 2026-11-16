package org.mudlet.android.ui

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import org.mudlet.android.protocol.ConsoleZoom
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * The text sizes the player pinched a profile's script consoles to ([ConsoleZoom]), kept across restarts by the
 * profile's name, ignoring case as the engine does, and the console's. Each goes to the engine as a script's
 * `setFontSize()` would ([ProfileSession.runAppLua]). Main thread only.
 */
internal class ConsoleZoomClient(profile: String, private val prefs: () -> SharedPreferences, private val runLua: (String) -> Boolean) {
    private val prefix = PREF_PREFIX + encode(profile.lowercase(Locale.ROOT)) + ":"
    private val zooms = mutableStateMapOf<String, ConsoleZoom>()

    init {
        for ((key, value) in prefs().all) {
            if (!key.startsWith(prefix) || value !is String) continue
            val chosen = value.substringBefore(',').toIntOrNull() ?: continue
            val scripts = value.substringAfter(',', "").toIntOrNull() ?: continue
            zooms[decode(key.removePrefix(prefix))] = ConsoleZoom(chosen, scripts)
        }
    }

    /** The size the player chose for the console [name], or null while its scripts' size stands. */
    fun chosen(name: String): Int? = zooms[name]?.chosen

    /** The engine reports the console [name] at [size] points: a size other than the player's is set back to it. */
    fun reported(name: String, size: Int) {
        val zoom = zooms[name] ?: return
        val (next, send) = zoom.reported(size)
        put(name, if (send == null) next else sent(next, name, send))
    }

    /** The player picked [size] for the console [name], which the engine reports at [reported] points. */
    fun choose(name: String, size: Int, reported: Int) {
        if (!zoomable(name, reported)) return
        val zoom = zooms[name]
        if (zoom == null && size == reported) return
        val next = (zoom ?: ConsoleZoom(reported, reported)).chosen(size)
        put(name, if (size == reported) next else sent(next, name, size))
    }

    /** Forgets the player's size for the console [name] and gives it its scripts' last size again; kept if that could not go. */
    fun reset(name: String, reported: Int) {
        val size = (zooms[name] ?: return).scripts
        if (size != reported && !runLua(ConsoleZoom.setFontSizeLua(name, size))) return
        zooms.remove(name)
        prefs().edit { remove(key(name)) }
    }

    // A send that did not go is not awaited, so the next report sends it again
    private fun sent(zoom: ConsoleZoom, name: String, size: Int): ConsoleZoom = if (runLua(ConsoleZoom.setFontSizeLua(name, size))) zoom else zoom.copy(inFlight = zoom.inFlight - size)

    private fun put(name: String, zoom: ConsoleZoom) {
        val old = zooms.put(name, zoom)
        if (old?.chosen != zoom.chosen || old.scripts != zoom.scripts) prefs().edit { putString(key(name), "${zoom.chosen},${zoom.scripts}") }
    }

    private fun key(name: String) = prefix + encode(name)

    companion object {
        private const val PREF_PREFIX = "consoleZoom:"

        /** Whether the player may pinch the console [name], which the engine reports at [reported] points. */
        fun zoomable(name: String, reported: Int): Boolean = reported > 0 && name.isNotEmpty() && name != MAIN_CONSOLE

        // setFontSize() takes this name, or none, for the main console
        private const val MAIN_CONSOLE = "main"

        // Neither part holds the separator, and both stay printable in the preferences' XML
        private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

        private fun decode(text: String): String = URLDecoder.decode(text, Charsets.UTF_8.name())
    }
}
