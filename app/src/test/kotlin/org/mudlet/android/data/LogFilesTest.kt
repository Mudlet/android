package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The logs a profile's editor and Logging page list and share, from the profile's `log` folder as Mudlet writes it. */
class LogFilesTest {
    private val root = Files.createTempDirectory("logs").toFile()
    private val folder = LogFiles.folder(root, "Game")

    @After
    fun removeFolder() {
        root.deleteRecursively()
    }

    @Test
    fun theLogsAreTheHtmlAndTextFiles_newestFirst() {
        write("2026-11-18#20-00-00.html", "<html>", modified = 2_000)
        write("2026-11-19#09-30-00.txt", "Hello", modified = 3_000)
        write("2026-11-17.HTML", "<html>", modified = 1_000)
        write("2026-11-19#10-00-00.dat", "replay", modified = 4_000)
        write("errors.txt", "", modified = 5_000)
        File(folder, "old.txt").mkdirs()

        assertEquals(
            listOf("2026-11-19#09-30-00.txt", "2026-11-18#20-00-00.html", "2026-11-17.HTML"),
            LogFiles.list(folder).map { it.name },
        )
        assertEquals(LogFile("2026-11-19#09-30-00.txt", 3_000, 5), LogFiles.list(folder).first())
    }

    @Test
    fun aProfileWithoutALogFolderHasNoLogs() {
        assertEquals(emptyList<LogFile>(), LogFiles.list(folder))
    }

    @Test
    fun logsWrittenAtOnceSortByName_latestFirst() {
        val logs = listOf(LogFile("2026-11-18.txt", 7_000, 1), LogFile("logfile.txt", 1_000, 1), LogFile("2026-11-19.txt", 7_000, 1))
        assertEquals(listOf("2026-11-19.txt", "2026-11-18.txt", "logfile.txt"), LogFiles.sorted(logs).map { it.name })
    }

    @Test
    fun anHtmlLogIsAWebPage_andTheOthersPlainText() {
        assertEquals("text/html", LogFile("2026-11-19.html", 0, 1).mimeType)
        assertEquals("text/html", LogFile("Raid.HTML", 0, 1).mimeType)
        assertEquals("text/plain", LogFile("2026-11-19.txt", 0, 1).mimeType)
    }

    @Test
    fun aCopyHoldsTheLog_inPlaceOfTheCopiesBefore() {
        write("first.txt", "one", modified = 1_000)
        write("second.html", "<p>two</p>", modified = 2_000)
        val into = File(root, "cache/logs")

        LogFiles.copy(folder, "first.txt", into)
        val copy = LogFiles.copy(folder, "second.html", into)

        assertEquals("<p>two</p>", copy.readText())
        assertEquals("second.html", copy.name)
        assertEquals("only the last copy is kept", listOf("second.html"), into.list()!!.toList())
        assertTrue("the log itself stays", File(folder, "first.txt").isFile)
    }

    @Test
    fun onlyALogInTheFolderIsCopied() {
        write("first.txt", "one", modified = 1_000)
        File(folder.parentFile, "settings.txt").writeText("not a log")
        val into = File(root, "cache/logs").apply { mkdirs() }
        for (name in listOf("../settings.txt", "missing.txt", "..", "")) {
            try {
                LogFiles.copy(folder, name, into)
                fail("\"$name\" was copied")
            } catch (expected: IOException) {
            }
        }
    }

    private fun write(name: String, text: String, modified: Long) {
        folder.mkdirs()
        File(folder, name).apply { writeText(text) }.setLastModified(modified)
    }
}
