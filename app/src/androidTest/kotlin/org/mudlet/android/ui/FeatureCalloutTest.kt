package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.GameTourShown

/**
 * The callout about switching games (FeatureCallout) over the game screen's title, against a fake engine, for a player
 * who has never seen it: none with one game open, and once a second opens, under the title, said by TalkBack and in
 * TalkBack's words while it is on; Got it, a tap on the title, or a switch through the title's action, Ctrl+Tab or the
 * profile list, ends it for good; it waits for the tour and for the question to a TalkBack player.
 */
@RunWith(AndroidJUnit4::class)
class FeatureCalloutTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA.name))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    // Alpha's, which the UI's control socket is answered on
    private lateinit var alpha: FakeEngine.Session

    private val gameTitle = hasTestTag(GAME_TITLE_TAG)
    private val calloutTitle get() = hasText(string(R.string.callout_switch_games_title))

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { FeatureCallouts.reset(compose.activity, dismissed = false) }
        alpha = fake.engine.nextSession()
        alpha.profiles(PROFILES, alpha.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 1 && EngineClient.sessions.all { it.linkState.startsWith("attached") } }
        alpha.synced(0)
        alpha.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.front.status["connection"] == "connected" }
    }

    // Stored, or lasting as long as the process, so they would outlast the test
    @After
    fun reset() = compose.runOnIdle {
        Announcer.touchExplorationOverride = null
        Settings.screenReaderOffered.reset()
        EngineClient.sessions.forEach { it.settings.clear() }
        FeatureCallouts.reset(compose.activity, dismissed = true)
    }

    @Test
    fun noCalloutWithOneGame_andOnceASecondOpensItShowsUnderTheTitle_said_untilGotItEndsItForGood() {
        show(ALPHA.name)
        assertNoCallout("with one game open")

        openBeta()
        waitFor(calloutTitle)
        val body = string(R.string.callout_switch_games)
        compose.onNode(hasText(body)).assertIsDisplayed()
        val title = compose.onNode(gameTitle).fetchSemanticsNode()
        val callout = compose.onNode(calloutTitle).fetchSemanticsNode()
        val titleBottom = title.positionOnScreen.y + title.size.height
        assertTrue("the callout at ${callout.positionOnScreen} is not under the title, which ends at $titleBottom", callout.positionOnScreen.y >= titleBottom - SLACK_PX)
        val said = string(R.string.callout_announcement, string(R.string.callout_switch_games_title), body)
        compose.waitUntil(WAIT_MS) { Announcer.current?.text?.endsWith(said) == true }
        assertEquals("an appearance is not counted", 1, prefs().getInt(SHOWN, 0))
        assertFalse(prefs().getBoolean(DISMISSED, false))

        activate(hasText(string(R.string.callout_got_it)))
        endedForGood(by = "Got it")
    }

    @Test
    fun aSwitchThroughTheTitlesActionEndsItForGood() {
        showCallout()
        checkAccessibility()
        val action = compose.onNode(gameTitle).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            .single { it.label == string(R.string.game_previous, ALPHA.name) }
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(ALPHA.name, alpha.receive("setFront").getString("name"))
        endedForGood(by = "the switch")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aSwitchByCtrlTabEndsItForGood() {
        showCallout()
        val input = hasSetTextAction()
        compose.onNode(input).requestFocus()
        compose.onNode(input).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        }
        assertEquals(ALPHA.name, alpha.receive("setFront").getString("name"))
        endedForGood(by = "the switch")
    }

    @Test
    fun aTapOnTheTitleEndsItForGood() {
        showCallout()
        activate(gameTitle)
        endedForGood(by = "the sheet")
    }

    @Test
    fun bringingAnotherOpenGameFromTheProfileListIsASwitch() {
        openBeta()
        show(ALPHA.name)
        assertTrue("the switch did not end it for good", prefs().getBoolean(DISMISSED, false))
        assertNoCallout("after a switch")
    }

    @Test
    fun itWaitsForTheTour_untilTheTourIsSkipped() {
        compose.runOnIdle { GameTourShown.set(compose.activity, false) }
        openBeta()
        show(BETA.name, titled = false)
        waitFor(hasText(string(R.string.tour_welcome_title)))
        assertNoCallout("over the tour")

        activate(hasText(string(R.string.tour_skip)))
        waitFor(calloutTitle)
        assertEquals(1, prefs().getInt(SHOWN, 0))
    }

    @Test
    fun underTalkBackItWaitsForTheQuestionToItsPlayer_andNamesTheTitlesActions() {
        compose.runOnIdle {
            Settings.screenReaderOffered.set(false)
            Announcer.touchExplorationOverride = true
        }
        openBeta()
        show(BETA.name)
        val no = hasText(string(R.string.screen_reader_offer_no))
        waitFor(no)
        assertNoCallout("over the question to a TalkBack player")

        activate(no)
        waitFor(calloutTitle)
        val body = string(R.string.callout_switch_games_talkback)
        compose.onNode(hasText(body)).assertIsDisplayed()
        val said = string(R.string.callout_announcement, string(R.string.callout_switch_games_title), body)
        compose.waitUntil(WAIT_MS) { Announcer.current?.text?.endsWith(said) == true }
        checkAccessibility()
        assertTrue(none(hasText(string(R.string.callout_switch_games))))
    }

    /** The callout over Beta's screen, Beta opened beside Alpha. */
    private fun showCallout() {
        openBeta()
        show(BETA.name)
        waitFor(calloutTitle)
    }

    private fun endedForGood(by: String) {
        waitUntilGone(calloutTitle)
        assertTrue("$by did not end it for good", prefs().getBoolean(DISMISSED, false))
    }

    /** Beta opened beside Alpha, as the engine does when the player starts a second game. */
    private fun openBeta() {
        fake.engine.openAlongside(BETA.name)
        alpha.openProfiles()
        val beta = alpha.forProfile(BETA.name)
        beta.synced(0)
        beta.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.sessions.all { it.status["connection"] == "connected" } }
    }

    /** Shows the open game [name] from the profile list; [titled]: TalkBack reaches the title then, not under the tour. */
    private fun show(name: String, titled: Boolean = true) {
        val row = hasContentDescription(string(R.string.game_state, name, string(R.string.connection_connected))) and hasClickAction()
        waitFor(row)
        compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(name, alpha.receive("setFront").getString("name"))
        if (titled) waitFor(gameTitle and hasContentDescription(name, substring = true))
    }

    // Past the callout's wait for its anchor to settle, on the test's clock and the device's
    private fun assertNoCallout(where: String) {
        compose.mainClock.advanceTimeBy(QUIET_MS)
        Thread.sleep(QUIET_MS)
        compose.waitForIdle()
        assertTrue("a callout $where", none(calloutTitle))
        assertEquals("an appearance is counted $where", 0, prefs().getInt(SHOWN, 0))
    }

    private fun prefs() = compose.activity.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun none(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { !none(matcher) }

    private fun waitUntilGone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { none(matcher) }

    // Each window's: the callout and a dialog are windows of their own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val ALPHA = Profile("Alpha", "127.0.0.1", 4001, false, "", false)
        val BETA = Profile("Beta", "127.0.0.1", 4002, false, "", false)
        val PROFILES = listOf(ALPHA, BETA)

        // The player's data, by the names it is stored under
        const val DISMISSED = "callout:gameSwitching:dismissed"
        const val SHOWN = "callout:gameSwitching:shown"

        // Rounding of the bounds to pixels
        const val SLACK_PX = 1f

        // Well past the callout's 800 ms wait for its anchor
        const val QUIET_MS = 2_000L
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
