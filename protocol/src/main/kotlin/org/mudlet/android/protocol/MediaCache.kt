package org.mudlet.android.protocol

/**
 * Desktop Mudlet's "Clear stored media" button (Preferences, Privacy and security) as Lua the app runs in the profile:
 * Mudlet's `purgeMediaCache()`, then the button's message in the main console, coloured as Mudlet's `postMessage()`
 * colours an `[  OK  ]` or `[ WARN ]` line, and its words without the bracketed prefix to screen readers, as the
 * settings page hides the console.
 */
object MediaCache {
    // Stands for the reason in the failure message until the Lua puts Mudlet's in
    private const val REASON = "\u0000"

    /**
     * [cleared] is the message for a cache cleared; [failed] makes the one for a failure from the reason Mudlet gives,
     * which it does not translate.
     */
    fun clearLua(cleared: String, failed: (reason: String) -> String): String {
        val (okPrefix, okTail) = postMessageParts(cleared)
        val (warnPrefix, warnTail) = postMessageParts(failed(REASON))
        val q = ConsoleZoom::luaString
        // A translation may have left the reason out
        val failedTail = warnTail.split(REASON, limit = 2).let { if (it.size == 2) "${q(it[0])} .. tostring(reason) .. ${q(it[1])}" else q(it[0]) }
        return """
            |do
            |  local ok, reason = true, nil
            |  local prefix, tail
            |  if ok then
            |    prefix, tail = ${q(okPrefix)}, ${q(okTail)}
            |    setFgColor(0, 160, 0)
            |  else
            |    prefix, tail = ${q(warnPrefix)}, $failedTail
            |    setFgColor(0, 150, 190)
            |  end
            |  echo(prefix)
            |  if ok then setFgColor(190, 100, 50) else setFgColor(190, 150, 0) end
            |  echo(tail .. "\n")
            |  resetFormat()
            |  announce(tail)
            |end
        """.trimMargin()
    }

    /**
     * [message] split as Mudlet's `postMessage()` splits it to colour it: the prefix in brackets up to the hyphen and
     * the spaces after it, then the rest. A message without one is all rest.
     */
    internal fun postMessageParts(message: String): Pair<String, String> {
        val open = message.indexOf('[')
        val close = message.indexOf(']')
        val hyphen = message.indexOf("- ")
        if (open < 0 || close <= 0 || close >= hyphen) return "" to message
        var end = hyphen + 1
        while (end < message.length && message[end] == ' ') end++
        return message.substring(0, end) to message.substring(end)
    }
}
