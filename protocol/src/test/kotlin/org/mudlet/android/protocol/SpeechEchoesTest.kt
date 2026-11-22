package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEchoesTest {
    @Test
    fun wordsSetCasePunctuationAndSpacingAside() {
        assertEquals("a rat bites you", SpeechEchoes.words("  A rat\tbites   YOU! "))
        assertEquals("hp 100 sp 42", SpeechEchoes.words("HP:100  SP:42"))
        assertEquals("", SpeechEchoes.words("... !!! --"))
        assertEquals("مرحبا بك", SpeechEchoes.words("مرحبا،  بك."))
        assertEquals("ünïcödé 𝟙", SpeechEchoes.words("ÜNÏCÖDÉ 𝟙"))
    }

    @Test
    fun aLineAfterTheSameSpeechIsAnEchoOnceWithinTheWindow() {
        val echoes = SpeechEchoes(windowMs = 1_000)
        echoes.speechSaid("A rat bites you.", nowMs = 10_000)
        assertFalse("other words", echoes.lineEchoesSpeech("A rat flees.", nowMs = 10_100))
        assertTrue(echoes.lineEchoesSpeech("a rat bites you", nowMs = 10_500))
        assertFalse("the speech answered one line", echoes.lineEchoesSpeech("A rat bites you.", nowMs = 10_600))

        echoes.speechSaid("A rat bites you.", nowMs = 20_000)
        assertFalse("too late", echoes.lineEchoesSpeech("A rat bites you.", nowMs = 21_001))
    }

    @Test
    fun speechAfterTheSameLineIsAnEchoOnceWithinTheWindow() {
        val echoes = SpeechEchoes(windowMs = 1_000)
        echoes.lineRead("You are hungry.", nowMs = 5_000)
        assertTrue(echoes.speechEchoesLine("you are HUNGRY", nowMs = 6_000))
        assertFalse("the line answered one speech", echoes.speechEchoesLine("You are hungry.", nowMs = 6_000))
    }

    @Test
    fun linesAndSpeechAreKeptApart() {
        val echoes = SpeechEchoes()
        echoes.lineRead("The guard waves you through.", nowMs = 0)
        assertFalse("a line does not echo a line: the game may send one twice", echoes.lineEchoesSpeech("The guard waves you through.", nowMs = 10))
        echoes.speechSaid("Low health!", nowMs = 0)
        assertFalse("speech does not echo speech: a script may say it twice", echoes.speechEchoesLine("Low health!", nowMs = 10))
    }

    @Test
    fun textWithNoWordsIsNeverAnEcho() {
        val echoes = SpeechEchoes()
        echoes.speechSaid("...", nowMs = 0)
        assertFalse(echoes.lineEchoesSpeech("!!!", nowMs = 1))
        assertEquals(-1, SpeechEchoes.indexOfSame(listOf("---", "..."), "!!!"))
    }

    @Test
    fun indexOfSameFindsTheFirstLineWithTheSameWords() {
        val lines = listOf("You see a lantern.", "A rat bites you.", "a rat bites you")
        assertEquals(1, SpeechEchoes.indexOfSame(lines, "A RAT bites you!"))
        assertEquals(-1, SpeechEchoes.indexOfSame(lines, "A rat flees."))
    }

    @Test
    fun aFloodKeepsTheLatest() {
        val echoes = SpeechEchoes()
        for (i in 1..40) echoes.speechSaid("line $i", nowMs = 0)
        assertFalse("the oldest went", echoes.lineEchoesSpeech("line 1", nowMs = 0))
        assertTrue(echoes.lineEchoesSpeech("line 40", nowMs = 0))
    }
}
