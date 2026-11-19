package org.mudlet.android.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    @Test
    fun unpacksWhatItExportsLessTheSecrets() {
        val folder = profile("Round Trip")
        write(folder, "current/a.xml", "<MudletPackage/>".toByteArray())
        write(folder, "map/m.dat", ByteArray(70_000) { it.toByte() })
        write(folder, "password", "hunter2".toByteArray())
        val out = ByteArrayOutputStream()
        ProfileArchive.export(folder, out)

        val unpacked = unpack(out.toByteArray()) as ProfileArchive.Unpacked.Folder

        assertEquals("Round Trip", unpacked.folder.name)
        assertArrayEquals(File(folder, "map/m.dat").readBytes(), File(unpacked.folder, "map/m.dat").readBytes())
        assertEquals(listOf("current", "map"), unpacked.folder.list()!!.sorted())
    }

    @Test
    fun leavesOutADesktopProfilesPasswordFilesAndMacOsForks() {
        val unpacked = unpack(
            zip(
                "Desk/current/a.xml" to "x",
                "Desk/password" to "hunter2",
                "Desk/irc_password" to "irc",
                "Desk/pkg/password" to "a package's",
                "__MACOSX/Desk/._a.xml" to "fork",
            ),
        ) as ProfileArchive.Unpacked.Folder
        assertEquals(listOf("current", "pkg"), unpacked.folder.list()!!.sorted())
        assertEquals(listOf("Desk"), staging.list()!!.toList())
    }

    @Test
    fun checksAnArchiveWithoutWritingAndSaysWhatUnpackingItWouldAdd() {
        val bytes = zip(
            "Desk/current/a.xml" to "save",
            "Desk/map/m.dat" to "a map",
            "Desk/password" to "hunter2",
            "__MACOSX/Desk/._a.xml" to "fork",
        )
        val before = root.walk().toList()
        val checked = ProfileArchive.check(ByteArrayInputStream(bytes)) as ProfileArchive.Checked.Profile
        assertEquals("nothing written", before, root.walk().toList())
        assertEquals(ProfileArchive.Contents("Desk", 2, "save".length + "a map".length.toLong()), checked.contents)

        val unpacked = unpack(bytes) as ProfileArchive.Unpacked.Folder
        val files = unpacked.folder.walk().filter { it.isFile }.toList()
        assertEquals(checked.contents.files, files.size)
        assertEquals(checked.contents.bytes, files.sumOf { it.length() })
    }

    @Test
    fun refusesAPathThatLeavesTheFolder() {
        for (name in listOf("Evil/../../outside.txt", "/etc/evil", "Evil/./a", "Evil//a", "Evil\\..\\a", "Evil/a\u0000b")) {
            assertRefused(ProfileArchive.Refusal.UNSAFE_PATH, zip("Evil/current/a.xml" to "x", name to "bad"), name)
        }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test
    fun saysAPathAndroidRefusesLeavesTheFolderRatherThanThatItIsNotAZip() {
        // What ZipInputStream throws from API 34 at a name with a `..` segment
        assertRefused(ProfileArchive.Refusal.UNSAFE_PATH, { failingAfterAnEntry("Invalid zip entry path: Evil/../../a") })
        assertRefused(ProfileArchive.Refusal.NOT_A_ZIP, { failingAfterAnEntry("invalid entry compressed size") })
    }

    @Test
    fun refusesADuplicateEntry() {
        assertRefused(ProfileArchive.Refusal.UNSAFE_PATH, zipAllowingDuplicates("D/current/a.xml", "D/current/a.xml"))
    }

    @Test
    fun refusesMoreThanOneFolderOrAFileAtTheTop() {
        assertRefused(ProfileArchive.Refusal.NOT_ONE_FOLDER, zip("A/current/a.xml" to "x", "B/current/b.xml" to "y"))
        assertRefused(ProfileArchive.Refusal.NOT_ONE_FOLDER, zip("A/current/a.xml" to "x", "loose.txt" to "y"))
    }

    @Test
    fun refusesAFolderWithNoSave() {
        assertRefused(ProfileArchive.Refusal.NO_SAVE, zip("NoSave/map/m.dat" to "x", "NoSave/current/notes.txt" to "y"))
    }

    @Test
    fun refusesWhatIsNotAZip() {
        assertRefused(ProfileArchive.Refusal.NOT_A_ZIP, "just some text, not a zip".toByteArray())
        assertRefused(ProfileArchive.Refusal.NOT_A_ZIP, zip())
    }

    @Test
    fun refusesAnEntryNameThatIsNotUtf8RatherThanFailingOnIt() {
        val bytes = zip("A/current/\u00e9.xml" to "x")
        // \u00e9 in UTF-8, made Latin-1's, as an archiver that writes the local code page does
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0xC3.toByte() && bytes[i + 1] == 0xA9.toByte()) {
                bytes[i] = 0xE9.toByte()
                bytes[i + 1] = 'x'.code.toByte()
            }
        }
        assertRefused(ProfileArchive.Refusal.UNREADABLE_NAME, bytes)
    }

    @Test
    fun refusesAZipBombByTheBytesItUnpacksNotTheSizeItClaims() {
        // Deflated zeros: 64 KiB in the archive unpacks to many megabytes
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("Bomb/current/a.xml"))
                zip.write(ByteArray(8 * 1024 * 1024))
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(bomb.size < 100_000)
        assertRefused(ProfileArchive.Refusal.TOO_LARGE, bomb, maxBytes = 1024 * 1024)
        assertRefused(ProfileArchive.Refusal.TOO_LARGE, zip("M/current/a.xml" to "x", "M/b" to "y", "M/c" to "z"), maxEntries = 2)
    }

    @Test
    fun namesAsMudletDoes() {
        assertTrue(ProfileArchive.usableName("Achaea (main) #2 & co._-"))
        for (bad in listOf("", " lead", "trail ", ".", "a..b", "slash/name", "dé", "back\\slash")) {
            assertFalse(bad, ProfileArchive.usableName(bad))
        }
    }

    @Test
    fun findsATakenNameIgnoringCaseAndSuggestsAFreeOneAsDesktopsCopyDoes() {
        val profiles = listOf("Achaea", "Achaea1", "Game7", "Game8")
        assertEquals("Achaea", ProfileArchive.taken(profiles, "ACHAEA"))
        assertEquals(null, ProfileArchive.taken(profiles, "Other"))
        assertEquals("Achaea2", ProfileArchive.freeName(profiles, "Achaea"))
        assertEquals("Game9", ProfileArchive.freeName(profiles, "Game7"))
        assertEquals("Fresh1", ProfileArchive.freeName(profiles, "Fresh"))
    }

    @Test
    fun installsUnderTheChosenNameAndNeverOverAnotherProfile() {
        val profiles = File(root, "profiles").apply { mkdirs() }
        File(profiles, "Kept").mkdirs()
        write(File(profiles, "Kept"), "current/old.xml", "old".toByteArray())
        val first = unpack(zip("Kept/current/new.xml" to "new")) as ProfileArchive.Unpacked.Folder
        try {
            ProfileArchive.install(first.folder, profiles, "kept")
            throw AssertionError("installed over Kept")
        } catch (_: FileAlreadyExistsException) {
        }
        assertEquals(listOf("old.xml"), File(profiles, "Kept/current").list()!!.toList())

        ProfileArchive.install(first.folder, profiles, "Kept Too")
        assertEquals("new", File(profiles, "Kept Too/current/new.xml").readText())
    }

    private val staging get() = File(root, "staging")

    private fun unpack(bytes: ByteArray, maxBytes: Long = ProfileArchive.MAX_BYTES, maxEntries: Int = ProfileArchive.MAX_ENTRIES) = ProfileArchive.unpack(ByteArrayInputStream(bytes), staging, maxBytes, maxEntries)

    private fun assertRefused(
        refusal: ProfileArchive.Refusal,
        bytes: ByteArray,
        message: String = refusal.name,
        maxBytes: Long = ProfileArchive.MAX_BYTES,
        maxEntries: Int = ProfileArchive.MAX_ENTRIES,
    ) = assertRefused(refusal, { ByteArrayInputStream(bytes) }, message, maxBytes, maxEntries)

    // Refused by unpack, and by check for the same reason, as a file opened from another app is checked before it is unpacked
    private fun assertRefused(
        refusal: ProfileArchive.Refusal,
        input: () -> InputStream,
        message: String = refusal.name,
        maxBytes: Long = ProfileArchive.MAX_BYTES,
        maxEntries: Int = ProfileArchive.MAX_ENTRIES,
    ) {
        val result = ProfileArchive.unpack(input(), staging, maxBytes, maxEntries)
        assertTrue("$message: $result", result is ProfileArchive.Unpacked.Refused && result.refusal == refusal)
        assertFalse("$message left files in staging", staging.exists() && staging.list()!!.isNotEmpty())
        val checked = ProfileArchive.check(input(), maxBytes, maxEntries)
        assertTrue("$message, checked: $checked", checked is ProfileArchive.Unpacked.Refused && checked.refusal == refusal)
    }

    /** A zip whose first entry is written, after which reading throws a ZipException saying [why]. */
    private fun failingAfterAnEntry(why: String): InputStream {
        val first = zip("Evil/current/a.xml" to "x")
        // The first entry ends where the central directory, or the next entry, begins
        val end = (0 until first.size - 3).first { i -> first[i] == 'P'.code.toByte() && first[i + 1] == 'K'.code.toByte() && first[i + 2].toInt() == 1 && first[i + 3].toInt() == 2 }
        return SequenceInputStream(
            ByteArrayInputStream(first, 0, end),
            object : InputStream() {
                override fun read(): Int = throw ZipException(why)
            },
        )
    }

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    // ZipOutputStream refuses a duplicate name, so the second one is written under a stand-in of the same length
    private fun zipAllowingDuplicates(first: String, second: String): ByteArray {
        val standIn = second.dropLast(1) + "~"
        val bytes = zip(first to "1", standIn to "2")
        val from = standIn.toByteArray()
        val to = second.toByteArray()
        var i = 0
        while (i <= bytes.size - from.size) {
            if (bytes.copyOfRange(i, i + from.size).contentEquals(from)) to.copyInto(bytes, i)
            i++
        }
        return bytes
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
