package org.mudlet.android.suggest

/** Whether a suggestion is a whole command or completes the word at the cursor. */
enum class SuggestionKind { COMMAND, WORD }

/** Where a suggestion came from; the best-scoring source wins when two give the same text. */
enum class SuggestionSource {
    /** A command the player sent before, matched against the input. */
    HISTORY,

    /** What the player usually sends after their previous command. */
    SEQUENCE,

    /** What the player sent before after a game line like the latest ones. */
    LINE,

    /** A word from recent game output, completing the word at the cursor. */
    OUTPUT,
}

/**
 * One candidate for the input line. [text] is the whole input line it leads to, with the cursor at [cursor];
 * [label] is what to show (the command, or the completed word).
 */
data class Suggestion(
    val text: String,
    val cursor: Int,
    val label: String,
    val kind: SuggestionKind,
    val source: SuggestionSource,
    val score: Double,
)

/**
 * The ranked, de-duplicated candidates for an input, best first. [ghost] is set when the best one only adds
 * text after the cursor at the end of the input, so the UI can show that text greyed after the cursor (fish
 * style) and accept it with one key: it is the text to append.
 */
data class Suggestions(
    val input: String,
    val items: List<Suggestion>,
    val ghost: String?,
) {
    companion object {
        internal fun of(input: String, cursor: Int, items: List<Suggestion>): Suggestions {
            val top = items.firstOrNull()
            val ghost = top?.text?.takeIf {
                cursor == input.length && input.isNotBlank() && it.length > input.length && it.startsWith(input)
            }?.substring(input.length)
            return Suggestions(input, items, ghost)
        }
    }
}
