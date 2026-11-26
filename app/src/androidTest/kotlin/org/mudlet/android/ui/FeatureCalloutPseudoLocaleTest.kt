package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import kotlin.math.abs

/**
 * The callout about switching games (FeatureCalloutTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: none of its text is cut short, it is on the screen, and its title starts at the start of a line.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class FeatureCalloutPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule(open = listOf(ALPHA.name, BETA.name))
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == AR_XB

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { FeatureCallouts.reset(compose.activity, dismissed = false) }
        val beta = fake.engine.nextSession()
        beta.profiles(listOf(ALPHA, BETA), beta.receive("listProfiles"))
        val sessions = listOf(beta.forProfile(ALPHA.name), beta)
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 2 && EngineClient.sessions.all { it.linkState.startsWith("attached") } }
        for (session in sessions) {
            session.synced(0)
            session.status("connection", "connected")
        }
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.all { it.status["connection"] == "connected" } }
    }

    @Test
    fun theCalloutsTextFits() {
        // Beta, the front: a tap on Alpha would be a switch, which ends the callout
        val row = hasContentDescription(string(R.string.game_state, BETA.name, string(R.string.connection_connected))) and hasClickAction()
        waitFor(row)
        compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
        val title = hasText(string(R.string.callout_switch_games_title))
        waitFor(title)
        waitFor(hasTestTag(GAME_TITLE_TAG))
        val callout = isRoot() and hasAnyDescendant(title)
        assertTextFits("the callout", callout)
        assertOnTheScreen(callout)
        assertAtStart("the callout's title", title, callout)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun screenWidth() = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(GAME_TITLE_TAG))).fetchSemanticsNode().size.width

    private fun assertOnTheScreen(window: SemanticsMatcher) {
        val root = compose.onNode(window).fetchSemanticsNode()
        val left = root.positionOnScreen.x
        val width = screenWidth()
        assertTrue("$locale: the callout from $left, ${root.size.width} px wide, is off the $width px screen", left >= -SLACK_PX && left + root.size.width <= width + SLACK_PX)
    }

    // assertTextFits and assertAtStart are PseudoLocaleTest's, for a window of their own: the callout is one
    private fun assertTextFits(where: String, window: SemanticsMatcher) {
        compose.onNode(window).tryPerformAccessibilityChecks()
        val root = compose.onNode(window, useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
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
                problems += "\"$text\" at $bounds is outside its window's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        // A window with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text gave its layout", checked > 0)
        assertTrue("$locale, $where: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    private fun assertAtStart(what: String, matcher: SemanticsMatcher, window: SemanticsMatcher) {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val width = compose.onNode(window).fetchSemanticsNode().size.width
        val fromLeft = bounds.left
        val fromRight = width - bounds.right
        val side = if (rtl) "right" else "left"
        val atStart = if (abs(fromLeft - fromRight) <= SLACK_PX) {
            linesStartAtStart(node)
        } else if (rtl) {
            fromRight < fromLeft
        } else {
            fromLeft < fromRight
        }
        assertTrue("$locale: $what at $bounds is not on the $side of its $width px window", atStart)
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

    companion object {
        private const val AR_XB = "ar-XB"

        // Short names, as PseudoLocaleTest's: a long one crowds the title bar in en-XA
        private val ALPHA = Profile("Alpha", "127.0.0.1", 4001, false, "", false)
        private val BETA = Profile("Beta", "127.0.0.1", 4002, false, "", false)

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L
        private const val WAIT_MS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", AR_XB)
    }
}
