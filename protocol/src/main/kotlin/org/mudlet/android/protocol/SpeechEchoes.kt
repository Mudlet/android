package org.mudlet.android.protocol

/**
 * Keeps a game line and a script's speech of the same words from both being read by the screen reader, as when a
 * package speaks each line with `ttsSpeak()` while "Read new text aloud" is on (docs/architecture.md, "Scripts'
 * speech"). Words are the same once case, punctuation and spacing are set aside, and an echo is one within
 * [windowMs]. Each record answers one echo: the game sending the same line twice is still read twice.
 */
class SpeechEchoes(private val windowMs: Long = WINDOW_MS) {
    private class Heard(val words: String, val atMs: Long)

    private val lines = ArrayDeque<Heard>()
    private val speech = ArrayDeque<Heard>()

    /** A game line the screen reader was given at [nowMs]. */
    fun lineRead(text: String, nowMs: Long) = remember(lines, text, nowMs)

    /** A script's speech the screen reader was given at [nowMs]. */
    fun speechSaid(text: String, nowMs: Long) = remember(speech, text, nowMs)

    /** Whether the game line [text] repeats a script's speech said within the window, which it then uses up. */
    fun lineEchoesSpeech(text: String, nowMs: Long) = take(speech, text, nowMs)

    /** Whether the script's speech [text] repeats a game line read within the window, which it then uses up. */
    fun speechEchoesLine(text: String, nowMs: Long) = take(lines, text, nowMs)

    private fun remember(heard: ArrayDeque<Heard>, text: String, nowMs: Long) {
        forget(heard, nowMs)
        val words = words(text)
        if (words.isEmpty()) return
        if (heard.size == MAX_KEPT) heard.removeFirst()
        heard.addLast(Heard(words, nowMs))
    }

    private fun take(heard: ArrayDeque<Heard>, text: String, nowMs: Long): Boolean {
        forget(heard, nowMs)
        val words = words(text)
        val i = heard.indexOfFirst { it.words == words }
        if (i < 0) return false
        heard.removeAt(i)
        return true
    }

    private fun forget(heard: ArrayDeque<Heard>, nowMs: Long) {
        while (heard.isNotEmpty() && nowMs - heard.first().atMs > windowMs) heard.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 1_000L

        // A flood of lines within the window keeps only the latest
        private const val MAX_KEPT = 32

        /** The index of the first of [lines] with the same words as [text], or -1. */
        fun indexOfSame(lines: List<String>, text: String): Int {
            val words = words(text)
            return if (words.isEmpty()) -1 else lines.indexOfFirst { words(it) == words }
        }

        /** [text]'s runs of letters and digits in lower case, one space apart: empty for one with none. */
        fun words(text: String): String {
            val out = StringBuilder(text.length)
            var space = false
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                i += Character.charCount(c)
                if (Character.isLetterOrDigit(c)) {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.appendCodePoint(Character.toLowerCase(c))
                } else {
                    space = true
                }
            }
            return out.toString()
        }
    }
}
