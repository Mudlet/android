package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_PILL_TAG
import org.mudlet.android.ui.game.QUICK_BAR_TAG
import org.mudlet.android.ui.game.switchHaptics

/**
 * Three games open against a fake engine, switched by a swipe on the game pill and by Ctrl+Tab (docs/multi-profile.md,
 * "The switcher"), in [locale], or the device's language when null.
 */
abstract class GameSwipeTestBase(locale: String?) {
    private val fake = FakeEngineRule(open = listOf(ALPHA.name, BETA.name, GAMMA.name))
    protected val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(if (locale != null && Build.VERSION.SDK_INT >= 33) AppLocaleRule(locale) else RuleChain.emptyRuleChain())
        .around(compose)

    // The one opened last, which the UI's control socket is answered on
    protected lateinit var gamma: FakeEngine.Session

    // Main thread only
    private var tickCount = 0

    protected val pill = hasTestTag(GAME_PILL_TAG)
    protected val input = hasSetTextAction()

    @Before
    fun attach() {
        switchHaptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                tickCount++
            }
        }
        gamma = fake.engine.nextSession()
        gamma.profiles(PROFILES, gamma.receive("listProfiles"))
        val sessions = listOf(gamma.forProfile(ALPHA.name), gamma.forProfile(BETA.name), gamma)
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 3 && EngineClient.sessions.all { it.linkState.startsWith("attached") } }
        for ((i, session) in sessions.withIndex()) {
            session.clear()
            session.line(1, "${PROFILES[i].name} says hello")
            session.synced(1)
            session.status("connection", "connected")
        }
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.all { it.status["connection"] == "connected" } }
    }

    @After
    fun forgetHaptics() {
        switchHaptics = null
    }

    /** Shows the profile [name] from the profile list's open games, and takes the engine's setFront for it. */
    protected fun show(name: String) {
        waitFor(hasText(string(R.string.games_title)))
        val row = hasContentDescription(string(R.string.game_state, name, string(R.string.connection_connected))) and hasClickAction()
        waitFor(row)
        compose.onNode(row).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(name, gamma.receive("setFront").getString("name"))
        waitFor(pillReading(name))
    }

    /**
     * A drag along [share] of the pill's width, for [ms], toward the start of the line (left to right: the left) when
     * [towardStart], else toward its end; [rtl] says which side the start is.
     */
    protected fun swipePill(towardStart: Boolean, rtl: Boolean, share: Float = 0.9f, ms: Long = 200) {
        val leftward = towardStart != rtl
        compose.onNode(pill).performTouchInput {
            val half = width * share / 2
            val from = Offset(centerX + if (leftward) half else -half, centerY)
            val to = Offset(centerX + if (leftward) -half else half, centerY)
            swipe(from, to, ms)
        }
        // The slide out runs on the test's clock, which waiting for the engine's request does not move on
        compose.waitForIdle()
    }

    /** The engine is asked to bring [name] to the front, and the pill reads it. */
    protected fun switchedTo(name: String) {
        assertEquals(name, gamma.receive("setFront").getString("name"))
        waitFor(pillReading(name))
    }

    /** No game is brought to the front: the engine is not asked, and the pill still reads [name]. */
    protected fun stillOn(name: String) {
        gamma.assertNoRequest("setFront", QUIET_MS)
        compose.onNode(pillReading(name)).assertIsDisplayed()
    }

    protected fun ticks() = compose.runOnIdle { tickCount }

    private fun pillReading(name: String): SemanticsMatcher {
        val index = PROFILES.indexOfFirst { it.name == name } + 1
        return pill and hasContentDescription(string(R.string.game_pill_description, name, string(R.string.connection_connected), index, 3))
    }

    protected fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    protected fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    protected companion object {
        val ALPHA = Profile("Alpha", "127.0.0.1", 4001, false, "", false)
        val BETA = Profile("Beta", "127.0.0.1", 4002, false, "", false)
        val GAMMA = Profile("Gamma", "127.0.0.1", 4003, false, "", false)
        val PROFILES = listOf(ALPHA, BETA, GAMMA)

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L

        // Long enough for a request that went astray to have arrived
        const val QUIET_MS = 500
    }
}

