package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
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
 * The One page design of the script editor (docs/architecture.md, "The script editor's designs") against a fake engine,
 * by the labels TalkBack reads: every kind on one page, where an item unfolds to be edited, folding it applies the edit
 * with Undo offered, and it is deleted; its add menu makes an item, which unfolds, and its search finds items of every
 * kind.
 */
@RunWith(AndroidJUnit4::class)
class OnePageTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.ONE_PAGE))
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun openTheLibrary() {
        compose.enableAccessibilityChecksOfWhatShows()
        t.openGame()
        t.openLibrary()
    }

    @Test
    fun anItemUnfoldsIsEditedAppliedByFoldingWithUndoAndDeleted_aNewOneUnfolds_andTheSearchFindsItems() {
        // Every kind is on the page, an item in a folder under the folder's heading
        for (name in listOf("You hit", "kick", "Login", "Heal", "North", "Sword")) compose.onNode(t.library).performScrollToNode(t.row(name))
        compose.onNode(t.library).performScrollToIndex(0)

        t.activate(t.row("Greet"))
        assertEquals(3, t.answerOpen().getInt("item"))
        t.waitFor(t.name and hasText("Greet") and hasAnyAncestor(t.library))
        t.waitFor(t.editor and hasText("echo(\"hi\")") and hasAnyAncestor(t.library))
        compose.onNode(t.name).performTextReplacement("Wave")

        // Done folds it, which applies it, with Undo offered
        t.activate(hasText(t.string(R.string.onepage_done)))
        val check = t.received("checkLua")
        val edit = t.received("editScriptItem")
        assertEquals("Wave", edit.getJSONObject("fields").getString("name"))
        val problem = t.luaProblem(check, 1, "unexpected symbol")
        t.saved("trigger", 3, 0, "Wave", edit)
        t.waitFor(hasText(t.string(R.string.scripts_applied, "Wave")))
        compose.onNode(t.name).assertDoesNotExist()
        // What is wrong with the Lua, found as it was applied, shows on the folded item
        t.waitFor(t.row("Wave") and problem)
        t.activate(hasText(t.string(R.string.scripts_undo)))
        val undo = t.received("editScriptItem")
        assertEquals("Greet", undo.getJSONObject("fields").getString("name"))
        t.saved("trigger", 3, 0, "Greet", undo)
        t.waitFor(t.row("Greet"))

        // Deleted where it is, with Undo offered, and sent once the page goes
        t.activate(t.row("Greet"))
        t.answerOpen()
        t.activate(hasText(t.string(R.string.scripts_delete, "Greet")))
        t.waitFor(hasText(t.string(R.string.scripts_deleted, "Greet")))
        compose.onNode(t.row("Greet")).assertDoesNotExist()

        // The add menu makes an item at the top level, which unfolds
        t.activate(hasContentDescription(t.string(R.string.designs_add)))
        val newTrigger = t.string(R.string.scripts_new_trigger)
        t.activate(hasText(newTrigger))
        val add = t.received("addScriptItem")
        assertEquals(listOf("trigger", 0), listOf(add.getString("kind"), add.getInt("parent")))
        assertFalse(add.getBoolean("folder"))
        t.saved("trigger", 9, 0, newTrigger, add)
        assertEquals(9, t.answerOpen().getInt("item"))
        t.waitFor(t.name and hasText(newTrigger))
        t.activate(hasContentDescription(t.string(R.string.onepage_fold, newTrigger)))
        compose.onNode(t.name).assertDoesNotExist()

        // The search, at the top of the page, which the item unfolded scrolled away, finds an item of another kind, with where it is
        val search = hasSetTextAction() and hasAnyAncestor(t.library)
        compose.onNode(t.library).performScrollToNode(search)
        compose.onNode(search).performTextInput("kick")
        t.waitFor(t.row("kick") and hasText(t.string(R.string.scripts_aliases)))
        compose.onNode(t.row(newTrigger)).assertDoesNotExist()

        // Back empties the search
        t.back()
        t.waitFor(t.row(newTrigger))

        // Leaving the page applies what was changed in the item unfolded, once, and lets the delete go
        t.activate(t.row(newTrigger))
        assertEquals(9, t.answerOpen().getInt("item"))
        t.waitFor(t.name and hasText(newTrigger))
        compose.onNode(t.name).performTextReplacement("Wave")
        t.activate(hasContentDescription(t.string(R.string.navigate_back)))
        t.waitFor(hasTestTag(GAME_TITLE_TAG))
        val left = t.received("editScriptItem")
        assertEquals(listOf(9, "Wave"), listOf(left.getInt("item"), left.getJSONObject("fields").getString("name")))
        val delete = t.received("deleteScriptItem")
        assertEquals(listOf("trigger", 3), listOf(delete.getString("kind"), delete.getInt("item")))
        t.session.assertNoRequest("editScriptItem", 1_000)
    }
}
