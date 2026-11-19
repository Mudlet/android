package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile

/**
 * The map beside the text on a tablet (MapBesideTextTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: none of the game screen's or the map's text is cut short or off the screen, the map is at the
 * console's end side, the left in a right-to-left language, and the setting's text fits too.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class TabletsPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(TabletScreen())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openOnATablet() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { Settings.mapBesideText.reset() }
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, CONSOLE_LINE)
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        waitFor(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
    }

    @After
    fun defaults() {
        compose.runOnIdle { Settings.mapBesideText.reset() }
    }

    @Test
    fun theGameScreenAndTheMapBesideItFitTheirText() {
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        val search = hasSetTextAction() and hasText(string(R.string.app_map_search))
        waitFor(hasText(CONSOLE_LINE))
        waitFor(search)

        val text = compose.onNode(hasText(CONSOLE_LINE), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val map = compose.onNode(search).fetchSemanticsNode().boundsInRoot
        val rtl = locale == "ar-XB"
        assertTrue("$locale: the map $map at the end side of the text $text", if (rtl) map.right <= text.left else map.left >= text.right)
        assertTextFits("the game screen with the map beside the text")
    }

    @Test
    fun theSettingFitsItsText() {
        activate(hasContentDescription(string(R.string.settings_title)))
        waitFor(hasText(string(R.string.settings_new_games)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        waitFor(hasText(string(R.string.settings_map_beside_text)))
        compose.onNode(hasText(string(R.string.settings_map_beside_text_hint)), useUnmergedTree = true).performScrollTo()
        assertTextFits("the Appearance page, at the map beside the text")
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // PseudoLocaleTest's check, copied rather than shared so the two files change apart
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
        val GAME = Profile("Tablet", "127.0.0.1", 4000, false, "", false)
        const val CONSOLE_LINE = "the main console"

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
