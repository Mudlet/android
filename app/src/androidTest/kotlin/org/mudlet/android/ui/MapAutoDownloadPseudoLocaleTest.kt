package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.areas

/**
 * The game screen's notice of the map the app downloads by itself, in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: none of its text is cut short, running with Cancel nor failed.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MapAutoDownloadPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake).around(AppLocaleRule(locale))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private lateinit var session: FakeEngine.Session

    // A profile of its own, which no earlier test decided
    private val game = Profile("Auto map ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle { Settings.mapBesideText.set(false) }
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText("the main console"))
    }

    @After
    fun defaults() {
        compose.runOnIdle { Settings.mapBesideText.reset() }
        AppMapClient.forgetAutoDownload(InstrumentationRegistry.getInstrumentation().targetContext, game.name)
    }

    @Test
    fun theTextFits() {
        session.send(mapDownload("none"))
        session.send(areas(session.receive("mapAreas")))
        session.receive("downloadMap")
        session.send(mapDownload("downloading").put("cancelable", true))
        val running = compose.activity.getString(R.string.map_auto_download_running, HOST)
        waitFor(hasText(compose.string(R.string.map_download_cancel)))
        assertFits(running)
        assertFits(compose.string(R.string.map_download_cancel))

        session.send(mapDownload("failed").put("error", REASON))
        val failed = compose.activity.getString(R.string.map_download_failed, REASON)
        waitFor(hasText(failed))
        assertFits(failed)
    }

    /** Every layout of the text is whole, after the accessibility checks of the screen. */
    private fun assertFits(text: String) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val layouts = ArrayList<TextLayoutResult>()
        for (node in compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()) {
            if (node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) continue
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        }
        assertTrue("$locale: no layout for \"$text\"", layouts.isNotEmpty())
        for (layout in layouts) {
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun mapDownload(state: String): JSONObject = JSONObject().put("t", "mapDownload").put("offered", true).put("host", HOST)
        .put("state", state).put("label", "").put("value", 0).put("maximum", 0).put("cancelable", false).put("error", "")

    companion object {
        private const val INSTANCE = "fake-map-auto-download-pseudo"
        private const val HOST = "maps.example.org"
        private const val REASON = "Map download encountered an error: Error transferring http://maps.example.org - server replied: Not Found"
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
