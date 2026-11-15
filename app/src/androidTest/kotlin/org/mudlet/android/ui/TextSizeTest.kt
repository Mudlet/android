package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.theme.MudletTheme

/**
 * The console's text size against a fake engine: the setting, reached from the game screen's title bar, and a pinch
 * both change it, and the columns and rows that then fit go to the engine with setScreenSize. At twice the system font
 * size, nothing on the game or settings screen is cut off.
 */
@RunWith(AndroidJUnit4::class)
class TextSizeTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val console = hasScrollToIndexAction()
    private var nextSeq = 1L

    @Before
    fun attach() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        resetTextSize()
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
    }

    // The setting is stored, so it would outlast the test
    @After
    fun resetTextSize() = compose.runOnIdle { Settings.textSize.set(Settings.textSize.default) }

    @Test
    fun theSettingChangesTheTextSizeAndSendsTheScreenSize() {
        openTheGameScreen()
        val before = settledScreenSize()
        assertConsoleFits(before)
        assertEquals(DEFAULT_SIZE.sp, consoleFontSize())

        activate(hasContentDescription(string(R.string.settings_title)))
        val slider = hasContentDescription(string(R.string.settings_text_size)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(28f) }
        activate(hasContentDescription(string(R.string.settings_text_smaller)))
        compose.runOnIdle { assertEquals(27, Settings.textSize.value) }
        compose.onNode(slider).assert(stateDescription(compose.activity.getString(R.string.settings_text_size_value, 27)))
        activate(hasContentDescription(string(R.string.navigate_back)))

        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
        assertEquals(27.sp, consoleFontSize())
        val after = settledScreenSize()
        assertTrue("larger text, fewer columns and rows: $before, then $after", after.first < before.first && after.second < before.second)
        assertConsoleFits(after)
    }

    @Test
    fun aPinchChangesTheSettingAndSendsTheScreenSize() {
        openTheGameScreen()
        val before = settledScreenSize()

        // Fingers 120 px apart moving to 240: twice the size
        compose.onNode(console).performTouchInput {
            pinch(center - Offset(60f, 0f), center - Offset(120f, 0f), center + Offset(60f, 0f), center + Offset(120f, 0f))
        }
        val size = compose.runOnIdle { Settings.textSize.value }
        assertTrue("a pinch outward makes the text larger: $size", size in DEFAULT_SIZE + 4..Settings.textSize.range.last)
        assertEquals(size.sp, consoleFontSize())
        val after = settledScreenSize()
        assertTrue("larger text, fewer columns: $before, then $after", after.first < before.first)
        assertConsoleFits(after)
    }

    @Test
    fun atTwiceTheSystemFontSizeNothingIsCutOff() {
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    MudletTheme { AppNavigation(onKillUi = {}) }
                }
            }
        }
        openTheGameScreen()
        assertEquals(DEFAULT_SIZE.sp, consoleFontSize())
        assertConsoleFits(settledScreenSize())
        assertNothingCutOff()

        activate(hasContentDescription(string(R.string.settings_title)))
        compose.onNodeWithText(string(R.string.settings_text_size_sample)).assertIsDisplayed()
        assertNothingCutOff()
    }

    private fun openTheGameScreen() {
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.game_title))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.game_title)).assertIsDisplayed()
        // A line to measure the console's text by
        line("size")
    }

    private fun line(text: String) {
        session.line(nextSeq++, text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(console).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The screen size once it has held for a second, which the engine has been sent. */
    private fun settledScreenSize(): Pair<Int, Int> {
        var last: Pair<Int, Int>? = null
        var since = SystemClock.elapsedRealtime()
        compose.waitUntil(WAIT_MS) {
            val now = compose.runOnIdle { EngineClient.screenSize }
            if (now != last) {
                last = now
                since = SystemClock.elapsedRealtime()
            }
            now != null && SystemClock.elapsedRealtime() - since >= SETTLE_MS
        }
        val size = last!!
        // Connecting reads past what the UI sent before openProfile, which can be the size it resends on attaching
        val earlier = session.received.lastOrNull { it.optString("t") == "setScreenSize" }
        if (earlier != null && earlier.getInt("columns") to earlier.getInt("rows") == size) return size
        while (true) {
            val sent = session.receive("setScreenSize")
            if (sent.getInt("columns") to sent.getInt("rows") == size) return size
        }
    }

    /** A line as many characters wide as [size] says fits on one line of the console, and one more does not. */
    private fun assertConsoleFits(size: Pair<Int, Int>) {
        val columns = size.first
        val full = "x".repeat(columns)
        val over = "y".repeat(columns + 1)
        line(full)
        line(over)
        assertEquals("$columns characters on one line", 1, layout(full).lineCount)
        assertEquals("${columns + 1} characters wrap", 2, layout(over).lineCount)
    }

    private fun consoleFontSize(): TextUnit = layout("size").layoutInput.style.fontSize

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /**
     * No text is clipped or cut short, and every control is within the window's width. Not hasVisualOverflow: a String
     * Text answers GetTextLayoutResult with its text laid out again at its whole maximum width, so didOverflowWidth
     * holds for any such Text narrower than that maximum. That layout wraps what the real one did not, so text too
     * wide for its space overflows its height there.
     */
    private fun assertNothingCutOff() {
        compose.onRoot().tryPerformAccessibilityChecks()
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes()) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            for (result in results) {
                val widestLine = (0 until result.lineCount).maxOf { result.getLineRight(it) - result.getLineLeft(it) }
                val fits = !result.didOverflowHeight && widestLine <= result.size.width
                assertTrue("'${result.layoutInput.text}' overflows its space ${result.size}: widest line $widestLine, height ${result.multiParagraph.height}", fits)
            }
        }
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val bounds = node.boundsInRoot
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)
            assertTrue("control $label at $bounds within the window's width $width", bounds.left >= 0f && bounds.right <= width)
        }
    }

    private fun stateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val DEFAULT_SIZE = 14
        const val WAIT_MS = 10_000L

        // Well past the console's own wait before it sends a size
        const val SETTLE_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
