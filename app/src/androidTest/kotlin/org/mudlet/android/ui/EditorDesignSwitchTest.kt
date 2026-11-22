package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.data.Settings.ScriptEditorDesign
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The setting "Script editor design" changed while the library shows (docs/architecture.md, "The script editor's
 * designs"): each design shows the same items at once, the engine is asked to change none of them, and a draft made in
 * one is the draft in the others, as they share one ScriptItemsClient.
 */
@RunWith(AndroidJUnit4::class)
class EditorDesignSwitchTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(ScriptEditorDesign.CURRENT))
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun openTheLibrary() {
        compose.enableAccessibilityChecksOfWhatShows()
        t.openGame()
        t.openLibrary()
    }

    @Test
    fun eachDesignShowsTheSameItemsAndChangesNone() {
        for (design in listOf(ScriptEditorDesign.WORKBENCH, ScriptEditorDesign.ONE_PAGE, ScriptEditorDesign.RECIPES, ScriptEditorDesign.CURRENT)) {
            switchTo(design)
            // Recipes' cards say what an item does, so ask for its fields the first time; the others list it
            if (design == ScriptEditorDesign.RECIPES) assertEquals(3, t.answerOpen().getInt("item"))
            t.waitFor(t.row("Greet"))
            t.checkAccessibility()
        }
        leaveTheLibrary()
        t.session.assertNoRequest(ScriptDesignHarness.CHANGES, 1_000)
        assertEquals(emptyList<String>(), t.session.received.map { it.optString("t") }.filter { it in ScriptDesignHarness.CHANGES })
    }

    @Test
    fun aDraftMadeInOneDesignIsTheDraftInTheOthers() {
        t.activate(t.row("Greet"))
        t.answerOpen()
        t.waitFor(t.name and hasText("Greet"))
        compose.onNode(t.name).performTextReplacement("Greet!")
        t.activate(hasContentDescription(t.string(R.string.scripts_back_to_game)))
        t.waitFor(hasTestTag(GAME_TITLE_TAG))
        t.openLibrary()

        val draft = hasText(t.string(R.string.scripts_draft))
        for (design in listOf(ScriptEditorDesign.WORKBENCH, ScriptEditorDesign.ONE_PAGE, ScriptEditorDesign.RECIPES)) {
            switchTo(design)
            t.waitFor(t.row("Greet") and draft)
        }
        // Workbench's page of the item opens with the draft, as the current editor's would
        switchTo(ScriptEditorDesign.WORKBENCH)
        t.activate(t.row("Greet"))
        t.answerOpen()
        t.activate(hasText(t.string(R.string.workbench_settings)))
        t.waitFor(t.name and hasText("Greet!"))
        t.waitFor(t.apply and isEnabled())
        t.back()
        t.activate(hasText(t.string(R.string.scripts_discard)))
        t.waitFor(t.row("Greet"))
        leaveTheLibrary()
        t.session.assertNoRequest(ScriptDesignHarness.CHANGES, 1_000)
        assertEquals(emptyList<String>(), t.session.received.map { it.optString("t") }.filter { it in ScriptDesignHarness.CHANGES })
    }

    /** Changes the setting, as its page does, while the library shows, and answers the lists the new design's library asks for. */
    private fun switchTo(design: ScriptEditorDesign) {
        compose.runOnIdle { Settings.scriptEditorDesign.set(design) }
        t.answerLists()
    }

    private fun leaveTheLibrary() {
        t.back()
        t.waitFor(hasTestTag(GAME_TITLE_TAG))
    }
}
