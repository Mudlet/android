package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
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
import org.mudlet.android.suggest.PinnedCommands
import org.mudlet.android.suggest.SuggestFile
import org.mudlet.android.ui.game.CommandSuggestions
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.HISTORY_SHEET_TAG
import org.mudlet.android.ui.game.QUICK_BAR_TAG
import java.io.File

/**
 * The command history and the pinned commands against a fake engine: the sheet the input line's History button and
 * Ctrl+R open, its search, its rows' actions, the quick bar's pinned buttons, and none of them while the game hides
 * input. Each test has a profile of its own, so it starts with nothing sent or pinned. Controls are found by the labels
 * TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class HistorySheetTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val profile = "history ${System.nanoTime()}"
    private val game = Profile(profile, "127.0.0.1", 4000, false, "", false)
    private lateinit var savedSuggestions: File
    private lateinit var savedPins: File

    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private val sheet = hasTestTag(HISTORY_SHEET_TAG)
    private val inSheet = hasAnyAncestor(sheet)
    private val input = hasSetTextAction() and !password and !inSheet
    private val search = hasSetTextAction() and inSheet
    private val quickBar = hasTestTag(QUICK_BAR_TAG)

    @Before
    fun openTheGameScreenOnANewProfile() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        val dir = CommandSuggestions.dir(compose.activity)
        savedSuggestions = SuggestFile.forProfile(dir, profile).file
        savedPins = SuggestFile.pinsForProfile(dir, profile).file
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertIsDisplayed()
        session.status("profile", "$profile loaded")
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { EngineClient.front.status["connection"] == "connected" }
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { CommandSuggestions.profile } == profile }
        compose.waitForIdle()
    }

    @After
    fun forgetTheProfile() {
        savedSuggestions.delete()
        savedPins.delete()
    }

    @Test
    fun theHistoryButtonOpensASearchThatFiltersAndARowFillsTheLineWithoutSending() {
        for (command in listOf("open door", "north", "look")) send(command)
        activate(historyButton)
        waitFor(row("north"))
        // The focus moves into the sheet, to its search field
        waitFor(search and isFocused())

        compose.onNode(search).performTextInput("od")
        waitUntilGone(row("north"))
        compose.onNode(row("look")).assertDoesNotExist()
        activate(row("open door"))

        waitUntilGone(sheet)
        waitFor(input and isFocused())
        compose.onNode(input).assert(editableText("open door"))
        // A request after the tap comes next: nothing was sent in between
        activate(hasContentDescription(string(R.string.input_complete)))
        assertEquals("open door", session.receive("complete").getString("text"))
        assertEquals(3, session.received.count { it.optString("t") == "send" })
    }

    @Test
    fun aRowsSendButtonSendsAtOnceAndClosesTheSheet() {
        send("open door")
        activate(historyButton)
        activate(labelled(R.string.history_send, "open door"))
        assertEquals("open door", session.receive("send").getString("text"))
        waitUntilGone(sheet)
        compose.onNode(input).assert(editableText(""))
    }

    @Test
    fun aPinnedCommandIsAButtonThatSendsItUntilItIsUnpinned() {
        send("open door")
        activate(historyButton)
        activate(labelled(R.string.history_pin, "open door"))
        // Listed as pinned, with its unpin button, and saved at once beside the suggestions
        waitFor(labelled(R.string.history_unpin, "open door"))
        compose.waitUntil(WAIT_MS) { savedPins.isFile && "open door" in savedPins.readText() }
        activate(hasContentDescription(string(R.string.history_close)))
        waitUntilGone(sheet)

        val pinned = labelled(R.string.pinned_command, "open door")
        waitFor(pinned)
        val button = compose.onNode(pinned and hasClickAction()).fetchSemanticsNode()
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("a ${button.boundsInRoot.height} px tall button, at least 48 dp ($minimum px)", button.boundsInRoot.height >= minimum)
        activate(pinned)
        assertEquals("open door", session.receive("send").getString("text"))

        activate(historyButton)
        activate(labelled(R.string.history_unpin, "open door"))
        waitFor(labelled(R.string.history_pin, "open door"))
        compose.waitUntil(WAIT_MS) { "open door" !in savedPins.readText() }
        activate(hasContentDescription(string(R.string.history_close)))
        waitUntilGone(sheet)
        compose.onNode(pinned).assertDoesNotExist()
    }

    @Test
    fun forgetRemovesACommandFromTheHistory() {
        send("open door")
        send("north")
        activate(historyButton)
        activate(labelled(R.string.history_forget, "north"))
        waitUntilGone(row("north"))
        compose.onNode(row("open door")).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun ctrlROpensTheSheetAndBackClosesIt() {
        send("look")
        compose.onNode(input).requestFocus()
        compose.onNode(input).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
        waitFor(row("look"))
        waitFor(search and isFocused())

        // Back closes it: a second Back when the first only put away the keyboard the search field opened
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        try {
            waitUntilGone(sheet, BACK_MS)
        } catch (_: ComposeTimeoutException) {
            device.pressBack()
            waitUntilGone(sheet)
        }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertIsDisplayed()
        // The R went to neither field
        compose.onNode(input).assert(editableText(""))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun whileTheGameHidesInputThereIsNeitherHistoryNorPinsAndThePasswordIsKeptOutOfBoth() {
        send("north")
        activate(historyButton)
        activate(labelled(R.string.history_pin, "north"))
        waitFor(labelled(R.string.history_unpin, "north"))

        session.hiddenInput(true)
        waitFor(password)
        waitUntilGone(sheet)
        compose.onNode(quickBar).assertDoesNotExist()
        compose.onNode(historyButton).assertDoesNotExist()
        compose.onNode(labelled(R.string.pinned_command, "north")).assertDoesNotExist()
        compose.onNode(password).performTextInput("hunter2")
        compose.onNode(password).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
        compose.waitForIdle()
        compose.onNode(sheet).assertDoesNotExist()
        compose.onNode(password).performImeAction()
        assertEquals("hunter2", session.receive("sendHidden").getString("text"))

        session.hiddenInput(false)
        waitFor(quickBar)
        waitFor(labelled(R.string.pinned_command, "north"))
        activate(historyButton)
        compose.onNode(search).performTextInput("hunter")
        waitFor(hasText(string(R.string.history_no_match)) and inSheet)
        assertFalse("the password is not pinned", "hunter" in savedPins.readText())
    }

    @Test
    fun aProfileWithAsManyPinsAsCanBeLoadsThemAndRefusesOneMore() {
        // Another profile, whose file is there before the game screen first shows it
        val full = "$profile full"
        val dir = CommandSuggestions.dir(compose.activity)
        val files = listOf(SuggestFile.forProfile(dir, full).file, SuggestFile.pinsForProfile(dir, full).file)
        val commands = (1..PinnedCommands.MAX).map { "say $it" }
        try {
            dir.mkdirs()
            files[1].writeText((listOf(PinnedCommands.HEADER) + commands).joinToString("\n", postfix = "\n"))
            session.status("profile", "$full loaded")
            compose.waitUntil(WAIT_MS) { compose.runOnIdle { CommandSuggestions.profile } == full }
            // All of them, in the order pinned; the quick bar scrolls to the last
            waitFor(labelled(R.string.pinned_command, commands.last()))
            compose.onNode(labelled(R.string.pinned_command, commands.last())).performScrollTo().assertIsDisplayed()

            send("north")
            activate(historyButton)
            // Below the twelve pins, the history's rows are off the sheet until the search leaves them out
            compose.onNode(search).performTextInput("north")
            activate(labelled(R.string.history_pin, "north"))
            val refused = compose.activity.resources.getQuantityString(R.plurals.history_pins_full, PinnedCommands.MAX, PinnedCommands.MAX)
            waitFor(hasText(refused) and inSheet)
            compose.onNode(labelled(R.string.history_unpin, "north")).assertDoesNotExist()
            compose.onNode(labelled(R.string.history_pin, "north")).assertIsDisplayed()
            assertFalse("not saved", "north" in files[1].readText())
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun theHistoryButtonIsOnTheInputLineBeforeItsArrows_andTheQuickBarBelowIt() {
        send("open door")
        activate(historyButton)
        activate(labelled(R.string.history_pin, "open door"))
        activate(hasContentDescription(string(R.string.history_close)))
        waitUntilGone(sheet)
        waitFor(labelled(R.string.pinned_command, "open door"))
        checkAccessibility()

        // TalkBack goes by position, so History is read after the line and before its arrows, and the quick bar after them
        val line = bounds(input)
        val history = bounds(historyButton)
        val previous = bounds(hasContentDescription(string(R.string.input_history_previous)) and hasClickAction())
        val pinned = bounds(labelled(R.string.pinned_command, "open door"))
        assertTrue("history $history after the input line $line in its row", history.left >= line.right && history.top < line.bottom && history.bottom > line.top)
        assertTrue("history $history before the up button $previous", history.right <= previous.left)
        assertTrue("pinned $pinned below the input line $line", pinned.top >= line.bottom)
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("a ${history.width} by ${history.height} px History button, at least 48 dp ($minimum px)", history.width >= minimum && history.height >= minimum)
    }

    private val historyButton get() = hasContentDescription(string(R.string.history_open)) and hasClickAction()

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun labelled(@StringRes id: Int, command: String) = hasContentDescription(compose.activity.getString(id, command))

    /** The row in the sheet that puts [command] on the input line. */
    private fun row(command: String) = hasText(command) and inSheet and hasClickAction()

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

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(matcher: SemanticsMatcher, timeoutMs: Long = WAIT_MS) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
    }

    // Each window's: the sheet is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        checkAccessibility()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // Long enough for the sheet's closing animation: a sheet still open after it had only its keyboard put away
        const val BACK_MS = 3_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
