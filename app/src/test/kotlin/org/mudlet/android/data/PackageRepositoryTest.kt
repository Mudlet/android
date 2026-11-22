package org.mudlet.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mudlet.android.protocol.InstalledPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** The package repository's rules, each against what desktop's dlgPackageManager and Qt do. */
class PackageRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val mapper = RepositoryPackage("generic_mapper", "Generic Mapper", "Mudlet", "1.3", "Maps as you walk", "generic_mapper.mpackage", "icons/generic mapper.png")

    private fun installed(version: String, author: String = "Mudlet", name: String = "generic_mapper") = InstalledPackage(name, "Generic Mapper", author, version, "", "")

    @Test
    fun anIndexIsAnObjectWithAPackagesArray() {
        val index = """
            {"packages": [
              {"mpackage": "generic_mapper", "title": "Generic Mapper", "author": "Mudlet", "version": "1.3",
               "description": "Maps as you walk", "filename": "generic_mapper.mpackage", "icon": "icons/generic mapper.png",
               "created": "2024-01-01", "dependencies": ""},
              {"title": "no name"},
              "not an object",
              {"mpackage": "bare"}
            ]}
        """.trimIndent()
        assertEquals(listOf(mapper, RepositoryPackage("bare", "", "", "", "", "", "")), PackageRepository.parse(index))
        assertEquals(emptyList<RepositoryPackage>(), PackageRepository.parse("""{"packages": []}"""))
    }

    @Test
    fun aPageThatIsNotAnIndexIsNone() {
        assertNull("a captive portal's page", PackageRepository.parse("<html><body>Sign in</body></html>"))
        assertNull(PackageRepository.parse("""{"message": "Not Found"}"""))
        assertNull(PackageRepository.parse("""{"packages": "none"}"""))
        assertNull(PackageRepository.parse("[]"))
        assertNull(PackageRepository.parse(""))
    }

    @Test
    fun versionsAreReadAsQVersionNumberReadsThem() {
        assertEquals(listOf(1, 2, 3), PackageRepository.versionNumber("1.2.3"))
        assertEquals(listOf(3, 0, 1), PackageRepository.versionNumber("3.0.1-live"))
        assertEquals(listOf(2), PackageRepository.versionNumber(" +2.x"))
        assertEquals(listOf(1, 10), PackageRepository.versionNumber("1.10."))
        assertEquals(emptyList<Int>(), PackageRepository.versionNumber("v1.2"))
        assertEquals(emptyList<Int>(), PackageRepository.versionNumber(""))
        assertEquals("over Int.MAX_VALUE ends it", listOf(1), PackageRepository.versionNumber("1.2147483648"))
    }

    @Test
    fun versionsCompareAsQVersionNumberComparesThem() {
        assertTrue(PackageRepository.newer("1.2", "1.10"))
        assertFalse(PackageRepository.newer("1.10", "1.2"))
        assertTrue(PackageRepository.newer("1.2", "1.2.1"))
        assertFalse("a trailing 0 is still the longer", PackageRepository.newer("1.2.0", "1.2"))
        assertTrue(PackageRepository.newer("1.2", "1.2.0"))
        assertFalse(PackageRepository.newer("1.2", "1.2"))
        assertFalse(PackageRepository.newer("1.2.1", "1.2"))
        assertTrue("letters after the numbers count for nothing", PackageRepository.newer("1.2-beta", "1.3"))
        assertFalse(PackageRepository.newer("1.2-beta", "1.2-rc"))
        assertEquals(0, PackageRepository.compareVersions(emptyList(), emptyList()))
        assertEquals(-1, PackageRepository.compareVersions(listOf(1), listOf(1, 0)))
        assertEquals(1, PackageRepository.compareVersions(listOf(1, 0), listOf(1)))
    }

    @Test
    fun anInstalledPackageIsTheRepositorysOnlyByTheSameAuthor() {
        assertTrue(PackageRepository.matches(installed("1.2"), mapper))
        assertFalse("anyone can make a package of that name", PackageRepository.matches(installed("1.2", author = "someone"), mapper))
        assertFalse(PackageRepository.matches(installed("1.2", name = "other"), mapper))
        val noDetails = InstalledPackage("generic_mapper", "", "", "", "", "")
        assertFalse("no config.lua, no package info", PackageRepository.matches(noDetails, mapper.copy(author = "")))
    }

    @Test
    fun anUpdateIsANewerVersionOfTheSamePackage() {
        assertTrue(PackageRepository.hasUpdate(installed("1.2"), mapper))
        assertFalse(PackageRepository.hasUpdate(installed("1.3"), mapper))
        assertFalse(PackageRepository.hasUpdate(installed("1.4"), mapper))
        assertFalse(PackageRepository.hasUpdate(installed("1.2", author = "someone"), mapper))
        assertFalse("no version, no update", PackageRepository.hasUpdate(installed(""), mapper))
        assertFalse(PackageRepository.hasUpdate(installed("1.2"), mapper.copy(version = "")))
    }

    @Test
    fun filesAndIconsDownloadFromTheRepositoryAsDesktopAsksForThem() {
        assertEquals("https://github.com/Mudlet/mudlet-package-repository/raw/refs/heads/main/packages/generic_mapper.mpackage", PackageRepository.fileUrl(mapper))
        assertEquals(
            "only the file's name, encoded",
            "https://github.com/Mudlet/mudlet-package-repository/raw/refs/heads/main/packages/a%20b%2Bc%C3%A9.mpackage",
            PackageRepository.fileUrl(mapper.copy(filename = "../x/a b+cé.mpackage")),
        )
        assertEquals("a b+cé.mpackage", PackageRepository.fileName(mapper.copy(filename = "../x/a b+cé.mpackage")))
        assertNull(PackageRepository.fileUrl(mapper.copy(filename = "")))
        assertNull(PackageRepository.fileUrl(mapper.copy(filename = "packages/..")))
        assertEquals("https://github.com/Mudlet/mudlet-package-repository/raw/refs/heads/main/icons/generic%20mapper.png", PackageRepository.iconUrl(mapper))
        assertNull(PackageRepository.iconUrl(mapper.copy(icon = "")))
        assertEquals("AZaz09-._~%2F%3F%23%25", PackageRepository.encode("AZaz09-._~/?#%"))
    }

    @Test
    fun onlyHttpsToTheRepositorysHostsIsAllowed() {
        assertTrue(PackageRepository.allowed(PackageRepository.INDEX_URL))
        assertTrue(PackageRepository.allowed(PackageRepository.fileUrl(mapper)!!))
        assertTrue(PackageRepository.allowed("https://GitHub.com:443/x"))
        assertFalse(PackageRepository.allowed("http://raw.githubusercontent.com/x"))
        assertFalse(PackageRepository.allowed("https://example.org/x"))
        assertFalse(PackageRepository.allowed("https://github.com.example.org/x"))
        assertFalse(PackageRepository.allowed("https://user@github.com/x"))
        assertFalse(PackageRepository.allowed("https://github.com:8443/x"))
        assertFalse(PackageRepository.allowed("not a url"))
    }

    @Test
    fun theSearchFindsAnyFieldWhateverItsCase() {
        assertTrue(PackageRepository.found("", "a", "", "", ""))
        assertTrue(PackageRepository.found("MAP", "generic_mapper", "", "", ""))
        assertTrue(PackageRepository.found("walk", "x", "", "Maps as you walk", ""))
        assertTrue(PackageRepository.found("mud", "x", "", "", "Mudlet"))
        assertTrue(PackageRepository.found("generic m", "x", "Generic Mapper", "", ""))
        assertFalse(PackageRepository.found("combat", "generic_mapper", "Generic Mapper", "Maps as you walk", "Mudlet"))
    }

    @Test
    fun theOrderIsByNameWhateverItsCaseThenUpperCaseFirst() {
        assertEquals(listOf("alpha", "Beta", "beta", "gamma"), listOf("gamma", "beta", "alpha", "Beta").sortedWith(PackageRepository.ORDER))
    }

    @Test
    fun aDownloadOverItsLimitFails() {
        val out = ByteArrayOutputStream()
        PackageRepository.copyAtMost(ByteArrayInputStream(ByteArray(10)), out, 10)
        assertEquals(10, out.size())
        try {
            PackageRepository.copyAtMost(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), 10)
            throw AssertionError("over the limit")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theIndexIsKeptWholeInTheProfilesFolder() {
        val filesDir = temp.newFolder("files")
        val file = PackageRepository.indexFile(filesDir, "Achaea")
        assertEquals(File(ProfileArchive.folder(filesDir, "Achaea"), "mpkg.packages.json"), file)
        PackageRepository.save(file, "old".toByteArray())
        PackageRepository.save(file, "new".toByteArray())
        assertArrayEquals("new".toByteArray(), file.readBytes())
        assertEquals("nothing left beside it", listOf("mpkg.packages.json"), file.parentFile!!.list()!!.toList())
    }
}
