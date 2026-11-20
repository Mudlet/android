package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleCellsTest {
    @Test
    fun aRowOfLettersTakesACellALetter() {
        assertEquals(3, ConsoleCells.end("abcdef", 3))
        assertEquals(6, ConsoleCells.end("abcdef", 40))
        assertEquals(0, ConsoleCells.end("abcdef", 0))
        assertEquals(0, ConsoleCells.end("", 3))
    }

    @Test
    fun aWideCharacterTakesTwoCellsAndIsLeftOutWhereOnlyOneIsLeft() {
        // Four CJK characters, eight cells
        val cjk = "\u4E2D\u6587\u5B57\u7B26"
        assertEquals(2, ConsoleCells.end(cjk, 4))
        assertEquals(2, ConsoleCells.end(cjk, 5))
        assertEquals(1, ConsoleCells.end("a$cjk", 2))
        // Hangul syllables, kana and fullwidth forms too
        assertEquals(1, ConsoleCells.end("\uD55C\uAE00", 3))
        assertEquals(2, ConsoleCells.end("\u30AB\u30CA\uFF21", 5))
        // A Hangul syllable written as its initial, medial and final jamo: one grapheme, two cells; a lone medial, none
        assertEquals(3, ConsoleCells.end("\u1100\u1161\u11A8a", 2))
        assertEquals(2, ConsoleCells.end("\u1161a", 1))
        // The place-of-interest sign, which the desktop widens itself
        assertEquals(0, ConsoleCells.end("\u2318", 1))
    }

    @Test
    fun aCharacterOutsideTheBmpIsNeverSplit() {
        // U+20000, a CJK ideograph outside the BMP (two cells), then a letter
        val row = "\uD840\uDC00a"
        assertEquals(0, ConsoleCells.end(row, 1))
        assertEquals(2, ConsoleCells.end(row, 2))
        assertEquals(3, ConsoleCells.end(row, 3))
        // U+1D400, a mathematical letter outside the BMP: one cell, two UTF-16 units
        assertEquals(4, ConsoleCells.end("\uD835\uDC00\uD835\uDC00b", 2))
    }

    @Test
    fun combiningMarksAndFormatCharactersTakeNoCellAndStayWithTheirLetter() {
        // e and a combining acute, twice: two cells, both marks kept
        assertEquals(4, ConsoleCells.end("e\u0301e\u0301x", 2))
        // Thai: a consonant and a vowel above it, then another consonant
        assertEquals(2, ConsoleCells.end("\u0E01\u0E34\u0E02", 1))
        // Thai SARA AM, a letter of its own but spacing as a mark of the consonant before it: one cell, never split
        assertEquals(2, ConsoleCells.end("\u0E01\u0E33x", 1))
        assertEquals(0, ConsoleCells.end("\u0E01\u0E33x", 0))
        // Halfwidth katakana with the voiced and semi-voiced marks: one cell each
        assertEquals(4, ConsoleCells.end("\uFF76\uFF9E\uFF8A\uFF9Fx", 2))
        // A soft hyphen and a zero-width space take none
        assertEquals(4, ConsoleCells.end("a\u00ADb\u200Bc", 2))
        assertEquals(0, ConsoleCells.width(0x200D))
        assertEquals(0, ConsoleCells.width(0x0007))
    }

    @Test
    fun anEmojiTakesTwoCellsWhateverItIsMadeOf() {
        // A face, a face with a skin tone, and a family joined by zero-width joiners: each two cells
        val face = "\uD83D\uDE00"
        val toned = "\uD83D\uDC4D\uD83C\uDFFD"
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        for (emoji in listOf(face, toned, family)) {
            assertEquals(emoji.length, ConsoleCells.end(emoji + "a", 2))
            assertEquals(0, ConsoleCells.end(emoji, 1))
        }
        // A flag, two regional indicators, takes the one cell the desktop gives a regional indicator
        val flag = "\uD83C\uDDEB\uD83C\uDDF7"
        assertEquals(flag.length, ConsoleCells.end(flag + flag, 1))
        assertEquals(2 * flag.length, ConsoleCells.end(flag + flag + "a", 2))
    }

    @Test
    fun aZeroWidthJoinerJoinsALetterToNothingAfterIt() {
        // A letter and a joiner, then a tab, which still runs to the stop, and a letter: nine cells
        assertEquals(3, ConsoleCells.end("a\u200D\tb", 8))
        assertEquals(4, ConsoleCells.end("a\u200D\tb", 9))
        // A letter and a joiner, then a CJK character, which still takes two cells of its own
        assertEquals(2, ConsoleCells.end("a\u200D\u4E2D", 2))
        assertEquals(3, ConsoleCells.end("a\u200D\u4E2D", 3))
    }

    @Test
    fun aSymbolDrawnAsAnEmojiTakesTwoCells() {
        // A smiling face symbol takes one; followed by U+FE0F, which asks for its emoji form, two
        assertEquals(2, ConsoleCells.end("\u263Aa", 2))
        assertEquals(0, ConsoleCells.end("\u263A\uFE0Fa", 1))
        assertEquals(2, ConsoleCells.end("\u263A\uFE0Fa", 2))
        // A keycap: a digit, U+FE0F and U+20E3; a letter with U+FE0F has no emoji form and stays one
        assertEquals(0, ConsoleCells.end("1\uFE0F\u20E3", 1))
        assertEquals(2, ConsoleCells.end("\u00E9\uFE0Fa", 1))
        // A squared A outside the BMP, U+1F170: one cell, two with U+FE0F
        assertEquals(2, ConsoleCells.end("\uD83C\uDD70a", 1))
        assertEquals(0, ConsoleCells.end("\uD83C\uDD70\uFE0Fa", 1))
        assertEquals(3, ConsoleCells.end("\uD83C\uDD70\uFE0Fa", 2))
    }

    @Test
    fun aTabRunsToTheNextStop() {
        assertEquals(2, ConsoleCells.end("ab\tc", 7))
        assertEquals(3, ConsoleCells.end("ab\tc", 8))
        assertEquals(2, ConsoleCells.end("\t\tc", 16))
        assertEquals(3, ConsoleCells.end("\t\tc", 17))
        assertEquals(1, ConsoleCells.end("\t\t", 15))
    }
}
