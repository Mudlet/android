package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The game's text at its smallest size, 6, in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens:
 * the console fits as many characters on a line as the game is told, and the Appearance page's text size setting is not
 * cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class TextSizePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val console = hasScrollToIndexAction()
    private var nextSeq = 1L

    @Before
    fun smallestSize() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { Settings.textSize.set(SMALLEST_SIZE) }
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
    }

    // The setting is stored, so it would outlast the test
    @After
    fun resetTextSize() = compose.runOnIdle { Settings.textSize.set(Settings.textSize.default) }

    @Test
    fun theConsoleAndTheSettingFit() {
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        line("size")
        assertEquals("$locale: the console's text", SMALLEST_SIZE.sp, layout("size").layoutInput.style.fontSize)
        val columns = settledColumns()
        val full = "x".repeat(columns)
        val over = "y".repeat(columns + 1)
        line(full)
        line(over)
        assertEquals("$locale: $columns characters on one line", 1, layout(full).lineCount)
        assertEquals("$locale: ${columns + 1} characters wrap", 2, layout(over).lineCount)

        compose.pickFromGameMenu(string(R.string.settings_title))
        val page = hasText(string(R.string.settings_appearance)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        val slider = hasContentDescription(string(R.string.settings_text_size)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        waitFor(slider)
        compose.onNode(slider).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        val texts = listOf(R.string.settings_text_size, R.string.settings_text_size_hint, R.string.settings_text_size_sample).map { string(it) } +
            compose.activity.resources.getQuantityString(R.plurals.settings_text_size_value, SMALLEST_SIZE, SMALLEST_SIZE)
        for (text in texts) {
            val node = compose.onNode(hasText(text), useUnmergedTree = true).performScrollTo()
            val layouts = ArrayList<TextLayoutResult>()
            node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    private fun line(text: String) {
        session.line(nextSeq++, text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(console).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The console's columns once the screen size has held for a second. */
    private fun settledColumns(): Int {
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
        return last!!.first
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        private val GAME = Profile("TextSize", "127.0.0.1", 4000, false, "", false)
        private const val SMALLEST_SIZE = 6
        private const val WAIT_MS = 10_000L

        // Well past the console's own wait before it sends a size
        private const val SETTLE_MS = 1_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
