package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
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

/**
 * The game screen's title bar menu in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: each
 * item's text is whole and within the menu.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class GameMenuPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("menu pseudo ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
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
    fun eachItemFits() {
        compose.openGameMenu()
        val items = listOf(string(R.string.console_search), string(R.string.packages_open, game.name))
        for (item in items) waitFor(hasText(item) and hasClickAction())
        // The open menu is a window, and a root, of its own
        val root = isRoot() and hasAnyDescendant(hasText(items.first()))
        compose.onNode(root).tryPerformAccessibilityChecks()
        val width = compose.onNode(root, useUnmergedTree = true).fetchSemanticsNode().size.width
        for (item in items) {
            val node = compose.onNode(hasText(item), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$item\"")
            assertFalse("$locale: \"$item\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$item\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            assertFalse("$locale: \"$item\" is ${layout.multiParagraph.height} px tall in its ${layout.size} box", layout.multiParagraph.height > layout.size.height + SLACK_PX)
            val bounds = node.boundsInRoot
            assertTrue("$locale: \"$item\" at $bounds is outside its menu's width $width", bounds.left >= -SLACK_PX && bounds.right <= width + SLACK_PX)
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    companion object {
        private const val INSTANCE = "fake-menu-pseudo"
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
