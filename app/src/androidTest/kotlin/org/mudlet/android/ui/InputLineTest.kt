package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * The input line's engine features against a fake engine: the masked field while the game hides input, the engine's
 * history, a line a script sets, completion, and why the engine could not send. Controls are found by the labels TalkBack reads and activated through
 * their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class InputLineTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private val input = hasSetTextAction() and !password

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
    }

    @Test
    fun theMaskedFieldTakesTheLinesPlaceWhileTheGameHidesInput() {
        compose.onNode(input).performTextInput("half a comm")
        session.hiddenInput(true)
        waitFor(password)
        // A password field: TalkBack says "password", not what is typed
        compose.onNode(password).assertIsFocused().assert(hasText(string(R.string.input_password_label)))
        compose.onRoot().tryPerformAccessibilityChecks()
        // TalkBack's order: the field, then its buttons
        val field = bounds(password)
        val close = bounds(hasContentDescription(string(R.string.input_password_close)))
        val send = bounds(hasContentDescription(string(R.string.input_send)))
        assertTrue("field $field, close $close, send $send left to right", field.right <= close.left && close.right <= send.left)

        compose.onNode(password).performTextInput("hunter2")
        compose.onNode(password).performImeAction()
        assertEquals("hunter2", session.receive("sendHidden").getString("text"))
        assertFalse("the password never goes out as a command", session.received.any { it.optString("t") == "send" })
        compose.onNode(password).assertIsFocused().assert(editableText(""))

        activate(hasContentDescription(string(R.string.input_password_close)))
        session.receive("dismissHidden")
        session.hiddenInput(false)
        waitFor(input)
        compose.onNode(input).assertIsFocused().assert(editableText("half a comm"))
    }

    @Test
    fun theHistoryIsTheEnginesWithTheCommandsSentSince() {
        session.history(listOf("north", "look"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.history } == listOf("north", "look") }
        compose.onNode(input).performTextInput("draft")
        previous()
        compose.onNode(input).assert(editableText("north"))
        previous()
        compose.onNode(input).assert(editableText("look"))
        next()
        next()
        compose.onNode(input).assert(editableText("draft"))

        compose.onNode(input).performImeAction()
        assertEquals("draft", session.receive("send").getString("text"))
        previous()
        compose.onNode(input).assert(editableText("draft"))
        previous()
        compose.onNode(input).assert(editableText("north"))
    }

    @Test
    fun aScriptSetsTheLine() {
        compose.onNode(input).performTextInput("typed")
        session.commandLine("say hello")
        compose.waitUntil(WAIT_MS) { hasEditableText("say hello") }

        // The engine empties its line after a send, which can reach the UI after the player typed on
        compose.onNode(input).performImeAction()
        session.receive("send")
        compose.onNode(input).performTextInput("n")
        session.commandLine("")
        session.history(listOf("marker"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.history } == listOf("marker") }
        compose.onNode(input).assert(editableText("n"))

        // A script's own clearCmdLine() still empties it
        session.commandLine("")
        compose.waitUntil(WAIT_MS) { hasEditableText("") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theCompletionButtonAndTabCompleteTheLine() {
        compose.onNode(input).performTextInput("lo")
        activate(hasContentDescription(string(R.string.input_complete)))
        val asked = session.receive("complete")
        assertEquals("lo", asked.getString("text"))
        assertEquals(false, asked.getBoolean("fromHistory"))
        session.reply("completion", "look", asked.get("id"))
        compose.waitUntil(WAIT_MS) { hasEditableText("look") }

        compose.onNode(input).performKeyInput { pressKey(Key.Tab) }
        val again = session.receive("complete")
        assertEquals("look", again.getString("text"))
        session.reply("completion", "", again.get("id"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(string(R.string.input_no_completion)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(input).assertIsFocused().assert(editableText("look"))

        // Typing on clears the notice
        compose.onNode(input).performTextInput("s")
        compose.onNode(input).assert(editableText("looks"))
        assertTrue("no notice once the player types", compose.onAllNodesWithText(string(R.string.input_no_completion)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun whyTheEngineCouldNotSendIsShownAndReadOut() {
        session.synced(0)
        compose.onNode(input).performTextInput("north")
        compose.onNode(input).performImeAction()
        session.receive("send")
        session.status("sendError", "not connected")
        val shown = compose.activity.getString(R.string.send_error, "not connected")
        // Inside a live region, so TalkBack reads it without moving focus
        val readOut = hasText(shown) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(readOut, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()

        // A new engine connection resends the engine's status, the old sendError with it: that is not news
        session.close()
        session = fake.engine.nextSession()
        session.status("sendError", "an old reason")
        session.synced(0)
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.front.status["connection"] == "connected" }
        val old = compose.activity.getString(R.string.send_error, "an old reason")
        assertTrue("a resent sendError is not shown", compose.onAllNodesWithText(old, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun previous() = activate(hasContentDescription(string(R.string.input_history_previous)))

    private fun next() = activate(hasContentDescription(string(R.string.input_history_next)))

    // As plain text: the field's EditableText can carry spans, so comparing AnnotatedStrings fails on equal text
    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun hasEditableText(text: String) = compose.onAllNodes(input and editableText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
