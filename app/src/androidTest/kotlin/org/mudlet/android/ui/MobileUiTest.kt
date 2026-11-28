package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.engine.EngineService
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.packages.PACKAGES_TAG

/**
 * A package's interface made for larger screens (docs/architecture.md, "Packages' interfaces on a phone"), against a fake
 * engine: the label and the dock of a package that does not declare one that fits a phone are neither drawn nor read,
 * while a label of a package that does, and the profile's own, are; the package manager offers that package, and only
 * it, a switch that shows its interface anyway, kept for the profile; and Mudlet is told the main window's size with
 * everything drawn either way. Controls are found by the labels TalkBack reads and activated
 * through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("mobile ui ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val list = hasTestTag(PACKAGES_TAG)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no window of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText("the main console"))
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        session.windows(
            listOf(
                label("big", y = 0).by("Big GUI", mobile = false),
                label("phone", y = 200).by("Phone GUI", mobile = true),
                label("mine", y = 400),
                FakeEngine.window("bigDock", kind = "userWindow", w = 200, h = 600, dockArea = "right").by("Big GUI", mobile = false),
            ),
            full = true,
        )
        session.label("big", text = BIG)
        session.label("phone", text = PHONE)
        session.label("mine", text = MINE)
        session.windowText("bigDock", listOf(DOCK))
        waitFor(hasText(PHONE))
        waitFor(hasText(MINE))
    }

    @After
    fun forgetTheChoice() {
        ScriptWindowsClient.forgetShownAnyway(compose.activity.applicationContext, game.name)
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun anUndeclaredPackagesLabelIsNeitherDrawnNorReadAndADeclaredOneIs() {
        compose.onNode(hasText(BIG)).assertDoesNotExist()
        compose.onNode(hasText(DOCK)).assertDoesNotExist()
        val reading = TalkBackReading(compose)
        assertTrue("TalkBack reads ${reading.read}", reading.reads(hasText(PHONE)))
        assertTrue("TalkBack reads ${reading.read}", reading.reads(hasText(MINE)))
        assertFalse("TalkBack reads ${reading.read}", reading.read.any { BIG in it || DOCK in it })
    }

    @Test
    fun theSwitchShowsAPackagesInterfaceAnywayAndIsKeptForTheProfile() {
        // Laid out with the dock that is not drawn: ScriptWindowsClient sends no size twice, so showing it sends none
        val told = session.receiveLast("setMainWindowSize", readPast = true)
        compose.pickFromGameMenu(string(R.string.packages_open, game.name))
        compose.waitForIdle()
        val listed = session.receive("packages")
        // Quiet made no window, so has nothing to show
        val packages = listOf(pack("Big GUI", mobile = false), pack("Phone GUI", mobile = true), pack("Quiet", mobile = false))
        session.send(JSONObject().put("t", "packages").put("packages", JSONArray(packages)).put("re", listed.get("id")))
        waitFor(hasText("Quiet") and hasAnyAncestor(list))

        val big = switchFor("Big GUI")
        waitFor(big)
        compose.onNode(big).assert(isOff())
        compose.onNode(switchFor("Phone GUI")).assertDoesNotExist()
        compose.onNode(switchFor("Quiet")).assertDoesNotExist()

        activate(big)
        waitFor(big and isOn())
        val prefs = compose.activity.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)
        assertEquals(setOf("Big GUI"), prefs.getStringSet("packageUiShown:${game.name.lowercase()}", null))

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(BIG))
        waitFor(hasText(DOCK))
        val reading = TalkBackReading(compose)
        assertTrue("TalkBack reads ${reading.read}", reading.reads(hasText(BIG)))
        val size = session.receiveLast("setMainWindowSize", readPast = true)
        assertEquals("Mudlet's main window", told.getInt("w") to told.getInt("h"), size.getInt("w") to size.getInt("h"))
    }

    private fun label(name: String, y: Int) = FakeEngine.window(name, kind = "label", x = 0, y = y, w = 400, h = 100)

    private fun JSONObject.by(packageName: String, mobile: Boolean) = put("packages", JSONArray(listOf(packageName))).put("mobile", mobile)

    private fun pack(name: String, mobile: Boolean) = JSONObject()
        .put("name", name).put("title", "").put("author", "").put("version", "").put("description", "").put("icon", "").put("mobile", mobile)

    private fun switchFor(packageName: String) = hasContentDescription(string(R.string.packages_ui_show_anyway_named, packageName)) and hasClickAction()

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
        const val INSTANCE = "fake-mobile-ui"
        const val BIG = "Made for a big screen"
        const val PHONE = "Made for a phone"
        const val MINE = "The profile's own"
        const val DOCK = "In the big screen's dock"
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
