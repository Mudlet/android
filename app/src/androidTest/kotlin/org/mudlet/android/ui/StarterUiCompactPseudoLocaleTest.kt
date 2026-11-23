package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
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
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile

/**
 * The starter UI in a compact main window (StarterUiCompactTest) in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: its bars above the console's text and its chat buttons below it, each button on
 * the screen, clear of the others and touched within 48 dp.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class StarterUiCompactPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private val console get() = hasScrollToIndexAction() and !hasAnyAncestor(hasContentDescription(compose.activity.getString(R.string.window_description, ""), substring = true))

    @Before
    fun showTheStarterUi() {
        compose.enableAccessibilityChecksOfWhatShows()
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        val session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText("the main console"))
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
        // With no main area yet, the console fills the game screen's area
        StarterUiCompact.show(session, compose.onNode(console).fetchSemanticsNode().boundsInRoot, compose.density.density)
        waitFor(hasContentDescription("Channels chat") and hasClickAction())
    }

    @Test
    fun theBarsAreAboveTheTextAndTheChatButtonsBelowItOnTheScreenAndApart() {
        compose.onRoot().tryPerformAccessibilityChecks()
        val dp = compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val text = compose.onNode(console).fetchSemanticsNode().boundsInRoot
        for ((_, name) in StarterUiCompact.GAUGES) {
            val bar = compose.onNode(hasContentDescription(name)).fetchSemanticsNode().boundsInRoot
            assertTrue("$locale: the $name bar $bar is not above the text $text", bar.bottom <= text.top + 1)
        }
        val drawn = listOf("All chat", "Tells chat, 2 new", "Channels chat").map { name ->
            val node = compose.onNode(hasContentDescription(name) and hasClickAction()).fetchSemanticsNode()
            val touch = node.touchBoundsInRoot
            assertTrue("$locale: $name is touched within $touch", touch.width >= 48 * dp - 1 && touch.height >= 48 * dp - 1)
            assertTrue("$locale: $name, touched within $touch, is off the screen $screen", touch.left >= screen.left - 1 && touch.right <= screen.right + 1 && touch.bottom <= screen.bottom + 1)
            assertTrue("$locale: $name ${node.boundsInRoot} is not below the text $text", node.boundsInRoot.top >= text.bottom - 1)
            name to node.boundsInRoot
        }
        for ((i, a) in drawn.withIndex()) {
            for (b in drawn.drop(i + 1)) assertFalse("$locale: ${a.first} ${a.second} overlaps ${b.first} ${b.second}", a.second.overlaps(b.second))
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-starter-compact-pseudo"

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
