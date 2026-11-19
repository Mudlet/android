package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.game.MOVEMENT_PAD_TAG

/**
 * The game screen's movement pad (MovementPadTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the
 * screens: west stays left of east where the language is written from the right, with the pad at the row's end; each
 * key has a name of its own; and what is said once the pad is put away, with its Undo, passes the accessibility checks
 * and is shown whole.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MovementPadPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val pad = hasTestTag(MOVEMENT_PAD_TAG)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle {
            HardwareKeyboard.attachedOverride = false
            // The fake engine's rule turns the pad off for the tests that are not about it
            Settings.movementPad.reset()
        }
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(pad)
    }

    // FakeEngineRule puts the setting back
    @After
    fun reset() = compose.runOnIdle { HardwareKeyboard.attachedOverride = null }

    @Test
    fun thePadIsLaidOutAsOnAMapAndPuttingItAwaySaysSo() {
        compose.onRoot().tryPerformAccessibilityChecks()
        val names = KEYS.map { compose.string(it) }
        assertEquals("$locale: each key has a name of its own: $names", names.size, names.toSet().size)

        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        assertTrue("$locale: west $west is left of east $east", west.right <= east.left && west.top == east.top)
        val hide = bounds(key(R.string.movement_pad_hide))
        val rightToLeft = locale == "ar-XB"
        assertTrue("$locale: the pad is at the row's end, the button that hides it $hide at its start", if (rightToLeft) hide.left >= east.right else hide.right <= west.left)

        activate(key(R.string.movement_pad_hide))
        waitFor(hasText(compose.string(R.string.movement_pad_hidden)))
        waitFor(hasText(compose.string(R.string.movement_pad_undo)))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.movement_pad_hidden)
        assertFits(R.string.movement_pad_undo)
    }

    // The pad's switch on the Appearance page is in PseudoLocaleTest's walk, which checks every text of that page
    private fun assertFits(@StringRes id: Int) {
        val text = compose.string(id)
        val layouts = ArrayList<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun key(@StringRes name: Int) = hasContentDescription(compose.string(name)) and hasClickAction()

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        val GAME = Profile("Walk", "127.0.0.1", 4000, false, "", false)

        val KEYS = listOf(
            R.string.movement_north,
            R.string.movement_northeast,
            R.string.movement_east,
            R.string.movement_southeast,
            R.string.movement_south,
            R.string.movement_southwest,
            R.string.movement_west,
            R.string.movement_northwest,
            R.string.movement_up,
            R.string.movement_down,
            R.string.movement_in,
            R.string.movement_out,
            R.string.movement_pad_hide,
        )

        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
