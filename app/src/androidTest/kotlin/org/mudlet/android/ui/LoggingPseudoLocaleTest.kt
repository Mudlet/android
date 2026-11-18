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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile

/**
 * A game's Logging page (LoggingSettingsTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks the
 * screens: none of its text is cut short, the log name field included, and the extension is at the field's end, its
 * right in en-XA and its left in ar-XB.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class LoggingPseudoLocaleTest(private val locale: String) {
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
    fun thePagesTextFits() {
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
        val page = hasText(string(R.string.settings_logging)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        // A named file, so the name's field shows too
        session.settings(mapOf("logInHTML" to false, "logTimestamps" to true, "logFileNameFormat" to "", "logFileName" to ""), session.receive("getSettings").getLong("id"))
        val field = hasSetTextAction() and hasText(string(R.string.settings_log_file_name))
        waitFor(field)
        compose.onNode(field).performScrollTo().assertIsDisplayed()
        compose.onNode(field).performTextInput("Raid")
        compose.onRoot().tryPerformAccessibilityChecks()

        val texts = listOf(
            R.string.settings_log_html,
            R.string.settings_log_html_hint,
            R.string.settings_log_timestamps,
            R.string.settings_log_timestamps_hint,
            R.string.settings_log_name_format,
            R.string.settings_log_name_date_and_time,
            R.string.settings_log_name_date_t_time,
            R.string.settings_log_name_day,
            R.string.settings_log_name_month,
            R.string.settings_log_name_named,
            R.string.settings_log_file_name_hint,
        ).map(::string) + ".txt"
        for (text in texts) {
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
        // The name typed is the field's own text, whose bounds are the whole field's
        val box = compose.onNode(field).fetchSemanticsNode().boundsInRoot
        val extension = compose.onNode(hasText(".txt"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        if (locale == "ar-XB") {
            assertTrue("$locale: the extension is not at the field's end", extension.right < box.center.x)
        } else {
            assertTrue("$locale: the extension is not at the field's end", extension.left > box.center.x)
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
        private val GAME = Profile("Logs", "127.0.0.1", 4000, false, "", false)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
