package org.mudlet.android.data

/**
 * How Mudlet names a new log of a game, desktop Mudlet's "Log format": by the date and time logging starts, so each
 * log is a file of its own, by its day or month, which gathers each day's or month's logs in one file, or by the
 * name the player gave ([NAMED], `logFileName`). [mudletValue] is the `logFileNameFormat` that `setConfig()` takes,
 * in the order desktop's preferences list them.
 */
enum class LogNameFormat(val mudletValue: String) {
    DATE_AND_TIME("yyyy-MM-dd#HH-mm-ss"),
    DATE_T_TIME("yyyy-MM-ddTHH-mm-ss"),
    DAY("yyyy-MM-dd"),
    MONTH("yyyy-MM"),
    NAMED(""),
}

/** The end of a log's file name, which "Save log files in HTML format" ([html]) decides, as Mudlet names it. */
fun logFileExtension(html: Boolean) = if (html) ".html" else ".txt"

/** Whether Mudlet refuses [name] as a log's name: one with a path separator, which would put the log in another folder. */
fun logFileNameRefused(name: String) = '/' in name || '\\' in name
