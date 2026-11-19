package org.mudlet.android.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.update.PtbUpdates
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The notice of a newer test build on the profile list, and the switch that turns it off, as a TalkBack user meets
 * them, against a fake engine. The debug build is the stable app, which never checks, so the test takes the place of a
 * PTB from GitHub and of GitHub's answer (PtbUpdates.applies, foundForTest); PtbUpdateCheckTest has the check itself.
 * The release page's address is written out, so a changed one fails. The browser is not started: the test sees the
 * intent only.
 */
@RunWith(AndroidJUnit4::class)
class PtbUpdateNoticeTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val browser = Browser()

    private val notice get() = hasText(string(R.string.ptb_notice_text))
    private val download get() = hasContentDescription(string(R.string.ptb_notice_download_description)) and hasClickAction()
    private val dismiss get() = hasContentDescription(string(R.string.ptb_notice_dismiss_description)) and hasClickAction()
    private val testBuilds get() = hasText(string(R.string.settings_test_builds)) and hasClickAction()

    /** Every web page the app asks to open, kept from opening */
    private class Browser : Instrumentation.ActivityMonitor() {
        val opened = CopyOnWriteArrayList<String>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW || intent.data?.scheme != "https") return null
            opened += intent.dataString
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    @Before
    fun setUp() {
        compose.enableAccessibilityChecksOfWhatShows()
        instrumentation.addMonitor(browser)
        compose.runOnIdle {
            PtbUpdates.resetForTest(compose.activity)
            Settings.checkTestBuilds.reset()
            PtbUpdates.applies = true
        }
        val session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    // What the test changed is stored, or the app's state, so it would outlast the test
    @After
    fun tearDown() {
        instrumentation.removeMonitor(browser)
        compose.runOnIdle {
            PtbUpdates.applies = false
            PtbUpdates.resetForTest(compose.activity)
            Settings.checkTestBuilds.reset()
        }
    }

    @Test
    fun aNewerBuildIsToldOfAboveTheList_readThenItsButtons_andDownloadOpensTheReleasePage() {
        found(BuildConfig.VERSION_CODE + 1L)
        waitFor(notice)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(notice).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val reading = TalkBackReading(compose)
        reading.assertInOrder(
            listOf(
                "the notice" to notice,
                "its Dismiss button" to dismiss,
                "its Download button" to download,
                "the first game" to hasContentDescription(string(R.string.profile_description, GAME.name, GAME.host, GAME.port)),
            ),
        )
        reading.assertEveryControlRead()

        activate(download)
        compose.waitUntil(WAIT_MS) { browser.opened.isNotEmpty() }
        assertEquals(listOf("https://github.com/Mudlet/android/releases/tag/ptb"), browser.opened.toList())
        compose.onNode(notice).assertIsDisplayed()
    }

    @Test
    fun dismissedItStaysAwayForThatBuild_andComesBackForANewerOne() {
        found(BuildConfig.VERSION_CODE + 1L)
        waitFor(notice)
        activate(dismiss)
        gone(notice)
        found(BuildConfig.VERSION_CODE + 1L)
        gone(notice)
        found(BuildConfig.VERSION_CODE + 2L)
        waitFor(notice)
        assertTrue("a page opened in the browser", browser.opened.isEmpty())
    }

    @Test
    fun theInstalledBuildIsNotToldOf_norAnythingInACopyThatDoesNotCheck() {
        found(BuildConfig.VERSION_CODE.toLong())
        compose.waitForIdle()
        compose.onNode(notice).assertDoesNotExist()

        compose.runOnIdle { PtbUpdates.applies = false }
        found(BuildConfig.VERSION_CODE + 1L)
        compose.waitForIdle()
        compose.onNode(notice).assertDoesNotExist()
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        // Through the whole lazy hub, which composes a row only near the screen; thrown or not, the row is then absent
        runCatching { compose.scrollSettingsHubTo(testBuilds) }
        compose.onNode(testBuilds).assertDoesNotExist()
    }

    @Test
    fun theTestBuildsPagesSwitchTurnsTheNoticeOffAndOn() {
        found(BuildConfig.VERSION_CODE + 1L)
        waitFor(notice)
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(testBuilds)
        activate(testBuilds)
        val row = hasText(string(R.string.settings_check_test_builds)) and hasClickAction()
        waitFor(row)
        compose.onNode(row).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.runOnIdle { assertFalse(Settings.checkTestBuilds.value) }
        backToTheProfiles()
        gone(notice)

        compose.runOnIdle { Settings.checkTestBuilds.set(true) }
        waitFor(notice)
    }

    private fun found(version: Long) = compose.runOnIdle { PtbUpdates.foundForTest(version) }

    // The page and the hub are one screen or two, by the window's width
    private fun backToTheProfiles() {
        repeat(2) {
            if (compose.onAllNodes(hasText(string(R.string.profiles_title))).fetchSemanticsNodes().isEmpty()) {
                activate(hasContentDescription(string(R.string.navigate_back)))
            }
        }
        waitFor(hasText(string(R.string.profiles_title)))
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("PTB notice", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
