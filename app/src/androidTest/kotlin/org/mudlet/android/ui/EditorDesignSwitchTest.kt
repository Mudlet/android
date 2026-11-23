package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
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
import org.mudlet.android.ui.settings.SETTINGS_HUB_TAG

/**
 * The setting "Script editor design" changed while the library shows (docs/architecture.md, "The script editor's
 * designs"): each design shows the same items at once, the engine is asked to change none of them, and a draft made in
 * one is the draft in the others, as they share one ScriptItemsClient. Each design's library changes the setting itself,
 * which its page in Settings shows, and the other way round, and an item unfolded in One page stays open.
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

    @Test
    fun eachDesignsLibraryChangesTheDesign_whichSettingsShows_andTheOtherWayRound() {
        var shown = ScriptEditorDesign.CURRENT
        // From each design's library in turn, ending on one that is not the default
        for (design in listOf(ScriptEditorDesign.WORKBENCH, ScriptEditorDesign.ONE_PAGE, ScriptEditorDesign.RECIPES, ScriptEditorDesign.WORKBENCH)) {
            pickInTheLibrary(shown, design)
            t.answerLists()
            if (design == ScriptEditorDesign.RECIPES) t.answerOpen()
            t.waitFor(t.row("Greet"))
            compose.runOnIdle { assertEquals(design, Settings.scriptEditorDesign.value) }
            t.checkAccessibility()
            shown = design
        }
        leaveTheLibrary()

        compose.pickFromGameMenu(t.string(R.string.settings_title))
        t.waitFor(hasTestTag(SETTINGS_HUB_TAG))
        val experimental = hasText(t.string(R.string.settings_experimental_page))
        compose.scrollSettingsHubTo(experimental)
        t.activate(experimental)
        t.waitFor(choice(ScriptEditorDesign.WORKBENCH))
        compose.onNode(choice(ScriptEditorDesign.WORKBENCH)).assertIsSelected()
        t.activate(choice(ScriptEditorDesign.ONE_PAGE))
        compose.runOnIdle { assertEquals(ScriptEditorDesign.ONE_PAGE, Settings.scriptEditorDesign.value) }
        t.back()
        t.waitFor(hasTestTag(SETTINGS_HUB_TAG))
        t.back()
        t.waitFor(hasTestTag(GAME_TITLE_TAG))

        // The library's list has the page's pick, and Cancel leaves it
        t.openLibrary()
        openTheList()
        t.waitFor(listed(ScriptEditorDesign.ONE_PAGE))
        compose.onNode(listed(ScriptEditorDesign.ONE_PAGE)).assertIsSelected()
        t.activate(hasText(t.string(R.string.settings_choice_cancel)))
        waitForNoDialog()
        compose.runOnIdle { assertEquals(ScriptEditorDesign.ONE_PAGE, Settings.scriptEditorDesign.value) }
        leaveTheLibrary()
        t.session.assertNoRequest(ScriptDesignHarness.CHANGES, 1_000)
    }

    @Test
    fun anItemUnfoldedInOnePageIsOpenInTheDesignPicked() {
        switchTo(ScriptEditorDesign.ONE_PAGE)
        t.activate(t.row("Greet"))
        t.answerOpen()
        t.waitFor(t.name and hasText("Greet"))

        pickInTheLibrary(ScriptEditorDesign.ONE_PAGE, ScriptEditorDesign.WORKBENCH)
        t.answerLists()
        // A phone's screen has no room beside the library, so the item's own page opens
        val asked = t.answerOpen()
        assertEquals(listOf("trigger", 3), listOf(asked.getString("kind"), asked.getInt("item")))
        t.activate(hasText(t.string(R.string.workbench_settings)))
        t.waitFor(t.name and hasText("Greet"))
        t.back()
        t.waitFor(t.row("Greet"))
        leaveTheLibrary()
        t.session.assertNoRequest(ScriptDesignHarness.CHANGES, 1_000)
    }

    private val designRow get() = hasText(t.string(R.string.settings_script_editor_design)) and hasAnyAncestor(t.library)

    /** [design]'s radio button, in the library's list of designs or on the settings page. */
    private fun choice(design: ScriptEditorDesign): SemanticsMatcher {
        val label = when (design) {
            ScriptEditorDesign.CURRENT -> R.string.settings_script_editor_current
            ScriptEditorDesign.WORKBENCH -> R.string.settings_script_editor_workbench
            ScriptEditorDesign.ONE_PAGE -> R.string.settings_script_editor_one_page
            ScriptEditorDesign.RECIPES -> R.string.settings_script_editor_recipes
        }
        return hasText(t.string(label)) and isSelectable()
    }

    /** [design]'s radio button in the library's list of designs. */
    private fun listed(design: ScriptEditorDesign) = choice(design) and hasAnyAncestor(isDialog())

    private fun waitForNoDialog() = compose.waitUntil(ScriptDesignHarness.WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

    /** Opens the list of designs from the row, last in the library, so not composed until scrolled to. */
    private fun openTheList() {
        compose.onNode(t.library).performScrollToNode(designRow)
        t.activate(designRow)
    }

    /** Opens the list of designs from the library's row, which marks [shown], and picks [design], which closes it. */
    private fun pickInTheLibrary(shown: ScriptEditorDesign, design: ScriptEditorDesign) {
        openTheList()
        t.waitFor(listed(design))
        compose.onNode(listed(shown)).assertIsSelected()
        t.activate(listed(design))
        waitForNoDialog()
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
