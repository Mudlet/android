package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
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
 * The Recipes design of the script editor (docs/architecture.md, "The script editor's designs") against a fake engine,
 * by the labels TalkBack reads: cards that say when each item runs and what it then does, a drawer of the kinds and
 * their folders, an item's overview whose parts are changed a step at a time, Apply, and Delete; its button makes an
 * item, which opens on its first part, and its search finds items of every kind.
 */
@RunWith(AndroidJUnit4::class)
class RecipesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val t = ScriptDesignHarness(compose, fake)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(ScriptDesignHarness.TIMEOUT_MS)).around(fake)
        .around(ScriptEditorDesignRule(Settings.ScriptEditorDesign.RECIPES))
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    @Before
    fun openTheLibrary() {
        compose.enableAccessibilityChecksOfWhatShows()
        t.openGame()
        t.openLibrary()
        // What a card says comes with its item's fields
        assertEquals(3, t.answerOpen().getInt("item"))
    }

    @Test
    fun cardsSayWhatItemsDo_anItemIsChangedAStepAtATimeAppliedAndDeleted_aNewOneOpensOnItsFirstPart_andTheSearchFindsItems() {
        t.waitFor(t.row("Greet") and hasText(t.string(R.string.recipes_when_substring, "You wave")) and hasText(t.string(R.string.recipes_then_send, "wave")))

        // The drawer goes to a folder, and Back goes up from it
        t.activate(hasContentDescription(t.string(R.string.recipes_menu)))
        t.activate(hasText("Combat") and !hasAnyAncestor(t.library))
        assertEquals(2, t.answerOpen().getInt("item"))
        t.waitFor(t.row("You hit"))
        compose.onNode(t.row("Greet")).assertDoesNotExist()
        t.back()
        t.waitFor(t.row("Greet"))

        // Each kind's cards say when its items run in words of their own
        val kinds = listOf(
            Triple(R.string.scripts_scripts, "script", t.string(R.string.recipes_when_script)),
            Triple(R.string.scripts_timers, "timer", t.string(R.string.recipes_when_timer, "00:00:02.500")),
            Triple(R.string.scripts_keys, "key", t.string(R.string.recipes_when_key, "F1")),
        )
        for ((title, kind, says) in kinds) {
            t.activate(hasContentDescription(t.string(R.string.recipes_menu)))
            t.activate(hasText(t.string(title)) and !hasAnyAncestor(t.library))
            assertEquals(kind, t.answerOpen().getString("kind"))
            t.waitFor(hasText(says) and hasAnyAncestor(t.library))
        }
        t.activate(hasContentDescription(t.string(R.string.recipes_menu)))
        t.activate(hasText(t.string(R.string.scripts_triggers)) and !hasAnyAncestor(t.library))
        t.waitFor(t.row("Greet"))

        // An item's overview, and its parts a step at a time, the last of which has its name
        t.activate(t.row("Greet"))
        assertEquals(3, t.answerOpen().getInt("item"))
        t.activate(hasText(t.string(R.string.recipes_when)) and hasAnyAncestor(t.page))
        t.waitFor(hasText(t.string(R.string.recipes_step, 1, 3)))
        t.waitFor(hasSetTextAction() and hasText("You wave"))
        t.activate(hasText(t.string(R.string.recipes_next, t.string(R.string.recipes_then))))
        t.waitFor(t.editor and hasText("echo(\"hi\")"))
        t.activate(hasText(t.string(R.string.recipes_next, t.string(R.string.recipes_more))))
        t.waitFor(t.name and hasText("Greet"))
        compose.onNode(t.name).performTextReplacement("Wave")
        t.activate(hasText(t.string(R.string.recipes_finish)))

        // Apply, on the overview, saves what each step changed
        t.activate(t.apply)
        val check = t.received("checkLua")
        val edit = t.received("editScriptItem")
        assertEquals("Wave", edit.getJSONObject("fields").getString("name"))
        assertEquals("You wave", edit.getJSONObject("fields").getJSONArray("patterns").getJSONObject(0).getString("text"))
        val problem = t.luaProblem(check, 1, "unexpected symbol")
        t.saved("trigger", 3, 0, "Wave", edit)
        t.waitFor(t.apply and !isEnabled())
        // What is wrong with the Lua, found on Apply, shows on the overview
        t.waitFor(problem)

        // Deleted from its overview, with Undo offered in the library, and sent once the library goes
        t.activate(hasContentDescription(t.string(R.string.scripts_delete, "Wave")))
        t.waitFor(hasText(t.string(R.string.scripts_deleted, "Wave")))
        compose.onNode(t.row("Wave")).assertDoesNotExist()

        // The button at the foot makes an item, which opens on its first part
        val newTrigger = t.string(R.string.scripts_new_trigger)
        t.activate(hasContentDescription(newTrigger))
        val add = t.received("addScriptItem")
        assertEquals(listOf("trigger", 0), listOf(add.getString("kind"), add.getInt("parent")))
        assertFalse(add.getBoolean("folder"))
        t.saved("trigger", 9, 0, newTrigger, add)
        assertEquals(9, t.answerOpen().getInt("item"))
        // The library goes for the item's page, and the delete with it
        val delete = t.received("deleteScriptItem")
        assertEquals(listOf("trigger", 3), listOf(delete.getString("kind"), delete.getInt("item")))
        t.waitFor(hasText(t.string(R.string.recipes_step, 1, 3)))
        t.back()
        t.waitFor(hasText(t.string(R.string.recipes_more)) and hasClickAction() and hasAnyAncestor(t.page))
        t.back()
        t.waitFor(t.row(newTrigger))
        compose.onNode(t.row("Wave")).assertDoesNotExist()

        // The search finds an item of another kind, as a card that says what it does
        t.activate(hasContentDescription(t.string(R.string.scripts_search)))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(t.library)).performTextInput("kick")
        assertEquals("alias", t.answerOpen().getString("kind"))
        t.waitFor(t.row("kick") and hasText(t.string(R.string.recipes_when_alias, "^kick$")))
        compose.onNode(t.row(newTrigger)).assertDoesNotExist()

        // Back closes the search, and then leaves the library, the delete gone once
        t.back()
        t.waitFor(t.row(newTrigger))
        t.back()
        t.waitFor(hasTestTag(GAME_TITLE_TAG))
        t.session.assertNoRequest("deleteScriptItem", 1_000)
    }
}
