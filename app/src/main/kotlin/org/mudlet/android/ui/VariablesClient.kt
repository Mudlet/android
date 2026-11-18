package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.VariableKey
import org.mudlet.android.protocol.VariableLevel
import org.mudlet.android.protocol.withPage

/**
 * The profile's Lua variables as the Variables screen shows them (docs/protocol.md, "Reading the Lua variables"): each
 * table read so far, keyed by its path from the globals, and the tables open. Read only. Requests go with [send], each
 * answer's `id` from [newId]. What it holds is Compose state, changed only here; the values are the player's data, so
 * nothing here logs them. Main thread only.
 */
internal class VariablesClient(private val newId: () -> Long, private val send: (JSONObject) -> Boolean) {
    private val read = mutableStateMapOf<List<VariableKey>, VariableLevel>()
    private val opened = mutableStateMapOf<List<VariableKey>, Unit>()

    /** Each table as the engine last listed it, the globals under no path. */
    val levels: Map<List<VariableKey>, VariableLevel> get() = read

    /** The tables whose members show. */
    val open: Set<List<VariableKey>> get() = opened.keys

    /** The hidden variables are listed too, as desktop's "Show hidden variables" does; off until switched on, not stored. */
    var showHidden by mutableStateOf(false)
        private set

    /** How many times the link to the engine has been made again, for the screen to read what it shows again. */
    var attaches by mutableIntStateOf(0)
        private set

    /** Why the engine last would not list a table, in English, until the variables are read again. */
    var refusal by mutableStateOf<String?>(null)
        private set

    // The path each request asked for; an answer to none of them is not this one's
    private val pending = HashMap<Long, List<VariableKey>>()

    /** Reads the globals and every open table again, from their first page, as their values change all the time. */
    fun refresh() {
        refusal = null
        pending.clear()
        read.clear()
        ask(emptyList())
        // A table open inside one closed does not show, so it is read once the one it is in opens again
        opened.keys.filter { path -> (1 until path.size).all { path.take(it) in opened } }.forEach { ask(it) }
    }

    /** Lists the hidden variables too, or not, and reads everything again. */
    fun showHidden(shown: Boolean) {
        showHidden = shown
        refresh()
    }

    /** Opens the table [path] names, reading it if it has not been, or closes it. */
    fun toggle(path: List<VariableKey>) {
        if (opened.remove(path) != null) return
        opened[path] = Unit
        if (path !in read) ask(path)
        // Those open inside it that were not read while it was closed
        opened.keys.filter { it.size > path.size && it.take(path.size) == path && it !in read }.forEach { ask(it) }
    }

    /** Asks for the members of [path] after the [from] already had. */
    fun more(path: List<VariableKey>, from: Int) = ask(path, from + 1)

    private fun ask(path: List<VariableKey>, from: Int = 0) {
        val id = newId()
        if (send(Requests.variables(path, false, from, id))) pending[id] = path
    }

    /** The engine's answer to a request from here; one to none of them is left alone. */
    fun onVariables(message: EngineMessage.Variables) {
        val path = pending.remove(message.replyTo.asId()) ?: return
        // Asked before a change of showHidden, which reads everything again
        if (message.showHidden != showHidden) return
        read[path].withPage(message)?.let { read[path] = it }
    }

    /** An `error` for `variables` answering [replyTo]: a table gone since it was listed closes, and [refusal] says why. */
    fun onRefused(reason: String, replyTo: Any?) {
        val path = pending.remove(replyTo.asId()) ?: return
        opened.remove(path)
        refusal = reason
    }

    /** What the last link did not answer is not answered; the screen, if it shows, reads everything again. */
    fun onAttached() {
        pending.clear()
        attaches++
    }

    private fun Any?.asId() = (this as? Number)?.toLong()

    companion object {
        /** The request sent from here, whose refusal goes to [onRefused]. */
        const val REQUEST = "variables"
    }
}
