package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.RepositoryFetch
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RUN_ITALIC
import org.mudlet.android.protocol.RUN_REVERSE
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.RUN_UNDERLINE
import org.mudlet.android.ui.game.GameTourShown
import java.io.IOException

/**
 * The self-test screen (debug builds), opened from the start screen, against a fake engine's message stream: how it
 * draws lines and their runs, and how it reattaches when the engine goes away. What the real engine sends is checked by native/tests/android_smoke.py.
 */
@RunWith(AndroidJUnit4::class)
class SelfTestScreenTest {
    // The screen shows the front profile's console
    private val fake = FakeEngineRule(open = listOf("Self-test"))
    private val compose = createAndroidComposeRule<MainActivity>()

    // The fake engine first, so the Activity attaches to it; the permission (Android 13 and later) so that its dialog
    // does not cover the screen
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun openTheSelfTest() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        compose.onNodeWithText(compose.activity.getString(R.string.selftest_title)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.selftest_connect)).assertExists()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun drawsLinesWithTheColoursAndStylesOfTheirRuns() {
        val s = fake.engine.nextSession()
        s.clear()
        s.line(1, RED_GREEN, listOf(listOf(0, 8, 1, -1, RUN_BOLD), listOf(8, 8, -1, -1, 0), listOf(16, 5, 2, -1, 0)))
        s.line(
            2,
            STYLES,
            listOf(
                listOf(0, 3, RUN_RGB or 0x3366FF, -1, 0),
                listOf(4, 7, 3, -1, RUN_REVERSE),
                listOf(12, 5, -1, -1, RUN_UNDERLINE or RUN_ITALIC),
            ),
        )
        s.tail("HP:100> ")
        s.synced(2)
        waitForText("HP:100> ")

        val redGreen = shown(RED_GREEN)
        assertEquals(SpanStyle(color = ANSI_RED, fontWeight = FontWeight.Bold), styleAt(redGreen, 0))
        assertEquals(SpanStyle(color = DEFAULT_FG), styleAt(redGreen, 10))
        assertEquals(SpanStyle(color = ANSI_GREEN), styleAt(redGreen, 16))

        val styles = shown(STYLES)
        assertEquals(SpanStyle(color = Color(0xFF3366FF)), styleAt(styles, 0))
        // Reverse with no background: the foreground colour behind black text
        assertEquals(SpanStyle(color = Color.Black, background = ANSI_YELLOW), styleAt(styles, 4))
        assertEquals(
            SpanStyle(color = DEFAULT_FG, fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline),
            styleAt(styles, 12),
        )

        // And the device draws them: red and green glyphs, and the yellow background of the reversed run
        val red = compose.onNodeWithText(RED_GREEN).captureToImage().toPixelMap()
        assertTrue("red pixels of 'Red bold'", count(red) { r, g, b -> r > 0x80 && g < 0x30 && b < 0x30 } >= 20)
        assertTrue("green pixels of 'green'", count(red) { r, g, b -> g > 0x80 && r < 0x30 && b < 0x30 } >= 20)
        val reversed = compose.onNodeWithText(STYLES).captureToImage().toPixelMap()
        assertTrue("the yellow background of 'reverse'", count(reversed) { r, g, b -> r > 0xA0 && g > 0xA0 && b < 0x30 } >= 100)
    }

    @Test
    fun reattachesToANewEngineAfterLosingOne() {
        val first = fake.engine.nextSession()
        first.clear()
        first.line(1, "before the engine went away")
        first.synced(1)
        waitForText("before the engine went away")
        val losses = EngineClient.engineLosses

        // Killed: the UI reconnects at once and says what it already has, but this is a new engine
        fake.engine.instance = "second"
        first.close()
        val second = fake.engine.nextSession()
        assertEquals(FakeEngine.FIRST_INSTANCE, second.hello.optString("instance"))
        assertEquals(1L, second.hello.optLong("lastSeq"))
        second.line(1, "from the new engine")
        second.engineStatus("restore", "reconnected 127.0.0.1:4000")
        second.synced(1)
        waitForText("--- Reconnected to 127.0.0.1:4000 after the engine restarted ---")
        compose.onNodeWithText("--- new engine instance second ---").assertExists()
        compose.onNodeWithText("from the new engine").assertExists()
        compose.onNodeWithText("before the engine went away").assertExists()
        compose.runOnIdle {
            assertEquals(losses + 1, EngineClient.engineLosses)
            assertTrue(EngineClient.linkState, EngineClient.linkState.contains("new engine instance"))
        }

        // The same engine again (only the connection was lost): no marker, and only what the UI lacks is sent
        second.close()
        val third = fake.engine.nextSession()
        assertEquals("second", third.hello.optString("instance"))
        assertEquals(1L, third.hello.optLong("lastSeq"))
        third.line(2, "after a reattach to the same engine")
        third.synced(2)
        waitForText("after a reattach to the same engine")
        assertEquals(1, compose.onAllNodesWithText("--- new engine instance second ---").fetchSemanticsNodes().size)
        compose.runOnIdle { assertTrue(EngineClient.linkState, EngineClient.linkState.contains("same engine")) }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shown(text: String): AnnotatedString = compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()

    /** Every span style that covers [index], merged as Compose draws them. */
    private fun styleAt(text: AnnotatedString, index: Int): SpanStyle = text.spanStyles.filter { index >= it.start && index < it.end }
        .fold(SpanStyle()) { merged, range -> merged.merge(range.item) }

    private fun count(pixels: androidx.compose.ui.graphics.PixelMap, pred: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (pred((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())) n++
            }
        }
        return n
    }

    private companion object {
        const val RED_GREEN = "Red bold normal green"
        const val STYLES = "rgb reverse under"

        // AnsiText.kt's palette and default foreground
        val ANSI_RED = Color(0xFFCD0000)
        val ANSI_GREEN = Color(0xFF00CD00)
        val ANSI_YELLOW = Color(0xFFCDCD00)
        val DEFAULT_FG = Color(0xFFC0C0C0)

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}

