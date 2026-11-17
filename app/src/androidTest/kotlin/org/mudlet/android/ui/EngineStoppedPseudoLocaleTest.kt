package org.mudlet.android.ui

import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R

/**
 * The explanation of a stop in the background (EngineStoppedExplanationTest) in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: none of its text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class EngineStoppedPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val stopped = EngineStoppedRule(fake)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = stopped.chain(compose, AppLocaleRule(locale))

    @Before
    fun attach() {
        compose.enableAccessibilityChecksOfWhatShows()
        stopped.attach()
    }

    @Test
    fun theTextFits() {
        stopped.restartEngine()
        stopped.waitForExplanation(compose, true)
        compose.onNode(isDialog()).tryPerformAccessibilityChecks()
        for (id in listOf(R.string.engine_stopped_title, R.string.engine_stopped_text, R.string.engine_stopped_battery_settings, R.string.engine_stopped_not_now)) {
            val text = compose.string(id)
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
