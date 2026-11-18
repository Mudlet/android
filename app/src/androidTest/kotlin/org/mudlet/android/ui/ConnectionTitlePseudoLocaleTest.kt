package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import org.mudlet.android.ui.game.CONNECTION_BANNER_TAG
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The game screen's connection controls in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens:
 * with the title bar's Connect button in, the game title keeps a touch target's width and every title bar button stays
 * on the screen, and the input line keeps its History button; the banner's text and buttons, failed and reconnecting,
 * and the menu's connection items and Settings are whole and within their window.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ConnectionTitlePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("title pseudo ${System.nanoTime()}", "127.0.0.1", 4000, false, "", true)
    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        val connect = hasContentDescription(string(R.string.profile_connect_to, game.name)) and hasClickAction()
        waitFor(connect)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(connect).performSemanticsAction(SemanticsActions.OnClick)
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    @Test
    fun theTitleBarTheInputLineTheBannerAndTheMenuFit() {
        session.status("connection", "disconnected")
        val connectButton = hasContentDescription(string(R.string.connection_connect_to, game.name)) and hasClickAction()
        waitFor(connectButton)
        val history = hasContentDescription(string(R.string.history_open)) and hasClickAction()
        assertWithinTheScreen(
            "the title bar and the input line",
            listOf(
                connectButton,
                hasContentDescription(string(R.string.scripts_open, game.name)) and hasClickAction(),
                hasContentDescription(string(R.string.app_map_show)) and hasClickAction(),
                hasContentDescription(string(R.string.game_menu)) and hasClickAction(),
                history,
                hasContentDescription(string(R.string.input_send)) and hasClickAction(),
            ),
        )
        val title = compose.onNode(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNode().boundsInRoot
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("$locale: the game title, ${title.width} px wide, is at least 48 dp ($minimum px)", title.width >= minimum - SLACK_PX)
        assertTextFits("the game screen with the Connect button")

        session.status("connection", "error: $REASON")
        waitFor(bannerWith(string(R.string.connection_try_again_to, game.name)))
        assertTextFits("the banner, failed")

        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(CONNECTION_BANNER_TAG)).fetchSemanticsNodes().isEmpty() }
        session.status("connection", "disconnected")
        waitFor(bannerWith(string(R.string.connection_stop_to, game.name)))
        waitFor(bannerWith(string(R.string.connection_reconnect_now_to, game.name)))
        assertTextFits("the banner, reconnecting")

        compose.openGameMenu()
        val items = listOf(
            string(R.string.connection_reconnect_to, game.name),
            string(R.string.connection_disconnect_from, game.name),
            string(R.string.settings_title),
        )
        for (item in items) waitFor(hasText(item) and hasClickAction())
        assertTextFits("the menu, reconnecting", window = hasText(items.first()))
    }

    private fun bannerWith(description: String) = hasContentDescription(description) and hasClickAction() and hasAnyAncestor(hasTestTag(CONNECTION_BANNER_TAG))

    private fun assertWithinTheScreen(where: String, matchers: List<SemanticsMatcher>) {
        val width = compose.onRoot().fetchSemanticsNode().size.width
        for (matcher in matchers) {
            val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
            assertTrue("$locale, $where: $bounds is outside the screen's width $width", bounds.left >= -SLACK_PX && bounds.right <= width + SLACK_PX && bounds.width > 0f)
        }
    }

    // Every text in [window]'s root, or the screen's, is whole and within it
    private fun assertTextFits(where: String, window: SemanticsMatcher? = null) {
        // A menu is a window, and a root, of its own
        val rootMatcher = if (window == null) isRoot() else isRoot() and hasAnyDescendant(window)
        compose.onNode(rootMatcher).tryPerformAccessibilityChecks()
        val root = compose.onNode(rootMatcher, useUnmergedTree = true).fetchSemanticsNode()
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

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    companion object {
        private const val INSTANCE = "fake-title-pseudo"
        private const val REASON = "connection refused"
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
