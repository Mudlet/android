package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.Writer

class SuggestionSessionTest {
    /** A [Worker] on a clock the test moves: [advance] runs what falls due, in order. */
    private class FakeWorker : Worker {
        var now = 0L
        private var scheduled = 0L

        private class Task(val due: Long, val order: Long, val run: () -> Unit) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
            val t = Task(now + delayMillis, scheduled++, task)
            tasks += t
            return Cancellable { t.cancelled = true }
        }

        fun advance(millis: Long = 0) {
            val end = now + millis
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= end }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: break
                tasks.remove(next)
                now = next.due
                next.run()
            }
            tasks.removeAll { it.cancelled }
            now = end
        }

        val pending get() = tasks.count { !it.cancelled }
    }

    /** A [CommandSuggester] that remembers every text it was given. */
    private class RecordingSuggester(private val real: CommandSuggester = CommandSuggester()) : Suggester by real {
        val asked = mutableListOf<String>()
        val recorded = mutableListOf<Pair<String, Boolean>>()
        val observed = mutableListOf<String>()

        override fun suggest(input: String, cursor: Int, limit: Int): Suggestions {
            asked += input
            return real.suggest(input, cursor, limit)
        }

        override fun recordSent(text: String, hidden: Boolean) {
            recorded += text to hidden
            real.recordSent(text, hidden)
        }

        override fun observeLine(line: String) {
            observed += line
            real.observeLine(line)
        }

        fun everything() = asked + recorded.map { it.first } + observed
    }

    private class MemoryStore(var text: String? = null) : SuggestStore {
        var failLoad = false
        var failSave = false
        var saves = 0

        override fun <T> load(read: (Reader) -> T): T? {
            if (failLoad) throw IOException("no access")
            return text?.let { read(it.reader()) }
        }

        override fun save(write: (Writer) -> Unit) {
            if (failSave) throw IOException("disk full")
            val out = java.io.StringWriter()
            write(out)
            text = out.toString()
            saves++
        }
    }

    private val worker = FakeWorker()
    private val suggester = RecordingSuggester()
    private val store = MemoryStore()
    private val logged = mutableListOf<String>()
    private val answers = mutableListOf<SuggestionSession.Answer>()

    private fun session(s: Suggester = suggester) = SuggestionSession(s, store, worker, { message, _ -> logged += message }).also {
        it.listener = { answer -> answers += answer }
        it.open()
    }

    private fun savedWith(vararg commands: String): String {
        val s = CommandSuggester()
        commands.forEach { s.recordSent(it) }
        return StringBuilder().also { s.save(it) }.toString()
    }

    @Test
    fun typingIsAnsweredOnceItPauses() {
        val session = session()
        session.sent("look")
        worker.advance()
        for (typed in listOf("l", "lo", "loo")) {
            session.inputChanged(typed, typed.length)
            worker.advance(SuggestionSession.TYPING_DELAY_MS - 1)
        }
        assertEquals("nothing asked while typing goes on", emptyList<String>(), suggester.asked)
        worker.advance(1)
        assertEquals(listOf("loo"), suggester.asked)
        assertEquals(1, answers.size)
        val answer = answers.single()
        assertEquals("loo" to 3, answer.input to answer.cursor)
        assertEquals(listOf("look"), answer.suggestions.items.map { it.text })
        assertEquals("k", answer.suggestions.ghost)
    }

    @Test
    fun anAnswerHasAtMostTheLimit() {
        val session = session()
        for (n in 1..9) session.sent("say $n")
        session.inputChanged("say", 3)
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals(SuggestionSession.LIMIT, answers.single().suggestions.items.size)
    }

    @Test
    fun anEmptyLineAfterASendShowsTheNextCommand() {
        val session = session()
        for (command in listOf("kill rat", "loot", "kill rat")) {
            session.sent(command)
            session.inputChanged("", 0)
        }
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals("loot", answers.single().suggestions.items.first().text)
    }

    @Test
    fun aSendWhileTheSavedStateLoadsIsLearnedOnTopOfIt() {
        store.text = savedWith("open door")
        val session = session()
        session.sent("north")
        session.inputChanged("", 0)
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals(setOf("open door", "north"), answers.single().suggestions.items.map { it.text }.toSet())
    }

    @Test
    fun hiddenInputIsNeverAnsweredOrKept() {
        val session = session()
        session.sent("north")
        session.inputChanged("hunt", 4)
        session.hiddenChanged(true)
        session.inputChanged("hunter2", 7)
        session.sentHidden()
        worker.advance(SuggestionSession.SAVE_DELAY_MS)
        assertEquals("nothing answered while input is hidden", emptyList<SuggestionSession.Answer>(), answers)
        session.hiddenChanged(false)
        session.inputChanged("", 0)
        session.save()
        worker.advance(SuggestionSession.TYPING_DELAY_MS)

        assertEquals(listOf("north" to false, "" to true), suggester.recorded)
        assertTrue(suggester.everything().none { "hunt" in it })
        assertFalse("hunt" in store.text!!)
        // The hidden send ends the sequence: "north" no longer predicts anything, so the empty line offers history
        assertEquals(SuggestionSource.HISTORY, answers.single().suggestions.items.single().source)
    }

    @Test
    fun whatWasLearnedIsSavedAWhileAfterASend() {
        val session = session()
        session.sent("look")
        worker.advance(SuggestionSession.SAVE_DELAY_MS - 1)
        session.sent("north")
        assertEquals(0, store.saves)
        worker.advance(1)
        assertEquals("one save for both sends", 1, store.saves)
        assertTrue("look" in store.text!! && "north" in store.text!!)
        worker.advance(SuggestionSession.SAVE_DELAY_MS)
        assertEquals(1, store.saves)

        session.save()
        worker.advance()
        assertEquals("nothing new to save", 1, store.saves)
        session.sent("east")
        session.save()
        worker.advance()
        assertEquals(2, store.saves)
        assertTrue("east" in store.text!!)
    }

    @Test
    fun aFailedSaveIsLoggedWithoutTheCommandAndTriedAgain() {
        val session = session()
        store.failSave = true
        session.sent("tell bob my secret")
        worker.advance(SuggestionSession.SAVE_DELAY_MS)
        assertEquals(1, logged.size)
        assertTrue(logged.none { "secret" in it })

        store.failSave = false
        session.sent("look")
        worker.advance(SuggestionSession.SAVE_DELAY_MS)
        assertTrue("tell bob my secret" in store.text!!)
    }

    @Test
    fun afterAFailedLoadNothingIsSavedButSuggestionsGoOn() {
        store.text = "kept"
        store.failLoad = true
        val session = session()
        session.sent("look")
        session.inputChanged("l", 1)
        session.save()
        worker.advance(SuggestionSession.SAVE_DELAY_MS)
        assertEquals(1, logged.size)
        assertEquals("kept", store.text)
        assertEquals(listOf("look"), answers.single().suggestions.items.map { it.text })
    }

    @Test
    fun aDamagedSaveIsLoggedAndTheRestKept() {
        store.text = savedWith("look") + "nonsense\n"
        val session = session()
        session.inputChanged("l", 1)
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals(1, logged.size)
        assertEquals(listOf("look"), answers.single().suggestions.items.map { it.text })
    }

    @Test
    fun theEchoOfASendIsNotLearnedFrom() {
        val session = session()
        session.sent("look")
        session.lineShown("HP:100> look")
        session.lineShown("You see a door.")
        session.sent("north")
        session.lineShown("A dark room.")
        session.lineShown("north")
        worker.advance()
        assertEquals(listOf("You see a door.", "A dark room."), suggester.observed)

        // An echo that does not come within three lines is not looked for any more
        session.sent("east")
        for (n in 1..3) session.lineShown("line $n")
        session.lineShown("> east")
        worker.advance()
        assertEquals(listOf("line 1", "line 2", "line 3", "> east"), suggester.observed.drop(2))

        // Only the whole command at the end of a line is its echo
        session.sent("n")
        session.lineShown("An open plain")
        session.lineShown("HP:9>n")
        worker.advance()
        assertEquals(listOf("An open plain"), suggester.observed.drop(6))
    }

    @Test
    fun openLoadsOnce() {
        store.text = savedWith("look")
        val session = session()
        worker.advance()
        session.sent("north")
        session.open()
        session.inputChanged("", 0)
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals(setOf("look", "north"), answers.single().suggestions.items.map { it.text }.toSet())
    }

    @Test
    fun withoutAListenerAnswersAreDropped() {
        val session = session()
        session.listener = null
        session.inputChanged("x", 1)
        worker.advance(SuggestionSession.TYPING_DELAY_MS)
        assertEquals(0, worker.pending)
        assertTrue(answers.isEmpty())
        assertNull(store.text)
    }
}
