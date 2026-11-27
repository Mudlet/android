package org.mudlet.android.suggest

/**
 * The words of the last [window] words of game output, for completing the word at the cursor (what Mudlet's
 * Tab does, but several candidates at once). Adding a line is O(its words); [completions] is O(distinct words
 * in the window). Not thread-safe; [CommandSuggester] holds the lock.
 */
internal class OutputWords(private val window: Int) {
    private class Word(var display: String, var count: Int, var lastSeen: Long)

    // lower case word, oldest first
    private val order = ArrayDeque<String>()
    private val words = HashMap<String, Word>()
    private var seen = 0L

    fun add(line: String) = forEachOutputWord(line) { display ->
        val key = display.lowercase()
        val word = words.getOrPut(key) { Word(display, 0, 0) }
        word.display = display
        word.count++
        word.lastSeen = ++seen
        order.addLast(key)
        if (order.size > window) {
            val old = order.removeFirst()
            val evicted = words.getValue(old)
            if (--evicted.count == 0) words.remove(old)
        }
    }

    /**
     * The words that start with [prefix] (ignoring case) and are longer, newest first, each with its recency:
     * 1 for the newest word in the window, near 0 for the oldest.
     */
    fun completions(prefix: String, limit: Int = MAX_COMPLETIONS): List<Pair<String, Double>> {
        if (words.isEmpty()) return emptyList()
        val oldest = seen - order.size
        return words.values
            .filter { it.display.length > prefix.length && it.display.startsWith(prefix, ignoreCase = true) }
            .sortedByDescending { it.lastSeen }
            .take(limit)
            .map { it.display to (it.lastSeen - oldest).toDouble() / order.size }
    }

    fun clear() {
        order.clear()
        words.clear()
    }

    private companion object {
        const val MAX_COMPLETIONS = 8
    }
}

private const val MIN_WORD_LENGTH = 3

/**
 * Each run of letters, digits, `_`, `'` and `-` in [line], as the regular expression `[\p{L}\p{N}_'-]+` finds
 * them, without its leading and trailing `'` and `-`, that is at least [MIN_WORD_LENGTH] long and not all digits.
 */
internal fun forEachOutputWord(line: String, action: (String) -> Unit) {
    var i = 0
    while (i < line.length) {
        var start = i
        i = line.codePointRunEnd(i, ::isWordCodePoint)
        if (i == start) {
            i += Character.charCount(line.codePointAt(i))
            continue
        }
        var end = i
        while (start < end && (line[start] == '\'' || line[start] == '-')) start++
        while (end > start && (line[end - 1] == '\'' || line[end - 1] == '-')) end--
        if (end - start < MIN_WORD_LENGTH) continue
        val display = line.substring(start, end)
        if (!display.all(Char::isDigit)) action(display)
    }
}
