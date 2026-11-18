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
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
 * The caret key's choices on a game's Accessibility page (CaretKeyTest) in the debug build's pseudo-locales, as
 * PseudoLocaleTest checks the screens: none of the text is cut short, and each choice's radio button is at its row's
 * start in the layout's direction.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class CaretKeyPseudoLocaleTest(private val locale: String) {
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
    fun theChoicesFit() {
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

        activate(hasContentDescription(string(R.string.settings_title)))
        val page = hasText(string(R.string.settings_accessibility)) and hasClickAction()
        compose.scrollSettingsHubTo(page)
        activate(page)
        session.settings(emptyMap(), session.receive("getSettings").getLong("id"))
        val heading = hasText(string(R.string.settings_caret_key))
        waitFor(heading)
        compose.onNode(heading).performScrollTo().assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        val choices = listOf(R.string.settings_caret_key_none, R.string.settings_caret_key_tab, R.string.settings_caret_key_ctrl_tab, R.string.settings_caret_key_f6)
        for (id in listOf(R.string.settings_caret_key) + choices) {
            val text = string(id)
            compose.onNode(if (id in choices) hasText(text) and isSelectable() else hasText(text)).performScrollTo()
            val layouts = ArrayList<TextLayoutResult>()
            compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
            assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
        }
        // The radio button, 20dp and its padding, leaves the label well clear of the row's start
        val clear = with(compose.density) { 32.dp.toPx() }
        for (id in choices) {
            val text = string(id)
            val row = compose.onNode(hasText(text) and isSelectable()).fetchSemanticsNode().boundsInRoot
            val label = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (locale == "ar-XB") {
                assertTrue("$locale: \"$text\" is not after its radio button", label.right <= row.right - clear)
            } else {
                assertTrue("$locale: \"$text\" is not after its radio button", label.left >= row.left + clear)
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
        private val GAME = Profile("Caret", "127.0.0.1", 4000, false, "", false)
        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
