package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * Selecting lines of the game's text (LineSelectTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: each of the app's items in Android's floating text toolbar there, in the toolbar or its overflow, in
 * full and in the screen, Copy and Share in the locale's own order, from the right in ar-XB; TalkBack's actions on the
 * selected line with the same labels; and the screen passing the accessibility checks.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SelectLinesPseudoLocaleTest(private val locale: String, private val helper: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private val game = Profile("select pseudo ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        LINES.forEachIndexed { i, line -> session.line(i + 1L, line) }
        session.synced(LINES.size.toLong())
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(LINES.last()))
    }

    @Test
    fun theToolbarsItemsAreThereInFullInTheLocalesOrderAndTalkBacksActionsMatchThem() {
        compose.onNode(hasText(LINES.first())).performTouchInput { longClick(center) }
        val copy = string(R.string.console_selection_copy)
        val share = string(R.string.console_selection_share)
        val rtl = compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        assertEquals("$locale: the toolbar's order", if (rtl) listOf(share, copy) else listOf(copy, share), FloatingToolbar.shown(copy, share))
        checkAccessibility()

        val width = compose.activity.resources.displayMetrics.widthPixels
        val items = listOf(R.string.console_selection_select_all, R.string.console_selection_to_command_line, R.string.console_selection_trigger).map(::string)
        for (item in listOf(copy, share) + items) {
            if (!(if (helper == "before") FloatingToolbar.offersBefore(item) else FloatingToolbar.offers(item))) throw AssertionError("$locale: no \"$item\" in the toolbar; ${FloatingToolbar.describe()}")
        }
        for (item in listOf(copy, share)) {
            val at = FloatingToolbar.bounds(item)
            assertTrue("$locale: \"$item\" at $at is out of the screen", at.left >= 0 && at.right <= width)
        }

        val actions = actionsOf(LINES.first())
        for (item in listOf(copy, share) + items + string(R.string.console_selection_stop)) {
            assertTrue("$locale: no TalkBack action \"$item\" in $actions", actions.contains(item))
        }
    }

    private fun actionsOf(line: String) = compose.onNode(hasText(line)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // Each window's: a menu is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        checkAccessibility()
        compose.onNode(node).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private const val INSTANCE = "fake-select-pseudo"
        private val LINES = listOf("You hit the rat.", "A bat bites you.", "The rat bites you.")
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun locales() = listOf("before", "after").flatMap { helper -> listOf("en-XA", "ar-XB").map { arrayOf(it, helper) } }
    }
}
