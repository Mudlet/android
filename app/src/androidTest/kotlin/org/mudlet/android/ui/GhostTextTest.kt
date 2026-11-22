package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The input line's ghost text against a fake engine: the rest of the best suggestion greyed after what is typed, no
 * row of suggestions above the line, a tap on the ghost, the right arrow or TalkBack's action to take it, and no ghost
 * while the game hides input. Each test has a profile of its own, so it starts with nothing learned.
 */
@RunWith(AndroidJUnit4::class)
class GhostTextTest {
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
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertIsDisplayed()
        session.status("profile", "$profile loaded")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { CommandSuggestions.profile } == profile }
        compose.waitForIdle()
    }

    @After
    fun forgetTheProfile() {
        saved.delete()
    }

    @Test
    fun theGhostShowsAfterWhatIsTypedWithNoRowOfSuggestionsAboveTheLine() {
        send("open door")
        send("north")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        // The ghost shows after the cursor but is not in the line: TalkBack reads what was typed, then the suggestion
        compose.onNode(input).assert(editableText("op"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.input_suggestion, "open door")))
        compose.onRoot().tryPerformAccessibilityChecks()

        // No button offers a suggestion: the keyboard has its own row of them
        for (command in listOf("open door", "north")) {
            assertNone(hasText(command) and hasClickAction())
            assertNone(hasContentDescription(string(R.string.input_suggestion, command)))
        }
        assertNone(hasContentDescription(string(R.string.input_accept_suggestion, "open door")))
    }

    @Test
    fun aTapOnTheGhostTakesItAndATapOnTheTypedTextDoesNot() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        compose.onNode(input).performTouchInput { click(Offset(textX(ghostMiddle()), center.y)) }
        compose.onNode(input).assertIsFocused().assert(editableText("open door"))
        assertEquals("nothing sent but the command", 1, sends())

        compose.onNode(input).performTextClearance()
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        compose.onNode(input).performTouchInput { click(Offset(textX(typedMiddle()), center.y)) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(ghost("open door")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(input).assert(editableText("op"))
    }

    @Test
    fun aTapThatFocusesTheLineOrIsOnItsCompleteButtonLeavesTheGhost() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        compose.onNode(button(R.string.input_complete)).performTouchInput { click() }
        assertEquals("op", session.receive("complete").getString("text"))
        compose.onNode(input).assert(editableText("op"))

        // Bite: the tap aimed while the keyboard is still up, and made once it has gone
        compose.waitUntil(WAIT_MS) { keyboardShown() }
        val up = compose.onNode(input).fetchSemanticsNode().boundsInWindow
        val aimed = Offset(up.left + textX(ghostMiddle()), up.center.y)

        // A view of the test's own: in touch mode no button of the app's takes the focus, and the line keeps it
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            // Labelled, as the screen's accessibility checks ask of anything focusable
            val view = View(compose.activity).apply {
                isFocusableInTouchMode = true
                contentDescription = "elsewhere"
            }
            content.addView(view, ViewGroup.LayoutParams(1, 1))
            view.requestFocus()
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isEmpty() }
        waitForTheKeyboardToGo()
        waitFor(ghost("open door"))
        val down = compose.onNode(input).fetchSemanticsNode().boundsInWindow
        assertTrue("the line moved as the keyboard went: from $up to $down", up != down)
        compose.onNode(input).performTouchInput { click(aimed - down.topLeft) }
        compose.onNode(input).assertIsFocused().assert(editableText("op"))
    }

    @Test
    fun talkBacksActionOnTheLineTakesTheGhost() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        acceptGhost("open door")
        compose.onNode(input).assertIsFocused().assert(editableText("open door"))
        assertEquals("nothing sent but the command", 1, sends())
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes().isEmpty() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theRightArrowAtTheEndOfTheLineTakesTheGhost() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        compose.onNode(input).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(input).assert(editableText("open door"))
    }

    @Test
    fun wordsTheGameShowedCompleteTheWordBeingTyped() {
        session.line(1, "You see a lantern.")
        compose.onNode(input).performTextInput("get lan")
        waitFor(ghost("get lantern"))
        acceptGhost("get lantern")
        compose.onNode(input).assert(editableText("get lantern"))
    }

    @Test
    fun theLineIsReadBeforeItsButtons() {
        send("open door")
        compose.onNode(input).performTextInput("op")
        waitFor(ghost("open door"))
        compose.onRoot().tryPerformAccessibilityChecks()

        val reading = TalkBackReading(compose)
        reading.assertInOrder(
            listOf(
                "the input line" to input,
                "its Complete button" to button(R.string.input_complete),
                "its Previous button" to button(R.string.input_history_previous),
                "its Next button" to button(R.string.input_history_next),
                "its Send button" to button(R.string.input_send),
            ),
        )
        reading.assertEveryControlRead()
    }

    @Test
    fun whileTheGameHidesInputThereIsNoGhostAndThePasswordIsKeptNowhere() {
        send("north")
        compose.onNode(input).performTextInput("n")
        waitFor(ghost("north"))

        session.hiddenInput(true)
        waitFor(password)
        // The line's action and state for any suggestion, not just any action or state the masked field has of its own
        val acceptPrefix = string(R.string.input_accept_suggestion, "")
        val suggestionPrefix = string(R.string.input_suggestion, "")
        val offersAGhost = SemanticsMatcher("offers a ghost") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label.startsWith(acceptPrefix) } ||
                node.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith(suggestionPrefix) == true
        }
        assertNone(password and offersAGhost)
        // The start of a command the line would offer: nothing typed here reaches the suggestions
        compose.onNode(password).performTextInput("no")
        // Long enough for an answer to the typing, were there one
        Thread.sleep(SuggestionSession.TYPING_DELAY_MS * 4)
        compose.waitForIdle()
        assertNone(password and offersAGhost)
        compose.onNode(password).performTextClearance()
        compose.onNode(password).performTextInput("hunter2")
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

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    // By its label alone: the Next button is disabled here, with nothing newer to go forward to
    private fun button(@StringRes id: Int) = hasContentDescription(string(id))

    /** The input line while it offers TalkBack the action that takes the ghost, which gives [line]. */
    private fun ghost(line: String) = input and SemanticsMatcher("offers to accept '$line'") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == string(R.string.input_accept_suggestion, line) }
    }

    /** What TalkBack's action does: the line's custom action, after the checks on the whole screen. */
    private fun acceptGhost(line: String) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val actions = compose.onNode(input).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == string(R.string.input_accept_suggestion, line) }.action()) }
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(input).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    // The middle of the ghost, and of the typed "op", in the text's own coordinates: the field lays out "op" + ghost
    private fun ghostMiddle(): Float = layout().let { (it.getHorizontalPosition(2, true) + it.getLineRight(0)) / 2 }

    private fun typedMiddle(): Float = layout().getBoundingBox(0).center.x

    // The text starts after the field's content padding, Material's 16 dp
    private fun textX(x: Float) = with(compose.density) { 16.dp.toPx() } + x

    private fun sends() = session.received.count { it.optString("t") == "send" }

    /**
     * Until the keyboard has gone and the line has stopped moving down after it: a line that loses the focus hides the
     * keyboard a frame or more later, and a tap aimed at the line before then lands where it was, or lifts off outside
     * it and is no tap.
     */
    private fun waitForTheKeyboardToGo() = compose.waitUntil(WAIT_MS) {
        val before = compose.onNode(input).fetchSemanticsNode().boundsInWindow
        nextFrame()
        !keyboardShown() && compose.onNode(input).fetchSemanticsNode().boundsInWindow == before
    }

    private fun keyboardShown(): Boolean = compose.runOnUiThread<Boolean> {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    // The window's next frame: the keyboard's insets reach Compose a frame or more after the window has them
    private fun nextFrame() {
        val drawn = CountDownLatch(1)
        compose.runOnUiThread { Choreographer.getInstance().postFrameCallback { drawn.countDown() } }
        assertTrue("a frame within $WAIT_MS ms", drawn.await(WAIT_MS, TimeUnit.MILLISECONDS))
    }

    private fun send(command: String) {
        compose.onNode(input).performTextInput(command)
        compose.onNode(input).performImeAction()
        assertEquals(command, session.receive("send").getString("text"))
    }

    // As plain text: the field's EditableText can carry spans, so comparing AnnotatedStrings fails on equal text
    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun assertNone(matcher: SemanticsMatcher) {
        val found = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue("no ${matcher.description}: found ${found.map { it.config }}", found.isEmpty())
    }

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
