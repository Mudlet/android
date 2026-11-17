package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipInputStream

class ProfileArchiveTest {
    private val root = Files.createTempDirectory("profile-archive").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun holdsOneFolderOfTheProfilesNameLaidOutAsDesktopsWithItsSecretsLeftOut() {
        val folder = profile("My Game")
        val map = ByteArray(300_000) { (it % 251).toByte() }
        write(folder, "current/2026-11-17#10-00-00.xml", "<MudletPackage/>".toByteArray())
        write(folder, "map/2026-11-17#10-00-00map.dat", map)
        write(folder, "url", "game.example".toByteArray())
        write(folder, "password", "hunter2".toByteArray())
        write(folder, "irc_password", "irc secret".toByteArray())
        // Only the profile's own data items are secrets: a package's file of the same name is the package's
        write(folder, "mypackage/password", "a package's".toByteArray())
        File(folder, "log").mkdirs()

        val entries = export(folder)

        assertEquals(
            listOf(
                "My Game/",
                "My Game/current/",
                "My Game/current/2026-11-17#10-00-00.xml",
                "My Game/log/",
                "My Game/map/",
                "My Game/map/2026-11-17#10-00-00map.dat",
                "My Game/mypackage/",
                "My Game/mypackage/password",
                "My Game/url",
            ),
            entries.keys.toList(),
        )
        assertArrayEquals(map, entries["My Game/map/2026-11-17#10-00-00map.dat"])
        assertEquals("game.example", String(entries.getValue("My Game/url")))
    }

    @Test
    fun leavesOutALinkRatherThanFollowingItOutOfTheFolder() {
        val folder = profile("Linked")
        val outside = File(root, "outside").apply { mkdirs() }
        write(outside, "private.txt", "not the profile's".toByteArray())
        write(folder, "current/a.xml", "<MudletPackage/>".toByteArray())
        Files.createSymbolicLink(File(folder, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(folder, "current/b.xml").toPath(), File(outside, "private.txt").toPath())

        assertEquals(listOf("Linked/", "Linked/current/", "Linked/current/a.xml"), export(folder).keys.toList())
    }

    @Test
    fun countsTheFilesWritten() {
        val folder = profile("Counted")
        write(folder, "current/a.xml", byteArrayOf(1))
        write(folder, "url", byteArrayOf(2))
        write(folder, "password", byteArrayOf(3))
        assertEquals(2, ProfileArchive.export(folder, ByteArrayOutputStream()))
    }

    @Test(expected = FileNotFoundException::class)
    fun refusesAProfileWithNoFolder() {
        ProfileArchive.export(File(root, "profiles/Missing"), ByteArrayOutputStream())
    }

    @Test
    fun findsTheProfileWhereTheEngineKeepsIt() {
        assertEquals(File(root, "mudlet/profiles/Achaea"), ProfileArchive.folder(root, "Achaea"))
        assertEquals("Achaea-2026-11-17.zip", ProfileArchive.fileName("Achaea", LocalDate.of(2026, 11, 17)))
    }

    private fun profile(name: String) = File(root, "profiles/$name").apply { mkdirs() }

    private fun write(folder: File, path: String, bytes: ByteArray) {
        File(folder, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    private fun export(folder: File): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        ProfileArchive.export(folder, out)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("${entry.name} is inside the profile's folder", entry.name.startsWith("${folder.name}/") && ".." !in entry.name.split('/'))
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}
