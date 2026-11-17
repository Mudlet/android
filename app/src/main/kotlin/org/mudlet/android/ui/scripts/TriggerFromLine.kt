package org.mudlet.android.ui.scripts

import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType

/** What a word of a line is in the pattern made from the line. */
enum class WordRole {
    /** The pattern has the word as it is. */
    FIXED,

    /** The pattern takes whatever is in the word's place, and hands it to the trigger's Lua. */
    CAPTURE,

    /** The pattern matches with the word there or not. */
    OPTIONAL,
    ;

    /** The role a tap on the word gives it next: fixed, captured, optional, and fixed again. */
    fun next(): WordRole = if (this == FIXED) CAPTURE else FIXED

    /** How [word] is written with this role, in a regex's own signs, which no language translates: `(word)`, `word?`. */
    fun shows(word: String): String = when (this) {
        FIXED -> word
        CAPTURE -> "($word)"
        OPTIONAL -> "$word?"
    }
}

// Letters and digits, with the apostrophes and hyphens inside them: "goblin's" and "well-known" are each one word
private val WORD = Regex("""[\p{L}\p{N}_]+(?:['’-][\p{L}\p{N}_]+)*""")

private val ASCII_NUMBER = Regex("[0-9]+")
private val ASCII_WORD = Regex("[A-Za-z0-9_]+")

private const val REGEX_SPECIAL = """\^$|?*+()[]{}"""

/** [text] as a Perl regex that matches just it. */
fun regexLiteral(text: String): String = buildString {
    for (c in text) {
        if (c in REGEX_SPECIAL) append('\\')
        append(c)
    }
}

/** The most of a line that names the trigger made from it. */
const val TRIGGER_NAME_LENGTH = 40

/**
 * A trigger's pattern being made from [line], a line of the game's text: each of the line's words has a role
 * ([roles], in the words' order), and what is between the words stays as it is. [wholeLine] has the pattern match only
 * a line that is all of this, rather than one with this in it; [wideCaptures] lets a captured word's place hold several
 * words.
 *
 * The pattern is a substring, or an exact match for a whole line, while every word is fixed, and else a Perl regex made
 * only of what Mudlet's PCRE and java.util.regex read alike (`\d+`, `\w+`, `\S+`, `.+`, `(?:…)?`, `^`, `$` and escaped
 * text), so that [captures], which tries it here, says what the trigger will do there.
 */
data class TriggerFromLine(val line: String, val roles: List<WordRole>, val wholeLine: Boolean = false, val wideCaptures: Boolean = false) {
    private val found: List<MatchResult> = WORD.findAll(line).toList()

    /** The line's words, which [roles] are of. */
    val words: List<String> get() = found.map { it.value }

    init {
        require(roles.size == found.size) { "${roles.size} roles for ${found.size} words" }
    }

    /** With the word at [index] given its next role. */
    fun withNextRole(index: Int): TriggerFromLine = if (index in roles.indices) copy(roles = roles.toMutableList().also { it[index] = it[index].next() }) else this

    /** Whether there is a pattern to make: a line of only optional words would match every line. */
    val usable: Boolean get() = line.isNotBlank() && (roles.isEmpty() || roles.any { it != WordRole.OPTIONAL })

    val pattern: TriggerPattern by lazy {
        when {
            roles.any { it != WordRole.FIXED } -> TriggerPattern(regex(), TriggerPatternType.REGEX)
            wholeLine -> TriggerPattern(line, TriggerPatternType.EXACT)
            else -> TriggerPattern(line.trim(), TriggerPatternType.SUBSTRING)
        }
    }

    // Compiled once for all the lines it is tried on
    private val compiled by lazy { Regex(pattern.text) }

    /** What the new trigger is called: the line, shortened. */
    val name: String get() = line.trim().take(TRIGGER_NAME_LENGTH).trim()

    private fun regex(): String {
        // What is before each word, and after the last: gaps[i] is before word i
        val gaps = (0..found.size).map { i ->
            val from = if (i == 0) 0 else found[i - 1].range.last + 1
            val to = if (i == found.size) line.length else found[i].range.first
            line.substring(from, to)
        }.toMutableList()
        if (!wholeLine) {
            gaps[0] = gaps[0].trimStart()
            gaps[gaps.lastIndex] = gaps[gaps.lastIndex].trimEnd()
        }
        // An optional word goes with the blanks before it, or, with nothing but optional words before it, with what is
        // after it
        val firstKept = roles.indexOfFirst { it != WordRole.OPTIONAL }
        return buildString {
            if (wholeLine) append('^')
            for ((i, word) in found.withIndex()) {
                val gap = gaps[i]
                when (roles[i]) {
                    WordRole.FIXED -> append(regexLiteral(gap)).append(regexLiteral(word.value))

                    WordRole.CAPTURE -> append(regexLiteral(gap)).append(capture(word.value))

                    WordRole.OPTIONAL -> if (i > firstKept) {
                        val blanks = gap.length - gap.trimEnd().length
                        append(regexLiteral(gap.dropLast(blanks))).append("(?:").append(regexLiteral(gap.takeLast(blanks) + word.value)).append(")?")
                    } else {
                        // Through the blanks, with what is joined to the word before them: "Suddenly, " goes as one
                        val after = gaps[i + 1]
                        val blank = after.indexOfFirst(Char::isWhitespace)
                        val taken = if (blank < 0) 0 else blank + after.drop(blank).takeWhile(Char::isWhitespace).length
                        append(regexLiteral(gap)).append("(?:").append(regexLiteral(word.value + after.take(taken))).append(")?")
                        gaps[i + 1] = after.drop(taken)
                    }
                }
            }
            append(regexLiteral(gaps.last()))
            if (wholeLine) append('$')
        }
    }

    private fun capture(word: String): String = when {
        wideCaptures -> "(.+)"
        ASCII_NUMBER.matches(word) -> """(\w+)"""
        ASCII_WORD.matches(word) -> """(\w+)"""
        else -> """(\S+)"""
    }

    /**
     * What the pattern captures from [text], in order, as the trigger's Lua has them from `matches[2]` on; empty where it
     * captures nothing, and null where [text] does not match.
     */
    fun captures(text: String): List<String>? = when (pattern.type) {
        TriggerPatternType.EXACT -> emptyList<String>().takeIf { text == pattern.text }
        TriggerPatternType.REGEX -> compiled.find(text)?.groupValues?.drop(1)
        else -> emptyList<String>().takeIf { pattern.text in text }
    }

    companion object {
        /** [line] with every word fixed. */
        fun of(line: String) = TriggerFromLine(line, WORD.findAll(line).map { WordRole.FIXED }.toList())
    }
}
