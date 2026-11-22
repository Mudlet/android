package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mudlet.android.data.PackageRepository
import org.mudlet.android.data.RepositoryFetch
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.InstalledPackage
import org.mudlet.android.protocol.PackageInstallResult
import java.io.File
import java.io.IOException

/** What the package manager makes of the repository, against a fetch and an engine caught here: never GitHub. */
class PackageRepositoryClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sent = ArrayList<JSONObject>()
    private val finished = ArrayList<String>()
    private var lastId = 0L
    private val packages = PackagesClient({ ++lastId }, { sent.add(it) }) { finished.add(it) }

    private val answers = HashMap<String, ByteArray>()
    private val asked = ArrayList<String>()
    private val fetch = RepositoryFetch { url, _, maxBytes, into ->
        asked.add(url)
        val bytes = answers[url] ?: throw IOException("offline")
        if (bytes.size > maxBytes) throw IOException("too big")
        into.write(bytes)
    }

    // Run when the test says, as the network's threads would finish, then what they post to the main thread
    private val queued = ArrayList<Runnable>()
    private val posted = ArrayList<() -> Unit>()

    private fun runQueued() {
        while (queued.isNotEmpty() || posted.isNotEmpty()) {
            if (queued.isNotEmpty()) queued.removeAt(0).run() else posted.removeAt(0)()
        }
    }

    private val profile by lazy { temp.newFolder("profile") }
    private val downloads by lazy { temp.newFolder("downloads") }
    private val indexFile by lazy { File(profile, PackageRepository.INDEX_FILE) }
    private var hasProfile = true

    private val repository by lazy {
        PackageRepositoryClient(
            indexFile = { indexFile.takeIf { hasProfile } },
            downloads = { downloads },
            packages = packages,
            decode = { bytes -> String(bytes).takeIf { it.startsWith("PNG") } },
            main = { posted.add(it) },
            network = { queued.add(it) },
            iconThreads = { queued.add(it) },
        ).also { PackageRepositoryClient.fetch = fetch }
    }

    private fun entry(name: String, version: String = "1.3", author: String = "Mudlet", file: String = "$name.mpackage", icon: String = "") = """{"mpackage": "$name", "title": "", "author": "$author", "version": "$version", "description": "", "filename": "$file", "icon": "$icon"}"""

    private fun index(vararg entries: String) = """{"packages": [${entries.joinToString()}]}"""

    private fun mine(name: String, version: String, author: String = "Mudlet") = InstalledPackage(name, name, author, version, "", "")

    private fun listed(list: List<InstalledPackage>) {
        packages.ask()
        val ask = sent.removeAt(sent.lastIndex)
        assertTrue(packages.onPackages(EngineMessage.Packages(list, ask.getLong("id"))))
    }

    private fun take(type: String): JSONObject {
        val request = sent.removeAt(sent.lastIndex)
        assertEquals(type, request.getString("t"))
        return request
    }

    @Test
    fun theKeptIndexIsReadWithoutADownload() {
        indexFile.writeText(index(entry("zmud"), entry("Beta"), entry("alpha")))
        repository.open()
        assertEquals(IndexState.LOADING, repository.state)
        runQueued()
        assertEquals(IndexState.READY, repository.state)
        assertEquals("in desktop's order", listOf("alpha", "Beta", "zmud"), repository.index!!.map { it.name })
        assertTrue(asked.isEmpty())
    }

    @Test
    fun whatMpkgKeptSinceIsReadOnTheNextOpen() {
        indexFile.writeText(index(entry("old")))
        repository.open()
        repository.open()
        assertEquals("read once at a time", 1, queued.size)
        runQueued()
        indexFile.writeText(index(entry("old"), entry("new")))
        repository.open()
        runQueued()
        assertEquals(listOf("new", "old"), repository.index!!.map { it.name })
        assertTrue(asked.isEmpty())

        indexFile.delete()
        repository.open()
        runQueued()
        assertEquals("the one shown stays", listOf("new", "old"), repository.index!!.map { it.name })
        assertTrue("and is not downloaded unasked", asked.isEmpty())
    }

    @Test
    fun aNameTheIndexGivesTwiceIsItsLast() {
        indexFile.writeText(index(entry("generic_mapper", "1.0"), entry("other"), entry("generic_mapper", "2.0")))
        repository.open()
        runQueued()
        assertEquals(listOf("generic_mapper" to "2.0", "other" to "1.3"), repository.index!!.map { it.name to it.version })
        assertEquals("2.0", repository.entry("generic_mapper")!!.version)
    }

    @Test
    fun withNoKeptIndexItIsDownloadedAndKeptForMpkg() {
        val text = index(entry("generic_mapper"))
        answers[PackageRepository.INDEX_URL] = text.toByteArray()
        repository.open()
        runQueued()
        assertEquals(listOf(PackageRepository.INDEX_URL), asked)
        assertEquals(listOf("generic_mapper"), repository.index!!.map { it.name })
        assertEquals("the very bytes downloaded", text, indexFile.readText())
        assertFalse(repository.refreshing)
    }

    @Test
    fun anUnreadableKeptIndexIsDownloadedAgain() {
        indexFile.writeText("{ cut short")
        answers[PackageRepository.INDEX_URL] = index(entry("generic_mapper")).toByteArray()
        repository.open()
        runQueued()
        assertEquals(listOf("generic_mapper"), repository.index!!.map { it.name })
    }

    @Test
    fun aPageThatIsNotAnIndexNeitherReplacesTheKeptOneNorIsShown() {
        val kept = index(entry("generic_mapper"))
        indexFile.writeText(kept)
        repository.open()
        runQueued()
        answers[PackageRepository.INDEX_URL] = "<html>Sign in to the Wi-Fi</html>".toByteArray()
        repository.refresh()
        assertTrue(repository.refreshing)
        runQueued()
        assertEquals(kept, indexFile.readText())
        assertEquals(listOf("generic_mapper"), repository.index!!.map { it.name })
        assertEquals(IndexState.READY, repository.state)
        assertEquals(listOf<PackageNotice>(PackageNotice.IndexFailed(cached = true)), packages.notices)
    }

    @Test
    fun offlineWithNothingKeptTheRepositoryIsUnavailable() {
        repository.open()
        runQueued()
        assertNull(repository.index)
        assertEquals(IndexState.UNAVAILABLE, repository.state)
        assertFalse(indexFile.exists())
        assertTrue("the views say so, unasked", packages.notices.isEmpty())

        repository.open()
        runQueued()
        assertEquals("tried again on the next open", listOf(PackageRepository.INDEX_URL, PackageRepository.INDEX_URL), asked)
        assertTrue(packages.notices.isEmpty())

        repository.refresh()
        runQueued()
        assertEquals("a Refresh is answered", listOf<PackageNotice>(PackageNotice.IndexFailed(cached = false)), packages.notices)

        answers[PackageRepository.INDEX_URL] = index(entry("generic_mapper")).toByteArray()
        repository.refresh()
        runQueued()
        assertEquals(IndexState.READY, repository.state)
    }

    @Test
    fun aRefreshThatFinishesFirstIsNotUndoneByTheOlderRead() {
        indexFile.writeText(index(entry("old")))
        answers[PackageRepository.INDEX_URL] = index(entry("new")).toByteArray()
        repository.open()
        repository.refresh()
        // The read reads the old file before the download replaces it, but its answer reaches the main thread last
        queued.toList().forEach(Runnable::run)
        queued.clear()
        posted.reversed().forEach { it() }
        posted.clear()
        assertEquals(listOf("new"), repository.index!!.map { it.name })
        assertEquals(index(entry("new")), indexFile.readText())
    }

    @Test
    fun withNoProfileThereIsNothingToReadOrKeep() {
        hasProfile = false
        repository.open()
        repository.refresh()
        assertTrue(queued.isEmpty())
        assertEquals(IndexState.UNAVAILABLE, repository.state)
    }

    @Test
    fun eachPackageStandsAsDesktopShowsIt() {
        indexFile.writeText(index(entry("generic_mapper", "1.3"), entry("current", "2.0"), entry("theirs", "5"), entry("fresh")))
        repository.open()
        runQueued()
        val installed = listOf(mine("generic_mapper", "1.2"), mine("current", "2.0"), mine("theirs", "1", author = "someone"))
        assertEquals(RepositoryState.UPDATE, repository.standing(repository.entry("generic_mapper")!!, installed))
        assertEquals(RepositoryState.INSTALLED, repository.standing(repository.entry("current")!!, installed))
        assertEquals("another author's, of the same name", RepositoryState.AVAILABLE, repository.standing(repository.entry("theirs")!!, installed))
        assertEquals(RepositoryState.AVAILABLE, repository.standing(repository.entry("fresh")!!, installed))
        assertEquals(RepositoryState.AVAILABLE, repository.standing(repository.entry("fresh")!!, null))
        assertEquals(listOf(mine("generic_mapper", "1.2")), repository.updates(installed))
        assertEquals(emptyList<InstalledPackage>(), repository.updates(null))
    }

    @Test
    fun anInstallDownloadsIntoAFolderOfItsOwnAndHandsTheFileToTheEngine() {
        indexFile.writeText(index(entry("generic_mapper")))
        repository.open()
        runQueued()
        val url = PackageRepository.fileUrl(repository.entry("generic_mapper")!!)!!
        answers[url] = "PK package".toByteArray()
        listed(emptyList())

        repository.install("generic_mapper")
        assertEquals(listOf("generic_mapper"), repository.downloading)
        repository.install("generic_mapper")
        runQueued()
        assertEquals("downloaded once", listOf(url), asked)
        assertTrue(repository.downloading.isEmpty())
        val path = take("installPackage").getString("path")
        val file = File(path)
        assertEquals("generic_mapper.mpackage", file.name)
        assertEquals(downloads, file.parentFile.parentFile)
        assertEquals("PK package", file.readText())

        assertTrue("busy until the engine answers", repository.busy("generic_mapper"))
        repository.install("generic_mapper")
        runQueued()
        assertEquals("not downloaded again while it installs", listOf(url), asked)
        packages.onInstalled(EngineMessage.PackageInstalled(path, PackageInstallResult.INSTALLED, ""))
        assertFalse(repository.busy("generic_mapper"))
    }

    @Test
    fun anUpdateRemovesTheInstalledPackageFirst() {
        indexFile.writeText(index(entry("generic_mapper", "1.3")))
        repository.open()
        runQueued()
        answers[PackageRepository.fileUrl(repository.entry("generic_mapper")!!)!!] = "PK package".toByteArray()
        listed(listOf(mine("generic_mapper", "1.2")))

        repository.install("generic_mapper")
        runQueued()
        val removal = take("uninstallPackage")
        assertEquals("generic_mapper", removal.getString("name"))
        assertTrue(sent.isEmpty())
        packages.onPackages(EngineMessage.Packages(emptyList(), removal.getLong("id")))
        assertTrue(File(take("installPackage").getString("path")).isFile)
    }

    @Test
    fun aFailedDownloadIsToldAndLeavesNothingBehind() {
        indexFile.writeText(index(entry("generic_mapper")))
        repository.open()
        runQueued()
        listed(emptyList())
        repository.install("generic_mapper")
        runQueued()
        assertTrue(sent.isEmpty())
        assertTrue(repository.downloading.isEmpty())
        assertEquals(listOf<PackageNotice>(PackageNotice.DownloadFailed("generic_mapper")), packages.notices)
        assertEquals(emptyList<String>(), downloads.list()!!.toList())
    }

    @Test
    fun aPackageWithNoFileIsNotInTheRepositoryAndTheIndexIsDownloadedAgain() {
        indexFile.writeText(index(entry("generic_mapper", file = "")))
        repository.open()
        runQueued()
        answers[PackageRepository.INDEX_URL] = index(entry("generic_mapper")).toByteArray()
        repository.install("generic_mapper")
        assertEquals(listOf<PackageNotice>(PackageNotice.NotInRepository("generic_mapper")), packages.notices)
        assertTrue(repository.refreshing)
        runQueued()
        assertEquals("generic_mapper.mpackage", repository.entry("generic_mapper")!!.filename)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anIconIsDownloadedOnceAndAFailedOneTriedAgainWithANewIndex() {
        indexFile.writeText(index(entry("a", icon = "icons/a.png"), entry("b", icon = "icons/b.png"), entry("c")))
        repository.open()
        runQueued()
        val a = PackageRepository.iconUrl(repository.entry("a")!!)!!
        val b = PackageRepository.iconUrl(repository.entry("b")!!)!!
        answers[a] = "PNG a".toByteArray()
        answers[b] = "not an image".toByteArray()
        listOf("a", "a", "b", "c").forEach(repository::iconShown)
        runQueued()
        assertEquals(listOf(a, b), asked)
        assertEquals(mapOf("a" to "PNG a"), repository.icons)
        repository.iconShown("a")
        repository.iconShown("b")
        assertTrue("not asked again", queued.isEmpty())

        answers[PackageRepository.INDEX_URL] = indexFile.readBytes()
        answers[b] = "PNG b".toByteArray()
        repository.refresh()
        runQueued()
        repository.iconShown("a")
        repository.iconShown("b")
        runQueued()
        assertEquals(mapOf("a" to "PNG a", "b" to "PNG b"), repository.icons)
    }
}
