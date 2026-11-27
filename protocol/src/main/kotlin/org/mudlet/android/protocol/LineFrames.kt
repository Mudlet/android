package org.mudlet.android.protocol

/**
 * The line, tail and lineUpdated frames read straight from their bytes into what [FrameReader.parse] would make of them
 * through JSONObject. In a flood they are nearly every frame, and JSONObject's tokenizer, map and boxed numbers are
 * most of the reader thread's time and of what it allocates for each line.
 *
 * It reads only what LineJson.cpp writes, and what JSONObject reads the same way: a frame with anything else in it (a
 * key it does not know, whitespace, a number with a fraction or a leading zero, an escape JSON has not, a raw control
 * character, a key twice) is null, for JSONObject to read as it always has.
 */
internal object LineFrames {
    fun parse(payload: ByteArray): EngineMessage? = Reader(payload).frame()

    private const val PROMPT = 1
    private const val REPLAY = 2
    private const val RUNS = 4
    private const val SEQ = 8
    private const val T = 16
    private const val TEXT = 32
    private const val TIME = 64
    private const val DELETED = 128

    private const val LINE = 1
    private const val TAIL = 2
    private const val LINE_UPDATED = 3

    private val KEY_NAMES = arrayOf("prompt", "replay", "runs", "seq", "t", "text", "time", "deleted").map { it.toByteArray() }.toTypedArray()
    private val KEY_BITS = intArrayOf(PROMPT, REPLAY, RUNS, SEQ, T, TEXT, TIME, DELETED)
    private val TYPE_NAMES = arrayOf("line", "tail", "lineUpdated").map { it.toByteArray() }.toTypedArray()
    private val TYPE_VALUES = intArrayOf(LINE, TAIL, LINE_UPDATED)
    private val NO_RUNS = IntArray(0)
    private val TRUE = "true".toByteArray()
    private val FALSE = "false".toByteArray()
    private const val SPACE = 0x20
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()

    // Digits a value may have and still be what JSONObject reads it as: an Int for a run's, a Long for the rest
    private const val INT_DIGITS = 9
    private const val LONG_DIGITS = 18

    private class Reader(private val p: ByteArray) {
        private var at = 0
        private var bad = false

        fun frame(): EngineMessage? {
            if (!take('{')) return null
            var seen = 0
            var prompt = false
            var replay = false
            var deleted = false
            var runs = NO_RUNS
            var seq = 0L
            var time = 0L
            var type = 0
            var text: String? = null
            do {
                val key = name(KEY_NAMES, KEY_BITS)
                if (key == 0 || (seen and key) != 0 || !take(':')) return null
                seen = seen or key
                when (key) {
                    PROMPT -> prompt = bool()
                    REPLAY -> replay = bool()
                    DELETED -> deleted = bool()
                    RUNS -> runs = runs() ?: return null
                    SEQ -> seq = number(LONG_DIGITS)
                    TIME -> time = number(LONG_DIGITS)
                    T -> type = name(TYPE_NAMES, TYPE_VALUES)
                    TEXT -> text = string() ?: return null
                }
                if (bad) return null
            } while (take(','))
            if (!take('}') || at != p.size) return null
            return when (type) {
                // Without its seq, or a line without its text, the frame is JSONObject's to throw on
                LINE -> if ((seen and SEQ) == 0 || text == null) null else EngineMessage.Line(seq, text, runs, prompt, time, replay)

                TAIL -> EngineMessage.Tail(text.orEmpty(), runs, prompt, time)

                LINE_UPDATED -> if ((seen and SEQ) == 0) null else EngineMessage.LineUpdated(seq, text.orEmpty(), runs, prompt, deleted)

                else -> null
            }
        }

        private fun take(c: Char): Boolean {
            if (at < p.size && p[at] == c.code.toByte()) {
                at++
                return true
            }
            return false
        }

