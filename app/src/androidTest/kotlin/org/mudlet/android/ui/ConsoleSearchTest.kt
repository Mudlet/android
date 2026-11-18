package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * Searching the game's text against a fake engine, as desktop's search bar does: the top bar, or Ctrl+F, opens the bar,
 * which says how many lines match once typing pauses; the first older match is the bottom one, each next goes on from
 * the last, and past the top it says there is no more; a newer match goes back down; matches are drawn black on yellow
 * until the bar closes. What the bar says is a polite live region, so TalkBack reads it.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleSearchTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("console search ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val said = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no line of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        LINES.forEachIndexed { i, line -> session.line(i + 1L, line) }
        session.synced(LINES.size.toLong())
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        waitFor(hasText(LINES.last()))
    }

    @Test
    fun theBarCountsTheMatchesStepsUpAndDownAsDesktopsAndDrawsThemUntilClosed() {
        compose.pickFromGameMenu(string(R.string.console_search))
        val field = hasSetTextAction() and hasText(string(R.string.console_search_field))
        waitFor(field)
        compose.onNode(field).performTextInput("RAT")
        waitFor(said and hasText(compose.activity.resources.getQuantityString(R.plurals.console_search_count, 2, 2)))
        assertEquals("drawn as desktop draws a match", Color.Yellow, backgroundAt(LINES[0], 12))

        val older = hasContentDescription(string(R.string.console_search_older))
        val newer = hasContentDescription(string(R.string.console_search_newer))
        activate(older)
        waitFor(said and hasText(LINES[2]))
        activate(older)
        waitFor(said and hasText(LINES[0]))
        activate(older)
        waitFor(said and hasText(string(R.string.console_search_none)))
        activate(newer)
        waitFor(said and hasText(LINES[2]))

        activate(hasContentDescription(string(R.string.console_search_close)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(field).fetchSemanticsNodes().isEmpty() }
        assertEquals("no match drawn once the bar is closed", Color.Unspecified, backgroundAt(LINES[0], 12))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun ctrlFOpensTheBarWithItsFieldFocused() {
        val input = hasSetTextAction() and !hasText(string(R.string.console_search_field))
        compose.onNode(input).requestFocus()
        compose.onNode(input).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        waitFor(hasSetTextAction() and hasText(string(R.string.console_search_field)) and isFocused())
    }

    // The background the character [index] of the console line [line] is drawn on
    private fun backgroundAt(line: String, index: Int): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(line) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and !said)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.spanStyles.lastOrNull { index >= it.start && index < it.end && it.item.background != Color.Unspecified }
            ?.item?.background ?: Color.Unspecified
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
        const val INSTANCE = "fake-console-search"
        val LINES = listOf("You hit the rat.", "A bat bites you.", "The rat bites you.")
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
