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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * "Notify on new text in the background" on a game's Display page, which turns desktop Mudlet's `alertOnNewData`, and
 * the app's Notifications page with "Show game text in notifications", and both pages' summaries in the settings list,
 * in the debug build's pseudo-locales, as PseudoLocaleTest checks the screens: none of their text is cut short.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class NotificationsPseudoLocaleTest(private val locale: String) {
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
    fun theSwitchesTheirLabelsAndThePagesFit() {
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.status("profile", "${GAME.name} loaded")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }

        compose.pickFromGameMenu(string(R.string.settings_title))
        val display = hasText(string(R.string.settings_display)) and hasClickAction()
        compose.scrollSettingsHubTo(display)
        assertFits(string(R.string.settings_display_summary))
        activate(display)
        session.settings(mapOf("alertOnNewData" to true), session.receive("getSettings").getLong("id"))
        val alert = hasText(string(R.string.settings_alert_on_new_data))
        waitFor(alert)
        compose.onNode(alert).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        listOf(R.string.settings_alert_on_new_data, R.string.settings_alert_on_new_data_hint).map { string(it) }.forEach(::assertFits)
        activate(alert and hasClickAction())
        assertEquals(mapOf("alertOnNewData" to false), values(session.receive("setSettings")))

        activate(hasContentDescription(string(R.string.navigate_back)))
        val notifications = hasText(string(R.string.settings_notifications)) and hasClickAction()
        compose.scrollSettingsHubTo(notifications)
        assertFits(string(R.string.settings_notifications_summary))
        activate(notifications)
        waitFor(hasText(string(R.string.settings_game_text_in_notifications)))
        compose.onRoot().tryPerformAccessibilityChecks()
        listOf(R.string.settings_game_text_in_notifications, R.string.settings_game_text_in_notifications_hint).map { string(it) }.forEach(::assertFits)
    }

    private fun values(request: JSONObject): Map<String, Any> = request.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.get(it) } }

    private fun assertFits(text: String) {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).performScrollTo()
        val layouts = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        private val GAME = Profile("Notices", "127.0.0.1", 4000, false, "", false)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
