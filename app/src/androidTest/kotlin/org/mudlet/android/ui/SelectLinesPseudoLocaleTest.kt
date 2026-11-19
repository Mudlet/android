package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * Selecting lines of the game's text (LineSelectTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: the line menu's "Select from here" and the selection's bar, its count, hint, Copy and Share, are not cut
 * short, and pass the accessibility checks.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SelectLinesPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("select pseudo ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        LINES.forEachIndexed { i, line -> session.line(i + 1L, line) }
        session.synced(LINES.size.toLong())
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(LINES.last()))
    }

    @Test
    fun theMenuItemAndTheBarFit() {
        compose.onNode(hasText(LINES.first())).performTouchInput { longClick(center) }
        val selectFrom = string(R.string.console_select_from_here)
        waitFor(hasText(selectFrom) and hasClickAction())
        assertFits(selectFrom)
        activate(hasText(selectFrom))

        val selectTo = string(R.string.console_select_to_here)
        compose.waitUntil(WAIT_MS) { actionsOf(LINES.last()).contains(selectTo) }
        val action = compose.onNode(hasText(LINES.last())).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == selectTo }
        compose.runOnIdle { action.action() }
        val count = compose.activity.resources.getQuantityString(R.plurals.console_selection_count, LINES.size, LINES.size)
        for (text in listOf(count, string(R.string.console_selection_hint), string(R.string.console_selection_copy), string(R.string.console_selection_share))) {
            assertFits(text)
        }
    }

    private fun actionsOf(line: String) = compose.onNode(hasText(line)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun assertFits(text: String) {
        val node = hasText(text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(node, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        checkAccessibility()
        val layouts = ArrayList<TextLayoutResult>()
        compose.onNode(node, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // Each window's: a menu is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val INSTANCE = "fake-select-pseudo"
        private val LINES = listOf("You hit the rat.", "A bat bites you.", "The rat bites you.")
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
