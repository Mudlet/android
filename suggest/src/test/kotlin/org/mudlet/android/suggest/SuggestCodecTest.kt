package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class SuggestCodecTest {
    private var now = 5_000_000L

    private fun suggester(config: CommandSuggester.Config = CommandSuggester.Config()) = CommandSuggester(config) { now }

    private fun CommandSuggester.saved() = StringBuilder().also { save(it) }.toString()

    private fun CommandSuggester.snapshot(vararg inputs: String) = inputs.map { suggest(it).items }

    @Test
    fun aRoundTripKeepsHistoryRankingAndPredictions() {
        val s = suggester()
        for (command in listOf("kill rat", "loot", "say tab\there", "say a\\b", "kill rat", "loot", "open door")) {
            s.recordSent(command)
            now += 1000
        }
        s.observeLine("The door is closed.")
        s.recordSent("open door")
        s.recordSent("kill rat")
        val text = s.saved()
        assertTrue(text.startsWith(SuggestCodec.HEADER + "\n"))

        val copy = suggester().apply { load(StringReader(text)) }
        assertEquals(text, copy.saved())
        val inputs = arrayOf("", "k", "lo", "say", "od")
        assertEquals(s.snapshot(*inputs), copy.snapshot(*inputs))
        assertTrue("say tab\there" in copy.suggest("say").items.map { it.text })
        assertTrue("say a\\b" in copy.suggest("say").items.map { it.text })
    }

    @Test
    fun loadingReplacesTheState() {
        val s = suggester()
        s.recordSent("north")
        s.load(StringReader(suggester().apply { recordSent("south") }.saved()))
        assertEquals(listOf("south"), s.suggest("").items.map { it.text })
    }

    @Test
    fun damagedLinesAreSkippedAndTheRestKept() {
        val text = listOf(
            SuggestCodec.HEADER,
            "h\t2\t1.5\t5000000\tlook",
            "h\tx\t1.5\t5000000\tbad count",
            "h\t1\tNaN\t5000000\tbad weight",
            "h\t1\t-1\t5000000\tnegative weight",
            "h\t1\t1.0\t5000000",
            "h\t1\t1.0\t5000000\tbad\\qescape",
            "h\t1\t1.0\t5000000\tdangling\\",
            "h\t1\t1.0\t5000000\t   ",
            "s\tc:look\tnorth\tInfinity",
            "s\tc:look\t\t1.0",
            "z\twhat\tis\tthis",
            "",
            "garbage",
            "h\t1\t1.0\t5000000\tnorth",
            "s\tc:look\tnorth\t2.0",
            "p\t",
            "p\tlook\textra",
        ).joinToString("\n")
        val s = suggester()
        assertEquals(CommandSuggester.LoadResult(recognised = true, skippedLines = 14), s.load(StringReader(text)))
        assertEquals(2, s.historySize)
        s.recordSent("look")
        assertEquals("north", s.suggest("").items.first().text)
        assertEquals(SuggestionSource.SEQUENCE, s.suggest("").items.first().source)
    }

    @Test
    fun anotherFormatOrVersionLoadsNothing() {
        for (text in listOf("", "mudlet-suggest 2\nh\t1\t1.0\t0\tlook", "h\t1\t1.0\t0\tlook", "\u0000\u0001binary")) {
            val s = suggester()
            s.recordSent("old")
            assertEquals(text, CommandSuggester.LoadResult(recognised = false, skippedLines = 0), s.load(StringReader(text)))
            assertEquals(text, 0, s.historySize)
        }
    }

    @Test
    fun theCapsApplyWhileLoading() {
        val big = suggester()
        repeat(50) {
            big.recordSent("say $it")
            now += 1000
        }
        val small = suggester(CommandSuggester.Config(maxHistory = 10)).apply { load(StringReader(big.saved())) }
        assertEquals(10, small.historySize)
        // the most recent are kept
        assertEquals((40..49).map { "say $it" }.toSet(), small.suggest("", limit = 20).items.map { it.text }.toSet())
    }

    @Test
    fun parseRefusesWhatWriteNeverWrites() {
        assertNull(SuggestCodec.parse("h\t1\t1.0\t0\ta\tb"))
        assertNull(SuggestCodec.parse("l\tkey\tnext"))
        assertEquals(SuggestCodec.Record.Line("a\tb", "c\nd", 1.0), SuggestCodec.parse("l\ta\\tb\tc\\nd\t1.0"))
    }
}
