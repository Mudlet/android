package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.protocol.Profile
import java.util.concurrent.TimeUnit

/**
 * What the player typed and what the game sent stay out of this app's logcat, which About > Report a problem shows
 * and the player may send: a command, the game's lines, the engine's refusals of a link or a text box's typing (whose
 * reasons quote the link, the label or the box) and a tell that could not be posted as notifications are off.
 */
@RunWith(AndroidJUnit4::class)
class LogKeepsOutPlayerTextTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    // Notifications are not granted, so the tell is not posted: android_instrumented.py installs without -g
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(compose)

    private lateinit var session: FakeEngine.Session
    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @Before
    fun listTheProfile() {
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @Test
    fun noLogLineHoldsTheCommandTheGamesTextOrATell() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(
            "a tell is posted, not logged, while notifications are allowed",
            Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 0,
        )
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.line(1, "$GAME_TEXT says hello")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(GAME_TEXT, substring = true)).fetchSemanticsNodes().isNotEmpty() }

        val input = hasSetTextAction() and !password
        compose.onNode(input).performTextInput(TYPED)
        compose.onNode(input).performImeAction()
        assertEquals(TYPED, session.receive("send").getString("text"))

        for (request in listOf("getLink", "followLink", "followLabelLink", "setTextBoxText")) {
            session.send(JSONObject().put("t", "error").put("message", "$request: The label \"$LINK\" has no link to \"$LINK\"."))
        }
        if (Build.VERSION.SDK_INT >= 33) EngineNotifications.postTell(app, GAME.name, TELL, TELL)
        // On the same socket after the refusals, so they have been handled once it shows
        session.line(2, DONE)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(DONE)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Log.w(TAG, SENTINEL)

        val log = readLog()
        assertTrue("the log was not read", log.any { it.contains(SENTINEL) })
        for ((text, what) in listOf(TYPED to "the command", GAME_TEXT to "the game's text", LINK to "a link or label", TELL to "a tell")) {
            val found = log.filter { it.contains(text) }
            assertTrue("$what is in the log:\n${found.joinToString("\n")}", found.isEmpty())
        }
    }

    // As the report reads it: this app's own lines, whose uid the instrumentation shares
    private fun readLog(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(10, TimeUnit.SECONDS)
        return lines
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click */
    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val TAG = "LogKeepsOutPlayerText"
        const val TYPED = "say typed-Lk7-command"
        const val GAME_TEXT = "game-Lk7-text"
        const val LINK = "link-Lk7-href"
        const val TELL = "tell-Lk7-from-Bob"
        const val DONE = "lk7 refusals handled"
        const val SENTINEL = "log-Lk7-sentinel"
        val GAME = Profile("Log Game", "l.example", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
