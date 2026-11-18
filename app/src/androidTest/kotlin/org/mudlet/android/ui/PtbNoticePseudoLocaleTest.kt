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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.update.PtbUpdates

/**
 * The notice of a newer test build and the "Test builds" page's switch (PtbUpdateNoticeTest) in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: none of their text is cut short, and Download ends the
 * notice's row in the layout's direction.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PtbNoticePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle {
            PtbUpdates.resetForTest(compose.activity)
            Settings.checkTestBuilds.reset()
            PtbUpdates.applies = true
            PtbUpdates.foundForTest(BuildConfig.VERSION_CODE + 1L)
        }
        val session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @After
    fun tearDown() = compose.runOnIdle {
        PtbUpdates.applies = false
        PtbUpdates.resetForTest(compose.activity)
    }

    @Test
    fun theNoticesTextFits_andDownloadEndsItsRow() {
        waitFor(hasText(string(R.string.ptb_notice_text)))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.ptb_notice_text, R.string.ptb_notice_dismiss, R.string.ptb_notice_download)
        val dismiss = compose.onNode(hasText(string(R.string.ptb_notice_dismiss)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val download = compose.onNode(hasText(string(R.string.ptb_notice_download)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        if (locale == "ar-XB") {
            assertTrue("$locale: Download is not at the row's end", download.right <= dismiss.left)
        } else {
            assertTrue("$locale: Download is not at the row's end", download.left >= dismiss.right)
        }
    }

    @Test
    fun theSwitchsTextFits() {
        activate(hasContentDescription(string(R.string.settings_title)))
        val page = hasText(string(R.string.settings_test_builds)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        assertFits(R.string.settings_test_builds, R.string.settings_test_builds_summary)
        activate(page)
        val row = hasText(string(R.string.settings_check_test_builds)) and hasClickAction()
        waitFor(row)
        compose.onNode(row).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.settings_check_test_builds, R.string.settings_check_test_builds_hint)
    }

    private fun assertFits(@StringRes vararg ids: Int) {
        for (id in ids) {
            val text = string(id)
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        private val GAME = Profile("Notice", "127.0.0.1", 4000, false, "", false)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
