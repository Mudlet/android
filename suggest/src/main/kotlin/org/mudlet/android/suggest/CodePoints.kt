package org.mudlet.android.suggest

// Character classes as Android's regular expressions (ICU's) read them, so that what is learned from a line, and
// the keys players have saved, are what they were when regular expressions found them

/** In `[\p{L}\p{N}_'-]`. */
internal fun isWordCodePoint(cp: Int): Boolean {
    if (cp < 0x80) {
        return cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code ||
            cp == '_'.code || cp == '\''.code || cp == '-'.code
    }
    if (Character.isLetter(cp)) return true
    val type = Character.getType(cp)
    return type == Character.DECIMAL_DIGIT_NUMBER.toInt() || type == Character.LETTER_NUMBER.toInt() ||
        type == Character.OTHER_NUMBER.toInt()
}

/** ICU's `\s`: Unicode's White_Space, where the JVM's is ASCII only. */
internal fun isWhiteSpaceCodePoint(cp: Int) = cp == ' '.code || cp in 0x09..0x0d || cp == 0x85 || (cp >= 0x80 && Character.isSpaceChar(cp))

/** ICU's `\d`: Unicode's Nd, where the JVM's is ASCII only. */
internal fun isDigitCodePoint(cp: Int) = if (cp < 0x80) cp in '0'.code..'9'.code else Character.isDigit(cp)

/** Where the run of code points from [from] that are all [inRun] ends. */
internal inline fun String.codePointRunEnd(from: Int, inRun: (Int) -> Boolean): Int {
    var i = from
    while (i < length) {
        val cp = codePointAt(i)
        if (!inRun(cp)) break
        i += Character.charCount(cp)
    }
    return i
}
