package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/** Learning from a game line splits and keys it exactly as the regular expressions did on Android. */
class ObserveLineTest {
    @Test
    fun everyCodePointIsSplitAndKeyedAsOnAndroid() {
        for (cp in 0..Character.MAX_CODE_POINT) {
            val c = String(Character.toChars(cp))
            assertSameAsRegex("Ab${c}12${c}Cd x${c}y 3$c the ${c}Ann$c'-x$c 7${c}7 end")
            assertSameAsRegex("$c$c$c ab$c")
        }
    }

    @Test
    fun randomLinesAreSplitAndKeyedAsOnAndroid() {
        val rng = Random(9011)
        repeat(50_000) {
            assertSameAsRegex(buildString { repeat(rng.nextInt(0, 100)) { append(TRICKY[rng.nextInt(TRICKY.size)]) } })
        }
    }

    @Test
    fun aFightsLineIsLearnedWithLittleGarbage() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assertTrue("this JVM counts what each thread allocates", threads != null && threads.isThreadAllocatedMemorySupported)
        val rng = Random(9011)
        val lines = List(2_000) { FIGHT[rng.nextInt(FIGHT.size)].replace("#", rng.nextInt(1, 999).toString()) }
        val suggester = CommandSuggester()
        val id = Thread.currentThread().id
        var perLine = Long.MAX_VALUE
        // The fewest of five rounds: the first also loads and compiles the code
        repeat(5) {
            val before = threads!!.getThreadAllocatedBytes(id)
            for (line in lines) suggester.observeLine(line)
            perLine = minOf(perLine, (threads.getThreadAllocatedBytes(id) - before) / lines.size)
        }
        assertTrue("$perLine bytes allocated a line", perLine < MAX_BYTES_A_LINE)
    }

    private fun assertSameAsRegex(line: String) {
        val expected = regexWords(line) to regexNormalizeLine(line)
        val actual = words(line) to CommandSuggester.normalizeLine(line)
        if (actual != expected) assertEquals(line.escaped(), expected, actual)
    }

    private fun words(line: String) = buildList { forEachOutputWord(line) { add(it) } }

    private fun regexWords(line: String) = WORD.findAll(line).map { it.value.trim('\'', '-') }.filter { it.length >= 3 && !it.all(Char::isDigit) }.toList()

    private fun regexNormalizeLine(line: String): String? {
        val words = line.trim().split(SPACES).filter { it.isNotEmpty() }.take(16)
        val key = words.mapIndexed { i, word ->
            if (i > 0 && word[0].isUpperCase()) "*" + word.dropWhile(Char::isLetter) else DIGITS.replace(word.lowercase(), "#")
        }.joinToString(" ")
        return key.takeIf { it.count(Char::isLetter) >= 3 }
    }

    private fun String.escaped() = map { if (it.code in 0x21..0x7e) "$it" else "\\u%04x".format(it.code) }.joinToString("")

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}_'-]+")

        // Android's regular expressions are ICU's, whose \s and \d are Unicode's White_Space and Nd: (?U) gives
        // the JVM the same, where without it they are ASCII only
        val SPACES = Regex("(?U)\\s+")
        val DIGITS = Regex("(?U)\\d+")

        val TRICKY = listOf(
            "a", "Z", "é", "Ä", "ß", "İ", "ı", "Σ", "ς", "ΟΔΟΣ", "ǅ", "ʰ", "中", "ー", "_", "'", "-", "--", "''",
            " ", "  ", "\t", "\n", "\u000b", "\u000c", "\r", "\u001c", "\u0085", " ", " ", " ",
            " ", " ", " ", "　", "​", "", "0", "7", "42", "٣", "۹", "१", "²", "½", "Ⅻ",
            "𝟎", "𝐀", "𐐀", "😀", "\ud800", "\udc00", ".", ",", "<", ">", "!",
            "Ann", "bob", "HP", "x2", "3rd", "o'clock", "-ish", "well-known",
        )

        val FIGHT = listOf(
            "Ann slashes at the goblin, wounding it for # damage.",
            "The goblin hits you for # damage.",
            "You have # health and # mana left.",
            "Bob casts a fireball at the orc chieftain!",
            "<# hp # mp # mv> ",
            "The orc chieftain is badly wounded and bleeding.",
        )

        // A line's key, its words and their lower case forms come to about 900; through the regular expressions,
        // nearer 6,000
        const val MAX_BYTES_A_LINE = 2_500L
    }
}
