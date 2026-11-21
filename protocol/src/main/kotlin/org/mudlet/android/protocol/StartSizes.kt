package org.mudlet.android.protocol

import org.json.JSONException
import org.json.JSONObject

/**
 * The sizes the game screen last gave Mudlet for a profile that has no windows yet, and the window and settings it gave
 * them in ([window]), which decide them. The app sends them on the control socket before it opens a profile, so the
 * profile's scripts read them from their first line ("Several profiles" in docs/protocol.md). They are kept between
 * runs: the profile the app opens first after it starts has no game screen to measure yet.
 */
data class StartSizes(val window: String, val mainWindow: MudletSize? = null, val screen: Pair<Int, Int>? = null) {
    /** What to send before opening a profile from [window]: nothing of sizes another window or other settings gave. */
    fun requestsFor(window: String): List<JSONObject> = if (window != this.window) {
        emptyList()
    } else {
        listOfNotNull(screen?.let { Requests.setScreenSize(it.first, it.second) }, mainWindow?.let { Requests.setMainWindowSize(it.w, it.h) })
    }

    fun encode(): String = JSONObject().put("window", window).apply {
        mainWindow?.let { put("mainW", it.w).put("mainH", it.h) }
        screen?.let { put("columns", it.first).put("rows", it.second) }
    }.toString()

    companion object {
        /** [known] with the main window's [size] given in [window]; sizes another window gave are dropped. */
        fun withMainWindow(known: StartSizes?, window: String, size: MudletSize): StartSizes = (known?.takeIf { it.window == window } ?: StartSizes(window)).copy(mainWindow = size)

        /** [known] with the console's [columns] and [rows] measured in [window]; sizes another window gave are dropped. */
        fun withScreen(known: StartSizes?, window: String, columns: Int, rows: Int): StartSizes = (known?.takeIf { it.window == window } ?: StartSizes(window)).copy(screen = columns to rows)

        /** What [encode] wrote, or null for nothing or what no version of it wrote. */
        fun decode(text: String?): StartSizes? {
            if (text == null) return null
            return try {
                val json = JSONObject(text)
                fun size(a: String, b: String): Pair<Int, Int>? {
                    val first = json.optInt(a, 0)
                    val second = json.optInt(b, 0)
                    return if (first > 0 && second > 0) first to second else null
                }
                StartSizes(
                    json.getString("window"),
                    size("mainW", "mainH")?.let { MudletSize(it.first, it.second) },
                    size("columns", "rows")?.takeIf { it.first <= 65535 && it.second <= 65535 },
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}
