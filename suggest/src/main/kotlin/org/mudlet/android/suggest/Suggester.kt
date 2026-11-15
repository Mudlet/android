package org.mudlet.android.suggest

import java.io.Reader

/**
 * What a [SuggestionSession] needs of a profile's suggestions: [CommandSuggester], or a stand-in in a test.
 * The calls are [CommandSuggester]'s, which documents them.
 */
interface Suggester {
    fun recordSent(text: String, hidden: Boolean = false)

    fun observeLine(line: String)

    fun suggest(input: String, cursor: Int = input.length, limit: Int = 8): Suggestions

    fun search(query: String, limit: Int): List<String>

    fun forget(command: String)

    fun save(out: Appendable)

    fun load(input: Reader): CommandSuggester.LoadResult
}
