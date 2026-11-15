package org.mudlet.android.suggest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PinnedCommandsTest {
    private val worker = FakeWorker()
    private val store = MemoryStore()
    private val logged = mutableListOf<String>()
    private val shown = mutableListOf<List<String>>()
    private val dir: File = Files.createTempDirectory("pins").toFile()

    @After
    fun removeTheDirectory() {
        dir.deleteRecursively()
    }

    // Opened before the listener is set, as the app does
    private fun pins(s: SuggestStore = store, max: Int = PinnedCommands.MAX) = PinnedCommands(s, worker, { message, _ -> logged += message }, max).also {
        it.open()
        it.listener = { list -> shown += list }
    }

    private fun saved(vararg lines: String) = (listOf(PinnedCommands.HEADER) + lines).joinToString("\n", postfix = "\n")

    @Test
    fun pinsAreListedInTheOrderPinnedAndSavedAtOnce() {
        val pins = pins()
        worker.advance()
        assertEquals("the load is listed, empty", listOf(emptyList<String>()), shown)
        pins.pin("open door")
        pins.pin(" kill rat ")
        assertEquals("changed on the worker, not in the call", 1, shown.size)
        worker.advance()
        assertEquals(listOf(emptyList(), listOf("open door"), listOf("open door", "kill rat")), shown)
        assertEquals(2, store.saves)
        assertEquals(saved("open door", "kill rat"), store.text)
    }

    @Test
    fun whatIsSavedLoadsBackFromTheProfilesFile() {
        val file = SuggestFile.pinsForProfile(dir, "Achaea")
        val first = pins(file)
        for (command in listOf("open door", "say a\tb\\c", "line\nbreak")) first.pin(command)
        worker.advance()
        shown.clear()
        pins(file)
        worker.advance()
        assertEquals(listOf(listOf("open door", "say a\tb\\c", "line\nbreak")), shown)
        assertEquals("one line per command", 4, file.file.readLines().size)
        assertEquals(listOf("Achaea.pins"), dir.list()!!.toList())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun eachProfileHasItsOwnPinsBesideItsSuggestions() {
        val achaea = pins(SuggestFile.pinsForProfile(dir, "Achaea"))
        achaea.pin("open door")
        worker.advance()
        SuggestFile.forProfile(dir, "Achaea").save { it.write("suggestions") }
        shown.clear()
        pins(SuggestFile.pinsForProfile(dir, "Aardwolf"))
        worker.advance()
        assertEquals(listOf(emptyList<String>()), shown)
        // A profile named like another's pins file has files of its own
        assertEquals("a.pins.txt", SuggestFile.forProfile(dir, "a.pins").file.name)
        assertEquals("a.pins", SuggestFile.pinsForProfile(dir, "a").file.name)
        assertEquals(setOf("Achaea.pins", "Achaea.txt"), dir.list()!!.toSet())
        assertEquals("suggestions", SuggestFile.forProfile(dir, "Achaea").file.readText())
    }

    @Test
    fun aCommandIsPinnedOnceAndABlankOrOverlongOneNever() {
        val pins = pins()
        pins.pin("open door")
        pins.pin("Open  Door")
        pins.pin("   ")
        pins.pin("x".repeat(PinnedCommands.MAX_LENGTH + 1))
        pins.pin("x".repeat(PinnedCommands.MAX_LENGTH))
        worker.advance()
        assertEquals(listOf("open door", "x".repeat(PinnedCommands.MAX_LENGTH)), shown.last())
        assertEquals("only real changes are saved", 2, store.saves)
    }

    @Test
    fun theCapHolds() {
        val pins = pins(max = 3)
        for (n in 1..4) pins.pin("say $n")
        worker.advance()
        assertEquals(listOf("say 1", "say 2", "say 3"), shown.last())
        assertEquals(3, store.saves)
        pins.unpin("say 2")
        pins.pin("say 4")
        worker.advance()
        assertEquals(listOf("say 1", "say 3", "say 4"), shown.last())
        assertEquals(12, PinnedCommands.MAX)
    }

    @Test
    fun unpinRemovesTheCommandAndSaves() {
        store.text = saved("open door", "kill rat")
        val pins = pins()
        worker.advance()
        pins.unpin("OPEN DOOR")
        pins.unpin("not pinned")
        worker.advance()
        assertEquals(listOf("kill rat"), shown.last())
        assertEquals(1, store.saves)
        assertEquals(saved("kill rat"), store.text)
    }

    @Test
    fun aNewListenerGetsTheListAsItIs() {
        store.text = saved("look")
        val pins = pins()
        worker.advance()
        val later = mutableListOf<List<String>>()
        pins.listener = { later += it }
        assertTrue("on the worker", later.isEmpty())
        worker.advance()
        assertEquals(listOf(listOf("look")), later)
        assertEquals("the old listener gets nothing more", 1, shown.size)
    }

    @Test
    fun aPinAskedForWhileTheFileLoadsIsAddedToWhatItHeld() {
        store.text = saved("look")
        val pins = pins()
        pins.pin("north")
        worker.advance()
        assertEquals(listOf("look", "north"), shown.last())
    }

    @Test
    fun aDamagedFileLosesOnlyItsDamagedLines() {
        val tooLong = "x".repeat(PinnedCommands.MAX_LENGTH + 1)
        store.text = saved("look", "bad \\q escape", "  ", "LOOK", tooLong, "say 1", "say 2", "say 3")
        pins(max = 3)
        worker.advance()
        assertEquals(listOf(listOf("look", "say 1", "say 2")), shown)
        assertEquals(listOf("the saved pinned commands were damaged: recognised true, 5 lines skipped"), logged)
    }

    @Test
    fun aFileOfAnotherFormatGivesNoPinsAndIsReplacedAtTheNextChange() {
        store.text = "mudlet-pins 999\nlook\n"
        val pins = pins()
        worker.advance()
        assertEquals(listOf(emptyList<String>()), shown)
        assertEquals(listOf("the saved pinned commands were damaged: recognised false, 0 lines skipped"), logged)
        pins.pin("north")
        worker.advance()
        assertEquals(saved("north"), store.text)
    }

    @Test
    fun afterAFailedLoadNothingIsSavedButThePinsWork() {
        store.failLoad = true
        val pins = pins()
        pins.pin("north")
        worker.advance()
        assertEquals(listOf("north"), shown.last())
        assertEquals(0, store.saves)
        assertEquals(1, logged.size)
    }

    @Test
    fun aFailedSaveIsLoggedWithoutTheCommandAndTriedAgainAtTheNextChange() {
        val pins = pins()
        store.failSave = true
        pins.pin("secret plan")
        worker.advance()
        assertEquals(listOf("secret plan"), shown.last())
        assertEquals(1, logged.size)
        assertFalse(logged.single(), "secret" in logged.single())
        store.failSave = false
        pins.pin("north")
        worker.advance()
        assertEquals(saved("secret plan", "north"), store.text)
    }
}
