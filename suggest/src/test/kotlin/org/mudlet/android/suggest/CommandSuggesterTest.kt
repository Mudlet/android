package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSuggesterTest {
    private var now = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun suggester(config: CommandSuggester.Config = CommandSuggester.Config()) = CommandSuggester(config) { now }

    private fun CommandSuggester.texts(input: String, cursor: Int = input.length) = suggest(input, cursor).items.map { it.text }

    private fun CommandSuggester.send(vararg commands: String) = commands.forEach {
        recordSent(it)
        now += 1000
    }

    @Test
    fun historyIsFuzzyMatched() {
        val s = suggester()
        s.send("get coins from corpse", "look", "say hello")
        assertEquals("get coins from corpse", s.texts("gc").first())
        assertFalse("say hello" in s.texts("gc"))
        assertEquals(listOf("look"), s.texts("lk"))
    }

    @Test
    fun aPrefixMatchOutranksAMoreFrequentFuzzyOne() {
        val s = suggester()
        repeat(20) { s.send("get coins") }
        s.send("gc on")
        assertEquals("gc on", s.texts("gc").first())
    }

    @Test
    fun frecencyRanksFrequentAndRecentFirst() {
        val s = suggester()
        repeat(5) { s.send("kill rat") }
        s.send("kick door")
        // used more often
        assertEquals(listOf("kill rat", "kick door"), s.texts("ki"))
        // after a month the five old uses weigh less than two new ones
        now += 30 * day
        s.send("kick door", "kick door")
        assertEquals(listOf("kick door", "kill rat"), s.texts("ki"))
    }

    @Test
    fun theSameCommandInAnotherCaseOrSpacingIsOneEntryShownAsLastTyped() {
        val s = suggester()
        s.send("Kill  Rat", "kill rat ")
        assertEquals(1, s.historySize)
        assertEquals(listOf("kill rat"), s.texts("k"))
    }

    @Test
    fun theInputItselfIsNotSuggested() {
        val s = suggester()
        s.send("look", "look at sign")
        assertEquals(listOf("look at sign"), s.texts("look"))
    }

    @Test
    fun ghostTextOnlyForAPrefixExtensionAtTheEnd() {
        val s = suggester()
        s.send("open door")
        val result = s.suggest("op")
        assertEquals("en door", result.ghost)
        // a fuzzy match is no extension
        assertNull(s.suggest("od").ghost)
        // the case differs, so appending would not give the suggestion
        assertNull(s.suggest("Op").ghost)
        // the cursor is not at the end
        assertNull(s.suggest("op", cursor = 1).ghost)
        assertNull(s.suggest("").ghost)
    }

    @Test
    fun theNextCommandIsPredictedFromThePreviousOne() {
        val s = suggester()
        repeat(3) { s.send("kill rat", "loot", "north") }
        s.send("kill rat")
        val top = s.suggest("").items.first()
        assertEquals("loot", top.text)
        assertEquals(SuggestionSource.SEQUENCE, top.source)
        assertEquals(SuggestionKind.COMMAND, top.kind)
    }

    @Test
    fun aPredictionCarriesOverToAnotherTargetByTheFirstWord() {
        val s = suggester()
        s.send("kill rat", "loot", "kill snake", "loot", "kill orc", "loot", "west", "east")
        s.send("kill goblin")
        val top = s.suggest("").items.first()
        assertEquals("loot", top.text)
        assertEquals(SuggestionSource.SEQUENCE, top.source)
    }

    @Test
    fun theNextCommandIsPredictedFromTheLastGameLine() {
        val s = suggester()
        s.send("east", "north", "say hi", "look")
        s.observeLine("The door is closed.")
        s.send("open door")
        s.observeLine("You are in a kitchen.")
        s.send("west")
        s.observeLine("The door is closed.")
        val top = s.suggest("").items.first()
        assertEquals("open door", top.text)
        assertEquals(SuggestionSource.LINE, top.source)
    }

    @Test
    fun lineAssociationsIgnoreNumbersAndNames() {
        val s = suggester()
        s.send("look")
        s.observeLine("You see Ann wave at Bob.")
        s.send("wave")
        s.observeLine("You see Cid wave at Dee.")
        assertEquals("wave", s.suggest("").items.first().text)
        assertEquals(SuggestionSource.LINE, s.suggest("").items.first().source)
        assertEquals("you see * wave at *.", CommandSuggester.normalizeLine("You see Cid wave at Dee."))
        assertEquals("ann gives you # gold coins.", CommandSuggester.normalizeLine("Ann gives you 12 gold coins."))
        assertNull(CommandSuggester.normalizeLine("<100 50>"))
    }

    @Test
    fun onlyLinesSinceTheLastSendArePredictionContext() {
        val s = suggester()
        s.observeLine("You are in a hall.")
        s.observeLine("The door is closed.")
        s.send("open door")
        s.send("look")
        // the hall and the door came before the last send, so it predicts nothing now
        assertTrue(s.suggest("").items.none { it.source == SuggestionSource.LINE })
    }

    @Test
    fun predictionsLiftMatchingHistoryWhileTyping() {
        val s = suggester()
        repeat(5) { s.send("order ale") }
        s.observeLine("The door is closed.")
        s.send("open door")
        s.send("look")
        s.observeLine("The door is closed.")
        assertEquals("open door", s.texts("o").first())
    }

    @Test
    fun anEmptyInputFallsBackToFrecentHistory() {
        val s = suggester()
        s.send("score", "score", "inventory")
        val items = s.suggest("").items
        assertEquals(setOf("score", "inventory"), items.map { it.text }.toSet())
        assertTrue(items.all { it.kind == SuggestionKind.COMMAND })
    }

    @Test
    fun outputWordsCompleteTheWordAtTheCursor() {
        val s = suggester()
        s.observeLine("A grizzled goblin stands here, guarding a golden key.")
        s.observeLine("The goblin snarls at you.")
        val words = s.suggest("kill go").items.filter { it.kind == SuggestionKind.WORD }
        // the newest first
        assertEquals(listOf("kill goblin", "kill golden"), words.map { it.text })
        assertEquals(listOf("goblin", "golden"), words.map { it.label })
        assertTrue(words.all { it.source == SuggestionSource.OUTPUT })
        assertEquals("ghost text for the top word", "blin", s.suggest("kill go").ghost)
    }

    @Test
    fun aWordInTheMiddleIsReplacedWholeAndTheCursorFollowsIt() {
        val s = suggester()
        s.observeLine("You see a goblin.")
        // the cursor after "go": the whole "gob" is replaced
        val word = s.suggest("kill gob now", cursor = 7).items.single { it.kind == SuggestionKind.WORD }
        assertEquals("kill goblin now", word.text)
        assertEquals(11, word.cursor)
        // no ghost text: the cursor is not at the end
        assertNull(s.suggest("kill gob now", cursor = 7).ghost)
        // a space before the cursor: no word to complete
        assertTrue(s.suggest("kill ").items.none { it.kind == SuggestionKind.WORD })
    }

    @Test
    fun outputWordsAreBoundedByTheWindow() {
        val s = suggester(CommandSuggester.Config(outputWordWindow = 3))
        s.observeLine("dragon")
        s.observeLine("one two three")
        assertTrue(s.suggest("dr").items.isEmpty())
        s.observeLine("dragon")
        assertEquals(listOf("dragon"), s.texts("dr"))
    }

    @Test
    fun theSameTextFromTwoSourcesIsListedOnceWithTheBetterScore() {
        val s = suggester()
        s.send("kill goblin")
        s.observeLine("A goblin is here.")
        val items = s.suggest("kill gob").items
        assertEquals(1, items.count { it.text == "kill goblin" })
        // the history prefix match outscores the word
        assertEquals(SuggestionSource.HISTORY, items.single { it.text == "kill goblin" }.source)
    }

    @Test
    fun theLimitHolds() {
        val s = suggester()
        repeat(30) { s.send("say $it") }
        assertEquals(5, s.suggest("say", limit = 5).items.size)
    }

    @Test
    fun theHistoryCapEvictsTheLeastFrecent() {
        val s = suggester(CommandSuggester.Config(maxHistory = 3))
        s.send("north", "north", "south", "south", "east")
        now += 10 * day
        s.send("west", "up")
        assertEquals(3, s.historySize)
        val left = s.texts("").toSet()
        assertEquals(setOf("west", "up", "south"), left - "north")
        assertFalse("east" in left)
    }

    @Test
    fun overlongCommandsAreNotRecorded() {
        val s = suggester(CommandSuggester.Config(maxCommandLength = 10))
        s.send("say this is far too long")
        assertEquals(0, s.historySize)
    }

    @Test
    fun theSequenceTablesAreBounded() {
        val s = suggester(CommandSuggester.Config(maxNextPerKey = 2))
        s.send("look", "a1", "look", "a2", "look", "a3", "look")
        val predicted = s.suggest("").items.filter { it.source == SuggestionSource.SEQUENCE }.map { it.text }
        assertEquals(listOf("a3", "a2"), predicted.sorted().reversed())
    }

    @Test
    fun hiddenInputNeverEntersAnyStoreOrPrediction() {
        val s = suggester()
        val secret = "hunter2 secret"
        s.observeLine("What is your password?")
        s.send("connect bob")
        s.observeLine("What is your password?")
        s.recordSent(secret, hidden = true)
        s.observeLine("Welcome back!")
        s.send("look")
        s.recordSent(secret, hidden = true)
        s.send("score")
        s.observeLine("What is your password?")

        for (input in listOf("", "h", "hu", "hunter", "hs", "secret", "s")) {
            val texts = s.suggest(input).items.map { it.text }
            assertTrue("'$input' suggested the secret: $texts", texts.none { "hunter" in it || "secret" in it })
        }
        val saved = StringBuilder().also { s.save(it) }.toString()
        assertFalse(saved, "hunter" in saved || "secret" in saved)
        assertEquals(3, s.historySize)
    }

    @Test
    fun hiddenInputBreaksTheSequence() {
        val s = suggester()
        s.send("connect bob")
        s.recordSent("hunter2", hidden = true)
        s.send("look")
        s.send("connect bob")
        // "look" never followed "connect bob" directly
        assertTrue(s.suggest("").items.none { it.text == "look" && it.source == SuggestionSource.SEQUENCE })
    }

    @Test
    fun forgetRemovesACommandEverywhere() {
        val s = suggester()
        s.send("kill rat", "loot", "kill rat")
        s.forget("LOOT")
        assertTrue(s.texts("").none { it == "loot" })
        assertTrue(s.texts("lo").isEmpty())
    }

    @Test
    fun clearForgetsEverything() {
        val s = suggester()
        s.send("kill rat", "loot")
        s.observeLine("A goblin is here.")
        s.clear()
        assertTrue(s.suggest("").items.isEmpty())
        assertTrue(s.suggest("gob").items.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCursorOutsideTheInputIsRefused() {
        suggester().suggest("abc", cursor = 4)
    }

    // without the lock, HashMap can loop forever
    @Test(timeout = 60_000)
    fun concurrentCallsAreSafe() {
        val s = suggester()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 4).map { t ->
            Thread {
                try {
                    // suggest is the slow one
                    repeat(if (t == 2) 200 else 2000) { i ->
                        when (t) {
                            0 -> s.recordSent("command $i")
                            1 -> s.observeLine("A goblin $i stands here.")
                            2 -> s.suggest("co")
                            else -> s.save(StringBuilder())
                        }
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(2000, s.historySize)
    }

    @Test
    fun thousandsOfEntriesStayFast() {
        val s = suggester()
        val verbs = listOf("kill", "get", "put", "open", "close", "look at", "cast", "say")
        val things = (1..500).map { "thing$it" }
        for (verb in verbs) for (thing in things) s.recordSent("$verb $thing")
        assertEquals(4000, s.historySize)
        repeat(20) { s.suggest("gt") }
        val start = System.nanoTime()
        repeat(50) { s.suggest("gt1") }
        val perCall = (System.nanoTime() - start) / 50 / 1_000_000.0
        assertTrue("$perCall ms per call", perCall < 50.0)
    }
}
