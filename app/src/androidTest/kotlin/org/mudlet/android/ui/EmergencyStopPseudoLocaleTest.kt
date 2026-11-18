package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.EMERGENCY_STOP_TAG
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The game sheet's emergency stop (EmergencyStopTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: none of its text is cut short, off or on.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class EmergencyStopPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGameSheet() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.status("profile", "${GAME.name} loaded")
        stopped(false)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && !EngineClient.front.scriptsStopped } }
        activate(hasTestTag(GAME_TITLE_TAG))
        waitFor(hasTestTag(EMERGENCY_STOP_TAG))
    }

    @Test
    fun theTextFits() {
        assertFits(R.string.game_emergency_stop, R.string.game_emergency_stop_off)
        stopped(true)
        waitFor(hasText(compose.string(R.string.game_emergency_stop_on), substring = true))
        assertFits(R.string.game_emergency_stop, R.string.game_emergency_stop_on)
    }

    private fun assertFits(vararg ids: Int) {
        compose.onNode(hasTestTag(EMERGENCY_STOP_TAG)).performScrollTo()
        compose.onRoot().tryPerformAccessibilityChecks()
        for (id in ids) {
            val text = compose.string(id)
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
    }

    private fun stopped(stopped: Boolean) = session.send(JSONObject().put("t", "scriptsStopped").put("stopped", stopped))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        val GAME = Profile("Emergency", "127.0.0.1", 4000, false, "", false)
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
