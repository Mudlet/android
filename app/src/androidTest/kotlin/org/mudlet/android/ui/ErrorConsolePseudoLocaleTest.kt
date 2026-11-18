package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.PluralsRes
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.errors.ERROR_CONSOLE_TAG
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The error console (ErrorConsoleTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: no
 * text is cut short, overflows its box or leaves the screen, and in ar-XB an entry's first line is on the right.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ErrorConsolePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == "ar-XB"

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("errors ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun theConsoleFitsItsTextAndFollowsTheLayoutDirection() {
        // A new engine instance, so no entry of an earlier test counts as seen
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-errors-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))

        session.errorConsole(true, errorEntry(1, "attempt to index a nil value", "trigger", 1, "Greet", "Trigger1", line = 2))
        activate(hasContentDescription(plural(R.plurals.errors_unseen, 1, string(R.string.scripts_open, game.name), 1)))
        compose.waitForIdle()
        repeat(KINDS) {
            val ask = session.receive("scriptItems")
            session.send(JSONObject().put("t", "scriptItems").put("kind", ask.getString("kind")).put("items", JSONArray()).put("re", ask.get("id")))
        }
        activate(hasContentDescription(plural(R.plurals.errors_unseen, 1, string(R.string.errors_open), 1)))
        waitFor(hasTestTag(ERROR_CONSOLE_TAG))
        assertTextFits("the error console")
        val at = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(locale)).withZone(ZoneId.systemDefault())
        assertAtStart("an error's first line", hasText(string(R.string.errors_error_at, at.format(Instant.ofEpochMilli(ERROR_AT)))))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = compose.activity.resources.getQuantityString(id, count, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    // PseudoLocaleTest's checks, copied rather than shared so that the two files do not change together
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

    private fun assertAtStart(what: String, matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
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
        assertTrue("$locale: $what at $bounds is not on the $side of the $width px screen", atStart)
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
        // The script library asks for triggers, aliases, scripts, timers, keys and buttons
        private const val KINDS = 6

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
