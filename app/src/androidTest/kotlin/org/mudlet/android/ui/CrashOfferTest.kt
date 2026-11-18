package org.mudlet.android.ui

import android.content.Context
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
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
 * The offer MainActivity makes when a new engine restores the games after the one before it crashed: once for each
 * crash, whether it is taken, declined or dismissed, and Report opens Report a problem, which sends nothing by itself.
 */
@RunWith(AndroidJUnit4::class)
class CrashOfferTest {
    private val fake = FakeEngineRule()
    private val crash = CrashOfferRule(fake)
    private val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule
    val rules: RuleChain = crash.chain(compose)

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        crash.attach()
    }

    @Test
    fun offeredOnceForEachCrash() {
        crash.restartEngine(crashedAtMs = CRASH_MS)
        crash.waitForOffer(compose, true)

        // Back: that crash is not offered again, by the same engine attached again or a new Activity
        device.pressBack()
        crash.waitForOffer(compose, false)
        assertEquals("the crash offered is kept", CRASH_MS, crash.offeredMs())
        crash.reattach()
        crash.assertStaysHidden(compose, "the same engine attached again")
        compose.activityRule.scenario.recreate()
        crash.restartEngine(crashedAtMs = CRASH_MS)
        crash.assertStaysHidden(compose, "the same crash, after a new Activity")

        // A new crash is offered, even one the clock puts earlier
        crash.restartEngine(crashedAtMs = CRASH_MS - 1)
        crash.waitForOffer(compose, true)
        compose.onNodeWithText(compose.string(R.string.crash_offer_not_now)).performClick()
        crash.waitForOffer(compose, false)
        assertEquals("Not now is kept", CRASH_MS - 1, crash.offeredMs())
    }

    @Test
    fun notOfferedWhenTheEngineDidNotCrash() {
        crash.restartEngine(crashedAtMs = null)
        crash.assertStaysHidden(compose, "an engine that ended otherwise")
    }

    @Test
    fun reportOpensReportAProblem() {
        crash.restartEngine(crashedAtMs = CRASH_MS)
        crash.waitForOffer(compose, true)
        compose.onNodeWithText(compose.string(R.string.crash_offer_report)).performClick()
        compose.waitUntil(UI_MS) {
            compose.onAllNodesWithText(compose.string(R.string.report_title)).fetchSemanticsNodes().isNotEmpty()
        }
        crash.waitForOffer(compose, false)
        assertEquals("Report is kept", CRASH_MS, crash.offeredMs())
    }

    private companion object {
        const val CRASH_MS = 1_700_000_000_000L
    }
}

/**
 * The fake engine's side of the offer's tests, on [EngineStoppedRule]'s new engines with Android never the cause: the
 * crash offered forgotten before the Activity reads it, and the hook that says when the engine before crashed.
 */
internal class CrashOfferRule(fake: FakeEngineRule) : ExternalResource() {
    @Volatile private var crashedAtMs: Long? = null

    private val engine = EngineStoppedRule(fake).apply { androidStopped = false }
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** [EngineStoppedRule.chain] with this inside it, around [inner] and [compose]. */
    fun chain(compose: TestRule, inner: TestRule = RuleChain.emptyRuleChain()): RuleChain = engine.chain(compose, RuleChain.outerRule(this).around(inner))

    // Inside FakeEngineRule, which puts back what the hook was
    override fun before() {
        forgetOffer()
        EngineClient.lastEngineCrashMs = { crashedAtMs }
    }

    // EngineClient lives as long as the test process: an offer left showing would cover every later test's screen
    override fun after() {
        forgetOffer()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { EngineClient.engineCrashedAtMs = 0L }
    }

    fun attach() = engine.attach()

    /** A new engine, after the one before crashed at [crashedAtMs] or, if null, ended otherwise, restoring the game. */
    fun restartEngine(crashedAtMs: Long?) {
        this.crashedAtMs = crashedAtMs
        engine.restartEngine()
    }

    fun reattach() = engine.reattach()

    fun waitForOffer(compose: AndroidComposeTestRule<*, MainActivity>, shown: Boolean) = compose.waitUntil(UI_MS) {
        compose.onAllNodesWithText(compose.string(R.string.crash_offer_title)).fetchSemanticsNodes().isNotEmpty() == shown
    }

    fun assertStaysHidden(compose: AndroidComposeTestRule<*, MainActivity>, what: String) {
        // The status is handled on the main thread after it arrives: a short wait, then idle
        Thread.sleep(QUIET_MS)
        compose.waitForIdle()
        assertTrue(what, compose.onAllNodesWithText(compose.string(R.string.crash_offer_title)).fetchSemanticsNodes().isEmpty())
    }

    fun offeredMs() = prefs().getLong(MainActivity.PREF_CRASH_REPORT_OFFERED, 0L)

    private fun forgetOffer() = prefs().edit(commit = true) { remove(MainActivity.PREF_CRASH_REPORT_OFFERED) }

    private fun prefs() = context.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE)
}

private const val UI_MS = 10_000L
private const val QUIET_MS = 1_000L
