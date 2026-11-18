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
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
 * The Colours page and its colour dialog, and the Display and Accessibility pages with "Links from the game" and
 * "Blinking text", in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: no text is cut off,
 * overflows its box or leaves the screen.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SettingsColoursPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun openTheSettings() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
    }

    @Test
    fun theColoursPageAndItsDialogFitTheirText() {
        openPage(R.string.settings_colours, R.string.settings_game_changes_colours)
        assertTextFits("the Colours page, with the game changing colours")
        compose.onNode(hasText(string(R.string.settings_colours_reset)) and hasClickAction()).performScrollTo()
        assertTextFits("the Colours page, at its reset")
        compose.onNode(hasText(string(R.string.settings_colour_foreground)) and hasClickAction()).performScrollTo()
        activate(hasText(string(R.string.settings_colour_foreground)))
        val dialog = hasText(string(R.string.settings_colour_ok))
        waitFor(dialog)
        assertTextFits("the dialog that changes a colour", window = dialog)
        compose.onNode(hasText(string(R.string.settings_choice_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(dialog).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theLinksAndBlinkingSwitchesFitTheirText() {
        openPage(R.string.settings_display, R.string.settings_game_links)
        compose.onNode(hasText(string(R.string.settings_game_links)), useUnmergedTree = true).performScrollTo()
        assertTextFits("the Display page, with the game's links")
        activate(hasContentDescription(string(R.string.navigate_back)))
        openPage(R.string.settings_accessibility, R.string.settings_blink_text)
        compose.onNode(hasText(string(R.string.settings_blink_text)), useUnmergedTree = true).performScrollTo()
        assertTextFits("the Accessibility page, with blinking text")
    }

    private fun openPage(@StringRes page: Int, @StringRes setting: Int) {
        compose.scrollSettingsHubTo(hasText(string(page)))
        activate(hasText(string(page)))
        waitFor(hasText(string(setting)))
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // PseudoLocaleTest's check, copied rather than shared so the two files change apart
    private fun assertTextFits(where: String, window: SemanticsMatcher? = null) {
        // A dialog is a window, and a root, of its own
        val rootMatcher = if (window == null) isRoot() else isRoot() and hasAnyDescendant(window)
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
        // A screen with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text on the screen gave its layout", checked > 0)
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
