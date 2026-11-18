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
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.ProfileArchive
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_PILL_TAG
import org.mudlet.android.ui.game.RECORD_REPLAY_TAG
import org.mudlet.android.ui.game.ReplayShare
import org.mudlet.android.ui.game.SHARE_REPLAY_TAG
import java.io.File

/**
 * Desktop Mudlet's replay recording against a fake engine: a switch on the game sheet that asks the engine to record or
 * stop and shows what the engine says, and, once a recording is saved, the button that shares its file, copied where
 * the app's FileProvider serves it.
 */
@RunWith(AndroidJUnit4::class)
class ReplayRecordingTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val recordSwitch = hasTestTag(RECORD_REPLAY_TAG)

    private val share get() = hasTestTag(SHARE_REPLAY_TAG) and hasText(string(R.string.game_share_replay))

    @Before
    fun openTheGameSheet() {
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
        recording(false, "")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && EngineClient.front.replayRecording.file.isEmpty() } }
        activate(hasTestTag(GAME_PILL_TAG))
        waitFor(recordSwitch)
    }

    @Test
    fun theSheetsSwitchAsksTheEngine_andShowsWhatTheEngineSays() {
        compose.onNode(recordSwitch).performScrollTo().assertIsDisplayed().assert(role(Role.Switch)).assert(toggled(ToggleableState.Off))
            .assert(hasText(string(R.string.game_record_replay)))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertEquals(0, compose.onAllNodes(share).fetchSemanticsNodes().size)

        activate(recordSwitch)
        assertEquals(true, session.receive("setReplayRecording").getBoolean("record"))
        // Off until the engine records: a file that could not be opened must not show as recording
        SystemClock.sleep(QUIET_MS)
        compose.onNode(recordSwitch).assert(toggled(ToggleableState.Off))

        recording(true, "")
        waitFor(recordSwitch and toggled(ToggleableState.On))
        compose.onNode(recordSwitch).assert(hasText(string(R.string.game_record_replay_on)))

        activate(recordSwitch)
        assertEquals(false, session.receive("setReplayRecording").getBoolean("record"))
        recording(false, FILE)
        waitFor(recordSwitch and toggled(ToggleableState.Off))
        waitFor(share)
        compose.onNode(share).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        // Recording again: nothing to share until it is saved
        recording(true, FILE)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(share).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theReplayIsCopiedWhereTheFileProviderServesIt_inPlaceOfTheLastCopy() {
        val context = compose.activity
        val log = File(ProfileArchive.folder(context.filesDir, GAME.name), "log").apply { mkdirs() }
        val bytes = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte())
        File(log, FILE).writeBytes(bytes)
        File(log, OTHER).writeBytes(byteArrayOf(9))
        try {
            ReplayShare.copy(context, GAME.name, OTHER)
            val copy = ReplayShare.copy(context, GAME.name, FILE) ?: throw AssertionError("the replay was not copied")
            assertArrayEquals(bytes, copy.readBytes())
            assertEquals("only the last copy is kept", listOf(FILE), copy.parentFile!!.list()!!.toList())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.replays", copy)
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            assertEquals("a missing file is not shared", null, ReplayShare.copy(context, GAME.name, "missing.dat"))
            assertTrue(File(log, FILE).exists())
        } finally {
            log.deleteRecursively()
        }
    }

    private fun recording(recording: Boolean, file: String) = session.send(JSONObject().put("t", "replayRecording").put("recording", recording).put("file", file))

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
        val GAME = Profile("Replays", "127.0.0.1", 4000, false, "", false)
        const val FILE = "2026-11-18#04-05-06.dat"
        const val OTHER = "2026-11-18#04-00-00.dat"
        const val WAIT_MS = 10_000L
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
