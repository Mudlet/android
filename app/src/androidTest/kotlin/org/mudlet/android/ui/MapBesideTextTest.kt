package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile

/**
 * On a screen as wide as a tablet's on its side, the app's map stands beside the game's text, as desktop Mudlet's
 * docked mapper (docs/architecture.md, "The map behind the text"): the map button puts it away and brings it back,
 * without the setting or with a script's mapper on the screen the button brings the map to the front as on a phone,
 * and the player's choice outlasts a rotation and a fold. The screen is a tablet's before the Activity starts
 * ([TabletScreen]), at its own density for the accessibility checks.
 */
@RunWith(AndroidJUnit4::class)
class MapBesideTextTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val tablet = TabletScreen()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(tablet)
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val console = hasText(CONSOLE_LINE)
    private lateinit var map: SemanticsMatcher
    private lateinit var button: SemanticsMatcher

    @Before
    fun openTheGameScreenOnATablet() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { Settings.mapBesideText.reset() }
        map = hasContentDescription(string(R.string.map_description)) or hasContentDescription(string(R.string.map_not_drawn))
        button = hasContentDescription(string(R.string.app_map_show)) and hasClickAction()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, CONSOLE_LINE)
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor("the console", console)
    }

    @After
    fun defaults() {
        compose.runOnIdle {
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.mapBesideText.reset()
        }
    }

    @Test
    fun theMapStandsBesideTheTextAndTheButtonPutsItAwayAndBringsItBack() {
        besideTheText()

        activate(button)
        textAlone()
        activate(button)
        besideTheText()

        // Desktop's Alt+M from a keyboard does as the button
        altM()
        textAlone()
        altM()
        besideTheText()
    }

    @Test
    fun withoutTheSettingTheButtonBringsTheMapToTheFrontAsOnAPhone() {
        compose.runOnIdle { Settings.mapBesideText.set(false) }
        textAlone()
        activate(button)
        inFront()
        activate(button)
        textAlone()
    }

    @Test
    fun aScriptsMapperIsTheMapThen() {
        besideTheText()
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = 400, h = 300)), full = true)
        textAlone(scriptMapper = true)
        activate(button)
        inFront()
    }

    @Test
    fun whereThePlayerPutTheMapOutlastsARotationAndAFold() {
        // Put away, it stays away as the activity is made anew
        besideTheText()
        activate(button)
        textAlone()
        compose.activityRule.scenario.recreate()
        waitFor("the console after the rotation", console)
        textAlone()
        activate(button)
        besideTheText()

        // Folded, the map goes and the text has the screen; unfolded, the map is back beside it
        fold()
        textAlone()
        unfold()
        besideTheText()

        // Brought to the front on the folded phone, it goes beside the text as it unfolds, and to the front as it folds
        fold()
        activate(button)
        inFront()
        unfold()
        besideTheText()
        fold()
        inFront()
    }

    // The map beside the console, at its end side, both read by TalkBack
    private fun besideTheText() {
        waitFor("the map beside the text", map)
        waitFor("the console beside the map", console)
        val text = bounds(console)
        val drawn = bounds(map)
        assertTrue("the map $drawn after the text $text", drawn.left >= text.right)
        compose.onNode(hasSetTextAction() and hasText(string(R.string.app_map_search))).assertExists()
        pressed(ToggleableState.On)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun textAlone(scriptMapper: Boolean = false) {
        waitFor("the console", console)
        // A script's mapper window has a map of its own, read as the script's
        if (!scriptMapper) waitGone("no map", map)
        waitGone("no room search", hasSetTextAction() and hasText(string(R.string.app_map_search)))
        pressed(ToggleableState.Off)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    // The map in front of the text, as on a phone: its search and the button pressed, with no text beside it
    private fun inFront() {
        waitFor("the room search", hasSetTextAction() and hasText(string(R.string.app_map_search)))
        pressed(ToggleableState.On)
        val search = bounds(hasSetTextAction() and hasText(string(R.string.app_map_search)))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the map in front spans the screen: $search in $root", search.width > root.width / 2)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun pressed(state: ToggleableState) = compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

    @OptIn(ExperimentalTestApi::class)
    private fun altM() {
        compose.onNode(hasSetTextAction() and hasText(string(R.string.input_label))).requestFocus().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.M) } }
        compose.waitForIdle()
    }

    // Each remakes the Activity, as a real fold does: what shows is the new one's once its root is the new width
    private fun fold() {
        tablet.fold()
        compose.waitUntil(WAIT_MS) { rootWidthDp()?.let(tablet::folded) == true }
    }

    private fun unfold() {
        tablet.unfold()
        compose.waitUntil(WAIT_MS) { rootWidthDp()?.let(tablet::unfolded) == true }
    }

    private fun rootWidthDp() = compose.onAllNodes(isRoot()).fetchSemanticsNodes().singleOrNull()?.let { it.size.width / compose.density.density }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun waitFor(what: String, matcher: SemanticsMatcher) = try {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("$what: not shown", e)
    }

    private fun waitGone(what: String, matcher: SemanticsMatcher) = try {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("$what: still shown", e)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Beside", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-beside"
        const val CONSOLE_LINE = "the main console"

        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
