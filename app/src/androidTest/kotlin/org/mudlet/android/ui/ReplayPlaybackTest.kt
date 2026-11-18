package org.mudlet.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.PLAY_REPLAY_TAG
import org.mudlet.android.ui.game.REPLAY_BAR_TAG
import org.mudlet.android.ui.game.ReplayFiles
import java.io.File

/**
 * Desktop Mudlet's replay against a fake engine: the bar that shows a replay playing, read by TalkBack, whose buttons
 * ask the engine to pause, resume, go slower or faster and stop; the game sheet's Play a replay, until one plays; why a
 * play was refused; and the picked file copied where the engine reads it.
 */
@RunWith(AndroidJUnit4::class)
class ReplayPlaybackTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val bar = hasTestTag(REPLAY_BAR_TAG)

    private val play get() = hasTestTag(PLAY_REPLAY_TAG) and hasText(string(R.string.game_play_replay))

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
        replay(playing = false, paused = false, speed = 0)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && !EngineClient.front.replay.playing } }
    }

    @Test
    fun theBarShowsTheReplayThatPlays_andItsButtonsAskTheEngine() {
        assertEquals(0, compose.onAllNodes(bar).fetchSemanticsNodes().size)

        replay(playing = true, paused = false, speed = 1)
        waitFor(onTheBar(string(R.string.replay_bar_playing, 1)))
        compose.onNode(bar).assertIsDisplayed()
        compose.onNode(button(R.string.replay_slower)).assertIsNotEnabled()
        compose.onNode(button(R.string.replay_faster)).assertIsEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(button(R.string.replay_pause))
        assertEquals(true, session.receive("setReplayPaused").getBoolean("paused"))
        replay(playing = true, paused = true, speed = 1)
        waitFor(onTheBar(string(R.string.replay_bar_paused, 1)))

        activate(button(R.string.replay_resume))
        assertEquals(false, session.receive("setReplayPaused").getBoolean("paused"))
        activate(button(R.string.replay_faster))
        assertEquals(true, session.receive("changeReplaySpeed").getBoolean("faster"))

        replay(playing = true, paused = false, speed = 1024)
        waitFor(onTheBar(string(R.string.replay_bar_playing, 1024)))
        compose.onNode(button(R.string.replay_faster)).assertIsNotEnabled()
        activate(button(R.string.replay_slower))
        assertEquals(false, session.receive("changeReplaySpeed").getBoolean("faster"))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(button(R.string.replay_stop))
        session.receive("stopReplay")
        replay(playing = false, paused = false, speed = 0)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(bar).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theSheetOffersAReplay_untilOnePlays() {
        activate(hasTestTag(GAME_TITLE_TAG))
        waitFor(play)
        compose.onNode(play).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()

        // Begun by a script, or by another UI attached to the engine
        replay(playing = true, paused = false, speed = 1)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(play).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aRefusedPlaySaysWhy() {
        val reason = "A replay is playing already; stop it or wait until it has finished."
        session.send(JSONObject().put("t", "error").put("message", "playReplay: $reason"))
        waitFor(hasText(string(R.string.game_play_replay_refused, reason)))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aPickedFileIsCopiedWhereTheEngineReadsIt_inPlaceOfTheLastCopy() {
        val context = compose.activity
        val picked = File(context.filesDir, "picked-replay-test").apply { mkdirs() }
        val bytes = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte())
        try {
            ReplayFiles.copyPicked(context, Uri.fromFile(File(picked, "first.dat").apply { writeBytes(byteArrayOf(9)) }))
            val copy = ReplayFiles.copyPicked(context, Uri.fromFile(File(picked, "second.dat").apply { writeBytes(bytes) }))
                ?: throw AssertionError("the replay was not copied")
            assertArrayEquals(bytes, copy.readBytes())
            assertEquals("only the last copy is kept", listOf(copy.name), copy.parentFile!!.list()!!.toList())
            assertNull("a missing file is not played", ReplayFiles.copyPicked(context, Uri.fromFile(File(picked, "missing.dat"))))
        } finally {
            picked.deleteRecursively()
        }
    }

    private fun replay(playing: Boolean, paused: Boolean, speed: Int) = session.send(JSONObject().put("t", "replay").put("playing", playing).put("paused", paused).put("speed", speed))

    private fun onTheBar(text: String) = hasText(text) and hasAnyAncestor(bar)

    private fun button(@StringRes label: Int) = hasContentDescription(string(label)) and hasClickAction()

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Replay player", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
