package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import org.mudlet.android.report.Redactor
import org.mudlet.android.report.ReportCollector
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * About > Report a problem against a fake engine, with this device's real logcat. A profile has a saved password and
 * a login, the player types a password while the game hides input, and a line that leaks the saved password and the
 * login is logged: the report holds that line, so the log was read, with neither secret in it, and nothing of the
 * hidden input. The screen shows the report before anything is sent, found and activated as TalkBack would.
 */
@RunWith(AndroidJUnit4::class)
class ReportProblemTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private lateinit var passwords: PasswordStore
    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @Before
    fun listTheProfile() {
        compose.enableAccessibilityChecksOfWhatShows()
        passwords = PasswordStore(compose.activity)
        assertTrue(passwords.set(GAME.name, SAVED_PASSWORD))
        session = fake.engine.nextSession()
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
    }

    @After
    fun forgetThePassword() {
        passwords.forget(GAME.name)
    }

    @Test
    fun theReportShowsTheLogWithoutTheSavedLoginOrWhatTheGameHid() {
        typeWhileTheGameHidesInput()
        Log.w(TAG, "$MARKER leaked $SAVED_PASSWORD for $LOGIN")

        activate(hasContentDescription(string(R.string.about_title)))
        activate(hasText(string(R.string.about_report)))
        compose.waitUntil(WAIT_MS * 2) {
            runCatching { compose.onNode(hasText(string(R.string.report_share)) and hasClickAction()).assertIsEnabled() }.isSuccess
        }
        compose.onRoot().tryPerformAccessibilityChecks()
        // The leaking line, as the player sees it before sending
        val line = hasText("$MARKER leaked ${Redactor.PASSWORD} for ${Redactor.LOGIN}", substring = true)
        compose.onNode(hasScrollAction()).performScrollToNode(line)
        compose.onNode(line).assertIsDisplayed()

        val report = ReportCollector.collect(compose.activity, EngineClient.profiles).text
        assertTrue("the log was read:\n${report.takeLast(2_000)}", report.contains(MARKER))
        for ((secret, what) in listOf(SAVED_PASSWORD to "the saved password", LOGIN to "the login", HIDDEN to "the hidden input")) {
            assertFalse("$what is in the report", report.contains(secret, ignoreCase = true))
        }
        assertTrue(report, report.contains("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
    }

    private fun typeWhileTheGameHidesInput() {
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        session.hiddenInput(true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(password).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(password and hasSetTextAction()).performTextInput(HIDDEN)
        compose.onNode(password and hasSetTextAction()).performImeAction()
        assertEquals(HIDDEN, session.receive("sendHidden").getString("text"))
        session.hiddenInput(false)
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.profiles_title))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click */
    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val TAG = "ReportProblemTest"
        const val SAVED_PASSWORD = "pw-Rp4-saved"
        const val LOGIN = "Zyxwerobin"
        const val HIDDEN = "pw-Rp4-typed-hidden"
        const val MARKER = "report-marker-7c1"
        val GAME = Profile("Report Game", "r.example", 4000, false, LOGIN, false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
