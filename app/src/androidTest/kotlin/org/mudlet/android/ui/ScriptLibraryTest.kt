package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG

/**
 * The script library (docs/architecture.md, "The script library") against a fake engine: opened from the game's title
 * bar, it lists each kind one folder at a time, its switches switch items on and off, and its search finds names; an
 * item's page saves an edit with Undo, asks before leaving one unsaved, and deletes with Undo, sent only once the
 * library goes; and what the engine refuses is said. Controls are found by the labels TalkBack reads and activated through
 * their accessibility actions.
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
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        activate(hasContentDescription(string(R.string.scripts_open)))
        val asked = (1..3).map { session.receive("scriptItems") }
        assertEquals(listOf("trigger", "alias", "script"), asked.map { it.getString("kind") })
        items("trigger", triggers, asked[0])
        items("alias", listOf(node(1, 0, "kick")), asked[1])
        items("script", emptyList(), asked[2])
        waitFor(row("Greet"))
    }

    @Test
    fun theLibraryShowsAFolderAtATimeAndItsSwitchesSwitchItemsOnAndOff() {
        compose.onNode(row("You hit")).assertDoesNotExist()
        compose.onNode(hasText("${string(R.string.scripts_triggers)} 2")).assertIsDisplayed()
        activate(row("Combat"))
        waitFor(row("You hit"))
        compose.onNode(row("Greet")).assertDoesNotExist()
        compose.onNode(hasText("Combat") and !hasClickAction()).assertIsDisplayed()

        activate(switch("You hit"))
        val enable = session.receive("enableScriptItem")
        assertEquals("trigger", enable.getString("kind"))
        assertEquals(2, enable.getInt("item"))
        assertFalse(enable.getBoolean("enabled"))
        session.send(fields("trigger", trigger(2, 1, "You hit", enabled = false), enable))
        items("trigger", listOf(triggers[0], node(2, 1, "You hit", enabled = false), triggers[2]), session.receive("scriptItems"))
        waitFor(switch("You hit") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        waitFor(hasText(string(R.string.scripts_off)))

        // The breadcrumb's first step goes back to the top
        activate(hasText(string(R.string.scripts_all, string(R.string.scripts_triggers))) and hasClickAction())
        waitFor(row("Greet"))
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
    fun anEditIsSavedWithUndoAndLeavingOneUnsavedAsks() {
        openGreet()
        val save = hasText(string(R.string.scripts_apply)) and hasClickAction()
        compose.onNode(save).assertIsNotEnabled()
        val name = hasSetTextAction() and hasText(string(R.string.scripts_name))
        compose.onNode(name).performTextClearance()
        compose.onNode(name).performTextInput("Wave")
        compose.onNode(save).assertIsEnabled()

        // Back asks, and Keep editing keeps the draft
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        activate(hasText(string(R.string.scripts_keep_editing)) and hasClickAction())
        waitFor(hasText("Wave") and hasSetTextAction())

        activate(save)
        val edit = session.receive("editScriptItem")
        assertEquals(3, edit.getInt("item"))
        assertEquals("Wave", edit.getJSONObject("fields").getString("name"))
        assertEquals("You wave", edit.getJSONObject("fields").getJSONArray("patterns").getJSONObject(0).getString("text"))
        session.send(fields("trigger", trigger(3, 0, "Wave"), edit))
        items("trigger", listOf(triggers[0], triggers[1], node(3, 0, "Wave")), session.receive("scriptItems"))

        activate(hasText(string(R.string.scripts_undo)) and hasClickAction())
        val undo = session.receive("editScriptItem")
        assertEquals("Greet", undo.getJSONObject("fields").getString("name"))

        // A change and then Back, discarded, leaves the page and sends nothing
        session.send(fields("trigger", trigger(3, 0, "Greet"), undo))
        waitFor(hasText("Greet") and hasSetTextAction())
        compose.onNode(name).performTextInput("!")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        activate(hasText(string(R.string.scripts_discard)) and hasClickAction())
        waitFor(library)
        session.assertNoRequest("editScriptItem", 1_000)
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
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        waitFor(hasText(string(R.string.game_title)))
        val delete = session.receive("deleteScriptItem")
        assertEquals("trigger", delete.getString("kind"))
        assertEquals(3, delete.getInt("item"))
        items("trigger", triggers.take(2), session.receive("scriptItems"))
    }

    @Test
    fun whatTheEngineRefusesIsSaid() {
        openGreet()
        val name = hasSetTextAction() and hasText(string(R.string.scripts_name))
        compose.onNode(name).performTextInput("!")
        activate(hasText(string(R.string.scripts_apply)) and hasClickAction())
        session.refuse(session.receive("editScriptItem"), "no such item")
        waitFor(hasText(string(R.string.scripts_refused_apply, "no such item")))
        // The draft is kept, to try again
        compose.onNode(hasText(string(R.string.scripts_apply)) and hasClickAction()).assertIsEnabled()
    }

    private fun openGreet() {
        activate(row("Greet"))
        val open = session.receive("scriptItem")
        assertEquals(3, open.getInt("item"))
        session.send(fields("trigger", trigger(3, 0, "Greet"), open))
        waitFor(hasText("Greet") and hasSetTextAction() and hasAnyAncestor(page))
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

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label and hasClickAction())
        if (compose.onAllNodes(library).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(label and hasAnyAncestor(library)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(library).performScrollToNode(label)
        }
        checkAccessibility()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-scripts"
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
