package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R

/**
 * The language row and its list in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: no text is
 * cut off, overflows its box or leaves the screen, and the accessibility checks pass, on the Appearance page and in the
 * list of languages.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class LanguagePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val row get() = hasText(string(R.string.settings_language)) and hasClickAction()
    private val list get() = hasText(string(R.string.settings_choice_cancel))

    @Before
    fun openTheAppearancePage() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        waitFor(row)
        // Below the text size and its sample, which a short screen can push it past
        compose.onNode(row).performScrollTo()
    }

    @Test
    fun theRowAndTheListFitTheirText() {
        // The label alone: in the unmerged tree it is on a child of the node with the click action
        assertTextFits("the Appearance page", hasText(string(R.string.settings_language)))
        activate(row)
        waitFor(list)
        val systemDefault = hasText(string(R.string.settings_language_system)) and hasAnyAncestor(isDialog()) and hasClickAction()
        compose.onNode(systemDefault).assertIsDisplayed()
        assertTextFits("the list of languages", list)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    // The merged tree, where a row's click action and its text are on one node
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // PseudoLocaleTest's check, copied rather than shared so the files change apart; on the window that has [marker]
    private fun assertTextFits(where: String, marker: SemanticsMatcher) {
        val rootMatcher = isRoot() and hasAnyDescendant(marker)
        compose.onNode(rootMatcher).tryPerformAccessibilityChecks()
        val root = compose.onNode(rootMatcher, useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val layouts = ArrayList<TextLayoutResult>()
            if (!text.isNullOrEmpty()) node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            checked += layouts.size
            for (layout in layouts) {
                if ((0 until layout.lineCount).any { layout.isLineEllipsized(it) }) problems += "\"$text\" is cut short"
                if (layout.multiParagraph.didExceedMaxLines) problems += "\"$text\" has more lines than it may show"
                if (layout.multiParagraph.height > layout.size.height + SLACK_PX) {
                    problems += "\"$text\" is ${layout.multiParagraph.height} px tall in its ${layout.size} box"
                }
            }
            val bounds = node.boundsInRoot
            if (layouts.isNotEmpty() && (bounds.left < -SLACK_PX || bounds.right > width + SLACK_PX)) {
                problems += "\"$text\" at $bounds is outside the screen's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        // A window with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text in the window gave its layout", checked > 0)
        assertTrue("$locale, $where: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    companion object {
        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
