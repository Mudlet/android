package org.mudlet.android.report

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Takes the player's secrets out of a report's text: each of [passwords] becomes [PASSWORD] and each of [logins]
 * [LOGIN], ignoring case, as written and in the escaped forms a log line may carry them in (JSON's or C's backslashes,
 * a URL's percent-encoding).
 */
class Redactor(passwords: Collection<String>, logins: Collection<String>) {
    // Longest first, so a secret that holds a shorter one goes whole
    private val replacements: List<Pair<String, String>> =
        (passwords.flatMap { forms(it).map { form -> form to PASSWORD } } + logins.flatMap { forms(it).map { form -> form to LOGIN } })
            .distinctBy { it.first.lowercase() }
            .sortedByDescending { it.first.length }

    fun redact(text: String): String = replacements.fold(text) { acc, (secret, label) -> acc.replace(secret, label, ignoreCase = true) }

    private fun forms(secret: String): Set<String> {
        if (secret.isEmpty()) return emptySet()
        val escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"")
        val encoded = URLEncoder.encode(secret, "UTF-8")
        return setOf(secret, escaped, encoded, encoded.replace("+", "%20"))
    }

    companion object {
        const val PASSWORD = "[password]"
        const val LOGIN = "[login]"
    }
}

/**
 * The text of a report: [header] (the versions, the device, the recent exits) and then as many of [log]'s newest
 * lines as fit in [maxChars], every line through [redactor] first. A null [log] is left out, as when a secret could
 * not be read and so could not be taken out of it. A report is sent as an Intent's text and put on the clipboard, both
 * through Binder, whose transaction is at most 1 MB for the whole process.
 */
fun composeReport(header: List<String>, log: List<String>?, redactor: Redactor, maxChars: Int = MAX_REPORT_CHARS): String {
    val top = header.joinToString("\n", postfix = "\n\n") { redactor.redact(it) }
    if (log == null) return top + LOG_WITHHELD + "\n"
    var room = maxChars - top.length - LOG_HEADING.length - 1 - leftOut(log.size).length
    val kept = ArrayDeque<String>()
    for (line in log.asReversed()) {
        val clean = redactor.redact(line)
        if (clean.length + 1 > room) break
        kept.addFirst(clean)
        room -= clean.length + 1
    }
    val cut = if (kept.size < log.size) leftOut(log.size - kept.size) else ""
    return top + LOG_HEADING + cut + kept.joinToString("") { "\n$it" } + "\n"
}

private fun leftOut(lines: Int) = "\n($lines older lines left out)"

/**
 * The address of a new issue on this app's GitHub with [summary] (the report's header) in its text and room for what
 * happened; GitHub refuses an address much over 8 KB, so the log itself is pasted by the player.
 */
fun newIssueUrl(summary: List<String>): String {
    val body = buildString {
        append("**What happened**\n\n\n**What you expected**\n\n\n**Report**\n")
        summary.forEach { append(it).append('\n') }
        append("\nThe full report, with the log, is on the clipboard: paste it here.\n")
    }
    return "$ISSUES_URL?body=" + URLEncoder.encode(body, "UTF-8").replace("+", "%20")
}

/**
 * One of Mudlet's profile items, as the engine's ProfileStore writes it: a QString in a QDataStream at Qt_5_12, its
 * length in bytes as a big-endian quint32 (0xFFFFFFFF for a null string), then UTF-16BE. Null if [bytes] is not one.
 */
fun readQString(bytes: ByteArray): String? {
    if (bytes.size < 4) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val length = buffer.int.toLong() and 0xFFFFFFFFL
    if (length == 0xFFFFFFFFL) return ""
    if (length % 2 != 0L || length > bytes.size - 4) return null
    return String(bytes, 4, length.toInt(), Charsets.UTF_16BE)
}

const val ISSUES_URL = "https://github.com/Mudlet/android/issues/new"
const val MAX_REPORT_CHARS = 80_000
private const val LOG_HEADING = "Log (newest last):"
const val LOG_WITHHELD = "Log: left out, as a saved password or login could not be read to take it out of the log."
