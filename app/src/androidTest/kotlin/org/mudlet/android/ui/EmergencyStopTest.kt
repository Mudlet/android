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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.EMERGENCY_STOP_TAG
import org.mudlet.android.ui.game.GAME_PILL_TAG

/**
 * Desktop Mudlet's emergency stop against a fake engine: a switch on the game sheet that asks the engine to stop or
 * start the game's scripts and shows what the engine says, and the stop on the game pill, which TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyStopTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val stopSwitch = hasTestTag(EMERGENCY_STOP_TAG)

    private val pill = hasTestTag(GAME_PILL_TAG)

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
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        // As the engine does as the profile loads: EngineClient outlives a test, so an earlier one's would be there
        session.status("profile", "${GAME.name} loaded")
        stopped(false)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && !EngineClient.front.scriptsStopped } }
    }

    @Test
    fun theSheetsSwitchAsksTheEngine_andShowsWhatTheEngineSays() {
        activate(pill)
        waitFor(stopSwitch)
        compose.onNode(stopSwitch).assertIsDisplayed().assert(role(Role.Switch)).assert(toggled(ToggleableState.Off))
            .assert(hasText(string(R.string.game_emergency_stop)))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(stopSwitch)
        assertEquals(true, session.receive("setScriptsStopped").getBoolean("stopped"))
        // Off until the engine has stopped them: a refused stop must not show the scripts as stopped
        SystemClock.sleep(QUIET_MS)
        compose.onNode(stopSwitch).assert(toggled(ToggleableState.Off))

        stopped(true)
        waitFor(stopSwitch and toggled(ToggleableState.On))
        compose.onNode(stopSwitch).assert(hasText(string(R.string.game_emergency_stop_on)))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(stopSwitch)
        assertEquals(false, session.receive("setScriptsStopped").getBoolean("stopped"))
        stopped(false)
        waitFor(stopSwitch and toggled(ToggleableState.Off))
    }

    @Test
    fun thePillShowsTheStop_andTalkBackReadsIt_whileTheScriptsAreStopped() {
        val readsTheStop = pill and hasContentDescription(string(R.string.game_scripts_stopped), substring = true)
        waitFor(pill)
        assertEquals(0, compose.onAllNodes(readsTheStop).fetchSemanticsNodes().size)

        // Stopped by another UI attached to the engine, or a reattach that finds it stopped
        stopped(true)
        waitFor(readsTheStop)
        compose.onNode(readsTheStop).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        stopped(false)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(readsTheStop).fetchSemanticsNodes().isEmpty() }
    }

    private fun stopped(stopped: Boolean) = session.send(JSONObject().put("t", "scriptsStopped").put("stopped", stopped))

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
        val GAME = Profile("Emergency", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
