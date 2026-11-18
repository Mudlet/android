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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
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
 * bar, it lists each kind one folder at a time, its switches switch items on and off, its search finds names and shows
 * where the engine found the text, asked again when an option changes, its
 * buttons make an item, which opens, and a folder, which is gone into, and the game's last line at its foot goes back
 * to the game; an item's page applies an edit with Undo, asks on Back before dropping one not applied, keeps it as a
 * draft over a trip to the game, edits a trigger's patterns and options, an alias's command, a script's events, a
 * timer's time, a key binding's key, a toolbar's place and a button's commands, moves to a folder picked from a sheet,
 * and deletes with Undo, sent only once
 * the library goes; a timer with offset timers in it is gone into, and the buttons' top level takes toolbars; its Lua
 * is edited in the page, which indents a new line, completes a function's name and types Lua's symbols, is checked
 * only when applied, when what the engine finds wrong shows with a way to its line until the code is changed, and is
 * kept or asked about as the other fields are; and what the engine refuses is said. Controls are found by the labels
 * TalkBack reads and activated through their accessibility actions.
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

    // "Heal" with the offset timer "Then" in it
    private val timers = listOf(node(1, 0, "Heal", summary = "00:00:02.500"), node(2, 1, "Then", summary = "00:00:01.000"))
    private val keys = listOf(node(1, 0, "North", summary = "keypad + 8"), node(2, 0, "Odd", summary = "control + Shift"))

    // The toolbar "Bar" with the button "Sword"
    private val buttons = listOf(node(1, 0, "Bar", folder = true), node(2, 1, "Sword", summary = "wield sword"))

    @Before
    fun openTheLibrary() {
        compose.enableAccessibilityChecksOfWhatShows()
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
        val asked = (1..6).map { received("scriptItems") }
        assertEquals(listOf("trigger", "alias", "script", "timer", "key", "button"), asked.map { it.getString("kind") })
        items("trigger", triggers, asked[0])
        items("alias", listOf(node(1, 0, "kick")), asked[1])
        items("script", listOf(node(1, 0, "On connect")), asked[2])
        items("timer", timers, asked[3])
        items("key", keys, asked[4])
        items("button", buttons, asked[5])
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
    fun aSearchShowsWhereTheEngineFoundTheTextAndItsOptionsAskAgain() {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(library)).performTextInput("hp")
        // The search waits for a pause in the typing, on the test's clock, which a wait on the socket does not move
        compose.mainClock.advanceTimeBy(1_000)
        val asked = received("searchScriptItems")
        assertEquals(listOf("hp", false, false), listOf(asked.getString("text"), asked.getBoolean("caseSensitive"), asked.getBoolean("wholeWord")))
        val lua = JSONObject().put("kind", "trigger").put("item", 3).put("name", "Greet").put("field", "lua").put("index", 1).put("text", "send(\"hp\")").put("start", 6)
        session.send(JSONObject().put("t", "scriptSearch").put("text", "hp").put("caseSensitive", false).put("wholeWord", false).put("matches", JSONArray().put(lua)).put("more", true).put("re", asked.get("id")))
        // No name has the text: the row is there for what the engine found in its Lua
        waitFor(row("Greet"))
        compose.onNode(hasText(string(R.string.scripts_place_lua, 2))).assertIsDisplayed()
        compose.onNode(hasText("send(\"hp\")")).assertIsDisplayed()
        compose.onNode(hasText(compose.activity.resources.getQuantityString(R.plurals.scripts_search_more, 1, 1))).assertIsDisplayed()
        checkAccessibility()

        activate(hasText(string(R.string.scripts_search_case)) and hasClickAction())
        compose.mainClock.advanceTimeBy(1_000)
        val again = received("searchScriptItems")
        assertTrue(again.getBoolean("caseSensitive"))
        compose.onNode(row("Greet")).assertDoesNotExist()
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
        compose.onNode(hasText(string(R.string.pattern_color))).assertExists()
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
    fun aColourPatternsColoursArePickedFromTheirSheets() {
        openGreet()
        activate(hasContentDescription(string(R.string.scripts_pattern_type_of, 1, string(R.string.pattern_substring))))
        activate(hasText(string(R.string.pattern_color)) and hasClickAction())
        // "You wave" names no colours, so none are kept, and the pattern says it does not run until one is picked
        waitFor(hasText(string(R.string.scripts_color_none)))
        val any = string(R.string.scripts_color_any)
        activate(hasText(string(R.string.scripts_color_text, any)))
        waitFor(hasText(string(R.string.scripts_color_text_title, 1)))
        val red = string(R.string.scripts_color_named, string(R.string.ansi_red), 1)
        activate(hasText(red))
        waitFor(hasText(string(R.string.scripts_color_text, red)))
        compose.onNode(hasText(string(R.string.scripts_color_none))).assertDoesNotExist()

        activate(hasText(string(R.string.scripts_color_background, any)))
        val other = field(R.string.scripts_color_other)
        waitFor(other)
        try {
            compose.onNode(other).performScrollTo().performTextReplacement("300")
        } catch (e: Throwable) {
            throw AssertionError("DIAG\n" + diagDump() + "\nENDDIAG", e)
        }
        waitFor(hasText(string(R.string.scripts_number_range, 16, 255)))
        // Not the 16 held from before, while the field shows what is not a colour
        compose.onNode(hasText(string(R.string.scripts_color_use)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(other).performTextReplacement("208")
        activate(hasText(string(R.string.scripts_color_use)))
        waitFor(hasText(string(R.string.scripts_color_background, string(R.string.scripts_color_number, 208))))

        activate(apply)
        val pattern = received("editScriptItem").getJSONObject("fields").getJSONArray("patterns").getJSONObject(0)
        assertEquals("color", pattern.getString("type"))
        assertEquals("ANSI_COLORS_F{1}_B{208}", pattern.getString("text"))
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
    fun aTimerWithOffsetTimersIsGoneIntoAndItsTimeIsSetInFourFields() {
        activate(kind(R.string.scripts_timers, 2))
        waitFor(row("Heal") and hasText("00:00:02.500"))
        compose.onNode(row("Then")).assertDoesNotExist()
        activate(row("Heal"))
        waitFor(row("Then"))
        // Mudlet adds only to a folder
        compose.onNode(hasContentDescription(string(R.string.scripts_new_timer))).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.scripts_add_folder))).assertDoesNotExist()

        activate(hasContentDescription(string(R.string.scripts_edit_item, "Heal")))
        val open = received("scriptItem")
        assertEquals("timer", open.getString("kind"))
        assertEquals(1, open.getInt("item"))
        session.send(fields("timer", timer(1, 0, "Heal", 2_500), open))
        waitFor(hasText(string(R.string.scripts_timer_every)))
        compose.onNode(field(R.string.scripts_timer_seconds) and hasText("2")).assertExists()
        compose.onNode(field(R.string.scripts_timer_milliseconds) and hasText("500")).assertExists()
        compose.onNode(hasText(string(R.string.scripts_timer_offset))).assertDoesNotExist()

        val minutes = field(R.string.scripts_timer_minutes)
        compose.onNode(minutes).performTextClearance()
        compose.onNode(minutes).performTextInput("1")
        // More than a minute has: the field says so, and the 2 seconds stay
        val seconds = field(R.string.scripts_timer_seconds)
        compose.onNode(seconds).performTextClearance()
        compose.onNode(seconds).performTextInput("75")
        waitFor(hasText(string(R.string.scripts_number_range, 0, 59)))
        compose.onNode(field(R.string.scripts_timer_command)).performTextReplacement("cast heal")
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        val edit = received("editScriptItem")
        assertEquals("timer", edit.getString("kind"))
        assertEquals(62_500, edit.getJSONObject("fields").getInt("time"))
        assertEquals("cast heal", edit.getJSONObject("fields").getString("command"))
        assertFalse("what is only read is not sent", edit.getJSONObject("fields").has("offset"))
        session.send(fields("timer", timer(1, 0, "Heal", 62_500).put("command", "cast heal"), edit))
        waitFor(hasText(string(R.string.scripts_applied, "Heal")))
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(row("Then"))
        session.send(fields("timer", timer(2, 1, "Then", 1_000).put("offset", true), received("scriptItem")))
        waitFor(hasText(string(R.string.scripts_timer_after)))
        compose.onNode(hasText(string(R.string.scripts_timer_offset))).assertExists()
        compose.onNode(hasText(string(R.string.scripts_timer_every))).assertDoesNotExist()
    }

    @Test
    fun aKeyIsPickedFromASheetAndTheKeysHeldAreChips() {
        activate(kind(R.string.scripts_keys, 2))
        activate(row("North"))
        val open = received("scriptItem")
        assertEquals("key", open.getString("kind"))
        session.send(fields("key", key(1, "North", 0x38, KEYPAD, "keypad + 8"), open))
        waitFor(hasText(string(R.string.scripts_key_pick, "8")))
        val keypad = hasText(string(R.string.scripts_key_keypad)) and hasClickAction()
        val control = hasText(string(R.string.scripts_key_control)) and hasClickAction()
        compose.onNode(keypad).assertIsSelected()
        compose.onNode(control).assertIsNotSelected()
        compose.onNode(hasText(string(R.string.scripts_key_note))).assertExists()

        activate(hasText(string(R.string.scripts_key_pick, "8")))
        waitFor(hasText(string(R.string.scripts_key_sheet)))
        compose.onNode(hasText("8") and hasClickAction()).assertIsSelected()
        // The sheet's Close leaves the key as it was
        activate(hasContentDescription(string(R.string.scripts_key_sheet_close)))
        waitFor(hasText(string(R.string.scripts_key_pick, "8")))
        compose.onNode(hasText(string(R.string.scripts_key_sheet))).assertDoesNotExist()

        activate(hasText(string(R.string.scripts_key_pick, "8")))
        waitFor(hasText(string(R.string.scripts_key_sheet)))
        compose.onNode(hasText("F1") and hasClickAction()).performScrollTo()
        activate(hasText("F1"))
        waitFor(hasText(string(R.string.scripts_key_pick, "F1")))
        activate(control)
        activate(keypad)
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        val edit = received("editScriptItem")
        assertEquals("key", edit.getString("kind"))
        assertEquals(F1, edit.getJSONObject("fields").getInt("key"))
        assertEquals(CONTROL, edit.getJSONObject("fields").getInt("modifiers"))
        assertFalse("what is only read is not sent", edit.getJSONObject("fields").has("keyName"))
        session.send(fields("key", key(1, "North", F1, CONTROL, "control + F1"), edit))
        waitFor(hasText(string(R.string.scripts_applied, "North")))
        activate(hasContentDescription(string(R.string.navigate_back)))

        // A key the sheet does not have, set on the desktop, goes by the name the engine wrote; no key at all says so
        activate(row("Odd"))
        session.send(fields("key", key(2, "Odd", SHIFT_KEY, CONTROL, "control + Shift"), received("scriptItem")))
        waitFor(hasText(string(R.string.scripts_key_pick, "Shift")))
        activate(hasText(string(R.string.scripts_key_pick, "Shift")))
        activate(hasText(string(R.string.scripts_key_none)))
        waitFor(hasText(string(R.string.scripts_key_pick, string(R.string.scripts_key_none))))
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        assertEquals(0, received("editScriptItem").getJSONObject("fields").getInt("key"))
    }

    @Test
    fun theButtonsTopLevelTakesToolbarsWhichButtonsGoIn() {
        activate(kind(R.string.scripts_buttons, 1))
        waitFor(row("Bar"))
        compose.onNode(hasContentDescription(string(R.string.scripts_new_button))).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.scripts_add_folder))).assertDoesNotExist()
        val toolbar = string(R.string.scripts_new_toolbar)
        activate(hasContentDescription(toolbar))
        val add = received("addScriptItem")
        assertEquals("button", add.getString("kind"))
        assertEquals(0, add.getInt("parent"))
        assertTrue("a toolbar is a folder", add.getBoolean("folder"))
        assertEquals(toolbar, add.getString("name"))
        session.send(fields("button", button(9, 0, toolbar, folder = true, enabled = false), add))
        items("button", buttons + node(9, 0, toolbar, folder = true, enabled = false), received("scriptItems"))
        // The new toolbar is gone into, where a button and a menu's folder are made
        waitFor(hasText(string(R.string.scripts_folder_empty)))
        compose.onNode(hasContentDescription(string(R.string.scripts_add_folder))).assertExists()
        activate(hasContentDescription(string(R.string.scripts_new_button)))
        val made = received("addScriptItem")
        assertEquals(9, made.getInt("parent"))
        assertFalse(made.getBoolean("folder"))
        assertEquals(string(R.string.scripts_new_button), made.getString("name"))
    }

    @Test
    fun aToolbarHasItsPlaceAndAButtonItsCommands() {
        activate(kind(R.string.scripts_buttons, 1))
        activate(row("Bar"))
        waitFor(row("Sword") and hasText("wield sword"))
        activate(hasContentDescription(string(R.string.scripts_edit_folder)))
        val open = received("scriptItem")
        assertEquals("button", open.getString("kind"))
        session.send(fields("button", button(1, 0, "Bar", folder = true), open))
        waitFor(hasText(string(R.string.scripts_bar_place)))
        compose.onNode(field(R.string.scripts_button_command)).assertDoesNotExist()
        compose.onNode(hasText(string(R.string.scripts_bar_top)) and hasClickAction()).assertIsSelected()
        activate(hasText(string(R.string.scripts_bar_left)))
        activate(hasText(string(R.string.scripts_bar_vertical)))
        val columns = field(R.string.scripts_bar_columns)
        compose.onNode(columns).performTextClearance()
        compose.onNode(columns).performTextInput("2")
        compose.onNode(field(R.string.scripts_button_css)).performTextReplacement("QToolBar { color: red; }")
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        val edit = received("editScriptItem")
        val bar = edit.getJSONObject("fields")
        assertEquals("left", bar.getString("location"))
        assertEquals("vertical", bar.getString("orientation"))
        assertEquals(2, bar.getInt("columns"))
        assertEquals("QToolBar { color: red; }", bar.getString("css"))
        val saved = button(1, 0, "Bar", folder = true).put("location", "left").put("orientation", "vertical").put("columns", 2).put("css", bar.getString("css"))
        session.send(fields("button", saved, edit))
        waitFor(hasText(string(R.string.scripts_applied, "Bar")))
        activate(hasContentDescription(string(R.string.navigate_back)))

        activate(row("Sword"))
        session.send(fields("button", button(2, 1, "Sword").put("commandDown", "wield sword").put("commandUp", "sheathe sword"), received("scriptItem")))
        val down = field(R.string.scripts_button_command)
        waitFor(down and hasText("wield sword"))
        compose.onNode(hasText(string(R.string.scripts_bar_place))).assertDoesNotExist()
        val up = field(R.string.scripts_button_command_up)
        compose.onNode(up).assertDoesNotExist()
        activate(hasText(string(R.string.scripts_button_push_down)))
        waitFor(up)
        compose.onNode(up).performTextReplacement("remove sword")
        compose.onNode(page).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PAGE_END_PX) }
        activate(apply)
        val sword = received("editScriptItem").getJSONObject("fields")
        assertEquals("wield sword", sword.getString("commandDown"))
        assertTrue(sword.getBoolean("pushDown"))
        assertEquals("remove sword", sword.getString("commandUp"))
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
    fun anItemIsMovedToAFolderPickedFromASheet() {
        openGreet()
        activate(hasContentDescription(string(R.string.scripts_move, "Greet")))
        waitFor(hasText(string(R.string.scripts_move_title, "Greet")))
        // Greet is at the top level, which is therefore not offered
        compose.onNode(hasText(string(R.string.scripts_move_top))).assertDoesNotExist()
        activate(hasText("Combat") and hasClickAction())
        val move = received("moveScriptItem")
        assertEquals(listOf("trigger", 3, 1, 1), listOf(move.getString("kind"), move.getInt("item"), move.getInt("parent"), move.getInt("position")))
        items("trigger", listOf(triggers[0], triggers[1], node(3, 1, "Greet")), received("scriptItems"))
        waitFor(hasText(string(R.string.scripts_moved, "Greet", "Combat")))
        compose.onNode(hasText(string(R.string.scripts_move_title, "Greet"))).assertDoesNotExist()
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
        typeLua("\n")
        waitFor(editor and hasText("if x then\n  "))
        // A name typed is completed from Mudlet's functions, and the row over the keyboard types what Lua needs
        typeLua("sen")
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
        typeLua("\n  send()")
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
        typeLua("\nend")
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
    fun theLuaFieldHasAllItsLinesAndThePageScrollsToTheCursorsLine() {
        val code = (1..80).joinToString("\n") { "line$it = $it" }
        openGreet(script = code)
        waitFor(editor and hasText(code))
        // The lines have no box of their own to scroll in: the field is as tall as they are, and the page scrolls them
        val shown = compose.onNode(page).fetchSemanticsNode().size.height
        assertTrue("the field is taller than what shows of the page", compose.onNode(editor).fetchSemanticsNode().size.height > shown)

        typeLua("-")
        activate(apply)
        val check = received("checkLua")
        val save = received("editScriptItem")
        session.send(JSONObject().put("t", "luaCheck").put("ok", false).put("line", 80).put("message", "unexpected symbol near '-'").put("re", check.get("id")))
        session.send(fields("trigger", trigger(3, 0, "Greet").put("script", check.getString("code")), save))
        items("trigger", triggers, received("scriptItems"))

        // The last line shows when the page is within the field's own edge of its end
        val edge = with(compose.density) { LUA_FIELD_EDGE.toPx() } + 1
        fun lastLineShows() = compose.onNode(page).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.maxValue() - it.value() <= edge }
        assertFalse("the cursor is on the first line", lastLineShows())
        val goTo = hasText(string(R.string.lua_go_to_line)) and hasClickAction()
        activate(goTo)
        compose.waitUntil(WAIT_MS) { lastLineShows() }
        // And again once the player has scrolled away from it, though the cursor is on it already
        compose.waitForIdle()
        compose.onNode(name).performScrollTo()
        compose.waitUntil(WAIT_MS) { !lastLineShows() }
        activate(goTo)
        compose.waitUntil(WAIT_MS) { lastLineShows() }
    }

    @Test
    fun luaTypedAndNotAppliedIsKeptOrAskedAbout() {
        openGreet(script = "x = 1")
        waitFor(editor and hasText("x = 1"))
        compose.onNode(editor).performSemanticsAction(SemanticsActions.SetSelection) { it(5, 5, true) }
        typeLua("2")
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

    /**
     * Types [text] at the Lua's cursor, and waits for the keyboard this brings where the accessibility checks run: they
     * are of the page as it stays, not as it moves over the keyboard.
     */
    private fun typeLua(text: String) {
        compose.onNode(editor).performTextInput(text)
        if (Build.VERSION.SDK_INT >= 34) compose.keyboardHasCome(hasContentDescription(string(R.string.lua_indent)), WAIT_MS)
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

    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false, enabled: Boolean = true, summary: String = ""): JSONObject = JSONObject()
        .put("id", id).put("parent", parent).put("name", name).put("folder", folder).put("enabled", enabled).put("active", enabled)
        .put("package", "").put("error", "").put("summary", summary)

    // The fields that take text have some: an empty one whose top alone shows at the page's foot has no name for the
    // checks (docs/roadmap.md, "A known gap, the toolkit's")
    private fun timer(id: Int, parent: Int, name: String, time: Int): JSONObject = node(id, parent, name).put("script", "").put("command", "look").put("time", time).put("offset", false)

    private fun key(id: Int, name: String, key: Int, modifiers: Int, keyName: String): JSONObject = node(id, 0, name).put("script", "").put("command", "north").put("key", key).put("modifiers", modifiers).put("keyName", keyName)

    private fun button(id: Int, parent: Int, name: String, folder: Boolean = false, enabled: Boolean = true): JSONObject = node(id, parent, name, folder, enabled)
        .put("script", "").put("commandDown", "").put("commandUp", "").put("pushDown", false).put("location", "top").put("orientation", "horizontal")
        .put("columns", 1).put("css", "color: blue;")

    private fun trigger(id: Int, parent: Int, name: String, enabled: Boolean = true): JSONObject = node(id, parent, name, enabled = enabled)
        .put("script", "").put("patterns", JSONArray().put(JSONObject().put("text", "You wave").put("type", "substring")))
        .put("command", "").put("lineDelta", -1).put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false)
        .put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")

    private fun items(kind: String, items: List<JSONObject>, request: JSONObject) = session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray(items)).put("re", request.get("id")))

    private fun fields(kind: String, fields: JSONObject, request: JSONObject): JSONObject = JSONObject().put("t", "scriptItem").put("kind", kind).put("fields", fields).put("re", request.get("id"))

    private fun diagDump(): String {
        val out = StringBuilder()
        val res = compose.activity.resources
        val dm = res.displayMetrics
        out.append("display ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} fontScale=${res.configuration.fontScale} screenDp=${res.configuration.screenWidthDp}x${res.configuration.screenHeightDp}\n")
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            out.append("decor ${view.width}x${view.height} insets sys=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())} ime=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}\n")
        }
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = ui.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        ui.serviceInfo = info
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            out.append("  ".repeat(depth)).append("A ${node.className} $r text=${node.text} desc=${node.contentDescription} click=${node.isClickable} scroll=${node.isScrollable} vis=${node.isVisibleToUser} id=${node.viewIdResourceName}\n")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        for (w in ui.windows) {
            val r = android.graphics.Rect()
            w.getBoundsInScreen(r)
            out.append("WINDOW type=${w.type} layer=${w.layer} $r title=${w.title}\n")
            walk(w.root, 1)
        }
        for (merged in listOf(false, true)) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = !merged).fetchSemanticsNodes()
            for (root in roots) {
                out.append("SEMANTICS merged=$merged root ${root.boundsInWindow}\n")
                fun sem(n: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                    val c = n.config
                    val role = c.getOrElseNullable(SemanticsProperties.Role) { null }
                    val click = c.contains(SemanticsActions.OnClick)
                    val text = c.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString()
                    val desc = c.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString()
                    val tag = c.getOrElseNullable(SemanticsProperties.TestTag) { null }
                    out.append("  ".repeat(depth)).append("S#${n.id} win=${n.boundsInWindow} size=${n.size} role=$role click=$click text=$text desc=$desc tag=$tag\n")
                    for (child in n.children) sem(child, depth + 1)
                }
                sem(root, 1)
            }
        }
        android.util.Log.i("DIAG", out.toString())
        return out.toString()
    }

    /** The library's chip of a kind, [title], that has [count] items. */
    private fun kind(@StringRes title: Int, count: Int) = hasText(string(R.string.scripts_kind_count, string(title), count)) and hasClickAction()

    /** A field of an item's page, by its label. */
    private fun field(@StringRes label: Int) = hasSetTextAction() and hasText(string(label))

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

        // Qt's numbers, as docs/protocol.md gives them for a key binding
        const val F1 = 0x01000030
        const val SHIFT_KEY = 0x01000020
        const val CONTROL = 0x04000000
        const val KEYPAD = 0x20000000

        // Further than any page is long
        const val PAGE_END_PX = 100_000f
        const val WAIT_MS = 10_000L

        // From the Lua field's last line to the end of its page
        val LUA_FIELD_EDGE = 24.dp

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
