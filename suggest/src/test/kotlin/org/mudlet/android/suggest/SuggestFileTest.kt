package org.mudlet.android.suggest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SuggestFileTest {
    private val dir: File = Files.createTempDirectory("suggest").toFile()

    @After
    fun removeTheDirectory() {
        dir.deleteRecursively()
    }

    @Test
    fun whatIsSavedLoadsBack() {
        val file = SuggestFile.forProfile(File(dir, "suggest"), "Achaea")
        val s = CommandSuggester().apply { recordSent("look") }
        file.save(s::save)
        val copy = CommandSuggester()
        val result = file.load(copy::load)
        assertEquals(CommandSuggester.LoadResult(recognised = true, skippedLines = 0), result)
        assertEquals(listOf("look"), copy.suggest("").items.map { it.text })
        assertEquals(listOf("Achaea.txt"), file.file.parentFile.list()!!.toList())
    }

    @Test
    fun nothingSavedLoadsNothing() {
        assertNull(SuggestFile.forProfile(dir, "new").load { fail("read with no file") })
    }

    @Test
    fun deleteRemovesWhatWasSavedAndNothingSavedIsNoError() {
        val file = SuggestFile.forProfile(dir, "Gone")
        file.save { it.write("look") }
        assertTrue(file.delete())
        assertFalse(file.file.exists())
        assertTrue("nothing saved is nothing left", file.delete())
    }

    @Test
    fun deleteSaysWhatItCouldNotDelete() {
        val file = SuggestFile.forProfile(dir, "Kept")
        // A folder that is not empty, where the file would be, is still there after a delete
        assertTrue(File(file.file, "inside").let { it.parentFile!!.mkdirs() && it.createNewFile() })
        assertFalse(file.delete())
    }

    @Test
    fun aFailedSaveLeavesTheOldFile() {
        val file = SuggestFile.forProfile(dir, "p")
        file.save { it.write("old") }
        try {
            file.save {
                it.write("half")
                throw IOException("disk full")
            }
            fail("the save did not fail")
        } catch (_: IOException) {
        }
        assertEquals("old", file.file.readText())
        assertEquals(listOf("p.txt"), dir.list()!!.toList())
    }

    @Test
    fun anyProfileNameIsOneFileInTheDirectory() {
        for (name in listOf("a/b", "..", "Ünïcode game", "C:\\x")) {
            val file = SuggestFile.forProfile(dir, name).file
            assertEquals(dir, file.parentFile)
            assertTrue(file.name, file.name.endsWith(".txt"))
        }
        assertEquals("a%2Fb.txt", SuggestFile.forProfile(dir, "a/b").file.name)
    }

    @Test
    fun aDirectoryThatCannotBeMadeFailsTheSave() {
        val blocked = File(dir, "blocked").apply { writeText("a file") }
        try {
            SuggestFile.forProfile(File(blocked, "suggest"), "p").save { it.write("x") }
            fail("saved under a file")
        } catch (_: IOException) {
        }
    }
}
