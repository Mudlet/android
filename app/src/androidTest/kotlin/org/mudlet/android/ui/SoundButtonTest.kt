package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.SOUND_BUTTON_TAG

/**
 * The game screen's Sound button against a fake engine: absent until the engine says the game has played a sound,
 * then desktop Mudlet's "Mute all media", sent to the engine as its two mutes, still there after leaving the game and
 * coming back; and the game's Sound settings page, which holds the two mutes on their own.
 */
@RunWith(AndroidJUnit4::class)
class SoundButtonTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val button get() = hasTestTag(SOUND_BUTTON_TAG) and hasContentDescription(string(R.string.sound_mute_all))

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
        sounds(played = false)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && !EngineClient.front.sounds.played } }
    }

    @Test
    fun notThereUntilTheGamePlaysASound_thenComesWithoutTakingFocusOrSpeaking() {
        assertNoButton()
        sounds(played = false)
        SystemClock.sleep(QUIET_MS)
        assertNoButton()

        val said = serial()
        sounds(played = true)
        waitFor(button)
        compose.onNode(button).assertIsDisplayed().assertIsNotFocused().assert(toggled(ToggleableState.Off))
        compose.onRoot().tryPerformAccessibilityChecks()
        SystemClock.sleep(QUIET_MS)
        assertEquals("its coming says nothing", said, serial())
    }

    @Test
    fun aTapMutesAllMediaInTheEngine_andAnotherUnmutesIt_asDesktopsButtonDoes() {
        sounds(played = true)
        activate(button)
        assertEquals(mapOf(MUTE_SCRIPTS to true, MUTE_GAME to true), values(session.receive("setSettings")))
        compose.onNode(button).assert(toggled(ToggleableState.On))
        sounds(played = true, scriptsMuted = true, gameMuted = true)

        activate(button)
        assertEquals(mapOf(MUTE_SCRIPTS to false, MUTE_GAME to false), values(session.receive("setSettings")))
        compose.onNode(button).assert(toggled(ToggleableState.Off))

        // One muted, as from the settings or a script's setConfig: not all, so a tap mutes the other too
        sounds(played = true, scriptsMuted = true)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.sounds.scriptsMuted } }
        compose.onNode(button).assert(toggled(ToggleableState.Off))
        activate(button)
        assertEquals(mapOf(MUTE_SCRIPTS to true, MUTE_GAME to true), values(session.receive("setSettings")))
    }

    @Test
    fun theMuteIsThereAfterLeavingTheGameAndComingBack() {
        sounds(played = true)
        activate(button)
        session.receive("setSettings")
        sounds(played = true, scriptsMuted = true, gameMuted = true)

        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.profiles_title))).fetchSemanticsNodes().isNotEmpty() }
        activate(showsAGame)
        waitFor(button)
        compose.onNode(button).assertIsDisplayed().assert(toggled(ToggleableState.On))

        // A muted game shows the button from its next load, before any sound, so the mute can be undone
        session.status("profile", "${GAME.name} loaded")
        sounds(played = false, scriptsMuted = true, gameMuted = true)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !EngineClient.front.sounds.played } }
        compose.onNode(button).assertIsDisplayed().assert(toggled(ToggleableState.On))
    }

    @Test
    fun theGamesSoundPageHasDesktopsTwoMutes() {
        sounds(played = true, gameMuted = true)
        activate(hasContentDescription(string(R.string.settings_title)))
        val page = hasText(string(R.string.settings_sound)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(mapOf(MUTE_SCRIPTS to false, MUTE_GAME to true), session.receive("getSettings").getLong("id"))
        val scripts = hasText(string(R.string.settings_mute_scripts)) and hasClickAction()
        val game = hasText(string(R.string.settings_mute_game)) and hasClickAction()
        waitFor(game)
        compose.onNode(game).performScrollTo().assert(toggled(ToggleableState.On))
        compose.onNode(scripts).performScrollTo().assert(toggled(ToggleableState.Off))

        activate(scripts)
        assertEquals(mapOf(MUTE_SCRIPTS to true), values(session.receive("setSettings")))
        compose.onNode(scripts).assert(toggled(ToggleableState.On))
    }

    private fun sounds(played: Boolean, scriptsMuted: Boolean = false, gameMuted: Boolean = false) = session.send(JSONObject().put("t", "sounds").put("played", played).put("scriptsMuted", scriptsMuted).put("gameMuted", gameMuted))

    private fun assertNoButton() = assertTrue("no Sound button", compose.onAllNodes(hasTestTag(SOUND_BUTTON_TAG)).fetchSemanticsNodes().isEmpty())

    private fun toggled(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    // The profile list's row for the open game, which brings it to the front
    private val showsAGame get() = SemanticsMatcher("shows a game") { it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.game_show) }

    private fun values(request: JSONObject): Map<String, Any> = request.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.get(it) } }

    private fun serial() = compose.runOnIdle { Announcer.current?.serial ?: 0L }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Sounds", "127.0.0.1", 4000, false, "", false)
        const val MUTE_SCRIPTS = "muteMediaAPI"
        const val MUTE_GAME = "muteMediaGame"
        const val WAIT_MS = 10_000L
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
