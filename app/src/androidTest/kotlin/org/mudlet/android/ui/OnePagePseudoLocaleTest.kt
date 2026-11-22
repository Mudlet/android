package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings

/**
 * The One page design of the script editor (OnePageTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: the page with its chips and headings, and an item unfolded with its options and buttons, fit
 * their text.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class OnePagePseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.ONE_PAGE))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun thePageAndAnItemUnfoldedFitTheirText() {
        t.openGame()
        t.openLibrary()
        t.assertTextFits(locale, "the page")

        t.activate(t.row("Greet"))
        t.answerOpen()
        t.waitFor(t.name and hasText("Greet") and hasAnyAncestor(t.library))
        t.activate(hasText(t.string(R.string.onepage_more)))
        t.waitFor(hasText(t.string(R.string.onepage_fewer)))
        t.assertTextFits(locale, "an item unfolded")
        t.activate(hasText(t.string(R.string.onepage_done)))
        t.waitFor(t.row("Greet"))
        t.assertTextFits(locale, "the page with the item folded again")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
