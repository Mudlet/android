package org.mudlet.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.SELECTION_END_HANDLE_TAG

/**
 * Selecting lines of the game's text against a fake engine: a long press on a line selects it, with Android's own
 * floating text toolbar beside it, and its end's handle dragged takes it to another line; the toolbar's Copy puts the
 * lines on the clipboard as desktop copies them, Select all takes every line, "Put on command line" hands them to the
 * input line, and an app that takes text, a translator say, is in its overflow and gets them read-only. With TalkBack,
 * the lines' actions "Select from here" and "Select to here" select without a touch, the toolbar's items are actions on
 * the lines, and each change of the selection is said once. Back, a tap on the text and TalkBack's "Stop selecting" end
 * it without copying, and the toolbar goes with it. Timestamps shown beside the lines are not copied.
 */
@RunWith(AndroidJUnit4::class)
class LineSelectTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("line select ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val copy by lazy { string(R.string.console_selection_copy) }

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no line of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.status("timeStamps", "true")
        LINES.forEachIndexed { i, line -> session.line(i + 1L, line, time = CAME) }
        session.synced(LINES.size.toLong())
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        waitFor(line(3))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.timeStampsShown } }
    }

    @Test
    fun aLongPressSelectsALineWithAndroidsToolbarAHandleTakesItToAnotherLineAndCopyPutsTheLinesOnTheClipboard() {
        compose.onNode(line(0)).performTouchInput { longClick(center) }
        compose.waitUntil(WAIT_MS) { selected(0) }
        compose.onNode(line(1)).assert(notSelected)
        val share = string(R.string.console_selection_share)
        assertEquals("Android's own items first", listOf(copy, share), FloatingToolbar.shown(copy, share, string(R.string.console_selection_select_all)).take(2))
        assertTrue(FloatingToolbar.offers(string(R.string.console_selection_to_command_line)))

        val handle = hasTestTag(SELECTION_END_HANDLE_TAG)
        waitFor(handle)
        val from = compose.onNode(handle).fetchSemanticsNode().boundsInRoot
        val to = compose.onNode(line(2)).fetchSemanticsNode().boundsInRoot
        compose.onNode(handle).performTouchInput {
            down(center)
            val by = to.center.y - from.top
            repeat(DRAG_STEPS) { moveBy(Offset(0f, by / DRAG_STEPS)) }
            up()
        }
        compose.waitUntil(WAIT_MS) { selected(2) }
        for (i in 0..2) compose.onNode(line(i)).assertIsSelected()
        compose.onNode(line(3)).assert(notSelected)
        checkAccessibility()

        FloatingToolbar.tap(copy)
        compose.waitUntil(WAIT_MS) { clipboard() == LINES.take(3).joinToString("\n") }
        assertTrue("the toolbar goes once the lines are copied", FloatingToolbar.waitUntilGone(copy))
        compose.waitUntil(WAIT_MS) { !selected(0) }
    }

    @Test
    fun talkBacksActionsSelectAndCopyWithoutATouchAndEachChangeIsSaidOnce() {
        val selectTo = string(R.string.console_select_to_here)
        assertTrue("no line offers \"$selectTo\" before a selection", actionsOf(line(1)).none { it == selectTo })
        perform(line(3), string(R.string.console_select_from_here))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { Announcer.current?.text }?.endsWith(count(1)) == true }
        quiet()
        val first = compose.runOnIdle { Announcer.current!!.serial }

        // While selecting, a line's actions are the selection's: the toolbar's items, and an end to it
        compose.waitUntil(WAIT_MS) { actionsOf(line(1)).contains(selectTo) }
        assertTrue(actionsOf(line(1)).none { it == string(R.string.console_copy_line) })
        for (item in listOf(copy, string(R.string.console_selection_share), string(R.string.console_selection_select_all), string(R.string.console_selection_to_command_line), string(R.string.console_selection_stop))) {
            assertTrue("\"$item\" in ${actionsOf(line(0))}", actionsOf(line(0)).contains(item))
        }
        perform(line(1), selectTo)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { Announcer.current?.text }?.endsWith(count(3)) == true }
        quiet()
        val said = compose.runOnIdle { Announcer.current!! }
        assertTrue("said once: ${said.text}", said.text.endsWith(count(3)) && said.text.indexOf(count(3)) == said.text.lastIndexOf(count(3)))
        assertTrue("one announcement for the change", said.serial <= first + 1)
        for (i in 1..3) compose.onNode(line(i)).assertIsSelected()
        compose.onNode(line(0)).assert(notSelected)

        perform(line(0), copy)
        compose.waitUntil(WAIT_MS) { clipboard() == LINES.drop(1).joinToString("\n") }
        compose.waitUntil(WAIT_MS) { !selected(1) }
    }

    @Test
    fun backATapOnTheTextAndStopSelectingEndASelectionWithoutCopyingAndTheToolbarGoes() {
        val clipped = clipboard()
        perform(line(1), string(R.string.console_select_from_here))
        FloatingToolbar.waitFor(copy)
        device.pressBack()
        compose.waitUntil(WAIT_MS) { !selected(1) }
        assertTrue(FloatingToolbar.waitUntilGone(copy))
        // Still on the game screen: Back only ended the selection
        compose.onNode(hasTestTag(GAME_TITLE_TAG)).assertIsDisplayed()

        compose.onNode(line(0)).performTouchInput { longClick(center) }
        FloatingToolbar.waitFor(copy)
        compose.onNode(line(3)).performTouchInput { click(center) }
        compose.waitUntil(WAIT_MS) { !selected(0) }
        assertTrue(FloatingToolbar.waitUntilGone(copy))
        compose.onNode(line(3)).assert(notSelected)

        perform(line(2), string(R.string.console_select_from_here))
        FloatingToolbar.waitFor(copy)
        perform(line(2), string(R.string.console_selection_stop))
        compose.waitUntil(WAIT_MS) { !selected(2) }
        assertTrue(FloatingToolbar.waitUntilGone(copy))
        assertEquals("ending a selection copies nothing", clipped, clipboard())
    }

    @Test
    fun putOnCommandLineFillsTheInputLineWithTheLinesOnOneLineWithoutSending() {
        perform(line(0), string(R.string.console_select_from_here))
        perform(line(1), string(R.string.console_select_to_here))
        compose.waitUntil(WAIT_MS) { selected(1) }
        FloatingToolbar.tap(string(R.string.console_selection_to_command_line))
        waitFor(input and editableText("${LINES[0]} ${LINES[1]}"))
        compose.waitUntil(WAIT_MS) { !selected(0) }
        assertTrue(FloatingToolbar.waitUntilGone(copy))
        assertTrue("nothing sent", session.received.none { it.optString("t") == "send" })
    }

    @Test
    fun selectAllTakesEveryLineAndTheToolbarStaysWithoutIt() {
        val all = string(R.string.console_selection_select_all)
        compose.onNode(line(2)).performTouchInput { longClick(center) }
        compose.waitUntil(WAIT_MS) { selected(2) }
        FloatingToolbar.tap(all)
        compose.waitUntil(WAIT_MS) { (0..3).all(::selected) }
        FloatingToolbar.waitFor(copy)
        assertFalse("Select all with every line selected", FloatingToolbar.offers(all))
        assertFalse(actionsOf(line(0)).contains(all))
    }

    @Test
    fun anAppThatTakesTextIsInTheToolbarsOverflowAndGetsTheLinesReadOnly() {
        compose.onNode(line(1)).performTouchInput { longClick(center) }
        compose.waitUntil(WAIT_MS) { selected(1) }
        FloatingToolbar.tap(TextTakerActivity.LABEL)
        val taken = TextTakerActivity.TAKEN.format(true, LINES[1])
        assertTrue("the app got \"$taken\"", device.wait(Until.hasObject(By.text(taken)), WAIT_MS))
        device.pressBack()
        waitFor(line(1))
        compose.waitUntil(WAIT_MS) { !selected(1) }
    }

    // The line's node, read with its time first
    private fun line(i: Int) = hasText(LINES[i], substring = true) and SemanticsMatcher("a line") { it.config.getOrNull(SemanticsActions.CustomActions) != null }

    private val notSelected = SemanticsMatcher("not selected") { it.config.getOrNull(SemanticsProperties.Selected) != true }

    private val input = hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text }

    private fun selected(i: Int) = compose.onAllNodes(line(i)).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun count(n: Int) = compose.activity.resources.getQuantityString(R.plurals.console_selection_count, n, n)

    private fun actionsOf(node: SemanticsMatcher) = compose.onNode(node).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun perform(node: SemanticsMatcher, label: String) {
        compose.waitUntil(WAIT_MS) { actionsOf(node).contains(label) }
        checkAccessibility()
        val action = compose.onNode(node).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    // Past the Announcer's merging of announcements close together, on the device's clock, and past any delay in an
    // effect, on the test's, so that a second announcement shows as one, not merged into the one before or after it
    private fun quiet() {
        SystemClock.sleep(QUIET_MS)
        compose.mainClock.advanceTimeBy(QUIET_MS)
    }

    private fun clipboard(): String? {
        var text: String? = null
        compose.runOnUiThread {
            text = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /** Activates the control TalkBack reads as [label], through its accessibility action, after the checks. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-line-select"
        val LINES = listOf("You hit the rat.", "The rat bites you.", "The rat dies.", "You get a coin.")
        const val CAME = 1_700_000_000_123L
        const val QUIET_MS = 500L
        const val WAIT_MS = 10_000L
        const val DRAG_STEPS = 10

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
