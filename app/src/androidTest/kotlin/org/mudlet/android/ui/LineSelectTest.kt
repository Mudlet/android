package org.mudlet.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
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
import androidx.test.uiautomator.UiDevice
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

/**
 * Selecting lines of the game's text against a fake engine: "Select from here" in a line's long press menu starts a
 * selection, a tap on another line takes it there, and Copy puts the lines on the clipboard as desktop copies them; with
 * TalkBack, the lines' actions "Select from here" and "Select to here" do the same without a touch, and each change of
 * the selection is said once. Timestamps shown beside the lines are not copied.
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
    fun selectFromHereThenATapOnAnotherLineSelectsTheLinesBetweenAndCopyPutsThemOnTheClipboard() {
        compose.onNode(line(0)).performTouchInput { longClick(center) }
        activate(hasText(string(R.string.console_select_from_here)))
        waitFor(hasText(count(1)))
        compose.onNode(line(0)).assertIsSelected()
        compose.onNode(line(1)).assert(notSelected)

        compose.onNode(line(2)).performTouchInput { click(center) }
        waitFor(hasText(count(3)))
        for (i in 0..2) compose.onNode(line(i)).assertIsSelected()
        compose.onNode(line(3)).assert(notSelected)
        checkAccessibility()

        activate(hasText(string(R.string.console_selection_copy)))
        compose.waitUntil(WAIT_MS) { clipboard() == LINES.take(3).joinToString("\n") }
        compose.onNode(hasText(count(3))).assertDoesNotExist()
        compose.onNode(line(0)).assert(notSelected)
    }

    @Test
    fun talkBacksActionsSelectARangeWithoutATouchAndEachChangeIsSaidOnce() {
        val selectTo = string(R.string.console_select_to_here)
        assertTrue("no line offers \"$selectTo\" before a selection", actionsOf(line(1)).none { it == selectTo })
        perform(line(3), string(R.string.console_select_from_here))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { Announcer.current?.text }?.endsWith(count(1)) == true }
        quiet()
        val first = compose.runOnIdle { Announcer.current!!.serial }

        // While selecting, a line's actions are the selection's, not its menu's
        compose.waitUntil(WAIT_MS) { actionsOf(line(1)).contains(selectTo) }
        assertTrue(actionsOf(line(1)).none { it == string(R.string.console_copy_line) })
        perform(line(1), selectTo)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { Announcer.current?.text }?.endsWith(count(3)) == true }
        quiet()
        val said = compose.runOnIdle { Announcer.current!! }
        assertTrue("said once: ${said.text}", said.text.endsWith(count(3)) && said.text.indexOf(count(3)) == said.text.lastIndexOf(count(3)))
        assertTrue("one announcement for the change", said.serial <= first + 1)
        for (i in 1..3) compose.onNode(line(i)).assertIsSelected()
        compose.onNode(line(0)).assert(notSelected)

        activate(hasText(string(R.string.console_selection_copy)))
        compose.waitUntil(WAIT_MS) { clipboard() == LINES.drop(1).joinToString("\n") }
    }

    @Test
    fun backAndTheCloseButtonEndASelectionWithoutCopying() {
        perform(line(0), string(R.string.console_select_from_here))
        waitFor(hasText(count(1)))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(count(1))).fetchSemanticsNodes().isEmpty() }
        compose.onNode(line(0)).assert(notSelected)
        // Still on the game screen: Back only ended the selection
        compose.onNode(hasTestTag(GAME_TITLE_TAG)).assertIsDisplayed()

        perform(line(1), string(R.string.console_select_from_here))
        waitFor(hasText(count(1)))
        activate(hasContentDescription(string(R.string.console_selection_close)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(count(1))).fetchSemanticsNodes().isEmpty() }
        compose.onNode(line(1)).assert(notSelected)
    }

    // The line's node, read with its time first
    private fun line(i: Int) = hasText(LINES[i], substring = true) and SemanticsMatcher("a line") { it.config.getOrNull(SemanticsActions.CustomActions) != null }

    private val notSelected = SemanticsMatcher("not selected") { it.config.getOrNull(SemanticsProperties.Selected) != true }

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

    // Each window's: a menu is a window of its own over the activity's
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

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
