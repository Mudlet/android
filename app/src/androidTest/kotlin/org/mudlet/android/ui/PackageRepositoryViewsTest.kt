package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.PackageRepository
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.data.RepositoryFetch
import org.mudlet.android.data.RepositoryPackage
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.packages.PACKAGES_TAG
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The package manager's Explore and Updates views against a fake engine and a fake repository, never GitHub: the index
 * downloaded is listed and kept in the profile's folder for mpkg, each package reads where it stands, Install downloads
 * the package and sends installPackage, Update removes the installed one first, the search filters a view, and a failed
 * Refresh keeps the index shown and says so. Controls are found by the labels TalkBack reads and activated through their
 * accessibility actions, after the Accessibility Test Framework's checks.
 */
@RunWith(AndroidJUnit4::class)
class PackageRepositoryViewsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("repository ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val list = hasTestTag(PACKAGES_TAG)
    private val folder by lazy { ProfileArchive.folder(compose.activity.filesDir, game.name) }
    private val indexFile by lazy { File(folder, PackageRepository.INDEX_FILE) }

    private val answers = ConcurrentHashMap<String, ByteArray>()
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val index = JSONObject().put(
        "packages",
        JSONArray()
            .put(entry("generic_mapper", "Generic Mapper", "1.3", "icons/generic_mapper.png"))
            .put(entry("fresh", "Fresh Package", "2.0", "")),
    ).toString()

    @Before
    fun answerAsTheRepository() {
        compose.enableAccessibilityChecksOfWhatShows()
        PackageRepositoryClient.fetch = RepositoryFetch { url, _, _, into ->
            asked.add(url)
            into.write(answers[url] ?: throw IOException("offline"))
        }
    }

    @After
    fun removeTheProfilesFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun theIndexIsListedAndKeptAndEachPackageReadsWhereItStands() {
        answers[PackageRepository.INDEX_URL] = index.toByteArray()
        answers[PackageRepository.iconUrl(repositoryPackage("generic_mapper", icon = "icons/generic_mapper.png"))!!] = png()
        open(listOf(installed("generic_mapper", "Generic Mapper", "1.2")))
        // Read on opening, so the Updates chip counts it from the Installed view
        waitFor(hasText(string(R.string.packages_view_updates_count, 1)))
        assertArrayEquals("the very bytes downloaded, where mpkg reads them", index.toByteArray(), indexFile.readBytes())

        activate(hasText(string(R.string.packages_view_explore)))
        waitFor(row("Generic Mapper") and hasText(string(R.string.packages_state_update)))
        waitFor(row("Fresh Package"))
        assertFalse("not installed", hasState(row("Fresh Package")))
        compose.onNode(hasContentDescription(string(R.string.packages_update_named, "generic_mapper"))).assertIsDisplayed()
        compose.onNode(hasContentDescription(string(R.string.packages_install_named, "fresh"))).assertIsDisplayed()
        compose.waitUntil(WAIT_MS) { PackageRepository.iconUrl(repositoryPackage("generic_mapper", icon = "icons/generic_mapper.png"))!! in asked }

        compose.onNode(hasSetTextAction()).performTextInput("fresh")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(row("Generic Mapper")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(row("Fresh Package")).assertIsDisplayed()
        // A search is of one view, as on desktop
        activate(hasText(string(R.string.packages_view_updates_count, 1)))
        waitFor(row("Generic Mapper") and hasText(string(R.string.packages_update_versions, "1.2", "1.3"), substring = true))
        compose.onNode(row("Fresh Package")).assertDoesNotExist()
    }

    @Test
    fun installDownloadsThePackageAndSendsItsFileToTheEngine() {
        answers[PackageRepository.INDEX_URL] = index.toByteArray()
        answers[PackageRepository.fileUrl(repositoryPackage("fresh"))!!] = PACKAGE
        open(emptyList())
        activate(hasText(string(R.string.packages_view_explore)))
        activate(hasContentDescription(string(R.string.packages_install_named, "fresh")))
        val path = received("installPackage").getString("path")
        val file = File(path)
        assertEquals("fresh.mpackage", file.name)
        assertEquals("in a folder of its own in the cache", File(compose.activity.cacheDir, "packages"), file.parentFile!!.parentFile)
        assertArrayEquals(PACKAGE, file.readBytes())

        session.send(JSONObject().put("t", "packageInstalled").put("path", path).put("result", "installed").put("error", ""))
        waitFor(hasText(string(R.string.packages_installed, "fresh.mpackage")))
        compose.waitUntil(WAIT_MS) { !file.parentFile!!.exists() }
        session.send(JSONObject().put("t", "packagesChanged"))
        packages(listOf(installed("fresh", "Fresh Package", "2.0")), received("packages"))
        waitFor(row("Fresh Package") and hasText(string(R.string.packages_state_installed)))
        compose.onNode(hasContentDescription(string(R.string.packages_install_named, "fresh"))).assertDoesNotExist()
    }

    @Test
    fun updateRemovesTheInstalledPackageThenInstallsTheNewOne() {
        answers[PackageRepository.INDEX_URL] = index.toByteArray()
        answers[PackageRepository.fileUrl(repositoryPackage("generic_mapper"))!!] = PACKAGE
        open(listOf(installed("generic_mapper", "Generic Mapper", "1.2")))
        activate(hasText(string(R.string.packages_view_updates_count, 1)))
        activate(hasContentDescription(string(R.string.packages_update_named, "generic_mapper")))
        val removal = received("uninstallPackage")
        assertEquals("generic_mapper", removal.getString("name"))
        session.assertNoRequest("installPackage", QUIET_MS)
        packages(emptyList(), removal)
        assertArrayEquals(PACKAGE, File(received("installPackage").getString("path")).readBytes())
    }

    @Test
    fun aFailedRefreshKeepsTheIndexShownAndSaysSo() {
        assertTrue(folder.mkdirs() || folder.isDirectory)
        indexFile.writeText(index)
        open(emptyList())
        activate(hasText(string(R.string.packages_view_explore)))
        waitFor(row("Fresh Package"))
        // Only the index: the rows shown download their icons
        assertFalse("read, not downloaded", PackageRepository.INDEX_URL in asked)

        activate(hasContentDescription(string(R.string.packages_refresh)))
        waitFor(hasText(string(R.string.packages_index_failed_cached)))
        compose.onNode(row("Fresh Package")).assertIsDisplayed()
        assertEquals(index, indexFile.readText())
    }

    @Test
    fun withNothingKeptAndNoNetworkTheRepositoryIsNotAvailable() {
        open(emptyList())
        activate(hasText(string(R.string.packages_view_explore)))
        waitFor(hasText(string(R.string.packages_repository_unavailable)))
        assertFalse(indexFile.exists())
    }

    private fun open(installed: List<JSONObject>) {
        // A new engine instance for each test, so no list of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        compose.pickFromGameMenu(string(R.string.packages_open, game.name))
        packages(installed, received("packages"))
        waitFor(hasText(string(R.string.packages_view_installed)))
    }

    private fun entry(name: String, title: String, version: String, icon: String) = JSONObject()
        .put("mpackage", name).put("title", title).put("author", "Mudlet").put("version", version)
        .put("description", "$title, from the repository").put("filename", "$name.mpackage").put("icon", icon)

    private fun repositoryPackage(name: String, icon: String = "") = RepositoryPackage(name, "", "Mudlet", "", "", "$name.mpackage", icon)

    private fun installed(name: String, title: String, version: String) = JSONObject()
        .put("name", name).put("title", title).put("author", "Mudlet").put("version", version).put("description", "").put("icon", "")

    private fun png(): ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun packages(items: List<JSONObject>, request: JSONObject) = session.send(JSONObject().put("t", "packages").put("packages", JSONArray(items)).put("re", request.get("id")))

    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun row(title: String) = hasText(title) and hasAnyAncestor(list)

    private fun hasState(row: SemanticsMatcher) = compose.onAllNodes(
        row and (hasText(string(R.string.packages_state_installed)) or hasText(string(R.string.packages_state_update))),
    ).fetchSemanticsNodes().isNotEmpty()

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(list)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(list).performScrollToNode(node)
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-repository"
        val PACKAGE = "PK a package".toByteArray()
        const val QUIET_MS = 1_000
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
