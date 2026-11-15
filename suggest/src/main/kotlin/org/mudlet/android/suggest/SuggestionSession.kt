package org.mudlet.android.suggest

import java.io.IOException

/**
 * One profile's suggestions while the app runs. [open] loads what [store] kept; then the session learns from what
 * the input line sends ([sent]) and what the game shows ([lineShown]), answers the input as it is typed
 * ([inputChanged]) and searches through the history ([search]), forgets a command the player asks it to
 * ([forget]), and saves what it learned a while after a send or a forget, and on [save].
 *
 * Every call to [suggester] and [store] runs on [worker], in the order the calls here were made: a command sent
 * while the saved state still loads is learned on top of it. The calls here come from one thread, the UI's, and
 * return at once; [listener] and [searchListener] get the answers on the worker's thread.
 *
 * Hidden input (a password) never reaches the session: [sentHidden] takes no text, and nothing is answered while
 * [hiddenChanged] says the game hides input, not even a search. An I/O error goes to [log] and the session carries on
 * in memory; after a failed load it saves nothing, so a file it could not read is not replaced with less. [log] is
 * never given a command or a game line.
 */
class SuggestionSession(
    private val suggester: Suggester,
    private val store: SuggestStore,
    private val worker: Worker,
    private val log: (message: String, error: Throwable?) -> Unit,
    private val limit: Int = LIMIT,
) {
    /** What the suggester answered for [input] with the cursor at [cursor]. */
    data class Answer(val input: String, val cursor: Int, val suggestions: Suggestions)

    /** Gets each answer, on the worker's thread. */
    @Volatile var listener: ((Answer) -> Unit)? = null

    /** The history's commands that [query] matched, best first. */
    data class SearchAnswer(val query: String, val commands: List<String>)

    /** Gets each search's answer, on the worker's thread. */
    @Volatile var searchListener: ((SearchAnswer) -> Unit)? = null

    // The calling thread's
    private var opened = false
    private var pendingAnswer: Cancellable? = null
    private var pendingSearch: Cancellable? = null

    @Volatile private var hidden = false

    // The worker's
    private var loadFailed = false
    private var changed = false
    private var savePending = false
    private val echoes = ArrayDeque<Echo>()

    private class Echo(val text: String, var linesLeft: Int)

    /** Loads what the store kept; only the first call does anything. */
    fun open() {
        if (opened) return
        opened = true
        worker.schedule(0) {
            val result = try {
                store.load(suggester::load)
            } catch (e: IOException) {
                loadFailed = true
                log("could not load the saved suggestions; nothing is saved until the app restarts", e)
                return@schedule
            }
            if (result != null && (!result.recognised || result.skippedLines > 0)) {
                log("the saved suggestions were damaged: recognised ${result.recognised}, ${result.skippedLines} lines skipped", null)
            }
        }
    }

    /**
     * The input line holds [input] with the cursor at [cursor]. It is answered once it has stayed so for
     * [TYPING_DELAY_MS]: a newer call drops the answer an older one was waiting for.
     */
    fun inputChanged(input: String, cursor: Int) {
        dropPendingAnswer()
        if (hidden) return
        pendingAnswer = worker.schedule(TYPING_DELAY_MS) {
            if (!hidden) listener?.invoke(Answer(input, cursor, suggester.suggest(input, cursor.coerceIn(0, input.length), limit)))
        }
    }

    /**
     * Searches the history for [query], as [CommandSuggester.search] does, for at most [SEARCH_LIMIT] commands. A newer
     * call drops the answer an older one has not begun on.
     */
    fun search(query: String) {
        dropPendingSearch()
        if (hidden) return
        pendingSearch = worker.schedule(0) {
            if (!hidden) searchListener?.invoke(SearchAnswer(query, suggester.search(query, SEARCH_LIMIT)))
        }
    }

    /** Forgets [command], as [CommandSuggester.forget] does, and saves that a while later. */
    fun forget(command: String) {
        worker.schedule(0) {
            learned()
        }
    }

    /** Whether the game hides what is typed (a password). While it does, nothing is answered. */
    fun hiddenChanged(hidden: Boolean) {
        this.hidden = hidden
        if (hidden) {
            dropPendingAnswer()
            dropPendingSearch()
        }
    }

    private fun dropPendingAnswer() {
        pendingAnswer?.cancel()
        pendingAnswer = null
    }

    private fun dropPendingSearch() {
        pendingSearch?.cancel()
        pendingSearch = null
    }

    /** The input line sent [command]. The game's echo of it, the next line or one of the two after, is not learned from. */
    fun sent(command: String) {
        worker.schedule(0) {
            suggester.recordSent(command)
            val echo = command.trim()
            if (echo.isNotEmpty()) echoes.addLast(Echo(echo, ECHO_LINES))
            learned()
        }
    }

    /** The masked field sent something. What it was is not passed: it is kept nowhere, and the command before it no longer predicts the next one. */
    fun sentHidden() {
        worker.schedule(0) {
            suggester.recordSent("", hidden = true)
            learned()
        }
    }

    /** The game showed [line], as plain text. */
    fun lineShown(line: String) {
        worker.schedule(0) { if (!isEcho(line)) suggester.observeLine(line) }
    }

    /** Saves now if anything was learned since the last save, as when the app leaves the front. */
    fun save() {
        worker.schedule(0) { saveIfChanged() }
    }

    private fun learned() {
        changed = true
        if (savePending) return
        savePending = true
        worker.schedule(SAVE_DELAY_MS) {
            savePending = false
            saveIfChanged()
        }
    }

    private fun saveIfChanged() {
        if (!changed || loadFailed) return
        try {
            store.save(suggester::save)
            changed = false
        } catch (e: IOException) {
            log("could not save the suggestions; trying again after the next send", e)
        }
    }

    // Mudlet echoes a sent command on the console, after the prompt if there is one: the line ends with the
    // command, whole ("n" is not the end of "plain")
    private fun isEcho(line: String): Boolean {
        val shown = line.trimEnd()
        val match = echoes.indexOfFirst { shown.endsWith(it.text) && shown.getOrNull(shown.length - it.text.length - 1)?.isLetterOrDigit() != true }
        if (match >= 0) {
            repeat(match + 1) { echoes.removeFirst() }
            return true
        }
        echoes.forEach { it.linesLeft-- }
        echoes.removeAll { it.linesLeft <= 0 }
        return false
    }

    companion object {
        /** How many suggestions an answer has at most. */
        const val LIMIT = 5

        /** How many commands a search answers with at most. */
        const val SEARCH_LIMIT = 100

        /** How long the input stays unchanged before it is answered. */
        const val TYPING_DELAY_MS = 120L

        /** How long after a send or a forget what was learned is saved, at the latest. */
        const val SAVE_DELAY_MS = 5_000L

        /** How many lines after a send may be its echo. */
        const val ECHO_LINES = 3
    }
}
