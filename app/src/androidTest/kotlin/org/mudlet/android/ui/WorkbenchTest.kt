package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/**
 * The Workbench design of the script editor (docs/architecture.md, "The script editor's designs") against a fake
 * engine, by the labels TalkBack reads: its tree opens a folder where it is and an item's page, whose Lua is the first
 * tab and the rest the second, where an edit is applied and the item deleted; its add menu makes an item, which opens,
 * and its search finds items of every kind.
 */
@RunWith(AndroidJUnit4::class)
class WorkbenchTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.WORKBENCH))
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun openTheLibrary() {
        compose.enableAccessibilityChecksOfWhatShows()
        t.openGame()
        t.openLibrary()
    }

    @Test
    fun anItemIsOpenedEditedAppliedAndDeleted_aNewOneOpens_andTheSearchFindsItems() {
        // A folder opens where it is
        compose.onNode(t.row("You hit")).assertDoesNotExist()
        t.activate(t.row("Combat"))
        t.waitFor(t.row("You hit"))

        // An item's page: its Lua first, and its name with the rest on the second tab
        t.activate(t.row("Greet"))
        assertEquals(3, t.answerOpen().getInt("item"))
        t.waitFor(t.editor and hasText("echo(\"hi\")"))
        t.activate(hasText(t.string(R.string.workbench_settings)))
        t.waitFor(t.name and hasText("Greet"))
        compose.onNode(t.name).performTextReplacement("Wave")
        t.activate(t.apply)
        val check = t.received("checkLua")
        val edit = t.received("editScriptItem")
        assertEquals("Wave", edit.getJSONObject("fields").getString("name"))
        assertEquals("You wave", edit.getJSONObject("fields").getJSONArray("patterns").getJSONObject(0).getString("text"))
        val problem = t.luaProblem(check, 1, "unexpected symbol")
        t.saved("trigger", 3, 0, "Wave", edit)
        t.waitFor(t.apply and !isEnabled())

        // What is wrong with the Lua, found on Apply, shows over both tabs, and going to its line goes to the first
        t.waitFor(problem and !hasAnyAncestor(t.library))
        t.activate(hasText(t.string(R.string.lua_go_to_line)))
        t.waitFor(t.editor and hasText("echo(\"hi\")"))
        t.activate(hasText(t.string(R.string.workbench_settings)))

        // Deleted from its page, with Undo offered in the library, and sent once the library goes
        t.activate(hasText(t.string(R.string.scripts_delete, "Wave")))
        t.waitFor(hasText(t.string(R.string.scripts_deleted, "Wave")))
        compose.onNode(t.row("Wave")).assertDoesNotExist()

        // The add menu makes an item at the top level, whose page opens
        t.activate(hasContentDescription(t.string(R.string.designs_add)))
        val newTrigger = t.string(R.string.scripts_new_trigger)
        t.activate(hasText(newTrigger))
        val add = t.received("addScriptItem")
        assertEquals(listOf("trigger", 0), listOf(add.getString("kind"), add.getInt("parent")))
        assertFalse(add.getBoolean("folder"))
        t.saved("trigger", 9, 0, newTrigger, add)
        assertEquals(9, t.answerOpen().getInt("item"))
        // The library goes for the item's page, and the delete with it
        val delete = t.received("deleteScriptItem")
        assertEquals(listOf("trigger", 3), listOf(delete.getString("kind"), delete.getInt("item")))
        t.waitFor(t.editor and !hasAnyAncestor(t.library))
        t.activate(hasContentDescription(t.string(R.string.navigate_back)))
        t.waitFor(t.row(newTrigger))
        compose.onNode(t.row("Wave")).assertDoesNotExist()

        // The search finds an item of another kind, with where it is
        t.activate(hasContentDescription(t.string(R.string.scripts_search)))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(t.library)).performTextInput("kick")
        t.waitFor(t.row("kick"))
        compose.onNode(t.row(newTrigger)).assertDoesNotExist()
        compose.onNode(hasText(t.string(R.string.scripts_aliases)) and hasAnyAncestor(t.library)).assertExists()

        // Back closes the search, and then leaves the library, the delete gone once
        t.back()
        t.waitFor(t.row(newTrigger))
        t.back()
        t.waitFor(hasTestTag(GAME_TITLE_TAG))
        t.session.assertNoRequest("deleteScriptItem", 1_000)
    }
}
