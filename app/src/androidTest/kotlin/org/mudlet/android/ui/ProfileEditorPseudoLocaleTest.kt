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
import org.mudlet.android.R
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile

/**
 * The profile editor's folded sections (ProfileEditorLayoutTest) in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: no heading, summary or hint is cut short or runs past its lines, each at its
 * longest, and each heading starts its line.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ProfileEditorPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var passwords: PasswordStore

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
        passwords = PasswordStore(compose.activity)
    }

    @After
    fun forgetPasswords() {
        passwords.forget(GAME.name)
        passwords.forget(GAME.name, PasswordStore.Kind.PROXY)
    }

    @Test
    fun theFoldedSectionsTextFits() {
        // Both stored, so each summary is at its longest
        assertTrue(passwords.set(GAME.name, "fold secret"))
        assertTrue(passwords.set(GAME.name, "fold proxy secret", PasswordStore.Kind.PROXY))
        val session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }

        activate(hasContentDescription(string(R.string.profile_edit, GAME.name)))
        val headings = listOf(R.string.profile_login_section, R.string.profile_advanced_section, R.string.profile_manage_section).map { string(it) }
        val summaries = listOf(
            string(R.string.profile_login_as_password, GAME.login),
            string(R.string.profile_advanced_summary, string(R.string.profile_advanced_reconnect_on), string(R.string.profile_advanced_proxy_password)),
            string(R.string.profile_manage_summary),
        )
        compose.onNode(hasText(string(R.string.profile_host_help))).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(string(R.string.profile_host_help))
        for ((heading, summary) in headings.zip(summaries)) {
            compose.onNode(hasText(heading) and hasClickAction()).performScrollTo().assertIsDisplayed()
            compose.onRoot().tryPerformAccessibilityChecks()
            assertFits(heading, summary)
            assertStartsItsLine(heading)
        }
    }

    // Every node showing each text, as a field's label is a node of its own beside the field's
    private fun assertFits(vararg texts: String) {
        for (text in texts) {
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

    // At the left in en-XA, the right in ar-XB
    private fun assertStartsItsLine(text: String) {
        val bounds = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        if (locale == "ar-XB") {
            assertTrue("$locale: \"$text\" is not at its line's start", width - bounds.right < bounds.left)
        } else {
            assertTrue("$locale: \"$text\" is not at its line's start", bounds.left < width - bounds.right)
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
        private val GAME = Profile("Fold Locale Test", "127.0.0.1", 4000, false, "hero", true)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
