package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.LogNameFormat
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * A game's Logging page against a fake engine: desktop Mudlet's log options, each sent to the game as its preferences
 * set them (`setSettings` by `getConfig()`'s keys), the log's name only for a named file, with the extension HTML
 * gives it, and a name Mudlet would refuse not sent.
 */
@RunWith(AndroidJUnit4::class)
class LoggingSettingsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val nameField get() = hasSetTextAction() and hasText(string(R.string.settings_log_file_name))

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        // As the engine does as the profile loads: EngineClient outlives a test, so an earlier one's would be there
        session.status("profile", "${GAME.name} loaded")
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }
    }

    @Test
    fun eachOptionIsSentAsDesktopsPreferencesSetIt_andTheNameOnlyForANamedFile() {
        openTheLoggingPage(MUDLETS)
        assertTrue("no name for a log named by its date", compose.onAllNodes(nameField).fetchSemanticsNodes().isEmpty())

        val html = hasText(string(R.string.settings_log_html)) and hasClickAction()
        assertEquals(Role.Switch, compose.onNode(html).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        activate(html, scroll = true)
        assertEquals(mapOf("logInHTML" to true), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_log_timestamps)) and hasClickAction(), scroll = true)
        assertEquals(mapOf("logTimestamps" to true), values(session.receive("setSettings")))

        val day = hasText(string(R.string.settings_log_name_day)) and hasClickAction()
        assertEquals(Role.RadioButton, compose.onNode(day).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        activate(day, scroll = true)
        assertEquals(mapOf("logFileNameFormat" to "yyyy-MM-dd"), values(session.receive("setSettings")))
        compose.onNode(day).assert(isSelected())
        assertTrue("no name for a log named by its day", compose.onAllNodes(nameField).fetchSemanticsNodes().isEmpty())

        activate(hasText(string(R.string.settings_log_name_named)) and hasClickAction(), scroll = true)
        assertEquals(mapOf("logFileNameFormat" to ""), values(session.receive("setSettings")))
        waitFor(nameField)
        compose.onNode(nameField).performScrollTo().assertIsDisplayed()
        compose.onNode(nameField).performTextInput("Raid")
        // HTML is on, so the file it names ends so
        compose.onNode(hasText(".html"), useUnmergedTree = true).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        session.assertNoRequest("setSettings", 500)
        compose.onNode(nameField).performImeAction()
        assertEquals(mapOf("logFileName" to "Raid"), values(session.receive("setSettings")))

        // Empty, it is Mudlet's own name
        compose.onNode(nameField).performTextClearance()
        compose.onNode(hasText(string(R.string.settings_log_file_name_default)), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(nameField).performImeAction()
        assertEquals(mapOf("logFileName" to ""), values(session.receive("setSettings")))

        compose.runOnIdle {
            assertEquals("new games keep Mudlet's", false, Settings.logInHtml.value)
            assertEquals("new games keep Mudlet's", LogNameFormat.DATE_AND_TIME, Settings.logNameFormat.value)
        }
    }

    @Test
    fun aNameWithAFolderSeparatorSaysWhyAndIsNotSent() {
        openTheLoggingPage(MUDLETS + mapOf("logFileNameFormat" to "", "logFileName" to "Raid"))
        waitFor(nameField)
        compose.onNode(nameField).performScrollTo().assert(hasText("Raid"))
        // The game's HTML is off
        compose.onNode(nameField).performTextInput("/")
        compose.onNode(hasText(".txt"), useUnmergedTree = true).assertIsDisplayed()
        val why = string(R.string.settings_log_file_name_refused)
        compose.waitForIdle()
        assertTrue("the field says why it is refused", compose.onAllNodes(hasText(why), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasText(why), useUnmergedTree = true).assertIsDisplayed()
        assertTrue("TalkBack says the field is in error", compose.onNode(nameField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error) != null)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(nameField).performImeAction()
        activate(hasContentDescription(string(R.string.navigate_back)))
        session.assertNoRequest("setSettings", 500)
    }

    private fun openTheLoggingPage(mudlets: Map<String, Any>) {
        compose.pickFromGameMenu(string(R.string.settings_title))
        val page = hasText(string(R.string.settings_logging)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(mudlets, session.receive("getSettings").getLong("id"))
        waitFor(hasText(string(R.string.settings_log_html)) and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun values(request: JSONObject): Map<String, Any> = request.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.get(it) } }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, on a page scrolled to as TalkBack does. */
    private fun activate(label: SemanticsMatcher, scroll: Boolean = false) {
        waitFor(label and hasClickAction())
        val node = compose.onNode(label and hasClickAction())
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private companion object {
        val GAME = Profile("Logs", "127.0.0.1", 4000, false, "", false)

        // What Mudlet starts a profile with
        val MUDLETS = mapOf("logInHTML" to false, "logTimestamps" to false, "logFileNameFormat" to "yyyy-MM-dd#HH-mm-ss", "logFileName" to "")
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
