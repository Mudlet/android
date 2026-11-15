package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
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
import org.mudlet.android.protocol.Profile
import org.mudlet.android.suggest.SuggestFile
import org.mudlet.android.suggest.SuggestionSession
import org.mudlet.android.ui.game.CommandSuggestions
import org.mudlet.android.ui.game.SUGGESTION_STRIP_TAG
import java.io.File

/**
 * The input line's suggestions against a fake engine: the strip of suggestions above the line, the ghost text and
 * its accept button, and neither while the game hides input. Each test has a profile of its own, so it starts with
 * nothing learned. Controls are found by the labels TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionStripTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val profile = "suggestions ${System.nanoTime()}"
    private val game = Profile(profile, "127.0.0.1", 4000, false, "", false)
    private lateinit var saved: File

    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private val input = hasSetTextAction() and !password
    private val strip = hasTestTag(SUGGESTION_STRIP_TAG)

    @Before
    fun openTheGameScreenOnANewProfile() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        saved = SuggestFile.forProfile(CommandSuggestions.dir(compose.activity), profile).file
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
        session.status("profile", "$profile loaded")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { CommandSuggestions.profile } == profile }
        compose.waitForIdle()
    }

    @After
    fun forgetTheProfile() {
        saved.delete()
    }

    @Test
    fun typingShowsSuggestionsAndOneFillsTheLineWithoutSending() {
        send("open door")
        send("north")
        compose.onNode(input).performTextInput("op")
        waitFor(suggestion("open door"))
        compose.onNode(strip).assertIsDisplayed()

        activate(suggestion("open door"))
        compose.onNode(input).assertIsFocused().assert(editableText("open door"))
        // A request after the tap comes next: nothing was sent in between
        activate(hasContentDescription(string(R.string.input_complete)))
        assertEquals("open door", session.receive("complete").getString("text"))
        assertEquals(2, session.received.count { it.optString("t") == "send" })
    }

    @Test
    fun wordsTheGameShowedCompleteTheWordBeingTyped() {
        session.line(1, "You see a lantern.")
        compose.onNode(input).performTextInput("get lan")
        waitFor(suggestion("lantern"))
        activate(suggestion("lantern"))
        compose.onNode(input).assert(editableText("get lantern"))
    }

    @Test
    fun anEmptyLineAfterASendOffersTheCommandThatUsuallyFollows() {
        for (command in listOf("kill rat", "loot", "kill rat")) send(command)
        compose.onNode(input).assert(editableText(""))
        waitFor(suggestion("loot"))
        activate(suggestion("loot"))
        compose.onNode(input).assert(editableText("loot"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theGhostTextIsAcceptedByItsButtonOrTheRightArrow() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        val accept = hasContentDescription(compose.activity.getString(R.string.input_accept_suggestion, "open door"))
        waitFor(accept)
        // The ghost shows after the cursor but is not in the line: TalkBack reads only what was typed
        compose.onNode(input).assert(editableText("op"))
        activate(accept)
        compose.onNode(input).assertIsFocused().assert(editableText("open door"))

        compose.onNode(input).performTextClearance()
        compose.onNode(input).performTextInput("op")
        waitFor(accept)
        compose.onNode(input).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(input).assert(editableText("open door"))
    }

    @Test
    fun whileTheGameHidesInputThereAreNoSuggestionsAndThePasswordIsKeptNowhere() {
        send("north")
        compose.onNode(input).performTextInput("n")
        waitFor(suggestion("north"))

        session.hiddenInput(true)
        waitFor(password)
        compose.onNode(strip).assertDoesNotExist()
        compose.onNode(password).performTextInput("hunter2")
        // Long enough for an answer to the typing, were there one
        Thread.sleep(SuggestionSession.TYPING_DELAY_MS * 4)
        compose.onNode(strip).assertDoesNotExist()
        compose.onNode(password).performImeAction()
        assertEquals("hunter2", session.receive("sendHidden").getString("text"))

        session.hiddenInput(false)
        waitFor(input)
        compose.onNode(input).performTextClearance()
        send("east")
        // Saved after the masked send, so the file holds whatever it left
        compose.runOnIdle { CommandSuggestions.saveAll() }
        compose.waitUntil(WAIT_MS) { saved.isFile && "east" in saved.readText() }
        val text = saved.readText()
        assertTrue("north" in text)
        assertFalse("the password is not in the saved suggestions", "hunter" in text)
    }

    @Test
    fun theSuggestionsAreLabelledButtonsReadAfterTheLineAndBeforeItsButtons() {
        send("open door")
        compose.onNode(input).performTextInput("o")
        waitFor(suggestion("open door"))
        compose.onRoot().tryPerformAccessibilityChecks()

        val chip = compose.onNode(suggestion("open door") and hasClickAction()).fetchSemanticsNode()
        assertEquals(Role.Button, chip.config.getOrNull(SemanticsProperties.Role))
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("a ${chip.boundsInRoot.height} px tall button, at least 48 dp ($minimum px)", chip.boundsInRoot.height >= minimum)

        // Directly above the line
        val stripBounds = bounds(strip)
        val inputBounds = bounds(input)
        assertTrue("strip $stripBounds above the input line $inputBounds", stripBounds.bottom <= inputBounds.top)

        // TalkBack's order: the line, then the strip as a group, then the line's buttons
        val line = traversalIndex(input)
        val suggestions = traversalIndex(strip)
        val send = traversalIndex(hasContentDescription(string(R.string.input_send)) and hasClickAction())
        assertTrue("line $line, strip $suggestions, send $send", line < suggestions && suggestions < send)
        assertEquals(true, compose.onNode(strip).fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsTraversalGroup))
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun suggestion(text: String) = hasContentDescription(compose.activity.getString(R.string.input_suggestion, text))

    private fun send(command: String) {
        compose.onNode(input).performTextInput(command)
        compose.onNode(input).performImeAction()
        assertEquals(command, session.receive("send").getString("text"))
    }

    // As plain text: the field's EditableText can carry spans, so comparing AnnotatedStrings fails on equal text
    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun traversalIndex(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
