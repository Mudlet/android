package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
 * The proxy section of a profile's editor (ProfilesTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: none of its text is cut short or runs past its lines, closed and open, and its heading starts its line.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ProxyPseudoLocaleTest(private val locale: String) {
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
    fun theSectionsTextFits() {
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }

        // Closed, the section says when its proxy can be set
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        val note = hasText(string(R.string.profile_proxy_while_open))
        waitFor(note)
        compose.onNode(note).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.profile_proxy_title, R.string.profile_proxy_while_open)
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.status("profile", "${GAME.name} loaded")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable } }
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        // Switched on with no address or port, so the hints under both fields show. With a user, and scrolled to the
        // port's hint, so the field at the page's foot has text: an empty one whose top alone shows there has no name
        // for the checks (docs/roadmap.md, "A known gap, the toolkit's")
        session.proxy(true, "", 0, "hero", session.receive("getProxy").get("id"))
        val host = hasSetTextAction() and hasText(string(R.string.profile_proxy_host))
        waitFor(host)
        compose.onNode(hasText(string(R.string.profile_port_invalid))).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(string(R.string.profile_proxy_host_needed))).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(
            R.string.profile_proxy_title,
            R.string.profile_proxy_use,
            R.string.profile_proxy_host,
            R.string.profile_proxy_host_needed,
            R.string.profile_proxy_port,
            R.string.profile_port_invalid,
        )
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_proxy_password))).performScrollTo()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.profile_proxy_user, R.string.profile_proxy_password)

        // The heading starts its line in the layout's direction: at the left in en-XA, the right in ar-XB
        val heading = compose.onNode(hasText(string(R.string.profile_proxy_title)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        if (locale == "ar-XB") {
            assertTrue("$locale: the heading is not at its line's start", width - heading.right < heading.left)
        } else {
            assertTrue("$locale: the heading is not at its line's start", heading.left < width - heading.right)
        }
    }

    // Every node showing each text, as a field's label is a node of its own beside the field's
    private fun assertFits(@StringRes vararg ids: Int) {
        for (id in ids) {
            val text = string(id)
            val layouts = ArrayList<TextLayoutResult>()
            for (node in compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } != true) continue
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            }
            assertTrue("$locale: no layout for \"$text\"", layouts.isNotEmpty())
            for (layout in layouts) {
                assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
            }
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
        private val GAME = Profile("Socks Test", "127.0.0.1", 4000, false, "", false)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
