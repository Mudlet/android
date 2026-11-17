package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.After
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
import org.mudlet.android.protocol.RefusedCertificate

/**
 * A game's Security page while its last secure connection stands refused for the certificate, in the debug build's
 * pseudo-locales, as PseudoLocaleTest checks the screens: the certificate's facts and the switches that would let it
 * through are laid out whole, within the screen's width.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class RefusedCertificatePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        // The game's settings section is in the hub only once the engine has given its settings
        session.status("profile", "Test loaded")
        session.settings(APP_VALUES, session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && EngineClient.front.settings.isNotEmpty() } }
    }

    // The settings are stored and the sessions live as long as the process, so both would outlast the test
    @After
    fun reset() = compose.runOnIdle {
        for (setting in Settings.mudletSettings) setting.reset()
        Settings.setAwaitsDefaults(GAME.name, false)
        Settings.setAwaitsInputClear(GAME.name, false)
        EngineClient.sessions.forEach { it.settings.clear() }
    }

    @Test
    fun theSecurityPageFitsTheRefusedCertificate() {
        val question = hasText(string(R.string.certificate_refused_title))
        session.certificateRefused(CERTIFICATE)
        waitFor(question)
        // The dialog is a window of its own, which activate()'s single-root checks do not take
        compose.onNode(hasText(string(R.string.certificate_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(question).fetchSemanticsNodes().isEmpty() }
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_security)))
        activate(hasText(string(R.string.settings_security)))
        waitFor(hasText(string(R.string.settings_refused_certificate_title)))
        assertTextFits()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** As PseudoLocaleTest's: each text laid out whole within its box and within the screen's width. */
    private fun assertTextFits() {
        compose.onRoot().tryPerformAccessibilityChecks()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val layouts = ArrayList<TextLayoutResult>()
            if (!text.isNullOrEmpty()) node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            checked += layouts.size
            for (layout in layouts) {
                if ((0 until layout.lineCount).any { layout.isLineEllipsized(it) }) problems += "\"$text\" is cut short"
                if (layout.multiParagraph.didExceedMaxLines) problems += "\"$text\" has more lines than it may show"
                if (layout.multiParagraph.height > layout.size.height + SLACK_PX) {
                    problems += "\"$text\" is ${layout.multiParagraph.height} px tall in its ${layout.size} box"
                }
            }
            val bounds = node.boundsInRoot
            if (layouts.isNotEmpty() && (bounds.left < -SLACK_PX || bounds.right > width + SLACK_PX)) {
                problems += "\"$text\" at $bounds is outside the screen's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        assertTrue("$locale: no text on the Security page gave its layout", checked > 0)
        assertTrue("$locale, the Security page with a refused certificate: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")

        private val APP_VALUES = mapOf("announceIncomingText" to true, "advertiseScreenReader" to false, "blankLinesBehaviour" to "show")
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private val CERTIFICATE = RefusedCertificate(
            "localhost",
            "localhost",
            "2020-01-02T00:00:00Z",
            "4A:2B",
            true,
            true,
            false,
            listOf("The certificate has expired", "The certificate is self-signed, and untrusted"),
        )
        private const val SLACK_PX = 1f
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L
    }
}
