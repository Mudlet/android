package org.mudlet.android.suggest

import java.io.Reader
import kotlin.math.exp
import kotlin.math.ln

/**
 * Suggestions for one profile's input line. It learns from what the player sends ([recordSent]) and what
 * the game shows ([observeLine]), and answers [suggest] with candidates from four sources:
 *
 * - history, fuzzy matched against the input and ranked by frecency (use count, decayed by age);
 * - with an empty input, the commands the player usually sends after their previous command ("kill rat",
 *   then "loot"), and after game lines like the latest ones ("The door is closed.", then "open door");
 *   with an input, those predictions lift the history entries they name;
 * - words from recent game output that complete the word at the cursor.
 *
 * Hidden input (a password) goes through `recordSent(text, hidden = true)`, which stores nothing at all.
 * The state stays in memory and in what [save] writes; nothing here touches the network or the disk.
 *
 * Thread-safe: every call holds one lock. [suggest] costs O(H·m) for H history entries of m characters to
 * reject the ones the input is not a subsequence of, plus O(q·m) per entry that matches a q-character input:
 * 1 to 4 ms for 5000 entries on a desktop JVM, so call it debounced and off the main thread.
 * [recordSent] is O(1) amortised, plus O(H + K) for K prediction keys when the history is full and one entry
 * is evicted.
 */
