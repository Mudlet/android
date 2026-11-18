package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.mudlet.android.R

/**
 * The explanation MainActivity shows when a new engine restores the games after Android stopped the one before it in
 * the background: once for each engine started again so, until Battery settings or Not now answers it, and never when
 * the engine ended otherwise.
 */
@RunWith(AndroidJUnit4::class)
class EngineStoppedExplanationTest {
    private val fake = FakeEngineRule()
    private val stopped = EngineStoppedRule(fake)
    private val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule
    val rules: RuleChain = stopped.chain(compose)

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        stopped.attach()
    }

    @Test
    fun explainedOnceForEachEngineAndroidStopped_untilAnswered() {
        stopped.restartEngine()
        stopped.waitForExplanation(compose, true)

        // Back: gone until the next engine Android stops, so not when the same engine is attached again
        device.pressBack()
        stopped.waitForExplanation(compose, false)
        stopped.reattach()
        stopped.assertStaysHidden(compose, "the same engine attached again")

        stopped.restartEngine()
        stopped.waitForExplanation(compose, true)
        compose.onNodeWithText(compose.string(R.string.engine_stopped_not_now)).performClick()
        stopped.waitForExplanation(compose, false)
        assertTrue("Not now is kept", stopped.answered())

        // A new Activity reads the answer
        compose.activityRule.scenario.recreate()
        stopped.restartEngine()
        stopped.assertStaysHidden(compose, "a later engine, once answered")
    }

    @Test
    fun notExplainedWhenAndroidDidNotStopTheEngine() {
        stopped.androidStopped = false
        stopped.restartEngine()
        stopped.assertStaysHidden(compose, "an engine that crashed or was stopped by the player")
    }

    @Test
    fun batterySettingsOpensTheAppsSettingsPage() {
        stopped.restartEngine()
        stopped.waitForExplanation(compose, true)
        compose.onNodeWithText(compose.string(R.string.engine_stopped_battery_settings)).performClick()
        assertTrue("Android's settings in front", device.wait(Until.hasObject(By.pkg("com.android.settings")), UI_MS))
        assertTrue("Battery settings is kept", stopped.answered())
        device.pressBack()
        assertTrue("the app in front again", device.wait(Until.hasObject(By.pkg(compose.activity.packageName)), UI_MS))
    }
}

/**
 * The fake engine's side of the explanation's tests: the answer forgotten before the Activity reads it, the hook that
 * says whether Android stopped the engine, and new engines restoring a game.
 */
internal class EngineStoppedRule(private val fake: FakeEngineRule) : ExternalResource() {
    @Volatile var androidStopped = true

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: FakeEngine.Session

    /**
     * The rules around [compose]: the fake engine, this, the notification permission, so that its explanation does not
     * cover this one, and [inner].
     */
    fun chain(compose: TestRule, inner: TestRule = RuleChain.emptyRuleChain()): RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS))
        .around(fake).around(this)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(inner)
        .around(compose)

    // Inside FakeEngineRule, which puts back what the hook was
    override fun before() {
        forgetAnswer()
        EngineClient.stoppedByAndroid = { androidStopped }
    }

    // EngineClient lives as long as the test process: an explanation left showing would cover every later test's screen
    override fun after() {
        forgetAnswer()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { EngineClient.engineStoppedInBackground = false }
    }

    fun attach() {
        session = fake.engine.nextSession()
    }

    /** A new engine, as after a kill, restoring the game; each is new to the process's EngineClient. */
    fun restartEngine() {
        fake.engine.instance = "stopped-${System.nanoTime()}"
        reattach()
    }

    /** The UI attached again, to the engine it had or to the one [restartEngine] started, which sends its status. */
    fun reattach() {
        session.close()
        session = fake.engine.nextSession()
        session.engineStatus("restore", "restoring Game")
    }

    fun waitForExplanation(compose: AndroidComposeTestRule<*, MainActivity>, shown: Boolean) = compose.waitUntil(UI_MS) {
        compose.onAllNodesWithText(compose.string(R.string.engine_stopped_title)).fetchSemanticsNodes().isNotEmpty() == shown
    }

    fun assertStaysHidden(compose: AndroidComposeTestRule<*, MainActivity>, what: String) {
        // The status is handled on the main thread after it arrives: a short wait, then idle
        Thread.sleep(QUIET_MS)
        compose.waitForIdle()
        assertTrue(what, compose.onAllNodesWithText(compose.string(R.string.engine_stopped_title)).fetchSemanticsNodes().isEmpty())
    }

    fun answered() = prefs().getBoolean(MainActivity.PREF_ENGINE_STOPPED_ANSWERED, false)

    private fun forgetAnswer() = prefs().edit(commit = true) { remove(MainActivity.PREF_ENGINE_STOPPED_ANSWERED) }

    private fun prefs() = context.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE)
}

internal fun AndroidComposeTestRule<*, MainActivity>.string(@StringRes id: Int): String = activity.getString(id)

private const val TIMEOUT_MS = 120_000L
private const val UI_MS = 10_000L
private const val QUIET_MS = 1_000L
