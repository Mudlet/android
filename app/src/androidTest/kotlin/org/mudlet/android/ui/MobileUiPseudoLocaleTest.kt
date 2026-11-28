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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import kotlin.math.abs

/**
 * A package whose interface is made for larger screens in the package manager (MobileUiTest), in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: no text of its note and switch is cut off, overflows its box
 * or leaves the screen, and the note and the switch's label start where the package's title does, on the right in
 * ar-XB.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MobileUiPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == AR_XB
    private val game = Profile("mobile ui ${System.nanoTime()}", "mud.example.org", 4000, false, "", false)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @After
    fun forgetTheChoice() = ScriptWindowsClient.forgetShownAnyway(compose.activity.applicationContext, game.name)

    @Test
    fun theNoteAndTheSwitchFitAndAreAtTheStart() {
        // A new engine instance, so no list of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        compose.waitForIdle()
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        val label = FakeEngine.window("big", kind = "label", w = 400, h = 100).put("packages", JSONArray().put("Big GUI")).put("mobile", false)
        session.windows(listOf(label), full = true)

        compose.pickFromGameMenu(string(R.string.packages_open, game.name))
        compose.waitForIdle()
        val listed = session.receive("packages")
        val pack = JSONObject().put("name", "Big GUI").put("title", "Big GUI").put("author", "").put("version", "1.0")
            .put("description", "Gauges and a chat window around the console.").put("icon", "").put("mobile", false)
        session.send(JSONObject().put("t", "packages").put("packages", JSONArray().put(pack)).put("re", listed.get("id")))
        waitFor(hasText(string(R.string.packages_ui_show_anyway)))
        assertTextFits("a package made for larger screens")
        assertStartsWithTitle("the note", hasText(string(R.string.packages_ui_larger_screens)))
        assertStartsWithTitle("the switch's label", hasText(string(R.string.packages_ui_show_anyway)))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Every text on the screen is laid out whole within its box (no ellipsis, no line left out, nothing clipped below
     * its last line) and within the screen's width, after the accessibility checks. Not hasVisualOverflow: the layout
     * the semantics hand over is laid out across the width the text was offered, so a single line narrower than that
     * would count as overflowing its box.
     */
    private fun assertTextFits(where: String) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
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

    /**
     * The text's own node starts where the package's title does, at the start of a line: the left in en-XA, the right
     * in ar-XB, and its lines start at the start of its box. Not by which side of the screen it is nearer, as
     * PackagesPseudoLocaleTest checks: the switch's label fills its row, and the note may, between unequal margins.
     */
    private fun assertStartsWithTitle(what: String, matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val title = compose.onNode(hasText("Big GUI"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val side = if (rtl) "right" else "left"
        val aligned = if (rtl) abs(bounds.right - title.right) <= SLACK_PX else abs(bounds.left - title.left) <= SLACK_PX
        assertTrue("$locale: $what at $bounds does not start on the $side as the title at $title does", aligned)
        assertTrue("$locale: $what at $bounds has lines that do not start on the $side", linesStartAtStart(node))
    }

    private fun linesStartAtStart(node: SemanticsNode): Boolean {
        val layouts = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return false
        val width = layout.multiParagraph.width
        return (0 until layout.lineCount).all { line ->
            val left = layout.getLineLeft(line)
            val right = width - layout.getLineRight(line)
            if (rtl) right <= left + SLACK_PX else left <= right + SLACK_PX
        }
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        // In the merged tree: an IconButton's click and its icon's label are one node only there
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val EN_XA = "en-XA"
        private const val AR_XB = "ar-XB"
        private const val INSTANCE = "fake-mobile-ui-pseudo"

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(EN_XA, AR_XB)
    }
}
