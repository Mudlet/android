package org.mudlet.android.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import java.util.Locale
import kotlin.math.abs

/**
 * The app in the debug build's pseudo-locales (docs/translations.md, "Pseudo-locales"): en-XA, whose accented text
 * runs longer than the English, and ar-XB, right to left. On the profile list, empty and with a game, the profile
 * editor, the settings and the game screen, with the connection chip's states and the password field, no text is
 * cut off, overflows its box or leaves the screen, and in ar-XB the screens are mirrored. The app's language is set as the system's per-app language setting sets it,
 * which Android 13 brought.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rtl = locale == AR_XB

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
    }

    @Test
    fun everyScreenFitsItsTextAndFollowsTheLayoutDirection() {
        // The pseudo-locale is in force: its text is not the English
        assertNotEquals("$locale's title", english(R.string.profiles_title), string(R.string.profiles_title))

        compose.onNodeWithText(string(R.string.profiles_title)).assertIsDisplayed()
        val session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))
        waitFor(hasText(string(R.string.profiles_empty)))
        assertTextFits("the empty profile list")
        assertAtStart("the empty profile list's text", hasText(string(R.string.profiles_empty)))

        activate(hasContentDescription(string(R.string.profiles_add)))
        waitFor(hasText(string(R.string.profile_new_title)))
        assertTextFits("the profile editor")
        assertAtStart("the profile editor's Back button", hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))

        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        assertTextFits("the settings")
        assertAtStart("the settings' first heading", hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        waitFor(hasText(string(R.string.settings_text_size)))
        assertTextFits("a settings page")
        assertAtStart("the page's first setting", hasText(string(R.string.settings_text_size)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))

        session.profiles(listOf(GAME))
        waitFor(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        assertTextFits("the profile list")
        assertAtStart("a profile", hasContentDescription(string(R.string.profile_description, GAME.name, GAME.host, GAME.port)))

        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        assertTextFits("the game screen")
        assertAtStart("the Back button", hasContentDescription(string(R.string.navigate_back)))

        session.status("connection", "connecting $ADDRESS")
        waitFor(hasText(string(R.string.connection_stop)))
        assertTextFits("the game pill's row, connecting")
        session.status("connection", "error: $REASON")
        waitFor(hasText(string(R.string.connection_try_again)))
        assertTextFits("the game pill's row, failed")

        session.hiddenInput(true)
        waitFor(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTextFits("the password field")
        session.hiddenInput(false)

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun english(@StringRes id: Int): String {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }

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
     * The node is nearer the start of a line than its end: the left in en-XA, the right in ar-XB. A node as wide as the
     * screen allows, such as text that wraps, is at neither side, so its lines must start at the start of its box.
     */
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

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val EN_XA = "en-XA"
        private const val AR_XB = "ar-XB"
        private const val ADDRESS = "mud.example.org:4000"
        private val GAME = Profile("Game", "mud.example.org", 4000, false, "", false)
        private const val REASON = "Connection refused by the remote host"

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
