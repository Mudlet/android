package org.mudlet.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * Copying a line of the game's text against a fake engine: a long press on a line selects it, with Android's floating
 * text toolbar offering to copy and share it beside the trigger, and TalkBack has copying as the action of each line with
 * text, not a blank one; either puts the line on the clipboard as it reads.
 */
@RunWith(AndroidJUnit4::class)
class LineCopyTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("line copy ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no line of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, OTHER)
        session.line(2, LINE)
        session.synced(2)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        waitFor(hasText(LINE))
    }

    @Test
    fun aLongPressOnALineOffersCopyAndShareBesideTheTriggerInAndroidsToolbarAndCopyPutsTheLineOnTheClipboard() {
        compose.onNode(hasText(LINE)).performTouchInput { longClick(center) }
        val copy = string(R.string.console_selection_copy)
        FloatingToolbar.waitFor(copy)
        FloatingToolbar.waitFor(string(R.string.console_selection_share))
        assertTrue(FloatingToolbar.offers(string(R.string.console_selection_trigger)))
        FloatingToolbar.tap(copy)
        compose.waitUntil(WAIT_MS) { clipboard() == LINE }
        assertTrue(FloatingToolbar.waitUntilGone(copy))
    }

    @Test
    fun talkBacksCopyLineActionIsOnEachLineWithTextAndCopiesIt() {
        val label = string(R.string.console_copy_line)
        // Only here: a blank line shown as the game sent it is a row with no text, which the checks before a touch report
        session.line(3, "")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.lines.any { it.text.isEmpty() } } }
        // Each line with text, the app's own among them, and not the blank one
        val withText = compose.runOnIdle { EngineClient.front.lines.count { it.text.isNotBlank() } }
        assertEquals(2, compose.runOnIdle { EngineClient.front.lines.count { it.text.isNotBlank() && !it.marker } })
        compose.onAllNodes(SemanticsMatcher("offers copying") { node -> node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label } }).assertCountEquals(withText)
        val action = compose.onNode(hasText(OTHER)).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitUntil(WAIT_MS) { clipboard() == OTHER }
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
        const val INSTANCE = "fake-line-copy"
        const val LINE = "A rat bites you."
        const val OTHER = "You hit the rat."
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
