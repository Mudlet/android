package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
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
import org.mudlet.android.data.Settings

/**
 * The script library's row that picks the editor's design, and its list of designs (EditorDesignSwitchTest), in the
 * debug build's pseudo-locales, as PseudoLocaleTest checks the screens: no text is cut off, overflows its box or leaves
 * the screen, and the accessibility checks pass.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class EditorDesignPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.CURRENT))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun theRowAndTheListOfDesignsFitTheirText() {
        t.openGame()
        // Not openLibrary(): en-XA's longer words push Greet below a small phone's screen, out of composition
        t.activate(hasContentDescription(t.string(R.string.scripts_open, t.game.name)))
        t.answerLists()
        val row = hasText(t.string(R.string.settings_script_editor_design)) and hasAnyAncestor(t.library)
        compose.onNode(t.library).performScrollToNode(row)
        assertTextFits("the library", t.library)

        t.activate(row)
        val recipes = hasText(t.string(R.string.settings_script_editor_recipes)) and hasAnyAncestor(isDialog())
        t.waitFor(recipes)
        assertTextFits("the list of designs", recipes)
    }

    // PseudoLocaleTest's check, copied rather than shared so the files change apart; on the window that has [marker]
    private fun assertTextFits(where: String, marker: SemanticsMatcher) {
        compose.waitForIdle()
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

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
