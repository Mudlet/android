package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import org.mudlet.android.ui.scripts.MAX_MATCH_LINES
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG

/**
 * The script library (docs/architecture.md, "The script library") against a fake engine: opened from the game's title
 * bar, it lists each kind one folder at a time, its switches switch items on and off, its search finds names, its
 * buttons make an item, which opens, and a folder, which is gone into, and the game's last line at its foot goes back
 * to the game; an item's page applies an edit with Undo, asks on Back before dropping one not applied, keeps it as a
 * draft over a trip to the game, edits a trigger's patterns and options, an alias's command and a script's events, and deletes with
 * Undo, sent only once the library goes; its Lua is edited in the page, which indents a new line, completes a
 * function's name and types Lua's symbols, is checked only when applied, when what the engine finds wrong shows with a
 * way to its line until the code is changed, and is kept or asked about as the other fields are; and what the engine
 * refuses is said. Controls are found by the labels TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class ScriptLibraryTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("scripts ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    private val library = hasTestTag(SCRIPT_LIBRARY_TAG)
    private val page = hasTestTag(SCRIPT_ITEM_TAG)
    private val editor = hasTestTag(LUA_EDITOR_TAG)

    // An item page's name field and Apply button, once the activity's strings can be read
    private val name by lazy { hasSetTextAction() and hasText(string(R.string.scripts_name)) }
    private val apply by lazy { hasText(string(R.string.scripts_apply)) and hasClickAction() }

    // Combat/"You hit", then "Greet", as the engine lists them, depth first
    private val triggers = listOf(node(1, 0, "Combat", folder = true), node(2, 1, "You hit"), node(3, 0, "Greet"))

    @Before
    fun openTheLibrary() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        // A new engine instance for each test, so no item of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, CONSOLE_LINE)
        session.synced(1)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        openTheLibraryFromTheGame()
    }

    private fun openTheLibraryFromTheGame() {
        activate(hasContentDescription(string(R.string.scripts_open, game.name)))
        val asked = (1..3).map { received("scriptItems") }
        assertEquals(listOf("trigger", "alias", "script"), asked.map { it.getString("kind") })
        items("trigger", triggers, asked[0])
        items("alias", listOf(node(1, 0, "kick")), asked[1])
        items("script", listOf(node(1, 0, "On connect")), asked[2])
        waitFor(row("Greet"))
    }

    @Test
    fun theLibraryShowsAFolderAtATimeAndItsSwitchesSwitchItemsOnAndOff() {
        compose.onNode(row("You hit")).assertDoesNotExist()
        compose.onNode(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_triggers), 2))).assertIsDisplayed()
        activate(row("Combat"))
        waitFor(row("You hit"))
        compose.onNode(row("Greet")).assertDoesNotExist()
        compose.onNode(hasText("Combat") and !hasClickAction()).assertIsDisplayed()

        activate(switch("You hit"))
        val enable = received("enableScriptItem")
        assertEquals("trigger", enable.getString("kind"))
        assertEquals(2, enable.getInt("item"))
        assertFalse(enable.getBoolean("enabled"))
        session.send(fields("trigger", trigger(2, 1, "You hit", enabled = false), enable))
        items("trigger", listOf(triggers[0], node(2, 1, "You hit", enabled = false), triggers[2]), received("scriptItems"))
        waitFor(switch("You hit") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        waitFor(hasText(string(R.string.scripts_off)))

        // The breadcrumb's first step goes back to the top, and so does Back, which leaves the library only from there
        activate(hasText(string(R.string.scripts_all, string(R.string.scripts_triggers))) and hasClickAction())
        waitFor(row("Greet"))
        activate(row("Combat"))
        waitFor(row("You hit"))
        back()
        waitFor(row("Greet"))
        compose.onNode(library).assertIsDisplayed()
        checkAccessibility()
    }

    @Test
    fun aSearchFindsNamesOfEveryKindWithWhereTheyAre() {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(library)).performTextInput("k")
        waitFor(row("kick"))
        compose.onNode(row("Greet")).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(library)).performTextClearance()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(library)).performTextInput("hit")
        waitFor(row("You hit"))
        compose.onNode(hasText(string(R.string.scripts_triggers) + string(R.string.scripts_path_separator) + "Combat")).assertIsDisplayed()
    }

    @Test
    fun anEditIsAppliedWithUndoAndBackAsksBeforeDroppingOne() {
        openGreet()
        compose.onNode(apply).assertIsNotEnabled()
        compose.onNode(name).performTextClearance()
        compose.onNode(name).performTextInput("Wave")
        compose.onNode(apply).assertIsEnabled()

        // Back asks, and Keep editing keeps the draft
        back()
        activate(hasText(string(R.string.scripts_keep_editing)) and hasClickAction())
        waitFor(hasText("Wave") and hasSetTextAction())

        activate(apply)
        val edit = received("editScriptItem")
        assertEquals(3, edit.getInt("item"))
        assertEquals("Wave", edit.getJSONObject("fields").getString("name"))
        assertEquals("You wave", edit.getJSONObject("fields").getJSONArray("patterns").getJSONObject(0).getString("text"))
        session.send(fields("trigger", trigger(3, 0, "Wave"), edit))
        items("trigger", listOf(triggers[0], triggers[1], node(3, 0, "Wave")), received("scriptItems"))

        activate(hasText(string(R.string.scripts_undo)) and hasClickAction())
        val undo = received("editScriptItem")
        assertEquals("Greet", undo.getJSONObject("fields").getString("name"))

        // A change and then Back, discarded, leaves the page and sends nothing
        session.send(fields("trigger", trigger(3, 0, "Greet"), undo))
        waitFor(hasText("Greet") and hasSetTextAction())
        compose.onNode(name).performTextInput("!")
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.scripts_discard)) and hasClickAction())
        waitFor(library)
        session.assertNoRequest("editScriptItem", 1_000)
        compose.onNode(row("Greet") and hasText(string(R.string.scripts_draft))).assertDoesNotExist()
    }

    @Test
    fun aDraftIsKeptOverATripToTheGame() {
        openGreet()
        compose.onNode(name).performTextClearance()
        compose.onNode(name).performTextInput("Wave")
        activate(hasContentDescription(string(R.string.scripts_back_to_game)))
        waitFor(hasText(string(R.string.game_title)))

        // The library marks the item, under the name the engine has, and its page has the draft
        openTheLibraryFromTheGame()
        waitFor(row("Greet") and hasText(string(R.string.scripts_draft)))
        openGreet(shown = "Wave")
        compose.onNode(apply).assertIsEnabled()
        session.assertNoRequest("editScriptItem", 500)

        // The game's last line, at the library's foot, goes back to the game too
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.scripts_discard)) and hasClickAction())
        activate(hasText(CONSOLE_LINE))
        waitFor(hasText(string(R.string.game_title)))
    }

    @Test
    fun aNewItemOpensAndANewFolderIsGoneInto() {
        activate(hasContentDescription(string(R.string.scripts_new_trigger)))
        val add = received("addScriptItem")
        assertEquals("trigger", add.getString("kind"))
        assertEquals(0, add.getInt("parent"))
        assertEquals("after the folder and the trigger at the top level", 2, add.getInt("position"))
        assertFalse(add.getBoolean("folder"))
        assertEquals(string(R.string.scripts_new_trigger), add.getString("name"))
        val made = trigger(9, 0, string(R.string.scripts_new_trigger)).put("active", false)
        session.send(fields("trigger", made, add))
        items("trigger", triggers + node(9, 0, string(R.string.scripts_new_trigger)).put("active", false), received("scriptItems"))
        assertEquals(9, received("scriptItem").getInt("item"))
        waitFor(hasText(string(R.string.scripts_new_trigger)) and hasSetTextAction() and hasAnyAncestor(page))
        activate(hasContentDescription(string(R.string.navigate_back)))
        // Switched on, but it runs only once applied
        waitFor(row(string(R.string.scripts_new_trigger)) and hasText(string(R.string.scripts_idle)))

        activate(hasContentDescription(string(R.string.scripts_add_folder)))
        val folder = received("addScriptItem")
        assertTrue(folder.getBoolean("folder"))
        assertEquals(3, folder.getInt("position"))
        val folderName = string(R.string.scripts_new_folder)
        session.send(fields("trigger", trigger(10, 0, folderName).put("folder", true), folder))
        items("trigger", triggers + node(9, 0, string(R.string.scripts_new_trigger)) + node(10, 0, folderName, folder = true), received("scriptItems"))
        waitFor(hasText(string(R.string.scripts_folder_empty)))
        compose.onNode(hasText(folderName) and !hasClickAction() and hasAnyAncestor(library)).assertIsDisplayed()

        // What is made in a folder goes into it
        activate(hasContentDescription(string(R.string.scripts_new_trigger)))
        assertEquals(10, received("addScriptItem").getInt("parent"))
    }

    @Test
    fun aTriggersPatternsAreAddedTypedMovedAndRemoved() {
        openGreet()
        activate(hasText(string(R.string.scripts_add_pattern)) and hasClickAction())
        val second = hasSetTextAction() and hasText(string(R.string.scripts_pattern, 2))
        waitFor(second)
        compose.onNode(second).performTextInput("^hi$")

        activate(hasContentDescription(string(R.string.scripts_pattern_type_of, 2, string(R.string.pattern_substring))))
        waitFor(hasText(string(R.string.pattern_regex_description)))
        // A colour pattern is made on the desktop for now
        compose.onNode(hasText(string(R.string.pattern_color))).assertDoesNotExist()
        activate(hasText(string(R.string.pattern_regex)) and hasClickAction())
        waitFor(hasContentDescription(string(R.string.scripts_pattern_type_of, 2, string(R.string.pattern_regex))))
        // The sheet's Close leaves the type as it was
        activate(hasContentDescription(string(R.string.scripts_pattern_type_of, 2, string(R.string.pattern_regex))))
        activate(hasContentDescription(string(R.string.scripts_pattern_type_close)))
        compose.onNode(hasText(string(R.string.pattern_regex_description))).assertDoesNotExist()
        waitFor(hasContentDescription(string(R.string.scripts_pattern_type_of, 2, string(R.string.pattern_regex))))

        activate(hasContentDescription(string(R.string.scripts_pattern_up, 2)))
        waitFor(hasContentDescription(string(R.string.scripts_pattern_type_of, 1, string(R.string.pattern_regex))))
        activate(hasContentDescription(string(R.string.scripts_pattern_remove, 2)))
        compose.onNode(second).assertDoesNotExist()

        activate(apply)
        val patterns = received("editScriptItem").getJSONObject("fields").getJSONArray("patterns")
        assertEquals(1, patterns.length())
        assertEquals("^hi$", patterns.getJSONObject(0).getString("text"))
        assertEquals("regex", patterns.getJSONObject(0).getString("type"))
    }

    @Test
    fun aTriggersOptionsAreSentAndANumberOutOfRangeIsNot() {
        openGreet()
        activate(hasText(string(R.string.scripts_match_all)))
        val within = hasSetTextAction() and hasText(string(R.string.scripts_match_within))
        waitFor(within)
        compose.onNode(within).performTextClearance()
        compose.onNode(within).performTextInput("3")
        // More lines than the desktop allows: the field says so, and the 3 stays
        compose.onNode(within).performTextClearance()
        compose.onNode(within).performTextInput("500")
        waitFor(hasText(string(R.string.scripts_number_range, 0, MAX_MATCH_LINES)))

        activate(hasText(string(R.string.scripts_highlight)))
        val colour = hasSetTextAction() and hasText(string(R.string.scripts_highlight_fg))
        waitFor(colour)
        compose.onNode(colour).performTextInput("#00ff00")

        activate(apply)
        val fields = received("editScriptItem").getJSONObject("fields")
        assertEquals(3, fields.getInt("lineDelta"))
        assertTrue(fields.getBoolean("highlight"))
        assertEquals("#00ff00", fields.getString("highlightFg"))
        assertEquals("", fields.getString("highlightBg"))
    }

    @Test
    fun anAliasHasItsCommandAndAScriptItsEvents() {
        activate(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_aliases), 1)) and hasClickAction())
        activate(row("kick"))
        val alias = node(1, 0, "kick").put("script", "").put("pattern", "^k$").put("command", "kick rat")
        session.send(fields("alias", alias, received("scriptItem")))
        val command = hasSetTextAction() and hasText(string(R.string.scripts_alias_command))
        waitFor(command and hasText("kick rat"))
        compose.onNode(hasSetTextAction() and hasText("^k$")).assertIsDisplayed()
        compose.onNode(command).performTextClearance()
        compose.onNode(command).performTextInput("kick orc")
        activate(apply)
        val edit = received("editScriptItem")
        assertEquals("alias", edit.getString("kind"))
        assertEquals("kick orc", edit.getJSONObject("fields").getString("command"))
        assertEquals("^k$", edit.getJSONObject("fields").getString("pattern"))
        session.send(fields("alias", JSONObject(alias.toString()).put("command", "kick orc"), edit))
        waitFor(hasText(string(R.string.scripts_applied, "kick")))
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(hasText(string(R.string.scripts_kind_count, string(R.string.scripts_scripts), 1)) and hasClickAction())
        activate(row("On connect"))
        val script = node(1, 0, "On connect").put("script", "").put("eventHandlers", JSONArray().put("sysConnectionEvent"))
        session.send(fields("script", script, received("scriptItem")))
        waitFor(hasText("sysConnectionEvent"))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.scripts_event_new))).performTextInput("sysDisconnectionEvent")
        activate(hasContentDescription(string(R.string.scripts_event_add)))
        activate(hasContentDescription(string(R.string.scripts_event_remove, "sysConnectionEvent")))
        // To the page's end, so that no empty field is cut off at the foot of what shows, as the event's can be with
        // the keyboard up: with only the 8 dp over its outline showing, an empty labelled field is an edit box with no
        // name (docs/roadmap.md, "A known gap, the toolkit's"). By the scroll action itself: performScrollTo() runs
        // the checks first, on the page as it is
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        val events = received("editScriptItem").getJSONObject("fields").getJSONArray("eventHandlers")
        assertEquals(listOf("sysDisconnectionEvent"), (0 until events.length()).map(events::getString))
    }

    @Test
    fun aDeleteIsSentOnlyOnceItsUndoHasGone() {
        openGreet()
        activate(hasContentDescription(string(R.string.scripts_delete, "Greet")))
        waitFor(hasText(string(R.string.scripts_deleted, "Greet")))
        compose.onNode(row("Greet")).assertDoesNotExist()
        activate(hasText(string(R.string.scripts_undo)) and hasClickAction())
        waitFor(row("Greet"))
        session.assertNoRequest("deleteScriptItem", 1_000)

        openGreet()
        activate(hasContentDescription(string(R.string.scripts_delete, "Greet")))
        waitFor(hasText(string(R.string.scripts_deleted, "Greet")))
        // Leaving the library while the Undo is offered lets it go
        back()
        waitFor(hasText(string(R.string.game_title)))
        val delete = received("deleteScriptItem")
        assertEquals("trigger", delete.getString("kind"))
        assertEquals(3, delete.getInt("item"))
        items("trigger", triggers.take(2), received("scriptItems"))
    }

    @Test
    fun anItemsLuaIsAFieldOfItsPageThatIndentsCompletesAndTypesLuasSymbols() {
        openGreet(script = "if x then")
        // In the page itself, among its other fields: nothing else there is named for the Lua, as a row that opened it was
        waitFor(editor and hasText("if x then") and hasSetTextAction() and hasAnyAncestor(page))
        compose.onNode(name).assertExists()
        compose.onAllNodes(hasText(string(R.string.scripts_lua)) and !editor).assertCountEquals(0)
        // The keys over the keyboard are the code's: they come once it is typed in
        val closeBracket = hasContentDescription(string(R.string.lua_type, ")"))
        compose.onNode(closeBracket).assertDoesNotExist()

        // Enter at the end of a line that opens a block indents the next
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(9, 9, true) }
        compose.onNode(editor).performTextInput("\n")
        waitFor(editor and hasText("if x then\n  "))
        // A name typed is completed from Mudlet's functions, and the row over the keyboard types what Lua needs
        compose.onNode(editor).performTextInput("sen")
        activate(hasContentDescription(string(R.string.lua_complete, "send(command, showOnScreen)")))
        activate(closeBracket)
        waitFor(editor and hasText("if x then\n  send()"))
        // What is typed is the item's draft, as in its other fields
        compose.onNode(apply).assertIsEnabled()
    }

    @Test
    fun luaIsCheckedOnlyWhenAppliedAndWhatIsWrongGoesOnceTheCodeIsChanged() {
        openGreet(script = "if x then")
        waitFor(editor and hasText("if x then"))
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(9, 9, true) }
        compose.onNode(editor).performTextInput("\n  send()")
        val broken = "if x then\n  send()"
        waitFor(editor and hasText(broken))

        // Code that does not compile is not checked as it is typed: the engine is asked nothing, and no problem shows
        compose.mainClock.advanceTimeBy(1_000)
        session.assertNoRequest("checkLua", 1_000)
        val goTo = hasText(string(R.string.lua_go_to_line)) and hasClickAction()
        compose.onNode(goTo).assertDoesNotExist()

        // Apply asks the engine what is wrong with it, and saves it all the same, as the desktop's editor does
        activate(apply)
        val check = received("checkLua")
        assertEquals(broken, check.getString("code"))
        val save = received("editScriptItem")
        assertEquals(broken, save.getJSONObject("fields").getString("script"))
        val message = "'end' expected near '<eof>'"
        session.send(JSONObject().put("t", "luaCheck").put("ok", false).put("line", 2).put("message", message).put("re", check.get("id")))
        val problem = hasText(string(R.string.lua_problem_at, 2, message))
        waitFor(problem)
        session.send(fields("trigger", trigger(3, 0, "Greet").put("script", broken).put("error", message), save))
        items("trigger", triggers, received("scriptItems"))
        waitFor(hasText(string(R.string.scripts_applied, "Greet")))
        compose.onNode(problem).assertExists()
        compose.onNode(apply).assertIsNotEnabled()
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(0, 0, true) }
        activate(goTo)
        waitFor(editor and SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(10)))

        // The problem was of the code as applied: it goes with the first key, and typing still asks the engine nothing
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(18, 18, true) }
        compose.onNode(editor).performTextInput("\nend")
        val mended = "$broken\nend"
        waitFor(editor and hasText(mended))
        compose.onNode(problem).assertDoesNotExist()
        compose.onNode(goTo).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        session.assertNoRequest("checkLua", 1_000)

        activate(apply)
        val again = received("checkLua")
        assertEquals(mended, again.getString("code"))
        val saved = received("editScriptItem")
        session.send(JSONObject().put("t", "luaCheck").put("ok", true).put("re", again.get("id")))
        val kept = saved.getJSONObject("fields").getString("script")
        session.send(fields("trigger", trigger(3, 0, "Greet").put("script", kept), saved))
        items("trigger", triggers, received("scriptItems"))
        waitFor(apply and !isEnabled())
        compose.onNode(problem).assertDoesNotExist()

        // Nothing is left to apply, so the page goes without asking, and the code comes back as the engine has it
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(row("Greet"))
        val engines = "$kept\n-- as the engine keeps it"
        openGreet(script = engines)
        waitFor(editor and hasText(engines) and hasAnyAncestor(page))
        compose.onNode(apply).assertIsNotEnabled()
    }

    @Test
    fun luaTypedAndNotAppliedIsKeptOrAskedAbout() {
        openGreet(script = "x = 1")
        waitFor(editor and hasText("x = 1"))
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(5, 5, true) }
        compose.onNode(editor).performTextInput("2")
        waitFor(editor and hasText("x = 12"))
        compose.onNode(apply).assertIsEnabled()

        // Back asks before the code typed is dropped, and from the page: there is no screen of the code's to leave first
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.scripts_keep_editing)) and hasClickAction())
        compose.onNode(editor and hasText("x = 12")).assertExists()

        // A trip to the game keeps it
        activate(hasContentDescription(string(R.string.scripts_back_to_game)))
        waitFor(hasText(string(R.string.game_title)))
        openTheLibraryFromTheGame()
        openGreet(script = "x = 1")
        waitFor(editor and hasText("x = 12") and hasAnyAncestor(page))

        back()
        activate(hasText(string(R.string.scripts_discard)) and hasClickAction())
        waitFor(row("Greet"))
        openGreet(script = "x = 1")
        waitFor(editor and hasText("x = 1") and hasAnyAncestor(page))
        compose.onNode(apply).assertIsNotEnabled()
    }

    @Test
    fun whatTheEngineRefusesIsSaid() {
        openGreet()
        compose.onNode(name).performTextInput("!")
        activate(apply)
        session.refuse(received("editScriptItem"), "no such item")
        waitFor(hasText(string(R.string.scripts_refused_apply, "no such item")))
        // The draft is kept, to try again
        compose.onNode(apply).assertIsEnabled()
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.scripts_discard)) and hasClickAction())

        // An item gone since it was listed says so on its page, and goes from the list
        activate(row("Greet"))
        session.refuse(received("scriptItem"), "no such item")
        waitFor(hasText(string(R.string.scripts_refused_open, "no such item")))
        items("trigger", triggers.take(2), received("scriptItems"))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(row("Combat"))
        compose.onNode(row("Greet")).assertDoesNotExist()
    }

    /** Opens Greet from the library; [shown] is the name its page shows, a draft's when it has one, and [script] its Lua. */
    private fun openGreet(shown: String = "Greet", script: String = "") {
        activate(row("Greet"))
        val open = received("scriptItem")
        assertEquals(3, open.getInt("item"))
        session.send(fields("trigger", trigger(3, 0, "Greet").put("script", script), open))
        waitFor(hasText(shown) and hasSetTextAction() and hasAnyAncestor(page))
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /**
     * The next request of [type] the app sends, once its screens have settled: what a screen asks for as it opens, or
     * sends as it goes, comes from its composition, which a test must wait on for it to go on.
     */
    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false, enabled: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("parent", parent).put("name", name).put("folder", folder).put("enabled", enabled).put("active", enabled)
        .put("package", "").put("error", "")

    private fun trigger(id: Int, parent: Int, name: String, enabled: Boolean = true): JSONObject = node(id, parent, name, enabled = enabled)
        .put("script", "").put("patterns", JSONArray().put(JSONObject().put("text", "You wave").put("type", "substring")))
        .put("command", "").put("lineDelta", -1).put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false)
        .put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")

    private fun items(kind: String, items: List<JSONObject>, request: JSONObject) = session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray(items)).put("re", request.get("id")))

    private fun fields(kind: String, fields: JSONObject, request: JSONObject): JSONObject = JSONObject().put("t", "scriptItem").put("kind", kind).put("fields", fields).put("re", request.get("id"))

    /** A library row: the item's name, which opens it or goes into the folder. */
    private fun row(name: String) = hasText(name) and hasClickAction() and hasAnyAncestor(library)

    private fun switch(name: String) = hasContentDescription(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // Each window's: a dialog is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /**
     * What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. What is
     * in the library's list or an item's page is scrolled to first, as TalkBack does.
     */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(library)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(library).performScrollToNode(node)
        } else if (compose.onAllNodes(node and hasAnyAncestor(page)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-scripts"
        const val CONSOLE_LINE = "A rat bites you."

        // Further than any page is long
        const val PAGE_END_PX = 100_000f
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
