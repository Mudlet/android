package org.mudlet.android.ui.scripts

import org.json.JSONObject

/** The Lua editor's text with the cursor in it, as an edit leaves them. */
data class LuaEdit(val text: String, val cursor: Int)

/** One of Mudlet's Lua functions, as its function list has it: its [signature] is how it is called, such as `send(command, [echo])`. */
data class LuaFunction(val name: String, val signature: String)

/** One step of indentation, as Mudlet's own Lua is written. */
const val LUA_INDENT = "  "

/** The fewest characters of a name typed before completions are offered. */
const val COMPLETION_MIN_PREFIX = 2

/** The most completions offered at once. */
const val COMPLETION_MAX = 8

/** The asset with Mudlet's function list, which scripts/sync-mudlet-games.py writes. */
const val LUA_FUNCTIONS_ASSET = "lua/functions.json"

/**
 * Mudlet's function list as [LUA_FUNCTIONS_ASSET] has it (`functions`: each function's name with how it is called),
 * by name; empty for text that is no such list.
 */
fun parseLuaFunctions(json: String): List<LuaFunction> {
    val list = runCatching { JSONObject(json).optJSONObject("functions") }.getOrNull() ?: return emptyList()
    return list.keys().asSequence().map { LuaFunction(it, list.optString(it)) }.sortedBy { it.name.lowercase() }.toList()
}

/** [text] with [inserted] in place of what is selected, from [start] to [end], and the cursor after it. */
fun insertAt(text: String, start: Int, end: Int, inserted: String): LuaEdit {
    val from = minOf(start, end).coerceIn(0, text.length)
    val to = maxOf(start, end).coerceIn(0, text.length)
    return LuaEdit(text.replaceRange(from, to, inserted), from + inserted.length)
}

// What ends a line that opens a block, whose next line goes a step deeper
private val opensBlock = Regex("""(\b(then|do|else|repeat)|\bfunction\b[^)]*\)|[{(])\s*(--.*)?$""")

/**
 * [after] as the editor is to show it once the player has typed it over [before]: a new line starts where the line
 * above it does, a step deeper under a line that opens a block (`then`, `do`, `else`, `repeat`, a `function` heading,
 * `{` or `(`). Any other edit is left as it is.
 */
fun autoIndent(before: String, after: LuaEdit): LuaEdit {
    val at = after.cursor - 1
    val typedNewLine = at >= 0 && after.text.length == before.length + 1 && after.text[at] == '\n' && after.text.removeRange(at, at + 1) == before
    if (!typedNewLine) return after
    val line = after.text.substring(after.text.lastIndexOf('\n', at - 1) + 1, at)
    val indent = line.takeWhile { it == ' ' || it == '\t' } + if (opensBlock.containsMatchIn(line) && line.isEmpty()) LUA_INDENT else ""
    return insertAt(after.text, after.cursor, after.cursor, indent)
}

private fun Char.inName() = isLetterOrDigit() || this == '_' || this == '.' || this == ':'

/**
 * The name being typed at [cursor], to complete: what of a name, with its dots and colon, ends there, or null when
 * that is shorter than [COMPLETION_MIN_PREFIX], starts with a digit, or the name goes on past the cursor.
 */
fun completionPrefix(text: String, cursor: Int): String? {
    if (cursor !in 0..text.length || (cursor < text.length && text[cursor].inName())) return null
    val prefix = text.substring(0, cursor).takeLastWhile { it.inName() }
    return prefix.takeIf { it.length >= COMPLETION_MIN_PREFIX && !it[0].isDigit() }
}

/**
 * The functions to offer for [prefix], at most [COMPLETION_MAX]: those whose name starts with it as typed first, then
 * those that do whatever the case, each group by name; none for a name typed in full.
 */
fun completions(prefix: String, functions: List<LuaFunction>): List<LuaFunction> {
    val (sameCase, otherCase) = functions.filter { it.name.startsWith(prefix, ignoreCase = true) && it.name != prefix }.partition { it.name.startsWith(prefix) }
    return (otherCase + sameCase).take(COMPLETION_MAX)
}

/** [text] with the name typed at [cursor] ([completionPrefix]) completed to [function], and an open bracket for its arguments. */
fun complete(text: String, cursor: Int, function: LuaFunction): LuaEdit {
    val prefix = completionPrefix(text, cursor).orEmpty()
    return insertAt(text, cursor - prefix.length, cursor, function.name + "(")
}

/** Where line [line], from 1, starts in [text]; the last line's start for a line past it. */
fun lineStart(text: String, line: Int): Int {
    var start = 0
    repeat(line) {
        val next = text.indexOf('\n', start)
        if (next < 0) return start
        start = next + 1
    }
    return start
}

/** How many lines [text] has: one more than its line breaks. */
fun lineCount(text: String): Int = text.count { it == '\n' } + 1
