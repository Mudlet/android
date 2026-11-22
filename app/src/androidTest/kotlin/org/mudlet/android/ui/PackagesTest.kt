package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.packages.PACKAGES_TAG

/**
 * The package manager against a fake engine: opened from the game's title bar, it lists the packages with their
 * details, a description cut short shows whole on a tap, an install shows until its answer, which is said, Remove
 * asks first, a URL installs through Mudlet's own installPackage(), a change by a script lists them again, and what
 * the engine refuses is said. Controls are found by the labels TalkBack reads and activated through their
 * accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class PackagesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("packages ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val list = hasTestTag(PACKAGES_TAG)

    private val mapper = pack("generic_mapper", "Generic Mapper", "Mudlet", "1.2", LONG_DESCRIPTION)
    private val plain = pack("plain", "", "", "", "")

    @Before
    fun openThePackages() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no list of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "A rat bites you.")
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        compose.pickFromGameMenu(string(R.string.packages_open, game.name))
        waitFor(hasText(string(R.string.packages_loading)))
        packages(listOf(mapper, plain), received("packages"))
        waitFor(row("Generic Mapper"))
    }

    @Test
    fun eachPackageShowsItsDetailsAndADescriptionCutShortShowsWholeOnATap() {
        compose.onNode(hasText("generic_mapper · ${string(R.string.packages_version, "1.2")} · ${string(R.string.packages_author, "Mudlet")}")).assertIsDisplayed()
        // A plain .xml package gave nothing but its name
        compose.onNode(row("plain")).assertIsDisplayed()
        compose.onNode(row("Generic Mapper") and stateIs(string(R.string.packages_collapsed))).assertIsDisplayed()
        activate(row("Generic Mapper"))
        waitFor(row("Generic Mapper") and stateIs(string(R.string.packages_expanded)))
    }

    @Test
    fun removeAsksFirstAndTheAnswerIsTheListWithoutIt() {
        activate(hasContentDescription(string(R.string.packages_remove_named, "generic_mapper")))
        waitFor(hasText(string(R.string.packages_remove_title, "generic_mapper")))
        activate(hasText(string(R.string.packages_cancel)))
        session.assertNoRequest("uninstallPackage", QUIET_MS)

        activate(hasContentDescription(string(R.string.packages_remove_named, "generic_mapper")))
        activate(hasText(string(R.string.packages_remove)))
        val removal = received("uninstallPackage")
        assertEquals("generic_mapper", removal.getString("name"))
        packages(listOf(plain), removal)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(row("Generic Mapper")).fetchSemanticsNodes().isEmpty() }

        activate(hasContentDescription(string(R.string.packages_remove_named, "plain")))
        activate(hasText(string(R.string.packages_remove)))
        session.refuse(received("uninstallPackage"), "the profile is being saved")
        waitFor(hasText(string(R.string.packages_refused_remove, "the profile is being saved")))
    }

    @Test
    fun anInstallShowsUntilItsAnswerWhichIsSaid() {
        // As the file picker's result installs it: the picker itself is the system's, out of the test's reach
        compose.runOnUiThread { EngineClient.front.packages.install(PICKED) }
        assertEquals(PICKED, received("installPackage").getString("path"))
        waitFor(hasText(string(R.string.packages_installing, "pack.mpackage")))
        session.send(JSONObject().put("t", "packageInstalled").put("path", PICKED).put("result", "failed").put("error", "not a package"))
        waitFor(hasText(string(R.string.packages_install_failed, "pack.mpackage", "not a package")))
        compose.onNode(hasText(string(R.string.packages_installing, "pack.mpackage"))).assertDoesNotExist()
    }

    @Test
    fun aUrlInstallsThroughMudletsOwnInstallPackageAndAChangeListsThemAgain() {
        activate(hasContentDescription(string(R.string.packages_install_url)))
        val install = hasText(string(R.string.packages_install)) and hasClickAction()
        waitFor(install)
        compose.onNode(install).assertIsNotEnabled()
        // The URL's, not the search field behind the dialog
        compose.onNode(hasSetTextAction() and hasText(string(R.string.packages_url))).performTextInput(URL)
        activate(install)
        assertEquals("installPackage([[$URL]])", received("runLua").getString("code"))

        session.send(JSONObject().put("t", "packagesChanged"))
        packages(listOf(mapper, plain, pack("new", "New One", "", "", "")), received("packages"))
        waitFor(row("New One"))
    }

    private fun pack(name: String, title: String, author: String, version: String, description: String) = JSONObject()
        .put("name", name).put("title", title).put("author", author).put("version", version).put("description", description).put("icon", "")

    private fun packages(items: List<JSONObject>, request: JSONObject) = session.send(JSONObject().put("t", "packages").put("packages", JSONArray(items)).put("re", request.get("id")))

    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun row(title: String) = hasText(title) and hasAnyAncestor(list)

    private fun stateIs(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(list)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(list).performScrollToNode(node)
        // Each window's: a dialog is a window of its own over the activity's
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-packages"
        const val URL = "https://example.org/pack.mpackage"
        const val PICKED = "/data/local/tmp/pack.mpackage"
        const val LONG_DESCRIPTION = "Maps the rooms you walk through.\nIt learns each exit as you take it.\n" +
            "It finds the way to any room it knows.\nIt shares the map with the game's own mapper."
        const val QUIET_MS = 1_000
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
