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
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextRange
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
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.protocol.Profile

/**
 * The input line's engine features against a fake engine: the masked field while the game hides input, the engine's
 * history, a command it brings back selected whole or not as the game's "Highlight history" says, a line a script sets,
 * completion, why the engine could not send, the command sent kept on the line in a game that keeps it, and a hardware
 * key the profile's key bindings take going to the engine. Controls are found by the labels TalkBack reads and
 * activated through their accessibility actions.
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

    // EngineClient lives as long as the process, and with it a game's settings
    @After
    fun forgetTheGamesSettings() = compose.runOnIdle { EngineClient.sessions.forEach { it.settings.clear() } }

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
    fun aCommandBroughtBackFromTheHistoryIsSelectedWholeUnlessTheGameSaysNot() {
        session.history(listOf("north", "look"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.history } == listOf("north", "look") }
        // Mudlet's default, which the app's is too until the game gives its own
        previous()
        // As for a kept command, TalkBack is told of a cursor at the end, and of the line's state
        compose.onNode(input).assert(editableText("north")).assert(recalledState)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(5)))
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(input).performTextInput("s")
        compose.onNode(input).assert(editableText("s")).assert(!recalledState)

        session.status("profile", "Unhighlighted history loaded")
        session.settings(mapOf(Settings.highlightHistory.key to false), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !EngineClient.front.setting(Settings.highlightHistory) } }
        previous()
        compose.onNode(input).assert(editableText("look")).assert(!recalledState)
        compose.onNode(input).performTextInput("s")
        compose.onNode(input).assert(editableText("looks"))
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

    @Test
    fun aGameThatKeepsTheCommandSentLeavesItOnTheLineSelected() {
        session.history(listOf("north"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.history } == listOf("north") }
        // Until the game has a value of its own the app's is used, which empties the line
        compose.onNode(input).performTextInput("first")
        compose.onNode(input).performImeAction()
        assertEquals("first", session.receive("send").getString("text"))
        compose.onNode(input).assert(editableText("")).assert(!keptState)

        gameKeepsTheCommandSent()
        compose.onNode(input).performTextInput("look")
        compose.onNode(input).performImeAction()
        assertEquals("look", session.receive("send").getString("text"))
        // TalkBack reads a selection as it is made, which would be the command again after every send: it is told
        // of a cursor at the end, and of the line's state
        compose.onNode(input).assertIsFocused().assert(editableText("look")).assert(keptState)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
        compose.onRoot().tryPerformAccessibilityChecks()

        // Send sends it again, and it stays
        activate(hasContentDescription(string(R.string.input_send)))
        assertEquals("look", session.receive("send").getString("text"))
        compose.onNode(input).assert(editableText("look")).assert(keptState)

        // It is selected whole: what is typed takes its place
        compose.onNode(input).performTextInput("n")
        compose.onNode(input).assert(editableText("n")).assert(!keptState)
        compose.onNode(input).performImeAction()
        assertEquals("n", session.receive("send").getString("text"))
        compose.onNode(input).assert(editableText("n")).assert(keptState)

        // The line is the history's newest, so the way back from it starts at the command before
        previous()
        compose.onNode(input).assert(editableText("look")).assert(!keptState)
        previous()
        compose.onNode(input).assert(editableText("first"))
        next()
        next()
        compose.onNode(input).assert(editableText("n"))
        next()
        compose.onNode(input).assert(editableText(""))

        // An empty line sent has nothing to keep, and a script's line takes a kept one's place
        compose.onNode(input).performImeAction()
        assertEquals("", session.receive("send").getString("text"))
        compose.onNode(input).assert(editableText("")).assert(!keptState)
        compose.onNode(input).performTextInput("east")
        compose.onNode(input).performImeAction()
        session.receive("send")
        session.commandLine("say hello")
        compose.waitUntil(WAIT_MS) { hasEditableText("say hello") }
        compose.onNode(input).assert(!keptState)
        compose.onNode(input).performTextInput("!")
        compose.onNode(input).assert(editableText("say hello!"))
    }

    @Test
    fun theMaskedFieldStillEmptiesInAGameThatKeepsTheCommandSent() {
        gameKeepsTheCommandSent()
        compose.onNode(input).performTextInput("connect bob")
        compose.onNode(input).performImeAction()
        session.receive("send")
        session.hiddenInput(true)
        waitFor(password)
        compose.onNode(password).performTextInput("hunter2")
        compose.onNode(password).performImeAction()
        assertEquals("hunter2", session.receive("sendHidden").getString("text"))
        compose.onNode(password).assert(editableText(""))

        session.hiddenInput(false)
        waitFor(input)
        compose.onNode(input).assert(editableText("connect bob")).assert(keptState)
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aKeyAKeyBindingTakesGoesToTheEngineAndTheRestToTheLine() {
        // As tempKey(mudlet.keymodifier.Keypad, mudlet.key["8"], [[send("north")]]) and tempKey(mudlet.key.F1, ...) give them
        val keypad8 = KeyBinding('8'.code, KeyModifier.KEYPAD.bit)
        val f1 = KeyBinding(0x01000030, 0)
        val keys = JSONArray(listOf(keypad8, f1).map { JSONObject().put("key", it.key).put("modifiers", it.modifiers) })
        session.send(JSONObject().put("t", "keyBindings").put("keys", keys))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.keyBindings } == setOf(keypad8, f1) }
        compose.onNode(input).requestFocus()

        compose.onNode(input).performKeyInput {
            if (!isNumLockOn) pressKey(Key.NumLock)
            pressKey(Key.NumPad8)
        }
        val north = session.receive("pressKey")
        assertEquals(listOf('8'.code, KeyModifier.KEYPAD.bit, "8"), listOf(north.getInt("key"), north.getInt("modifiers"), north.getString("text")))
        compose.onNode(input).performKeyInput { pressKey(Key.F1) }
        val help = session.receive("pressKey")
        assertEquals(listOf(0x01000030, 0, ""), listOf(help.getInt("key"), help.getInt("modifiers"), help.getString("text")))
        compose.onNode(input).assertIsFocused().assert(editableText(""))

        // A key no binding takes types as before
        compose.onNode(input).performKeyInput { pressKey(Key.A) }
        compose.waitUntil(WAIT_MS) { hasEditableText("a") }
        session.assertNoRequest("pressKey", 500)
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

    // What TalkBack reads of a line whose sent command is still on it
    private val keptState get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.input_kept))

    private val recalledState get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.input_recalled))

    // The game's profile loads with desktop Mudlet's "Auto clear the input line after you sent text" off. The name is
    // its own, so the commands sent here are suggested to no other test
    private fun gameKeepsTheCommandSent() {
        session.status("profile", "Kept commands loaded")
        session.settings(mapOf(Settings.clearInputAfterSend.key to false), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !EngineClient.front.setting(Settings.clearInputAfterSend) } }
    }

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
