package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
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
 * The Recipes design of the script editor (RecipesTest) in the debug build's pseudo-locales, as PseudoLocaleTest
 * checks the screens: the cards, the drawer, an item's overview and each of its steps fit their text.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class RecipesPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.RECIPES))
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecksOfWhatShows()
    }

    @Test
    fun theCardsTheDrawerAndAnItemsStepsFitTheirText() {
        t.openGame()
        t.openLibrary()
        t.answerOpen()
        t.waitFor(hasText(t.string(R.string.recipes_when_substring, "You wave")))
        t.assertTextFits(locale, "the cards")

        t.activate(hasContentDescription(t.string(R.string.recipes_menu)))
        t.waitFor(hasText("Combat") and !hasAnyAncestor(t.library))
        t.assertTextFits(locale, "the drawer")
        t.back()
        t.waitFor(t.row("Greet"))

        t.activate(t.row("Greet"))
        t.answerOpen()
        t.waitFor(hasText(t.string(R.string.recipes_more)) and hasAnyAncestor(t.page))
        t.assertTextFits(locale, "an item's overview")
        t.activate(hasText(t.string(R.string.recipes_when)) and hasAnyAncestor(t.page))
        t.waitFor(hasSetTextAction() and hasText("You wave"))
        t.assertTextFits(locale, "the When step")
        t.activate(hasText(t.string(R.string.recipes_next, t.string(R.string.recipes_then))))
        t.waitFor(t.editor)
        t.assertTextFits(locale, "the Then step")
        t.activate(hasText(t.string(R.string.recipes_next, t.string(R.string.recipes_more))))
        t.waitFor(t.name)
        t.assertTextFits(locale, "the Name and options step")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