/**
 * A [FakeEngine] that EngineClient attaches to instead of the engine, which is never started, with the profiles [open]
 * open from the start. The movement pad is turned off: on, as it is from the start, its Swipe strip takes 96 dp of the
 * game's text, which the tests that are not about it were laid out without. One that is turns it on again (MovementPadTest).
 */
class FakeEngineRule(private val open: List<String> = emptyList()) : ExternalResource() {
    lateinit var engine: FakeEngine
    private lateinit var previous: (String) -> org.mudlet.android.protocol.Transport
    private lateinit var previousStoppedByAndroid: () -> Boolean
    private lateinit var previousLastEngineCrashMs: () -> Long?
    private lateinit var previousFetch: RepositoryFetch

    override fun before() {
        EngineClient.init(InstrumentationRegistry.getInstrumentation().targetContext)
        // So MainActivity does not start the real engine
        EngineClient.engineWanted = false
        // A test of the game screen sees it as a returning player does; GameTourTest shows the tour itself
        GameTourShown.set(InstrumentationRegistry.getInstrumentation().targetContext, true)
        engine = FakeEngine(open)
        previous = EngineClient.connector
        EngineClient.connector = engine::connect
        // A test's restore status is not Android stopping the engine, nor a crash, unless the test says so
        previousStoppedByAndroid = EngineClient.stoppedByAndroid
        EngineClient.stoppedByAndroid = { false }
        previousLastEngineCrashMs = EngineClient.lastEngineCrashMs
        EngineClient.lastEngineCrashMs = { null }
        // No test reaches GitHub: one that shows the package repository gives its own answers
        previousFetch = PackageRepositoryClient.fetch
        PackageRepositoryClient.fetch = RepositoryFetch { _, _, _, _ -> throw IOException("no network in the tests") }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // Loaded first: MainActivity's own init would otherwise read the stored value over this one
            Settings.init(instrumentation.targetContext)
            Settings.movementPad.set(false)
            // A design test that stopped short leaves its design stored, which the pad's other tests do not expect
            Settings.movementPadDesign.reset()
            Settings.movementPadSide.reset()
            // As a player who has seen every callout; FeatureCalloutTest shows them itself
            FeatureCallouts.reset(instrumentation.targetContext, dismissed = true)
        }
    }

    override fun after() {
        EngineClient.connector = previous
        EngineClient.stoppedByAndroid = previousStoppedByAndroid
        EngineClient.lastEngineCrashMs = previousLastEngineCrashMs
        PackageRepositoryClient.fetch = previousFetch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Settings.movementPad.reset()
            Settings.movementPadDesign.reset()
            Settings.movementPadSide.reset()
        }
        engine.close()
    }
}
