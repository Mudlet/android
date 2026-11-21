package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
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
import androidx.compose.ui.test.performTouchInput
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
 * The colour dialog in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: no text is cut off,
 * overflows its box or leaves the screen, folded or with its sliders out; and its hue bar starts at the right in a
 * right-to-left layout.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ColourPickerPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val dialog get() = hasText(string(R.string.settings_colour_ok))
    private val hue get() = hasContentDescription(string(R.string.settings_colour_hue)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Before
    fun openTheBackgroundsDialog() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_colours)))
        activate(hasText(string(R.string.settings_colours)))
        // A background's preview draws the text on it
        val background = hasText(string(R.string.settings_colour_background)) and hasClickAction()
        waitFor(background)
        compose.onNode(background).performScrollTo()
        activate(background)
        waitFor(dialog)
    }

    @Test
    fun theDialogFitsItsTextFoldedAndWithItsSliders() {
        assertTextFits("the colour dialog")
        val fineTune = hasText(string(R.string.settings_colour_fine_tune)) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.onNode(fineTune).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        val blue = hasContentDescription(string(R.string.settings_colour_blue_amount)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        waitFor(blue)
        compose.onNode(blue).performScrollTo()
        assertTextFits("the colour dialog with its red, green and blue sliders")
    }

    @Test
    fun theHueBarStartsWhereTheLayoutDoes() {
        val rtl = compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        assertTrue("$locale lays out ${if (rtl) "right to left" else "left to right"}", rtl == (locale == "ar-XB"))
        // A quarter of the way along from where the bar starts: a quarter of the way round, yellowish green
        compose.onNode(hue).performScrollTo().performTouchInput { click(Offset(if (rtl) width * 0.75f else width * 0.25f, centerY)) }
        val degrees = compose.activity.resources.getQuantityString(R.plurals.settings_colour_hue_value, 90, 90)
        compose.onNode(hue).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, degrees))
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    // The merged tree, where a row's click action and its text are on one node
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // PseudoLocaleTest's check, copied rather than shared so the files change apart; always on the dialog's window
    private fun assertTextFits(where: String) {
        val rootMatcher = isRoot() and hasAnyDescendant(dialog)
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
