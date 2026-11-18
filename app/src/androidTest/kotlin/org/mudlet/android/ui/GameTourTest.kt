package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GameTourShown

/**
 * The game screen's tour against a fake engine, as a TalkBack user goes through it: it shows the first time the game
 * screen does and not the next, with desktop Mudlet's steps in its order, each said once and its card away from the
 * control it lights; while it shows TalkBack reads the card alone; Skip and Finish close it, and About's "Take a UI
 * tour" shows it again. It waits while the explanation of an engine Android stopped is up.
 */
@RunWith(AndroidJUnit4::class)
class GameTourTest {
    private val fake = FakeEngineRule(open = listOf(GAME.name))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val steps = listOf(
        R.string.tour_welcome_title to R.string.tour_welcome,
        R.string.tour_console_title to R.string.tour_console,
        R.string.tour_input_title to R.string.tour_input,
        R.string.tour_scripts_title to R.string.tour_scripts,
        R.string.tour_settings_title to R.string.tour_settings,
        R.string.tour_done_title to R.string.tour_done,
    )

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        val session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.sessions.size == 1 }
    }

    @Test
    fun theTourShowsTheFirstTimeTheGameScreenDoesAndNotTheNext_andSkipClosesIt() {
        compose.runOnIdle { GameTourShown.set(compose.activity, false) }
        showGame()
        atStep(0)
        // Marked shown as it starts, as desktop's is
        assertFalse(GameTourShown.due(compose.activity))
        activate(hasText(string(R.string.tour_next)))
        atStep(1)
        activate(hasText(string(R.string.tour_back)))
        atStep(0)
        activate(hasText(string(R.string.tour_next)))
        atStep(1)
        activate(hasText(string(R.string.tour_skip)))
        waitFor(hasText(string(R.string.game_title)))
        assertTrue(none(hasText(string(R.string.tour_skip))))

        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasText(string(R.string.profiles_title)))
        showGame()
        waitFor(hasText(string(R.string.game_title)))
        compose.waitForIdle()
        assertTrue("the tour shows a second time", none(hasText(string(R.string.tour_welcome_title))))
    }

    @Test
    fun aboutsTakeAUiTourShowsTheTourAgain_throughEveryStepToFinish() {
        assertFalse(GameTourShown.due(compose.activity))
        activate(hasContentDescription(string(R.string.about_title)))
        val tour = hasText(string(R.string.help_tour)) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(tour)
        activate(tour)
        var centred = 0f
        for (step in steps.indices) {
            atStep(step)
            // The card goes to the other end of the screen from the control the step lights, and to the middle without one
            val top = bounds(steps[step].first).top
            when (steps[step].first) {
                R.string.tour_welcome_title -> centred = top
                R.string.tour_input_title -> assertTrue("the card is not above the input line: $top, centred $centred", top < centred)
                R.string.tour_scripts_title, R.string.tour_settings_title -> assertTrue("the card is not below the title bar: $top, centred $centred", top > centred)
            }
            if (step < steps.lastIndex) {
                activate(hasText(string(R.string.tour_next)))
            } else {
                assertTrue("Skip on the last step", none(hasText(string(R.string.tour_skip))))
                activate(hasText(string(R.string.tour_finish)))
            }
        }
        waitFor(hasText(string(R.string.game_title)))
        assertTrue(none(hasText(string(R.string.tour_finish))))
        assertFalse(GameTourShown.due(compose.activity))
    }

    @Test
    fun theTourWaitsForTheExplanationOfAnEngineAndroidStopped_untilItIsAnswered() {
        compose.runOnIdle {
            GameTourShown.set(compose.activity, false)
            EngineClient.engineStoppedInBackground = true
        }
        try {
            waitFor(hasText(string(R.string.engine_stopped_title)))
            // Under the explanation's dialog, so without the checks on one root, which the dialog makes two
            val show = SemanticsMatcher("shows a game") { it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.game_show) }
            compose.onNode(show).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasText(string(R.string.game_title)))
            Thread.sleep(QUIET_MS)
            compose.waitForIdle()
            assertTrue("the tour shows under the explanation", none(hasText(string(R.string.tour_welcome_title))))
            assertTrue("the tour is marked shown before it shows", GameTourShown.due(compose.activity))

            compose.onNode(hasText(string(R.string.engine_stopped_not_now)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            atStep(0)
        } finally {
            compose.runOnIdle { EngineClient.engineStoppedInBackground = false }
            compose.activity.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE).edit(commit = true) {
                remove(MainActivity.PREF_ENGINE_STOPPED_ANSWERED)
            }
        }
    }

    private fun showGame() = activate(SemanticsMatcher("shows a game") { it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.game_show) })

    /** The card shows step [index] of the six, TalkBack has been told it, and reaches nothing of the game screen. */
    private fun atStep(index: Int) {
        val (title, body) = steps[index]
        waitFor(hasText(string(title)))
        compose.onNode(hasText(string(body))).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.tour_progress, index + 1, steps.size))).assertIsDisplayed()
        val said = string(R.string.tour_announcement, string(title), string(body))
        compose.waitUntil(WAIT_MS) { Announcer.current?.text?.endsWith(said) == true }
        assertTrue("TalkBack reaches the game screen under the tour", none(hasText(string(R.string.game_title))))
        assertTrue(none(hasContentDescription(string(R.string.scripts_open, GAME.name))))
    }

    private fun bounds(@StringRes id: Int) = compose.onNode(hasText(string(id))).fetchSemanticsNode().boundsInRoot

    private fun none(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { !none(matcher) }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("tour ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Long enough for a tour that would show under the explanation to have come
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
