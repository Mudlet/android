package org.mudlet.android.suggest

import java.io.BufferedReader
import java.io.Reader

/**
 * The saved form of a [CommandSuggester]: UTF-8 text, one record per line, fields separated by tabs, with
 * backslash, tab, newline and carriage return escaped as `\\`, `\t`, `\n` and `\r`:
 *
 * ```
 * mudlet-suggest 1
 * h	<count>	<weight>	<weight time, epoch ms>	<command>
 * p	<previous command, normalised>
 * s	<previous-command key>	<next command, normalised>	<count>
 * l	<game line key>	<next command, normalised>	<count>
 * ```
 *
 * A reader skips any line that does not parse, and reads nothing from a text whose first line is not the
 * header, so a damaged file loses only its damaged records.
 */
internal object SuggestCodec {
    const val HEADER = "mudlet-suggest 1"

    sealed interface Record {
        data class History(val text: String, val count: Int, val weight: Double, val weightAt: Long) : Record

        data class Previous(val key: String) : Record

        data class Sequence(val key: String, val next: String, val count: Double) : Record

        data class Line(val key: String, val next: String, val count: Double) : Record
    }

    fun write(
        out: Appendable,
        history: Collection<CommandSuggester.HistoryEntry>,
        previous: String?,
        sequences: TransitionTable,
        lines: TransitionTable,
    ) {
        out.append(HEADER).append('\n')
        for (entry in history) {
            line(out, "h", entry.count.toString(), entry.weight.toString(), entry.weightAt.toString(), entry.text)
        }
        previous?.let { line(out, "p", it) }
        for ((key, next, count) in sequences.entries()) line(out, "s", key, next, count.toString())
        for ((key, next, count) in lines.entries()) line(out, "l", key, next, count.toString())
    }

    fun read(input: Reader, onRecord: (Record) -> Unit): CommandSuggester.LoadResult {
        val reader = input as? BufferedReader ?: BufferedReader(input)
        if (reader.readLine() != HEADER) return CommandSuggester.LoadResult(recognised = false, skippedLines = 0)
        var skipped = 0
        while (true) {
            val line = reader.readLine() ?: break
            val record = parse(line)
            if (record == null) skipped++ else onRecord(record)
        }
        return CommandSuggester.LoadResult(recognised = true, skippedLines = skipped)
    }

    internal fun parse(line: String): Record? {
        val fields = line.split('\t').map { unescape(it) ?: return null }
        return when (fields.firstOrNull()) {
            "h" -> {
                if (fields.size != 5) return null
                val count = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                val weight = fields[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
                val weightAt = fields[3].toLongOrNull() ?: return null
                fields[4].takeIf { it.isNotBlank() }?.let { Record.History(it, count, weight, weightAt) }
            }

            "p" -> fields.takeIf { it.size == 2 && it[1].isNotBlank() }?.let { Record.Previous(it[1]) }

            "s", "l" -> {
                if (fields.size != 4 || fields[1].isEmpty() || fields[2].isEmpty()) return null
                val count = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                if (fields[0] == "s") Record.Sequence(fields[1], fields[2], count) else Record.Line(fields[1], fields[2], count)
            }

            else -> null
        }
    }

    private fun line(out: Appendable, vararg fields: String) {
        fields.forEachIndexed { i, field ->
            if (i > 0) out.append('\t')
            escape(out, field)
        }
        out.append('\n')
    }

    private fun escape(out: Appendable, field: String) {
        for (ch in field) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
    }

    // null for a dangling or unknown escape
    private fun unescape(field: String): String? {
        if ('\\' !in field) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val ch = field[i++]
            if (ch != '\\') {
                out.append(ch)
                continue
            }
            if (i == field.length) return null
            out.append(
                when (field[i++]) {
                    '\\' -> '\\'
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> return null
                },
            )
        }
        return out.toString()
    }
}
