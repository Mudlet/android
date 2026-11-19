package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.KeyBinding
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * Desktop Mudlet's caret mode against a fake engine: the game's Accessibility key ("Switch between input line and main
 * window using") moves a hardware keyboard's focus from the input line to the game's last line, which TalkBack reads,
 * the arrow keys move it line by line, and the key again, or a key that types, goes back to the input line.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CaretKeyTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private var nextSeq = 1L

    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private val input = hasSetTextAction() and !password

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertIsDisplayed()
    }

    // EngineClient lives as long as the process, and with it a game's settings
    @After
    fun forgetTheGamesSettings() = compose.runOnIdle { EngineClient.sessions.forEach { it.settings.clear() } }

    @Test
    fun theCaretKeyMovesBetweenTheInputLineAndTheGamesLines() {
        // The game's settings are asked for once its profile has loaded
        session.status("profile", "${GAME.name} loaded")
        session.settings(mapOf(Settings.caretKey.key to "f6"), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.setting(Settings.caretKey) } == Settings.CaretKey.F6 }
        // The caret's own Up does not run a binding; a key that types goes to the input line, where X's binding runs
        val up = KeyBinding(0x01000013, 0)
        val x = KeyBinding('X'.code, 0)
        val keys = JSONArray(listOf(up, x).map { JSONObject().put("key", it.key).put("modifiers", it.modifiers) })
        session.send(JSONObject().put("t", "keyBindings").put("keys", keys))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.keyBindings } == setOf(up, x) }
        for (text in listOf("The first line.", "The second line.", "The third line.")) line(text)
        compose.onNode(input).requestFocus()

        compose.onNode(input).performKeyInput { pressKey(Key.F6) }
        waitForFocus("The third line.")
        compose.onRoot().tryPerformAccessibilityChecks()
        focused().performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus("The second line.")
        focused().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        waitForFocus("The first line.")
        focused().performKeyInput { pressKey(Key.DirectionDown) }
        waitForFocus("The second line.")
        session.assertNoRequest("pressKey", 500)

        focused().performKeyInput { pressKey(Key.F6) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and isFocused()).fetchSemanticsNodes().isNotEmpty() }

        // A key that types goes back to the input line, typed there
        compose.onNode(input).performKeyInput { pressKey(Key.F6) }
        waitForFocus("The third line.")
        focused().performKeyInput { pressKey(Key.N) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and isFocused() and editableText("n")).fetchSemanticsNodes().isNotEmpty() }
        session.assertNoRequest("pressKey", 500)

        compose.onNode(input).performKeyInput { pressKey(Key.F6) }
        waitForFocus("The third line.")
        focused().performKeyInput { pressKey(Key.X) }
        val bound = session.receive("pressKey")
        assertEquals(listOf(x.key, x.modifiers), listOf(bound.getInt("key"), bound.getInt("modifiers")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and isFocused() and editableText("n")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun withNoKeyChosenF6StaysOnTheInputLine() {
        line("A line.")
        compose.onNode(input).requestFocus()
        compose.onNode(input).performKeyInput { pressKey(Key.F6) }
        compose.waitForIdle()
        compose.onNode(input).assertIsFocused()
        compose.onNode(hasText("A line.") and isFocused()).assertDoesNotExist()
    }

    private fun focused() = compose.onNode(isFocused() and !input)

    private fun waitForFocus(text: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(text) and isFocused()).fetchSemanticsNodes().isNotEmpty() }

    private fun line(text: String) {
        session.line(nextSeq++, text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    // As plain text: the field's EditableText can carry spans, so comparing AnnotatedStrings fails on equal text
    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
