package org.mudlet.android.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.linkSpans

/**
 * The main console's links, as a [ProfileSession] keeps them (docs/architecture.md, "The main console's links"): what
 * `getLink` last said of each, and the requests about them. A link's state changes come as `linksChanged`, after
 * which the lines shown ask about their links again ([changes]). Main thread only, as the session's state.
 */
class ConsoleLinksClient internal constructor(
    private val newRequestId: () -> Long,
    private val send: (JSONObject) -> Boolean,
    private val beforeFollow: () -> Unit,
) {
    private val links = mutableStateMapOf<Int, EngineMessage.Link>()

    /** Counts the `linksChanged` and attaches: a line shown asks about its links again each time it changes. */
    var changes by mutableIntStateOf(0)
        private set

    /**
     * The number ([ProfileSession.linesAdded]) of the first line whose links this engine instance holds: a new one
     * counts its links from 1 again, so an older line's id would name another link.
     */
    var firstLine by mutableLongStateOf(0L)
        private set

    // The links asked about since the engine last said they may have changed, answered or not
    private val asked = HashSet<Int>()

    // The getLink requests not yet answered, by request id, and the followLink requests, which only an error answers
    private val pending = HashMap<Long, Int>()
    private val follows = HashSet<Long>()

    // How many links to keep before letting go of those no line holds any more
    private var pruneAbove = PRUNE_AT

    /** What `getLink` last said of the link [link]; null until it answers, and after it said the link is gone. */
    fun info(link: Int): EngineMessage.Link? = links[link]

    /** Asks the engine what the link [link] offers, unless it was asked since it last may have changed; [info] gets it. */
    fun request(link: Int) {
        if (!asked.add(link)) return
        val id = newRequestId()
        if (send(Requests.getLink(link, null, id))) pending[id] = link else asked.remove(link)
    }

    /**
     * A tap on the link [link], or with [item], that item picked from its menu: what it runs changes the link, which
     * `linksChanged` then says. False as for [ProfileSession.send].
     */
    fun follow(link: Int, item: Int?): Boolean {
        beforeFollow()
        val id = newRequestId()
        if (!send(Requests.followLink(link, item, null, id))) return false
        follows.add(id)
        return true
    }

    /** A `link` message: one with a window is a script window's, which [ScriptWindowsClient] keeps. */
    internal fun onLink(message: EngineMessage.Link) {
        if (message.window != null) return
        (message.replyTo as? Number)?.toLong()?.let(pending::remove)
        links[message.link] = message
    }

    /** What the engine says of [ids], or of every link where empty, may have changed: those shown are asked again. */
    internal fun onLinksChanged(ids: List<Int>) {
        if (ids.isEmpty()) asked.clear() else asked.removeAll(ids.toSet())
        changes++
    }

    /**
     * The engine's `error` for one of this console's requests, which only the log hears: a link a script let go of, or
     * one the player tapped as it changed, is no fault of theirs. False for another's request.
     */
    internal fun onError(message: EngineMessage.Error): Boolean {
        val id = (message.replyTo as? Number)?.toLong() ?: return false
        val link = pending.remove(id)
        if (link == null && !follows.remove(id)) return false
        // The last answer stands while the link can still be asked about; one the console no longer holds goes
        if (link != null && message.message.substringAfter(':').trim() == UNKNOWN) links.remove(link)
        Log.w(TAG, "a link of the main console: ${message.message}")
        return true
    }

    /**
     * The link attached again: what the last connection did not answer is asked again. A new engine instance holds none
     * of the last one's links, which the lines before [linesAdded] have.
     */
    internal fun onAttached(sameInstance: Boolean, linesAdded: Long) {
        if (!sameInstance) {
            links.clear()
            firstLine = linesAdded
        }
        asked.clear()
        pending.clear()
        follows.clear()
        pruneAbove = PRUNE_AT
        changes++
    }

    /** Lets go of the links no line of [lines] or [tail] holds, once there are many, as no tap reaches them. */
    internal fun pruneTo(lines: List<UiLine>, tail: UiLine?) {
        if (links.size + asked.size <= pruneAbove) return
        val held = HashSet<Int>()
        for (line in lines + listOfNotNull(tail)) linkSpans(line.runs, line.text.length).mapTo(held) { it.link }
        links.keys.filter { it !in held }.forEach { links.remove(it) }
        asked.retainAll(held)
        pruneAbove = maxOf(PRUNE_AT, (links.size + asked.size) * 2)
    }

    private companion object {
        const val TAG = "ConsoleLinks"
        const val UNKNOWN = "unknown"

        // Far more than a screen of lines holds, so a scan of the lines is rare
        const val PRUNE_AT = 256
    }
}
