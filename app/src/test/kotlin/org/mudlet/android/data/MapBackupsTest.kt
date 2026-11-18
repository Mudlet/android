package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset

class MapBackupsTest {
    private val root = Files.createTempDirectory("map-backups").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(name: String, modified: Long): File = File(root, name).apply {
        writeText("map")
        setLastModified(modified)
    }

    @Test
    fun theMapFolderIsTheOneMudletSavesIn() {
        assertEquals(File(root, "mudlet/profiles/Achaea/map"), MapBackups.folder(root, "Achaea"))
    }

    @Test
    fun theSavesAreListedAsDesktopListsThemTheNewestFirst() {
        file("18-11-2026#10-20-30.dat", 5_000)
        file("2019-03-04#01-02-03map.XML", 1_000)
        file("autosave.dat", 9_000)
        file("before the war.json", 3_000)
        file("31-02-2026#00-00-00.dat", 2_000)

        val listed = MapBackups.list(root, ZoneOffset.UTC)

        assertEquals(listOf("autosave.dat", "18-11-2026#10-20-30.dat", "before the war.json", "31-02-2026#00-00-00.dat", "2019-03-04#01-02-03map.XML"), listed.map { it.file.name })
        val (autosave, dated, named, impossible, yearFirst) = listed
        assertEquals(MapBackups.Kind.AUTOSAVE, autosave.kind)
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0, 9), autosave.time)
        assertEquals(MapBackups.Kind.DATED, dated.kind)
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 20, 30), dated.time)
        assertEquals(MapFileStaging.Format.DAT, dated.format)
        assertEquals(MapBackups.Kind.NAMED, named.kind)
        assertEquals(MapFileStaging.Format.JSON, named.format)
        // No such day: listed by the file's own time, as a map the player named
        assertEquals(MapBackups.Kind.NAMED, impossible.kind)
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0, 2), impossible.time)
        assertEquals(LocalDateTime.of(2019, 3, 4, 1, 2, 3), yearFirst.time)
        assertEquals(MapFileStaging.Format.XML, yearFirst.format)
    }

    @Test
    fun aFileThatIsNoMapIsLeftOut() {
        file("autosave.dat.Xa81Qp", 1_000)
        file("notes.txt", 1_000)
        File(root, "old.dat").mkdir()
        assertEquals(emptyList<MapBackups.Backup>(), MapBackups.list(root))
        assertEquals(emptyList<MapBackups.Backup>(), MapBackups.list(File(root, "missing")))
    }
}
