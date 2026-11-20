package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineScrollback
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RefusedCertificate
import org.mudlet.android.protocol.ServerEncodings
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.settings.encodingLabels

/**
 * What TalkBack is given, against a fake engine, as the semantics tree shows it: new lines read as one announcement
 * while touch exploration is on (the Announcer's switch stands in for the system's), a script's announce(), the
 * console's lines as plain text, a Variables row as one node read as name, type and value, and the settings, which are each game's own: a game added here is given the app's, then a
 * settings page shows and changes the game's, and says once that a change waits for the game's next connection; and an
 * open game's proxy in its profile's editor.
 */
@RunWith(AndroidJUnit4::class)
class ScreenReaderTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private var nextSeq = 1L

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        reset()
        compose.runOnIdle { Settings.screenReaderOffered.set(true) }
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, so an earlier test's lines would still be there
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(GAME_TITLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GAME_TITLE_TAG).assertIsDisplayed()
    }

    // The settings are stored and the Announcer lives as long as the process, so both would outlast the test
    @After
    fun reset() = compose.runOnIdle {
        Announcer.touchExplorationOverride = null
        HardwareKeyboard.attachedOverride = null
        for (setting in Settings.mudletSettings + Settings.screenReaderOffered) setting.reset()
        Settings.setAwaitsDefaults(GAME.name, false)
        Settings.setAwaitsInputClear(GAME.name, false)
        EngineClient.sessions.forEach { it.settings.clear() }
    }

    @Test
    fun newLinesAreReadTogetherWhileTouchExplorationIsOn() {
        val before = serial()
        line("not read: touch exploration is off")
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement while touch exploration is off", before, serial())

        compose.runOnIdle { Announcer.touchExplorationOverride = true }
        line("not read: replayed", replay = true)
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement of a replayed line", before, serial())

        // A few lines are read as they are, without the blank one
        for (text in listOf("first of two", "", "second of two")) session.line(nextSeq++, text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("first of two\nsecond of two", LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        SystemClock.sleep(QUIET_MS)
        assertEquals("the lines are one announcement", before + 1, serial())

        val burst = (1..8).map { "burst line $it" }
        burst.forEach { session.line(nextSeq++, it) }
        val expected = compose.activity.resources.getQuantityString(R.plurals.announce_burst, 8, 8, burst.takeLast(3).joinToString("\n"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement(expected, LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        SystemClock.sleep(QUIET_MS)
        assertEquals("the burst is one announcement", before + 2, serial())

        compose.runOnIdle { Settings.readNewText.set(false) }
        line("not read: the setting is off")
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement with the setting off", before + 2, serial())
    }

    @Test
    fun announceIsReadAssertiveOrPolite() {
        session.announce("You are hungry.", assertive = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Assertive)).fetchSemanticsNodes().size == 1 }
        val first = serial()
        // The same words again are a second announcement, in the other live region
        SystemClock.sleep(QUIET_MS)
        session.announce("You are hungry.", assertive = false)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Polite)).fetchSemanticsNodes().size == 1 }
        assertEquals(first + 1, serial())
        assertEquals("the first one's live region is empty", 0, compose.onAllNodes(announcement("You are hungry.", LiveRegionMode.Assertive)).fetchSemanticsNodes().size)
    }

    @Test
    fun eachConsoleLineIsANodeWithItsPlainText() {
        val styled = "Red and bold, then plain"
        line(styled, listOf(listOf(0, 12, 1, -1, RUN_BOLD)))
        line("a second line")
        for (text in listOf(styled, "a second line")) {
            val node = compose.onNodeWithText(text).fetchSemanticsNode()
            val shown = node.config[SemanticsProperties.Text].single()
            assertEquals(text, shown.text)
            assertTrue("'$text' is read without its styles: ${shown.spanStyles}", shown.spanStyles.isEmpty() && shown.paragraphStyles.isEmpty())
        }
    }

    @Test
    fun aVariablesRowIsOneNodeReadAsNameTypeAndValue() {
        activate(hasContentDescription(compose.activity.getString(R.string.scripts_open, GAME.name)))
        for (kind in listOf("trigger", "alias", "script", "timer", "key", "button")) {
            compose.waitForIdle()
            val asked = session.receive("scriptItems")
            session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray()).put("re", asked.get("id")))
        }
        activate(hasText(string(R.string.variables_chip)))
        compose.waitForIdle()
        val asked = session.receive("variables")
        val entries = JSONArray()
            .put(JSONObject().put("name", "name").put("key", "string").put("type", "string").put("hidden", false).put("value", "Ana").put("cut", false))
            .put(JSONObject().put("name", "gmcp").put("key", "string").put("type", "table").put("hidden", false).put("members", 3).put("indexed", false))
        session.send(
            JSONObject().put("t", "variables").put("path", JSONArray()).put("showHidden", false).put("from", 0).put("total", 2)
                .put("hiddenLeftOut", 0).put("entries", entries).put("re", asked.get("id")),
        )
        val text = compose.activity.getString(R.string.variables_reading, "name", string(R.string.variables_type_string), compose.activity.getString(R.string.variables_quoted, "Ana"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }
        // The drawn name and value are not nodes of their own, which TalkBack would stop at one by one
        compose.onAllNodes(hasText("name")).assertCountEquals(0)
        val table = compose.onNode(hasContentDescription(compose.activity.resources.getQuantityString(R.plurals.variables_reading_table, 3, "gmcp", 3)) and hasClickAction())
        val config = table.fetchSemanticsNode().config
        assertEquals(string(R.string.variables_collapsed), config[SemanticsProperties.StateDescription])
        assertEquals(string(R.string.variables_open), config[SemanticsActions.OnClick].label)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun eachKeyOfTheMovementPadIsAButtonNamedByWhereItGoes() {
        // On, as it is from the start: the fake engine's rule turns it off for the tests that are not about it
        compose.runOnIdle {
            HardwareKeyboard.attachedOverride = false
            Settings.movementPad.reset()
        }
        // Each key's name, and the word it sends, which is what a player types and not a name to read out
        val keys = listOf(
            R.string.movement_north to "n",
            R.string.movement_northeast to "ne",
            R.string.movement_east to "e",
            R.string.movement_southeast to "se",
            R.string.movement_south to "s",
            R.string.movement_southwest to "sw",
            R.string.movement_west to "w",
            R.string.movement_northwest to "nw",
            R.string.movement_up to "u",
            R.string.movement_down to "d",
            R.string.movement_in to "in",
            R.string.movement_out to "out",
            R.string.movement_pad_hide to "",
        )
        for ((id, word) in keys) {
            val name = string(id)
            assertFalse("\"$name\" is a name, not the command \"$word\"", name.equals(word, ignoreCase = true))
            val key = hasContentDescription(name) and hasClickAction()
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(key).fetchSemanticsNodes().size == 1 }
            compose.onNode(key).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
        assertEquals("each has a name of its own", keys.size, keys.map { string(it.first) }.toSet().size)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aGameAddedHereIsGivenTheAppsSettingsThenHasItsOwn() {
        compose.runOnIdle {
            Settings.setAwaitsDefaults(GAME.name, true)
            Settings.blankLines.set(Settings.BlankLines.HIDE)
        }
        session.status("profile", "Test loaded")
        val defaults = session.receive("setSettings")
        val own = mapOf("blankLinesBehaviour" to "hide", "consoleBufferSize" to 10_000, "autoClearInputLine" to true)
        assertEquals("what the player changed for new games, and the app's value where Mudlet starts with another", own, values(defaults))
        session.settings(values(defaults), defaults.getLong("id"))
        val loaded = session.receive("getSettings")
        assertEquals(Settings.mudletSettings.map { it.key }, (0 until loaded.getJSONArray("keys").length()).map { loaded.getJSONArray("keys").getString(it) })
        session.settings(APP_VALUES + ("blankLinesBehaviour" to "hide"), loaded.getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !Settings.awaitsDefaults(GAME.name) } }

        // A page asks for the game's own values as it opens: a script, or desktop Mudlet, may have changed them
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_accessibility)), scroll = true)
        val asked = session.receive("getSettings")
        session.settings(mapOf("announceIncomingText" to false, "advertiseScreenReader" to true, "blankLinesBehaviour" to "hide"), asked.getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !EngineClient.front.setting(Settings.readNewText) } }
        compose.onNode(hasText(string(R.string.settings_blank_lines_hide)) and isSelected()).performScrollTo().assertIsDisplayed()

        // A change is the game's alone: what new games start with stays as it was
        activate(hasText(string(R.string.settings_blank_lines_space)), scroll = true)
        assertEquals(mapOf("blankLinesBehaviour" to "replacewithspace"), values(session.receive("setSettings")))
        compose.runOnIdle {
            assertEquals(Settings.BlankLines.SPACE, EngineClient.front.setting(Settings.blankLines))
            assertEquals(Settings.BlankLines.HIDE, Settings.blankLines.value)
            assertEquals("the game's other settings are its own too", false, EngineClient.front.setting(Settings.readNewText))
        }
    }

    // Mudlet picks some settings for the game it knows, which the app's unchanged values would undo. Mudlet starts a
    // profile keeping the command sent on its input line, which the app does not, and with 100,000 lines, where the app
    // keeps 10,000 (docs/divergences.md)
    @Test
    fun aGameAddedHereWithNothingChangedForNewGamesKeepsMudletsOwnButForTheInputLineAndScrollback() {
        compose.runOnIdle { Settings.setAwaitsDefaults(GAME.name, true) }
        session.status("profile", "Test loaded")
        val defaults = session.receive("setSettings")
        assertEquals(mapOf("consoleBufferSize" to 10_000, "autoClearInputLine" to true), values(defaults))
        session.settings(values(defaults), defaults.getLong("id"))
        session.receive("getSettings")
        session.assertNoRequest("setSettings", 500)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !Settings.awaitsDefaults(GAME.name) } }
    }

    @Test
    fun aGameAddedHereKeepsTheCommandSentWhenNewGamesDo() {
        compose.runOnIdle {
            Settings.setAwaitsDefaults(GAME.name, true)
            Settings.clearInputAfterSend.set(false)
        }
        session.status("profile", "Test loaded")
        val defaults = session.receive("setSettings")
        assertEquals("the input line's setting is Mudlet's own, so not given", mapOf("consoleBufferSize" to 10_000), values(defaults))
        session.settings(values(defaults), defaults.getLong("id"))
        session.receive("getSettings")
        session.assertNoRequest("setSettings", 500)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !Settings.awaitsDefaults(GAME.name) } }
    }

    // The app emptied the input line of every game before it had the setting, whatever the game's profile said
    @Test
    fun aGameFromBeforeTheInputLineCouldKeepACommandIsToldOnceToGoOnClearingIt() {
        compose.runOnIdle { Settings.setAwaitsInputClear(GAME.name, true) }
        session.status("profile", "Test loaded")
        val told = session.receive("setSettings")
        assertEquals(mapOf("autoClearInputLine" to true), values(told))
        compose.runOnIdle { assertTrue("until Mudlet answers, it is still to be told", Settings.awaitsInputClear(GAME.name)) }
        session.settings(values(told), told.getLong("id"))
        session.settings(APP_VALUES + ("autoClearInputLine" to true), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { !Settings.awaitsInputClear(GAME.name) } }

        // The next load leaves the game's own value alone: the player may have turned it off since
        val givenOnce = session.received.count { it.optString("t") == "setSettings" }
        session.status("profile", "Test loaded")
        session.receive("getSettings")
        session.assertNoRequest("setSettings", 500)
        assertEquals(givenOnce, session.received.count { it.optString("t") == "setSettings" })
    }

    // As one copied from desktop Mudlet: a profile the app did not add has settings of its own already
    @Test
    fun aGameNotAddedHereKeepsItsOwnSettings() {
        session.status("profile", "Test loaded")
        val asked = session.receive("getSettings")
        session.settings(mapOf("announceIncomingText" to false, "advertiseScreenReader" to true, "blankLinesBehaviour" to "hide"), asked.getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.setting(Settings.blankLines) == Settings.BlankLines.HIDE } }
        session.assertNoRequest("setSettings", 500)
        compose.runOnIdle {
            assertEquals(false, EngineClient.front.setting(Settings.readNewText))
            assertEquals("the app's own values stay as they were", true, Settings.readNewText.value)
        }
    }

    @Test
    fun aSearchFindsASettingForTheGameAndForNewGames() {
        loaded()
        // The profile list's settings have every open game's, and what new games start with
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.onNode(hasSetTextAction()).performTextInput("screen read")
        val label = string(R.string.settings_advertise_screen_reader)
        fun found(place: String) = hasText(compose.activity.getString(R.string.settings_breadcrumb, place, string(R.string.settings_accessibility))) and hasText(label)

        // Each chip narrows the search to one of them
        activate(hasText(string(R.string.settings_new_games)))
        compose.onNode(found(GAME.name)).assertDoesNotExist()
        activate(found(string(R.string.settings_new_games)), scroll = true)
        compose.runOnIdle {
            assertEquals(true, Settings.advertiseScreenReader.value)
            assertEquals(false, EngineClient.front.setting(Settings.advertiseScreenReader))
        }
        session.assertNoRequest("setSettings", 500)
        activate(hasText(GAME.name))
        activate(found(GAME.name), scroll = true)
        assertEquals(mapOf("advertiseScreenReader" to true), values(session.receive("setSettings")))

        // A result that is not a switch opens its page, which asks for the game's values
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("blank")
        val crumb = compose.activity.getString(R.string.settings_breadcrumb, GAME.name, string(R.string.settings_accessibility))
        activate(hasText(crumb) and hasText(string(R.string.settings_blank_lines)), scroll = true)
        session.receive("getSettings")
        compose.onNode(hasText(string(R.string.settings_blank_lines_show)) and isSelected()).assertIsDisplayed()
    }

    @Test
    fun theInputAndConnectionPagesChangeTheGamesOwnSettings() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_connection)), scroll = true)
        session.receive("getSettings")
        // No hint says when its switch applies: a message does, when one is turned while the game is connected
        compose.onNode(hasText("next connection", substring = true, ignoreCase = true)).assertDoesNotExist()
        activate(hasText(string(R.string.settings_gmcp)), scroll = true)
        assertEquals(mapOf("enableGMCP" to false), values(session.receive("setSettings")))
        compose.runOnIdle { assertEquals("new games keep it", true, Settings.gmcp.value) }
        activate(hasContentDescription(string(R.string.navigate_back)))

        // Found by a keyword its label does not have, as a choice it opens its page
        compose.onNode(hasSetTextAction()).performTextInput("echo")
        activate(hasText(string(R.string.settings_sent_text)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_sent_text_never)), scroll = true)
        assertEquals(mapOf("showSentText" to "never"), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_clear_input)), scroll = true)
        assertEquals(mapOf("autoClearInputLine" to false), values(session.receive("setSettings")))
        compose.runOnIdle { assertEquals("new games keep it", true, Settings.clearInputAfterSend.value) }
    }

    @Test
    fun theConnectionPagePicksTheGamesEncodingFromMudletsList() {
        assertEquals("every encoding Mudlet has is named as desktop Mudlet names it", ServerEncodings.names, encodingLabels.keys.toList())
        session.status("profile", "Test loaded")
        session.settings(APP_VALUES + ("serverEncoding" to "ISO 8859-1"), session.receive("getSettings").getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.setting(Settings.serverEncoding) == "ISO 8859-1" } }
        compose.pickFromGameMenu(string(R.string.settings_title))
        // Found by the name of an encoding, which only its list has
        compose.onNode(hasSetTextAction()).performTextInput("cyrillic")
        activate(hasText(string(R.string.settings_server_encoding)), scroll = true)
        session.settings(mapOf("serverEncoding" to "ISO 8859-1"), session.receive("getSettings").getLong("id"))
        val row = hasText(string(R.string.settings_server_encoding)) and hasClickAction()
        compose.onNode(row).assert(hasText(string(R.string.settings_encoding_iso_8859_1)))
        activate(row, scroll = true)

        val dialog = isRoot() and hasAnyDescendant(hasText(string(R.string.settings_choice_cancel)))
        compose.onNode(dialog).tryPerformAccessibilityChecks()
        val western = hasText(string(R.string.settings_encoding_iso_8859_1)) and hasAnyAncestor(isDialog())
        compose.onNode(western).assertIsDisplayed().assert(isSelected())
        val cyrillic = hasText(string(R.string.settings_encoding_koi8_r)) and hasAnyAncestor(isDialog())
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog())).performScrollToNode(cyrillic)
        compose.onNode(cyrillic).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(mapOf("serverEncoding" to "KOI8-R"), values(session.receive("setSettings")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(row).assert(hasText(string(R.string.settings_encoding_koi8_r)))
        compose.runOnIdle { assertEquals("new games keep Mudlet's", ServerEncodings.DEFAULT, Settings.serverEncoding.value) }

        // A name the app has no label for, as from a newer Mudlet, shows as Mudlet gives it, and is in the list to see
        compose.runOnIdle { EngineClient.front.settings["serverEncoding"] = "X-NEWER" }
        compose.onNode(row).assert(hasText("X-NEWER"))
        activate(row)
        compose.onNode(hasText("X-NEWER") and hasAnyAncestor(isDialog())).assertIsDisplayed().assert(isSelected())
        compose.onNode(hasText(string(R.string.settings_choice_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        session.assertNoRequest("setSettings", 500)
    }

    @Test
    fun theDisplayAndAccessibilityPagesTurnTheGamesLinksAndBlinkingText() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_display)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_game_links)), scroll = true)
        assertEquals(mapOf("enableOSC8Hyperlinks" to false), values(session.receive("setSettings")))
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasText(string(R.string.settings_accessibility)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_blink_text)), scroll = true)
        assertEquals(mapOf("enableBlinkText" to true), values(session.receive("setSettings")))
        compose.runOnIdle { assertEquals("new games keep Mudlet's", false, Settings.blinkText.value) }
    }

    @Test
    fun theInputPageTakesTheCommandSeparatorAsTheFieldIsDoneWithAndTurnsItsSwitches() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_input)), scroll = true)
        val mudlets = mapOf(
            "commandSeparator" to ";;",
            "highlightHistory" to true,
            "disablePasswordMasking" to false,
            "runAllKeyMatches" to false,
        )
        session.settings(mudlets, session.receive("getSettings").getLong("id"))
        activate(hasText(string(R.string.settings_highlight_history)), scroll = true)
        assertEquals(mapOf("highlightHistory" to false), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_show_passwords)), scroll = true)
        assertEquals(mapOf("disablePasswordMasking" to true), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_run_all_key_bindings)), scroll = true)
        assertEquals(mapOf("runAllKeyMatches" to true), values(session.receive("setSettings")))
        val field = hasSetTextAction() and hasText(string(R.string.settings_command_separator))
        compose.onNode(field).performScrollTo().assert(hasText(";;"))
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(field).performTextClearance()
        compose.onNode(field).performTextInput("|")
        session.assertNoRequest("setSettings", 500)
        compose.onNode(field).performImeAction()
        assertEquals(mapOf("commandSeparator" to "|"), values(session.receive("setSettings")))
        // None splits nothing, as desktop Mudlet's blank field
        compose.onNode(field).performTextClearance()
        compose.onNode(field).performImeAction()
        assertEquals(mapOf("commandSeparator" to ""), values(session.receive("setSettings")))
        session.assertNoRequest("setSettings", 500)
        // Typed and left by Back, with no Done, it is still taken
        compose.onNode(field).performTextInput("&&")
        activate(hasContentDescription(string(R.string.navigate_back)))
        assertEquals(mapOf("commandSeparator" to "&&"), values(session.receive("setSettings")))

        compose.runOnIdle { assertEquals("new games keep Mudlet's", ";;", Settings.commandSeparator.value) }
    }

    @Test
    fun theColoursPageChangesAColourBySliderOrHexAndPutsThemAllBackToMudlets() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_colours)), scroll = true)
        session.settings(mapOf("ansiRed" to "#aa0000", "serverMayRedefineColors" to false), session.receive("getSettings").getLong("id"))
        activate(hasText(string(R.string.settings_game_changes_colours)), scroll = true)
        assertEquals(mapOf("serverMayRedefineColors" to true), values(session.receive("setSettings")))
        val red = hasText(string(R.string.settings_colour_red)) and hasClickAction()
        compose.onNode(red).performScrollTo().assert(hasText("#aa0000"))
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(red)

        compose.onNode(isRoot() and hasAnyDescendant(hasText(string(R.string.settings_colour_ok)))).tryPerformAccessibilityChecks()
        val redAmount = hasContentDescription(string(R.string.settings_colour_red_amount)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(redAmount).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "170"))
        val hex = hasSetTextAction() and hasAnyAncestor(isDialog())
        compose.onNode(hex).performTextClearance()
        compose.onNode(hex).performTextInput("#12AB3")
        val ok = hasText(string(R.string.settings_colour_ok)) and hasClickAction()
        compose.onNode(ok).assertIsNotEnabled()
        compose.onNode(hex).performTextInput("4")
        compose.onNode(redAmount).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "18"))
        session.assertNoRequest("setSettings", 500)
        compose.onNode(ok).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(mapOf("ansiRed" to "#12ab34"), values(session.receive("setSettings")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(red).assert(hasText("#12ab34"))

        // A slide changes the colour; Cancel leaves the game's as it was
        activate(red)
        compose.onNode(redAmount).performSemanticsAction(SemanticsActions.SetProgress) { it(255f) }
        compose.onNode(hex).assert(hasText("#ffab34"))
        compose.onNode(hasText(string(R.string.settings_choice_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        session.assertNoRequest("setSettings", 500)

        activate(hasText(string(R.string.settings_colours_reset)), scroll = true)
        val mudlets = Settings.colours.associate { it.key to Settings.colourName(it.mudletDefault) }
        assertEquals("all twenty in one request", mudlets, values(session.receive("setSettings")))
        compose.onNode(red).assert(hasText("#800000"))
        compose.runOnIdle { assertEquals("new games keep Mudlet's", 0x800000, Settings.ansiColours[1].value) }
    }

    @Test
    fun aGamesNumberIsSentOnceASlideEndsAndAStepAtATime() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_display)))
        session.receive("getSettings")
        // As on desktop Mudlet, what becomes of blank lines is on the Accessibility page
        compose.onNode(hasText(string(R.string.settings_blank_lines))).assertDoesNotExist()
        val width = hasContentDescription(string(R.string.settings_undo_wrap_width)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(width).performScrollTo().performTouchInput { swipeRight(startX = centerX, endX = (centerX + right) / 2, durationMillis = 500) }
        val slid = values(session.receive("setSettings"))["undoServerWrapWidth"] as Int
        assertTrue("slid to $slid", slid > 80)
        session.assertNoRequest("setSettings", 500)
        activate(hasContentDescription(string(R.string.settings_undo_wrap_width_more)), scroll = true)
        assertEquals(mapOf("undoServerWrapWidth" to slid + 1), values(session.receive("setSettings")))
        val shown = compose.activity.resources.getQuantityString(R.plurals.settings_undo_wrap_width_value, slid + 1, slid + 1)
        compose.onNode(width).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))
        compose.runOnIdle { assertEquals("new games keep it", 80, Settings.undoWrapWidth.value) }
    }

    @Test
    fun theScrollbackLengthIsTheGamesBufferSizeAThousandLinesAStep() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_display)))
        session.receive("getSettings")
        val more = hasContentDescription(string(R.string.settings_console_buffer_more))
        val less = hasContentDescription(string(R.string.settings_console_buffer_less))
        activate(more, scroll = true)
        assertEquals(mapOf("consoleBufferSize" to 11_000), values(session.receive("setSettings")))
        activate(less, scroll = true)
        assertEquals(mapOf("consoleBufferSize" to 10_000), values(session.receive("setSettings")))
        activate(less, scroll = true)
        assertEquals(mapOf("consoleBufferSize" to 9_000), values(session.receive("setSettings")))
        val length = hasContentDescription(string(R.string.settings_console_buffer)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        val shown = compose.activity.resources.getQuantityString(R.plurals.settings_console_buffer_value, 9_000, 9_000)
        compose.onNode(length).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))
        compose.runOnIdle { assertEquals("new games keep the app's own", 10_000, Settings.consoleBufferSize.value) }
    }

    // As desktop Mudlet's "Use maximum lines possible", with the most this device keeps (docs/divergences.md)
    @Test
    fun keepingTheMostHoldsTheScrollbackLengthAtWhatTheDeviceKeeps() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_display)))
        session.receive("getSettings")
        val most = EngineScrollback.maxLines(compose.activity)
        compose.runOnIdle { assertEquals("the scrollback length goes up to what the device keeps", most, Settings.consoleBufferSize.range.last) }
        val switch = hasText(string(R.string.settings_console_buffer_max))
        val length = hasContentDescription(string(R.string.settings_console_buffer)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        val more = hasContentDescription(string(R.string.settings_console_buffer_more))
        activate(switch, scroll = true)
        assertEquals(mapOf("useMaxConsoleBufferSize" to true), values(session.receive("setSettings")))
        val shownMost = compose.activity.resources.getQuantityString(R.plurals.settings_console_buffer_value, most, most)
        compose.onNode(length).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shownMost)).assertIsNotEnabled()
        compose.onNode(more).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(switch, scroll = true)
        assertEquals(mapOf("useMaxConsoleBufferSize" to false), values(session.receive("setSettings")))
        val shownOwn = compose.activity.resources.getQuantityString(R.plurals.settings_console_buffer_value, 10_000, 10_000)
        compose.onNode(length).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shownOwn)).assertIsEnabled()
        compose.runOnIdle { assertEquals("new games keep the app's own", false, Settings.useMaxConsoleBufferSize.value) }
    }

    @Test
    fun theAdvancedPageChangesTheGamesOwnSettings() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_advanced)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_lazy_trigger_variables)), scroll = true)
        assertEquals(mapOf("lazyCaptureGlobals" to false), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_show_script_errors)), scroll = true)
        assertEquals(mapOf("echoLuaErrors" to true), values(session.receive("setSettings")))
        // Desktop Mudlet's "Fix unnecessary linebreaks on GA servers", with its other workarounds
        activate(hasText(string(R.string.settings_fix_linebreaks)), scroll = true)
        assertEquals(mapOf("fixUnnecessaryLinebreaks" to true), values(session.receive("setSettings")))
    }

    // Mudlet reads a protocol's switch as the game connects: turned while it is connected, it waits for the next one
    @Test
    fun aSettingReadAsTheGameConnectsSaysSoOnceWhileItIsConnected() {
        loaded()
        session.status("connection", "connected")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.status["connection"] == "connected" } }
        val notice = hasText(string(R.string.settings_next_connection))
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_connection)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_gmcp)), scroll = true)
        assertEquals(mapOf("enableGMCP" to false), values(session.receive("setSettings")))
        compose.onNode(notice).assertIsDisplayed()
        // Another turned while it shows is not said again, after it either
        activate(hasText(string(R.string.settings_msdp)), scroll = true)
        assertEquals(mapOf("enableMSDP" to true), values(session.receive("setSettings")))
        compose.onNode(notice).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SNACKBAR_MS)
        compose.onNode(notice).assertDoesNotExist()

        // A search's switch, turned where it is found, says so there
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.onNode(hasSetTextAction()).performTextInput("gmcp")
        val crumb = compose.activity.getString(R.string.settings_breadcrumb, GAME.name, string(R.string.settings_connection))
        activate(hasText(crumb) and hasText(string(R.string.settings_gmcp)), scroll = true)
        assertEquals(mapOf("enableGMCP" to true), values(session.receive("setSettings")))
        compose.onNode(notice).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SNACKBAR_MS)
        compose.onNode(notice).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextClearance()

        // A setting that applies at once says nothing; one turned later that does not says so again
        activate(hasText(string(R.string.settings_advanced)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_fix_linebreaks)), scroll = true)
        assertEquals(mapOf("fixUnnecessaryLinebreaks" to true), values(session.receive("setSettings")))
        compose.onNode(notice).assertDoesNotExist()
        activate(hasText(string(R.string.settings_force_compression_off)), scroll = true)
        assertEquals(mapOf("specialForceCompressionOff" to true), values(session.receive("setSettings")))
        compose.onNode(notice).assertIsDisplayed()

        // Once the connection ends there is nothing to wait for
        session.status("connection", "disconnected")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.status["connection"] == "disconnected" } }
        compose.onNode(notice).assertDoesNotExist()
    }

    // The change is there for the connection to come
    @Test
    fun aSettingReadAsTheGameConnectsSaysNothingWhileItIsNotConnected() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_connection)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_gmcp)), scroll = true)
        assertEquals(mapOf("enableGMCP" to false), values(session.receive("setSettings")))
        compose.onNode(hasText(string(R.string.settings_next_connection))).assertDoesNotExist()
    }

    @Test
    fun theSecurityPageChangesTheGamesOwnSettings() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_security)), scroll = true)
        session.receive("getSettings")
        activate(hasText(string(R.string.settings_game_installs_packages)), scroll = true)
        assertEquals(mapOf("acceptServerGUI" to false), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_accept_any_certificate)), scroll = true)
        assertEquals(mapOf("sslIgnoreAll" to true), values(session.receive("setSettings")))
    }

    // As desktop's Connection page lists the certificate beside the boxes it highlights
    @Test
    fun theSecurityPageShowsTheRefusedCertificateAndTheSwitchesThatLetItThrough() {
        loaded()
        val question = hasText(string(R.string.certificate_refused_title))
        session.certificateRefused(EXPIRED_SELF_SIGNED)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(question).fetchSemanticsNodes().isNotEmpty() }
        // The dialog is a window of its own, which activate()'s single-root checks do not take
        compose.onNode(hasText(string(R.string.certificate_cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(question).fetchSemanticsNodes().isEmpty() }
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_security)), scroll = true)
        session.receive("getSettings")

        val heading = hasText(string(R.string.settings_refused_certificate_title))
        compose.onNode(heading).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        for (error in EXPIRED_SELF_SIGNED.errors) compose.onNodeWithText(compose.activity.getString(R.string.certificate_error, error)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.certificate_serial, EXPIRED_SELF_SIGNED.serial)).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        // TalkBack reads it with the switch, not by its colour alone
        val letsThrough = hasText(string(R.string.settings_certificate_lets_through))
        for (label in listOf(R.string.settings_accept_expired_certificate, R.string.settings_accept_self_signed_certificate)) {
            compose.onNode(hasText(string(label)) and letsThrough and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
        compose.onNode(hasText(string(R.string.settings_accept_any_certificate)) and letsThrough).assertDoesNotExist()

        // One only "Ignore all certificate errors" lets past points at no switch, as desktop's page does not
        session.certificateRefused(EXPIRED_SELF_SIGNED.copy(others = true))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.certificate_only_ignore_all))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(letsThrough).assertDoesNotExist()

        // Gone as the next connection is tried
        session.certificateRefused(null)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(heading).fetchSemanticsNodes().isEmpty() }
        session.assertNoRequest("setSettings", 500)
    }

    @Test
    fun theMapPageChangesTheGamesMapAsDesktopsPreferencesDo() {
        loaded()
        compose.pickFromGameMenu(string(R.string.settings_title))
        activate(hasText(string(R.string.settings_map)), scroll = true)
        // A profile keeps 50 / 3 as 16.7, which desktop's preferences show as 3
        session.settings(mapOf("mapExitSize" to 16.7, "mapPlayerRoomStyle" to "redRing"), session.receive("getSettings").getLong("id"))
        val exits = hasContentDescription(string(R.string.settings_map_exit_size)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        val three = compose.activity.resources.getQuantityString(R.plurals.settings_map_size_value, 3, 3)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(exits and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, three)).fetchSemanticsNodes().isNotEmpty() }
        activate(hasContentDescription(string(R.string.settings_map_exit_size_more)), scroll = true)
        assertEquals(mapOf("mapExitSize" to 12.5), values(session.receive("setSettings")))

        compose.onNode(hasText(string(R.string.settings_map_player_room_red_ring)) and isSelected()).performScrollTo().assertIsDisplayed()
        activate(hasText(string(R.string.settings_map_player_room_custom_ring)), scroll = true)
        assertEquals(mapOf("mapPlayerRoomStyle" to "customRing"), values(session.receive("setSettings")))
        activate(hasText(string(R.string.settings_map_large_area_exit_arrows)), scroll = true)
        assertEquals(mapOf("mapLargeAreaExitArrows" to true), values(session.receive("setSettings")))

        activate(hasText(string(R.string.settings_map_colours_reset)), scroll = true)
        val mudlets = Settings.mapColours.associate { it.key to Settings.colourName(it.mudletDefault) }
        assertEquals("the map's colours in one request, as desktop's reset", mudlets, values(session.receive("setSettings")))
        compose.runOnIdle { assertEquals("new games keep Mudlet's", 5, Settings.mapExitSize.value) }
    }

    @Test
    fun anOpenGamesProxyIsAHeadedSectionOfLabelledControls() {
        loaded()
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(compose.activity.getString(R.string.profile_edit, GAME.name)))
        session.proxy(true, "socks.example", 1080, "proxy-user", session.receive("getProxy").get("id"))
        activate(hasText(string(R.string.profile_advanced_section)), scroll = true)
        val host = hasSetTextAction() and hasText(string(R.string.profile_proxy_host))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(host).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText(string(R.string.profile_proxy_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        // The switch and its words are one control, read as a switch that is on
        compose.onNode(hasText(string(R.string.profile_proxy_use)) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNode(host).assert(hasText("socks.example"))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_proxy_port))).assert(hasText("1080"))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_proxy_user))).assert(hasText("proxy-user"))
        compose.onNode(hasSetTextAction() and hasText(string(R.string.profile_proxy_password)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNode(host).performScrollTo()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theFirstRunWithTouchExplorationOffersToTellGames() {
        loaded()
        compose.runOnIdle {
            Settings.screenReaderOffered.set(false)
            Announcer.touchExplorationOverride = true
        }
        val title = hasText(string(R.string.screen_reader_offer_title))
        compose.onNode(title).assertIsDisplayed()
        // The dialog is a window of its own, with a root of its own
        compose.onNode(isRoot() and hasAnyDescendant(title)).tryPerformAccessibilityChecks()
        compose.onNode(hasText(string(R.string.screen_reader_offer_yes)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // Every open game is told, and new games will be
        assertEquals(mapOf("advertiseScreenReader" to true), values(session.receive("setSettings")))
        compose.runOnIdle {
            assertEquals(true, Settings.advertiseScreenReader.value)
            assertEquals(true, Settings.screenReaderOffered.value)
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(title).fetchSemanticsNodes().isEmpty() }
    }

    // The profile loads, and the app asks for its settings, which are the app's values
    private fun loaded() {
        session.status("profile", "Test loaded")
        val asked = session.receive("getSettings")
        session.settings(APP_VALUES, asked.getLong("id"))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.settingsAvailable && EngineClient.front.settings.isNotEmpty() } }
    }

    private fun values(request: JSONObject): Map<String, Any> = request.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.get(it) } }

    private fun serial() = compose.runOnIdle { Announcer.current?.serial ?: 0L }

    private fun line(text: String, runs: List<List<Int>> = emptyList(), replay: Boolean = false) {
        session.line(nextSeq++, text, runs, replay = replay)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A live region of [mode] that TalkBack would read as [text]. */
    private fun announcement(text: String, mode: LiveRegionMode) = SemanticsMatcher("live region $mode reading '$text'") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == mode && it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(text)
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher, scroll: Boolean = false) {
        compose.onRoot().tryPerformAccessibilityChecks()
        val node = compose.onNode(label and hasClickAction())
        // The settings screen scrolls, and TalkBack scrolls to what it moves to
        if (scroll) {
            if (compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isEmpty()) compose.scrollSettingsHubTo(label and hasClickAction())
            node.performScrollTo()
        }
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        // The test's clock drives the composition: until it runs, a page it opened has not asked for the game's values
        compose.waitForIdle()
    }

    private companion object {
        // The app's values of the settings Mudlet keeps, as the app starts
        val APP_VALUES = mapOf("announceIncomingText" to true, "advertiseScreenReader" to false, "blankLinesBehaviour" to "show")
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        val EXPIRED_SELF_SIGNED = RefusedCertificate(
            "localhost",
            "localhost",
            "2020-01-02T00:00:00Z",
            "4A:2B",
            true,
            true,
            false,
            listOf("The certificate has expired", "The certificate is self-signed, and untrusted"),
        )

        const val WAIT_MS = 10_000L

        // Longer than Material's short snackbar stays, and too short for one after it to have gone too
        const val SNACKBAR_MS = 4_500L

        // Well past the Announcer's wait for more lines
        const val QUIET_MS = 1_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
