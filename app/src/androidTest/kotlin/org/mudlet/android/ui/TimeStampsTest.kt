package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.LAG_TAG
import org.mudlet.android.ui.game.TIME_STAMPS_TAG
import org.mudlet.android.ui.game.timeStamp

/**
 * Desktop Mudlet's timestamps and latency box against a fake engine: a switch on the game sheet that asks the engine to
 * show or hide them and shows what the engine says, each line's time before it, which TalkBack reads first, while they
 * show, and the game's lag on the sheet, which TalkBack spells out.
 */
@RunWith(AndroidJUnit4::class)
class TimeStampsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val stampsSwitch = hasTestTag(TIME_STAMPS_TAG)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        // As the engine does as the profile loads: EngineClient outlives a test, so an earlier one's would be there
        session.status("profile", "${GAME.name} loaded")
        session.status("timeStamps", "false")
        session.status("lag", "")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && !EngineClient.front.timeStampsShown } }
    }

    @Test
    fun theSheetsSwitchAsksTheEngine_andEachLineShowsItsTimeWhileTheEngineSaysTheyShow() {
        session.line(1, "A rat scurries past.", time = CAME)
        val plainLine = hasText("A rat scurries past.")
        waitFor(plainLine)

        activate(hasTestTag(GAME_TITLE_TAG))
        waitFor(stampsSwitch)
        compose.onNode(stampsSwitch).performScrollTo().assertIsDisplayed().assert(role(Role.Switch)).assert(toggled(ToggleableState.Off))
            .assert(hasText(string(R.string.game_time_stamps)))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(stampsSwitch)
        assertEquals(true, session.receive("setTimeStamps").getBoolean("shown"))
        // Off until the engine has shown them: a refused change must not show them
        SystemClock.sleep(QUIET_MS)
        compose.onNode(stampsSwitch).assert(toggled(ToggleableState.Off))

        session.status("timeStamps", "true")
        waitFor(stampsSwitch and toggled(ToggleableState.On))
        compose.onRoot().tryPerformAccessibilityChecks()
        val stamped = hasText(timeStamp(CAME) + "A rat scurries past.")
        waitFor(stamped)
        // The time is part of the line's one node, not a node of its own
        assertEquals(0, compose.onAllNodes(hasText(timeStamp(CAME))).fetchSemanticsNodes().size)

        activate(stampsSwitch)
        assertEquals(false, session.receive("setTimeStamps").getBoolean("shown"))
        // A script's disableTimeStamps() is told the same way
        session.status("timeStamps", "false")
        waitFor(stampsSwitch and toggled(ToggleableState.Off))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(stamped).fetchSemanticsNodes().isEmpty() }
        waitFor(plainLine)
    }

    @Test
    fun theSheetShowsTheLagAsDesktopsLatencyBox_andTalkBackSpellsItOut() {
        activate(hasTestTag(GAME_TITLE_TAG))
        waitFor(stampsSwitch)
        assertEquals("no lag before the game's first text", 0, compose.onAllNodes(hasTestTag(LAG_TAG)).fetchSemanticsNodes().size)

        session.status("lag", "0.250 0.004")
        waitFor(hasTestTag(LAG_TAG) and hasContentDescription(string(R.string.game_lag_description, "0.250", "0.004")))
        compose.onNode(hasText(string(R.string.game_lag, "0.250", "0.004")), useUnmergedTree = true).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        session.status("lag", "- 0.004")
        waitFor(hasTestTag(LAG_TAG) and hasContentDescription(string(R.string.game_lag_no_ga_description, "0.004")))
        compose.onNode(hasText(string(R.string.game_lag_no_ga, "0.004")), useUnmergedTree = true).assertIsDisplayed()

        session.status("lag", "")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(LAG_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    private fun toggled(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Stamped", "127.0.0.1", 4000, false, "", false)
        const val CAME = 1_700_000_000_123L
        const val WAIT_MS = 10_000L
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
