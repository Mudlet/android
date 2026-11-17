package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.ErrorConsoleEntry
import org.mudlet.android.protocol.Requests

/**
 * The profile's error console (docs/protocol.md, `errorConsole`): its entries as the engine last listed them and
 * those it added since, the oldest first, kept within the engine's caps, and how many of its errors the player has not
 * yet seen. What it holds is Compose state, changed only here. Main thread only.
 */
internal class ErrorConsoleClient(private val send: (JSONObject) -> Boolean) {
    private val kept = mutableStateListOf<ErrorConsoleEntry>()
    private var keptChars = 0L

    // The n of the newest entry the player has seen
    private var seen by mutableLongStateOf(0L)

    /** The entries, the oldest first. */
    val entries: List<ErrorConsoleEntry> get() = kept

    /** How many errors, not debugc() lines, came after the player last looked ([markSeen]). */
    val unseen: Int get() = kept.count { !it.debug && it.n > seen }

    fun onMessage(message: EngineMessage.ErrorConsole) {
        if (message.full) {
            kept.clear()
            keptChars = 0
        }
        kept.addAll(message.entries)
        keptChars += message.entries.sumOf(::chars)
        while (kept.size > MAX_ENTRIES || keptChars > MAX_CHARS) keptChars -= chars(kept.removeAt(0))
    }

    /** A new engine instance counts its entries from 1 again, so none of them has been seen. */
    fun onAttached(sameInstance: Boolean) {
        if (!sameInstance) seen = 0
    }

    /** The player has seen every entry there is. */
    fun markSeen() {
        seen = maxOf(seen, kept.lastOrNull()?.n ?: 0L)
    }

    /** Asks the engine to empty the console, which it answers with the empty list; false when that could not go. */
    fun clear(): Boolean = send(Requests.clearErrorConsole())

    private companion object {
        // The engine's caps (native/api/IEngine.h), so that what is appended here holds no more than a full list would
        const val MAX_ENTRIES = 1000
        const val MAX_CHARS = 1_000_000L

        fun chars(entry: ErrorConsoleEntry): Long = (entry.obj.length + entry.function.length + entry.event.length + entry.message.length).toLong()
    }
}