class CommandSuggester(
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Suggester {
    /**
     * The caps that bound memory and the saved state, and the frecency half-life.
     *
     * @property maxHistory commands kept; the least frecent goes first
     * @property maxCommandLength longer commands are not recorded
     * @property maxSequenceKeys previous commands (and first words) whose successors are counted
     * @property maxLineKeys game lines whose successors are counted
     * @property maxNextPerKey successors counted per previous command or game line
     * @property outputWordWindow how many words of recent output offer completions
     * @property lineContext how many of the game lines since the last send predict the next one
     * @property halfLifeMillis how long until a use counts half in the frecency
     */
    data class Config(
        val maxHistory: Int = 5000,
        val maxCommandLength: Int = 256,
        val maxSequenceKeys: Int = 4000,
        val maxLineKeys: Int = 2000,
        val maxNextPerKey: Int = 8,
        val outputWordWindow: Int = 2000,
        val lineContext: Int = 3,
        val halfLifeMillis: Long = 7L * 24 * 60 * 60 * 1000,
    ) {
        init {
            require(maxHistory > 0 && maxCommandLength > 0 && maxSequenceKeys > 0 && maxLineKeys > 0)
            require(maxNextPerKey > 0 && outputWordWindow > 0 && lineContext > 0 && halfLifeMillis > 0)
        }
    }

    internal class HistoryEntry(var text: String, var weight: Double, var weightAt: Long, var count: Int)

    private val lock = Any()

    // normalised command -> entry
    private val history = HashMap<String, HistoryEntry>()
    private val sequences = TransitionTable(config.maxSequenceKeys, config.maxNextPerKey)
    private val lineResponses = TransitionTable(config.maxLineKeys, config.maxNextPerKey)
    private val outputWords = OutputWords(config.outputWordWindow)

    // the normalised previous command, null after hidden input or before the first send
    private var previous: String? = null

    // normalised game lines since the last send, newest last
    private val recentLines = ArrayDeque<String>()

    /**
     * Learns from a command the player sent. With [hidden] (the game asked for a password) nothing is
     * stored, and the next command is not linked to the one before the hidden input either.
     */
    override fun recordSent(text: String, hidden: Boolean): Unit = synchronized(lock) {
        if (hidden) {
            previous = null
            recentLines.clear()
            return@synchronized
        }
        val display = text.trim()
        val key = normalizeCommand(display)
        if (key.isEmpty() || display.length > config.maxCommandLength) return@synchronized
        val now = clock()
        val entry = history[key]
        if (entry == null) {
            history[key] = HistoryEntry(display, 1.0, now, 1)
            if (history.size > config.maxHistory) evictHistory(now)
        } else {
            entry.weight = decayed(entry, now) + 1.0
            entry.weightAt = now
            entry.count++
            entry.text = display
        }
        previous?.let { prev ->
            sequences.add(SEQUENCE_KEY + prev, key)
            sequences.add(FIRST_WORD_KEY + firstWord(prev), key)
        }
        for (line in recentLines) lineResponses.add(line, key)
        previous = key
        recentLines.clear()
    }

    /** Learns from a line the game showed: its words for completion, and it as context for the next send. */
    override fun observeLine(line: String): Unit = synchronized(lock) {
        outputWords.add(line)
        val key = normalizeLine(line) ?: return@synchronized
        recentLines.remove(key)
        recentLines.addLast(key)
        while (recentLines.size > config.lineContext) recentLines.removeFirst()
    }

    /** The candidates for [input] with the cursor at [cursor], best first, at most [limit]. */
    override fun suggest(input: String, cursor: Int, limit: Int): Suggestions = synchronized(lock) {
        require(cursor in 0..input.length) { "cursor $cursor outside the input" }
        require(limit >= 0) { "negative limit $limit" }
        val now = clock()
        val predicted = predictions()
        val best = HashMap<String, Suggestion>()

        fun offer(s: Suggestion) {
            if (s.text == input) return
            val old = best[s.text]
            if (old == null || s.score > old.score) best[s.text] = s
        }

        fun offerCommand(text: String, source: SuggestionSource, score: Double) = offer(Suggestion(text, text.length, text, SuggestionKind.COMMAND, source, score))
        if (input.isBlank()) {
            for ((key, prediction) in predicted) {
                offerCommand(history[key]?.text ?: key, prediction.source, PREDICTION_WEIGHT * prediction.score)
            }
            for ((key, entry) in history) {
                if (key !in predicted) offerCommand(entry.text, SuggestionSource.HISTORY, frecencyScore(entry, now))
            }
        } else {
            for ((key, entry) in history) {
                val match = FuzzyMatch.score(input, entry.text) ?: continue
                val prediction = predicted[key]
                val score = match + frecencyScore(entry, now) + CONTEXT_WEIGHT * (prediction?.score ?: 0.0)
                val source = if (prediction != null && prediction.score >= CONTEXT_SOURCE_MIN) prediction.source else SuggestionSource.HISTORY
                offerCommand(entry.text, source, score)
            }
            wordCompletions(input, cursor).forEach(::offer)
        }
        val items = best.values.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.text }).take(limit)
        Suggestions.of(input, cursor, items)
    }

    /**
     * The history's commands that [query] matches, as [suggest] matches them (a loose subsequence, ranked by the
     * match and frecency), best first, at most [limit]; for a blank query every command, most frecent first. Unlike
     * [suggest] it offers only what was sent: no predictions lift an entry and no words from the output are added.
     */
    override fun search(query: String, limit: Int): List<String> = synchronized(lock) {
        require(limit >= 0) { "negative limit $limit" }
        val now = clock()
        val q = query.trim()
        val scored = history.values.mapNotNull { entry ->
            val match = FuzzyMatch.score(q, entry.text) ?: return@mapNotNull null
            entry.text to match + frecencyScore(entry, now)
        }
        scored.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit).map { it.first }
    }

    /** Forgets a command: its history entry and every prediction of it. */
    override fun forget(command: String): Unit = synchronized(lock) {
        val key = normalizeCommand(command)
        history.remove(key)
        sequences.removeNext(key)
        sequences.removeKey(SEQUENCE_KEY + key)
        lineResponses.removeNext(key)
        if (previous == key) previous = null
    }

    /** Forgets everything this profile learned. */
    fun clear() = synchronized(lock) {
        history.clear()
        sequences.clear()
        lineResponses.clear()
        outputWords.clear()
        previous = null
        recentLines.clear()
    }

    /**
     * Writes what was learned for [load]: the history, the predictions and the previous command, but not the
     * session's output words or game lines.
     */
    override fun save(out: Appendable): Unit = synchronized(lock) {
        SuggestCodec.write(out, history.values, previous, sequences, lineResponses)
    }

    /** What [load] made of a saved text. */
    data class LoadResult(
        /** False when the text was not [save]'s format or version: the state is then empty. */
        val recognised: Boolean,
        /** Lines that did not parse and were skipped. */
        val skippedLines: Int,
    )

    /**
     * Replaces the state with what [save] wrote. Lines that do not parse are skipped, and a text of another
     * format or version leaves the state empty; the caps apply as entries are read. An I/O error from [input]
     * propagates, with what was read before it kept.
     */
    override fun load(input: Reader): LoadResult = synchronized(lock) {
        clear()
        val result = SuggestCodec.read(input) { record ->
            when (record) {
                is SuggestCodec.Record.History -> {
                    val key = normalizeCommand(record.text)
                    if (key.isNotEmpty() && record.text.length <= config.maxCommandLength && key !in history) {
                        history[key] = HistoryEntry(record.text, record.weight, record.weightAt, record.count)
                    }
                }

                is SuggestCodec.Record.Previous -> previous = normalizeCommand(record.key).ifEmpty { null }

                is SuggestCodec.Record.Sequence -> sequences.restore(record.key, record.next, record.count)

                is SuggestCodec.Record.Line -> lineResponses.restore(record.key, record.next, record.count)
            }
        }
        val now = clock()
        while (history.size > config.maxHistory) evictHistory(now)
        result
    }

    internal val historySize: Int get() = synchronized(lock) { history.size }

    private class Prediction(var score: Double, var source: SuggestionSource, var best: Double)

    // normalised command -> how strongly the previous command and the recent lines predict it, 0 to about 3
    private fun predictions(): Map<String, Prediction> {
        val result = HashMap<String, Prediction>()

        fun add(next: String, p: Double, source: SuggestionSource) {
            val entry = result.getOrPut(next) { Prediction(0.0, source, 0.0) }
            entry.score += p
            if (p > entry.best) {
                entry.best = p
                entry.source = source
            }
        }
        previous?.let { prev ->
            sequences.probabilities(SEQUENCE_KEY + prev).forEach { (next, p) -> add(next, FULL_WEIGHT * p, SuggestionSource.SEQUENCE) }
            sequences.probabilities(FIRST_WORD_KEY + firstWord(prev)).forEach { (next, p) -> add(next, WORD_WEIGHT * p, SuggestionSource.SEQUENCE) }
        }
        var weight = 1.0
        for (line in recentLines.asReversed()) {
            lineResponses.probabilities(line).forEach { (next, p) -> add(next, weight * p, SuggestionSource.LINE) }
            weight *= LINE_DECAY
        }
        return result
    }

    private fun wordCompletions(input: String, cursor: Int): List<Suggestion> {
        var start = cursor
        while (start > 0 && !input[start - 1].isWhitespace()) start--
        var end = cursor
        while (end < input.length && !input[end].isWhitespace()) end++
        val prefix = input.substring(start, cursor)
        if (prefix.isEmpty()) return emptyList()
        val whole = input.substring(start, end)
        val penalty = if (start == 0) FIRST_WORD_PENALTY else 0.0
        return outputWords.completions(prefix).filterNot { it.first.equals(whole, ignoreCase = true) }.map { (word, recency) ->
            val text = input.substring(0, start) + word + input.substring(end)
            Suggestion(text, start + word.length, word, SuggestionKind.WORD, SuggestionSource.OUTPUT, WORD_BASE + WORD_RECENCY * recency - penalty)
        }
    }

    private fun decayed(entry: HistoryEntry, now: Long): Double {
        val age = (now - entry.weightAt).coerceAtLeast(0)
        return entry.weight * exp(-LN2 * age / config.halfLifeMillis)
    }

    private fun frecencyScore(entry: HistoryEntry, now: Long) = FRECENCY_WEIGHT * ln(1.0 + decayed(entry, now))

    private fun evictHistory(now: Long) {
        val victim = history.entries.minByOrNull { decayed(it.value, now) }?.key ?: return
        history.remove(victim)
        sequences.removeNext(victim)
        sequences.removeKey(SEQUENCE_KEY + victim)
        lineResponses.removeNext(victim)
    }

    internal companion object {
        const val SEQUENCE_KEY = "c:"
        const val FIRST_WORD_KEY = "w:"
        const val FULL_WEIGHT = 1.0
        const val WORD_WEIGHT = 0.4
        const val LINE_DECAY = 0.5
        const val PREDICTION_WEIGHT = 100.0
        const val CONTEXT_WEIGHT = 40.0
        const val CONTEXT_SOURCE_MIN = 0.25
        const val FRECENCY_WEIGHT = 8.0
        const val WORD_BASE = 60.0
        const val WORD_RECENCY = 20.0
        const val FIRST_WORD_PENALTY = 20.0
        val LN2 = ln(2.0)
        private val SPACES = Regex("\\s+")
        private val DIGITS = Regex("\\d+")

        /** Trimmed, lower case, runs of white space as one space: "Kill  Rat" and "kill rat" are one command. */
        fun normalizeCommand(text: String) = text.trim().replace(SPACES, " ").lowercase()

        fun firstWord(normalized: String) = normalized.substringBefore(' ')

        /**
         * A game line as a key that recurs: lower case, numbers as "#", and capitalised words after the first
         * as "*", since those are mostly names: "Ann hits Bob for 12." gives "ann hits * for #.". Null for a
         * line with too few letters to mean much (a bare prompt like "<120 45>").
         */
        fun normalizeLine(line: String): String? {
            val words = line.trim().split(SPACES).filter { it.isNotEmpty() }.take(MAX_LINE_WORDS)
            val key = words.mapIndexed { i, word ->
                if (i > 0 && word[0].isUpperCase()) "*" + word.dropWhile(Char::isLetter) else DIGITS.replace(word.lowercase(), "#")
            }.joinToString(" ")
            return key.takeIf { it.count(Char::isLetter) >= MIN_LINE_LETTERS }
        }

        private const val MAX_LINE_WORDS = 16
        private const val MIN_LINE_LETTERS = 3
    }
}
