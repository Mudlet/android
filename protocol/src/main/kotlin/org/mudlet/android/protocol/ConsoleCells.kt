package org.mudlet.android.protocol

import java.text.BreakIterator

/**
 * The columns of a console's grid that text takes, counted as desktop Mudlet's TTextEdit counts them (layoutLine(),
 * layoutGrapheme() and graphemeInfo in TTextProperties.h): the text is split into graphemes, each a letter and what
 * goes with it, such as its marks or the emoji a zero-width joiner joins it to, by Unicode's rules (UAX #29), as the
 * desktop's QTextBoundaryFinder splits it. Each grapheme takes the cells of its first character: two for an East Asian
 * wide or fullwidth one or an emoji (a flag, two regional indicators, takes the one the desktop gives the first), none
 * for a combining mark, a format character such as a zero-width joiner, or a control, and one for any other; a symbol
 * followed by U+FE0F, which fonts draw as an emoji, takes two; a tab runs to the next stop of [TAB_STOP] columns. East
 * Asian ambiguous characters take one and controls none, the desktop's defaults (a profile can set the first to two,
 * and draw the second as pictures or in an OEM font, a cell each: settings the app does not get). The wide characters
 * are the desktop's table's; the ones that take none are found by their general category (`Character.getType()`).
 *
 * The graphemes ([BreakIterator]'s) and the general categories follow the runtime's Unicode version, not Qt's: on
 * Android the device's ICU, older on an older Android (Android 9's is Unicode 10), and on the JVM the JDK's (JDK 21's
 * is Unicode 15.0, before 15.1 made an Indic consonant, virama and consonant one grapheme), so text that only a newer
 * version than the runtime's joins or makes a combining mark can be counted a cell or two off the desktop.
 */
object ConsoleCells {
    /** The columns between the desktop console's tab stops. */
    internal const val TAB_STOP = 8

    private const val EMOJI_PRESENTATION = '\uFE0F'
    private const val KEYCAP = '\u20E3'

    /**
     * The end, in [text]'s UTF-16 units, of its first [columns] cells: of the graphemes that fit in them whole, so
     * neither a surrogate pair nor a grapheme is split, and a wide character that would take the last cell and one
     * past it is left out. All of [text] when it fits.
     */
    fun end(text: CharSequence, columns: Int): Int {
        val row = text.toString()
        var cells = 0
        var start = 0
        var next = if (row.isEmpty()) BreakIterator.DONE else Character.charCount(row.codePointAt(0))
        while (next != BreakIterator.DONE) {
            val base = row.codePointAt(start)
            val width = if (base == '\t'.code) TAB_STOP - cells % TAB_STOP else withPresentation(row, start, next, base)
            if (cells + width > columns) return start
            cells += width
            start = next
            next = if (start == row.length) BreakIterator.DONE else start + Character.charCount(row.codePointAt(start))
        }
        return row.length
    }

    /** The cells [codePoint] takes as the first character of a grapheme, a tab aside: 0, 1 or 2. */
    internal fun width(codePoint: Int): Int = when {
        codePoint in 0x20..0x7E -> 1
        isWide(codePoint) -> 2
        isZeroWidth(codePoint) -> 0
        else -> 1
    }

    // The cells of the grapheme from [start] to [end] of [row], whose first character is [base], as
    // graphemeInfo::withPresentation(): a 1-wide symbol with an emoji form, or a keycap, followed by U+FE0F takes two
    private fun withPresentation(row: String, start: Int, end: Int, base: Int): Int {
        fun has(c: Char) = (start until end).any { row[it] == c }
        val width = width(base)
        return if (width == 1 && has(EMOJI_PRESENTATION) && (hasEmojiForm(base) || has(KEYCAP))) 2 else width
    }

    private fun isZeroWidth(c: Int): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT,
        Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE,
        -> true

        // Hangul's medial and final jamo, which take none of their own, and the noncharacters
        else -> c in 0x1160..0x11FF || c in 0xD7B0..0xD7FF || c in 0xFDD0..0xFDEF || (c and 0xFFFE) == 0xFFFE
    }

    // The symbols that have an emoji form (Unicode's emoji-variation-sequences.txt), as graphemeInfo::hasEmojiForm()
    private fun hasEmojiForm(c: Int): Boolean = c == 0xA9 || c == 0xAE || c in 0x2000..0x2BFF || c == 0x3030 || c == 0x303D || c == 0x3297 || c == 0x3299 || c >= 0x1F000

    // In a run when it is one of the runs' ends, or binarySearch() would put it after a run's first: at an odd index
    private fun isWide(c: Int): Boolean {
        val i = WIDE.binarySearch(c)
        return i >= 0 || (-i - 1) % 2 == 1
    }

    // The characters that take two cells, as first and last of each run: those Mudlet's codepointWidth() gives two from
    // widechar_width.h (Unicode 17), the East Asian wide and fullwidth ones and the emoji Unicode 9 widened, and the
    // three it widens itself (U+2318, U+2764, U+1F6E1)
    private val WIDE = intArrayOf(
        0x1100, 0x115F, 0x2318, 0x2318, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0,
        0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2630, 0x2637, 0x2648, 0x2653, 0x267F, 0x267F,
        0x268A, 0x268F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
        0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA,
        0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E,
        0x2753, 0x2755, 0x2757, 0x2757, 0x2764, 0x2764, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x2E99, 0x2E9B, 0x2EF3, 0x2F00, 0x2FD5,
        0x2FF0, 0x3029, 0x3030, 0x303E, 0x3041, 0x3096, 0x309B, 0x30FF, 0x3105, 0x312F, 0x3131, 0x318E,
        0x3190, 0x31E5, 0x31EF, 0x321E, 0x3220, 0x3247, 0x3250, 0xA48C, 0xA490, 0xA4C6, 0xA960, 0xA97C,
        0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE52, 0xFE54, 0xFE66, 0xFE68, 0xFE6B,
        0xFF01, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE3, 0x16FF2, 0x16FF6, 0x17000, 0x18CD5, 0x18CFF, 0x18D1E,
        0x18D80, 0x18DF2, 0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE, 0x1B000, 0x1B122, 0x1B132, 0x1B132,
        0x1B150, 0x1B152, 0x1B155, 0x1B155, 0x1B164, 0x1B167, 0x1B170, 0x1B2FB, 0x1D300, 0x1D356, 0x1D360, 0x1D376,
        0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F202, 0x1F210, 0x1F23B,
        0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265, 0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C,
        0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E,
        0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A,
        0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2,
        0x1F6D5, 0x1F6D8, 0x1F6DC, 0x1F6DF, 0x1F6E1, 0x1F6E1, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB,
        0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA8A,
        0x1FA8E, 0x1FAC6, 0x1FAC8, 0x1FAC8, 0x1FACD, 0x1FADC, 0x1FADF, 0x1FAEA, 0x1FAEF, 0x1FAF8, 0x20000, 0x2FFFD,
        0x30000, 0x3FFFD,
    )
}