        // The value of whichever of names the next string is, with no escape in it; 0 for none
        private fun name(names: Array<ByteArray>, values: IntArray): Int {
            if (!take('"')) return 0
            val from = at
            while (at < p.size && p[at] != QUOTE && p[at] != BACKSLASH) at++
            if (!take('"')) return 0
            for (n in names.indices) {
                if (same(names[n], from, at - 1)) return values[n]
            }
            return 0
        }

        private fun same(bytes: ByteArray, from: Int, to: Int): Boolean {
            if (bytes.size != to - from) return false
            for (k in bytes.indices) {
                if (p[from + k] != bytes[k]) return false
            }
            return true
        }

        private fun bool(): Boolean {
            if (matches(TRUE)) return true
            if (!matches(FALSE)) bad = true
            return false
        }

        private fun matches(word: ByteArray): Boolean {
            if (at + word.size > p.size || !same(word, at, at + word.size)) return false
            at += word.size
            return true
        }

        private fun number(digits: Int): Long {
            val negative = take('-')
            val from = at
            var value = 0L
            while (at < p.size && p[at] in ZERO..NINE) value = value * 10 + (p[at++] - ZERO)
            val n = at - from
            // json.org reads -0 as a Double, and Android's org.json a leading zero as octal
            if (n == 0 || n > digits || (n > 1 && p[from] == ZERO) || (negative && value == 0L)) bad = true
            return if (negative) -value else value
        }

        // As flattenRuns lays them out: start, length and fg, which each run must have, then bg, attributes and link
        private fun runs(): IntArray? {
            if (!take('[')) return null
            if (take(']')) return NO_RUNS
            var flat = IntArray(RUN_STRIDE * 4)
            var count = 0
            do {
                if (!take('[')) return null
                if (flat.size < (count + 1) * RUN_STRIDE) flat = flat.copyOf(flat.size * 2)
                val base = count * RUN_STRIDE
                flat[base + 3] = -1
                var i = 0
                if (!take(']')) {
                    do {
                        val value = number(INT_DIGITS).toInt()
                        if (bad) return null
                        if (i < RUN_STRIDE) flat[base + i] = value
                        i++
                    } while (take(','))
                    if (!take(']')) return null
                }
                if (i < 3) return null
                count++
            } while (take(','))
            if (!take(']')) return null
            return if (flat.size == count * RUN_STRIDE) flat else flat.copyOf(count * RUN_STRIDE)
        }

        // A string as JSONObject reads it from the frame's text, a stretch at a time between escapes: quotes and
        // backslashes are ASCII, so no UTF-8 sequence is cut, and a broken one is replaced as decoding the whole would
        private fun string(): String? {
            if (!take('"')) return null
            var from = at
            var i = at
            var built: StringBuilder? = null
            while (i < p.size) {
                val b = p[i]
                when {
                    b == QUOTE -> {
                        at = i + 1
                        val last = String(p, from, i - from, Charsets.UTF_8)
                        return built?.append(last)?.toString() ?: last
                    }

                    b == BACKSLASH -> {
                        if (i + 1 >= p.size) return null
                        val sb = built ?: StringBuilder(i - from + 16).also { built = it }
                        sb.append(String(p, from, i - from, Charsets.UTF_8))
                        when (p[i + 1].toInt().toChar()) {
                            '"' -> sb.append('"')

                            '\\' -> sb.append('\\')

                            '/' -> sb.append('/')

                            'b' -> sb.append('\b')

                            'f' -> sb.append('\u000c')

                            'n' -> sb.append('\n')

                            'r' -> sb.append('\r')

                            't' -> sb.append('\t')

                            'u' -> {
                                val unit = hex4(i + 2)
                                if (unit < 0) return null
                                sb.append(unit.toChar())
                                i += 4
                            }

                            else -> return null
                        }
                        i += 2
                        from = i
                        continue
                    }

                    b >= 0 && b < SPACE -> return null
                }
                i++
            }
            return null
        }

        private fun hex4(from: Int): Int {
            if (from + 4 > p.size) return -1
            var unit = 0
            for (k in from until from + 4) {
                val digit = Character.digit(p[k].toInt(), 16)
                if (digit < 0) return -1
                unit = unit * 16 + digit
            }
            return unit
        }
    }
}
