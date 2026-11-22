package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R

/**
 * The app's Screen reader settings page, with "Scripts speak through TalkBack", and its title and summary in the settings
 * list, in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: none of their text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ScriptSpeechPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() = compose.enableAccessibilityChecksOfWhatShows()

    @Test
    fun thePageItsSwitchAndItsSummaryFit() {
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(emptyList(), session.receive("listProfiles"))

        activate(hasContentDescription(string(R.string.settings_title)))
        val page = hasText(string(R.string.settings_screen_reader)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        assertFits(string(R.string.settings_screen_reader))
        assertFits(string(R.string.settings_screen_reader_summary))
        activate(page)
        val switch = hasText(string(R.string.settings_script_speech_talkback))
        waitFor(switch)
        compose.onNode(switch, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        listOf(R.string.settings_script_speech_talkback, R.string.settings_script_speech_talkback_hint)
            .map { string(it) }
            .forEach(::assertFits)
    }

    private fun assertFits(text: String) {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).performScrollTo()
        val layouts = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        private const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