/** Left to right: the next game is the one a swipe toward the left brings. */
@RunWith(AndroidJUnit4::class)
class GameSwipeTest : GameSwipeTestBase(locale = null) {
    @Test
    fun aSwipeOnThePillBringsTheGameBeside_withATick_andGoesNoFurtherThanTheEnds() {
        show(ALPHA.name)
        // No game before the first
        swipePill(towardStart = false, rtl = false)
        stillOn(ALPHA.name)
        assertEquals(0, ticks())

        swipePill(towardStart = true, rtl = false)
        switchedTo(BETA.name)
        assertEquals(1, ticks())

        // Short of the way and slow: it springs back
        swipePill(towardStart = true, rtl = false, share = SHORT, ms = 1500)
        stillOn(BETA.name)
        // As short, but flung
        swipePill(towardStart = true, rtl = false, share = SHORT, ms = 40)
        switchedTo(GAMMA.name)
        assertEquals(2, ticks())

        // No game after the last
        swipePill(towardStart = true, rtl = false)
        stillOn(GAMMA.name)

        swipePill(towardStart = false, rtl = false)
        switchedTo(BETA.name)
        assertEquals(3, ticks())
        // The game swiped to has the console
        compose.onNode(hasText("${BETA.name} says hello")).assertIsDisplayed()
    }

    @Test
    fun ctrlTabBringsTheNextGameAndCtrlShiftTabThePrevious_withoutGoingRound() {
        show(ALPHA.name)
        ctrlTab()
        switchedTo(BETA.name)
        ctrlTab()
        switchedTo(GAMMA.name)
        ctrlTab()
        stillOn(GAMMA.name)
        ctrlTab(shift = true)
        switchedTo(BETA.name)
        ctrlTab(shift = true)
        switchedTo(ALPHA.name)
        ctrlTab(shift = true)
        stillOn(ALPHA.name)
        // A keyboard's switch gives no tick
        assertEquals(0, ticks())
    }

    @Test
    fun aSidewaysSwipeOnTheConsoleOrTheQuickBarSwitchesNothing() {
        show(BETA.name)
        compose.onNode(hasText("${BETA.name} says hello")).performTouchInput { swipeLeft() }
        compose.onNode(hasText("${BETA.name} says hello")).performTouchInput { swipeRight() }
        stillOn(BETA.name)
        compose.onNode(hasTestTag(QUICK_BAR_TAG)).performTouchInput { swipeLeft() }
        compose.onNode(hasTestTag(QUICK_BAR_TAG)).performTouchInput { swipeRight() }
        stillOn(BETA.name)
    }

    // On the input line of the game in front, as a keyboard's keys go to the focused field
    private fun ctrlTab(shift: Boolean = false) {
        compose.onNode(input).requestFocus()
        compose.onNode(input).performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                if (shift) withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } else pressKey(Key.Tab)
            }
        }
    }

    private companion object {
        // Short of GameSwipe.COMMIT, with the touch slop taken off
        const val SHORT = 0.35f
    }
}

/** Right to left (ar-XB): the next game is the one a swipe toward the right, the line's start, brings. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class GameSwipeRightToLeftTest : GameSwipeTestBase(locale = "ar-XB") {
    @Test
    fun theSwipeIsMirrored() {
        show(ALPHA.name)
        swipePill(towardStart = false, rtl = true)
        stillOn(ALPHA.name)
        swipePill(towardStart = true, rtl = true)
        switchedTo(BETA.name)
        swipePill(towardStart = false, rtl = true)
        switchedTo(ALPHA.name)
        assertEquals(2, ticks())
    }
}
