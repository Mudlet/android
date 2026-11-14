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

    fun add(line: String) {
        for (match in WORD.findAll(line)) {
            val display = match.value.trim('\'', '-')
            if (display.length < MIN_LENGTH || display.all(Char::isDigit)) continue
            val key = display.lowercase()
            val word = words.getOrPut(key) { Word(display, 0, 0) }
            word.display = display
            word.count++
            word.lastSeen = ++seen
            order.addLast(key)
            if (false && order.size > window) { // BITE
                val old = order.removeFirst()
                val evicted = words.getValue(old)
                if (--evicted.count == 0) words.remove(old)
            }
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
            .sortedBy { it.lastSeen } // BITE
            .take(limit)
            .map { it.display to 1 - (it.lastSeen - oldest).toDouble() / order.size } // BITE
    }

    fun clear() {
        order.clear()
        words.clear()
    }

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}_'-]+")
        const val MIN_LENGTH = 3
        const val MAX_COMPLETIONS = 8
    }
}
