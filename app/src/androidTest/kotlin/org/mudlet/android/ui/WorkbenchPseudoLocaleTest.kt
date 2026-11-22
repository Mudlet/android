package org.mudlet.android.ui

import android.Manifest
import android.os.Build
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
 * The Workbench design of the script editor (WorkbenchTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: its tree with the kinds' tabs, and an item's two tabs, fit their text.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class WorkbenchPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.WORKBENCH))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun theTreeAndAnItemsTabsFitTheirText() {
        t.openGame()
        t.openLibrary()
        t.activate(t.row("Combat"))
        t.waitFor(t.row("You hit"))
        t.assertTextFits(locale, "the tree")

        t.activate(t.row("Greet"))
        t.answerOpen()
        t.waitFor(t.editor and hasText("echo(\"hi\")"))
        t.assertTextFits(locale, "an item's Lua")
        t.activate(hasText(t.string(R.string.workbench_settings)))
        t.waitFor(t.name)
        t.assertTextFits(locale, "an item's settings")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
