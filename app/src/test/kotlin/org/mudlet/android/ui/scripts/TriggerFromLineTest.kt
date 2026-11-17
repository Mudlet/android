package org.mudlet.android.ui.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType

class TriggerFromLineTest {
    private val hit = "You hit the goblin for 12 damage."

    private fun TriggerFromLine.with(vararg roles: Pair<String, WordRole>): TriggerFromLine {
        val words = words
        return copy(roles = this.roles.mapIndexed { i, role -> roles.firstOrNull { it.first == words[i] }?.second ?: role })
    }

    @Test
    fun aLinesWordsAreItsLettersAndDigitsWithWhatJoinsThem() {
        assertEquals(listOf("You", "hit", "the", "goblin", "for", "12", "damage"), TriggerFromLine.of(hit).words)
        assertEquals(listOf("The", "goblin's", "well-known", "über", "axe"), TriggerFromLine.of("The goblin's well-known über axe!").words)
        assertEquals(emptyList<String>(), TriggerFromLine.of("--- ***").words)
    }

    @Test
    fun aTapGivesAWordItsNextRoleAndTheThirdBringsItBack() {
        val line = TriggerFromLine.of(hit)
        val once = line.withNextRole(3)
        assertEquals(WordRole.CAPTURE, once.roles[3])
        assertEquals(WordRole.OPTIONAL, once.withNextRole(3).roles[3])
        assertEquals(line, once.withNextRole(3).withNextRole(3))
        assertEquals("a word the line does not have changes nothing", line, line.withNextRole(7))
    }

    @Test
    fun aLineOfFixedWordsIsASubstringOrAnExactMatch() {
        val line = TriggerFromLine.of("  $hit ")
        assertEquals(TriggerPattern(hit, TriggerPatternType.SUBSTRING), line.pattern)
        assertEquals(TriggerPattern("  $hit ", TriggerPatternType.EXACT), line.copy(wholeLine = true).pattern)
        assertEquals(emptyList<String>(), line.captures("Ouch! $hit Again."))
        assertNull(line.copy(wholeLine = true).captures("Ouch!   $hit "))
        assertEquals(emptyList<String>(), line.copy(wholeLine = true).captures("  $hit "))
    }

    @Test
    fun aCapturedWordIsARegexGroupOfItsKind() {
        val line = TriggerFromLine.of(hit).with("goblin" to WordRole.CAPTURE, "12" to WordRole.CAPTURE)
        assertEquals(TriggerPattern("""You hit the (\w+) for (\d+) damage\.""", TriggerPatternType.REGEX), line.pattern)
        assertEquals(listOf("goblin", "12"), line.captures(hit))
        assertEquals(listOf("orc", "7"), line.captures("> You hit the orc for 7 damage. Wow"))
        assertNull(line.captures("You hit the cave troll for 7 damage."))
        assertEquals("""^You hit the (\w+) for (\d+) damage\.$""", line.copy(wholeLine = true).pattern.text)
        assertNull(line.copy(wholeLine = true).captures("> $hit"))
        val wide = line.copy(wideCaptures = true)
        assertEquals("""You hit the (.+) for (.+) damage\.""", wide.pattern.text)
        assertEquals(listOf("cave troll", "7"), wide.captures("You hit the cave troll for 7 damage."))
        assertEquals("""The (\S+) axe""", TriggerFromLine.of("The goblin's axe").with("goblin's" to WordRole.CAPTURE).pattern.text)
    }

    @Test
    fun anOptionalWordGoesWithTheGapBesideIt() {
        val line = TriggerFromLine.of(hit).with("the" to WordRole.OPTIONAL)
        assertEquals("""You hit(?: the)? goblin for 12 damage\.""", line.pattern.text)
        assertEquals(emptyList<String>(), line.captures("You hit goblin for 12 damage."))
        assertEquals(emptyList<String>(), line.captures(hit))
        val first = TriggerFromLine.of("Suddenly, you see a goblin").with("Suddenly" to WordRole.OPTIONAL, "you" to WordRole.OPTIONAL, "goblin" to WordRole.CAPTURE)
        assertEquals("""(?:Suddenly, )?(?:you )?see a (\w+)""", first.pattern.text)
        assertEquals(listOf("orc"), first.captures("see a orc"))
        assertEquals(listOf("orc"), first.captures("you see a orc"))
        val last = TriggerFromLine.of("(A goblin arrives) now.").with("now" to WordRole.OPTIONAL)
        assertEquals("""\(A goblin arrives\)(?: now)?\.""", last.pattern.text)
        assertEquals(emptyList<String>(), last.captures("(A goblin arrives)."))
    }

    @Test
    fun whatIsBetweenTheWordsIsTakenAsItIs() {
        assertEquals("""[HP: 100/200] $5.00 a+b (x) {y} ^z |w \v ?u *t""".let(::regexLiteral), """\[HP: 100/200\] \$5\.00 a\+b \(x\) \{y\} \^z \|w \\v \?u \*t""")
        val prompt = TriggerFromLine.of("[HP: 100/200]").with("100" to WordRole.CAPTURE)
        assertEquals("""\[HP: (\d+)/200\]""", prompt.pattern.text)
        assertEquals(listOf("57"), prompt.captures("[HP: 57/200] >"))
    }

    @Test
    fun aLineOfOnlyOptionalWordsMakesNoTrigger() {
        assertTrue(TriggerFromLine.of(hit).usable)
        assertTrue("a line of no words is matched as it is", TriggerFromLine.of("***").usable)
        assertFalse(TriggerFromLine.of("   ").usable)
        val line = TriggerFromLine.of("Hello there")
        assertFalse(line.copy(roles = listOf(WordRole.OPTIONAL, WordRole.OPTIONAL)).usable)
        assertTrue(line.copy(roles = listOf(WordRole.OPTIONAL, WordRole.CAPTURE)).usable)
    }

    @Test
    fun theTriggerIsNamedAfterTheLine() {
        assertEquals(hit, TriggerFromLine.of("  $hit ").name)
        assertEquals(TRIGGER_NAME_LENGTH, TriggerFromLine.of("x".repeat(100)).name.length)
    }
}
