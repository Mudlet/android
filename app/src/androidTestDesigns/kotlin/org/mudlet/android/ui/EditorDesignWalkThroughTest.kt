package org.mudlet.android.ui

import android.Manifest
import android.app.UiModeManager
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mudlet.android.BuildConfig
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG
import org.mudlet.android.ui.scripts.designs.EditorDesign
import java.io.File

/**
 * A walk through the alternative script editor this app was built with (`-PeditorDesign`), against a fake engine, in
 * the light theme and the dark: the game opens, the editor opens from it and lists the items, an item opens, its Lua
 * is typed into, and what was changed is sent to the engine to save and shown as saved. Each screen on the way is
 * kept as a picture, for the three designs to be compared by.
 */
@RunWith(AndroidJUnit4::class)
class EditorDesignWalkThroughTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(NightMode()).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("Achaea ${System.nanoTime() % 1000}", "127.0.0.1", 4000, false, "", false)
    private val design = EditorDesign.of(BuildConfig.EDITOR_DESIGN)

    private val library = hasTestTag(SCRIPT_LIBRARY_TAG)
    private val page = hasTestTag(SCRIPT_ITEM_TAG)
    private val lua = hasTestTag(LUA_EDITOR_TAG)

    // Combat with two triggers in it, then two at the top level, as the engine lists them, depth first
    private val triggers = listOf(
        node(1, 0, "Combat", folder = true),
        node(2, 1, "You hit"),
        node(4, 1, "You miss", enabled = false),
        node(3, 0, "Greet"),
        node(5, 0, "Low health"),
    )

    @Before
    fun openTheEditorFromTheGame() {
        assertTrue("built without -PeditorDesign: there is no design to walk through", design != EditorDesign.CURRENT)
        // A new engine instance for each test, so no item of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "A rat bites you.")
        session.line(2, "You have 312 of 480 health.")
        session.synced(2)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        tap(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasText(string(R.string.game_title)))
        tap(hasContentDescription(string(R.string.scripts_open, game.name)))
        val asked = (1..3).map { received("scriptItems") }
        assertEquals(listOf("trigger", "alias", "script"), asked.map { it.getString("kind") })
        items("trigger", triggers, asked[0])
        items("alias", listOf(node(1, 0, "kick"), node(2, 0, "heal me")), asked[1])
        items("script", listOf(node(1, 0, "On connect")), asked[2])
    }

    @Test
    fun light() = walk("light")

    @Test
    fun dark() = walk("dark")

    private fun walk(theme: String) = when (design) {
        EditorDesign.WORKBENCH -> workbench("design-a-$theme")
        EditorDesign.ONE_PAGE -> onePage("design-b-$theme")
        else -> recipes("design-c-$theme")
    }

    // Design A: the tree under the kinds' tabs; an item opens on its code, with its settings on a second tab
    private fun workbench(shots: String) {
        waitFor(row("Greet"))
        tap(row("Combat"))
        waitFor(row("You hit"))
        save("$shots-1-list")

        tap(row("Greet"))
        answerOpen(GREET_LUA)
        waitFor(lua)
        compose.onNode(lua).performTextInput(TYPED)
        waitFor(hasText(string(R.string.design_save)) and isEnabled())
        save("$shots-2-lua")

        tap(hasText(string(R.string.design_a_settings)) and hasClickAction())
        waitFor(hasSetTextAction() and hasText(string(R.string.scripts_name)))
        save("$shots-3-item")

        tap(hasText(string(R.string.design_save)) and hasClickAction())
        answerSave(expectName = "Greet")
        save("$shots-4-saved")
    }

    // Design B: one page; an item unfolds where it is, and folding it saves it
    private fun onePage(shots: String) {
        waitFor(row("Greet"))
        save("$shots-1-list")

        tap(row("Greet"))
        answerOpen(GREET_LUA)
        val name = hasSetTextAction() and hasText(string(R.string.scripts_name))
        waitFor(name)
        save("$shots-2-item")
        compose.onNode(name).performTextReplacement("Greet back")

        compose.onNode(library).performScrollToNode(lua)
        compose.onNode(lua).performTextInput(TYPED)
        // The keys that type Lua's symbols come over the keyboard once the Lua has it
        waitFor(hasContentDescription(string(R.string.lua_indent)))
        save("$shots-3-lua")

        tap(hasText(string(R.string.design_b_done)) and hasClickAction())
        answerSave(expectName = "Greet back")
        waitFor(row("Greet back"))
        save("$shots-4-saved")
    }

    // Design C: cards that say what each item does, a drawer of folders, and an item edited one part at a time
    private fun recipes(shots: String) {
        // Each card asks for its item, to say what it does: Greet and Low health are the top level's
        repeat(2) {
            val open = received("scriptItem")
            session.send(fields("trigger", if (open.getInt("item") == GREET) greet(GREET_LUA) else trigger(open.getInt("item"), 0, "Low health", "^You have (\\d+) of", "", "regex"), open))
        }
        waitFor(hasText(string(R.string.design_c_when_substring, "You wave")))
        save("$shots-1-list")

        tap(hasContentDescription(string(R.string.design_c_menu)))
        save("$shots-2-drawer")
        back()
        compose.waitForIdle()

        tap(row("Greet"))
        answerOpen(GREET_LUA)
        waitFor(hasText(string(R.string.design_c_more)) and hasClickAction())
        save("$shots-3-item")

        tap(hasText(string(R.string.design_c_then)) and hasClickAction())
        val command = hasSetTextAction() and hasText(string(R.string.scripts_trigger_command))
        waitFor(command)
        compose.onNode(command).performTextInput("wave")
        save("$shots-4-part")

        tap(hasText(string(R.string.design_c_edit_code)) and hasClickAction())
        waitFor(lua)
        compose.onNode(lua).performTextInput(TYPED)
        save("$shots-5-lua")

        tap(hasText(string(R.string.design_c_finish)) and hasClickAction())
        tap(hasText(string(R.string.design_c_overview)) and hasClickAction())
        tap(hasText(string(R.string.design_save)) and hasClickAction())
        val saved = answerSave(expectName = "Greet")
        assertEquals("wave", saved.getString("command"))
        save("$shots-6-saved")
    }

    // The engine's answer to the item's page asking for Greet
    private fun answerOpen(script: String) {
        val open = received("scriptItem")
        assertEquals(GREET, open.getInt("item"))
        session.send(fields("trigger", greet(script), open))
    }

    /**
     * What the editor sent to save Greet, which is to have what the walk typed into its Lua; the engine's answer says
     * it is saved, which the editor then says with its Undo.
     */
    private fun answerSave(expectName: String): JSONObject {
        val edit = received("editScriptItem")
        assertEquals(GREET, edit.getInt("item"))
        val sent = edit.getJSONObject("fields")
        assertEquals(expectName, sent.getString("name"))
        assertTrue("the Lua sent is ${sent.getString("script")}", sent.getString("script").contains(TYPED.trim()))
        session.send(fields("trigger", greet(sent.getString("script")).put("name", expectName).put("command", sent.getString("command")), edit))
        items("trigger", triggers.map { if (it.getInt("id") == GREET) node(GREET, 0, expectName) else it }, received("scriptItems"))
        waitFor(hasText(string(R.string.scripts_applied, expectName)))
        return sent
    }

    private fun greet(script: String) = trigger(GREET, 0, "Greet", "You wave", script)

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // Idle first, so that what a LaunchedEffect sends is sent
    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false, enabled: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("parent", parent).put("name", name).put("folder", folder).put("enabled", enabled).put("active", enabled)
        .put("package", "").put("error", "")

    private fun trigger(id: Int, parent: Int, name: String, pattern: String, script: String, type: String = "substring"): JSONObject = node(id, parent, name)
        .put("script", script).put("patterns", JSONArray().put(JSONObject().put("text", pattern).put("type", type)))
        .put("command", "").put("lineDelta", -1).put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false)
        .put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")

    private fun items(kind: String, items: List<JSONObject>, request: JSONObject) = session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray(items)).put("re", request.get("id")))

    private fun fields(kind: String, fields: JSONObject, request: JSONObject): JSONObject = JSONObject().put("t", "scriptItem").put("kind", kind).put("fields", fields).put("re", request.get("id"))

    /** An item in the list: its name, on what a tap opens. */
    private fun row(name: String) = hasText(name) and hasClickAction() and hasAnyAncestor(library)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, once what scrolls has brought it into view. */
    private fun tap(node: SemanticsMatcher) {
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(library)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(library).performScrollToNode(node)
        } else if (compose.onAllNodes(node and hasAnyAncestor(page)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        compose.onNode(node).performSemanticsAction(SemanticsActions.OnClick)
    }

    // The screen as it is once it has settled, kept in the app's files, where the test's runner collects them
    private fun save(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("no screenshot of $name")
        val dir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The app's own night mode for the test named dark, set before the activity starts: changing it later would
     * recreate the activity, which attaches to the engine anew. Each test ends following the system again.
     */
    private class NightMode : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                if (Build.VERSION.SDK_INT < 31) return base.evaluate()
                val modes = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UiModeManager::class.java)
                modes.setApplicationNightMode(if (description.methodName == "dark") UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                try {
                    base.evaluate()
                } finally {
                    modes.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                }
            }
        }
    }

    private companion object {
        const val INSTANCE = "fake-designs"
        const val GREET = 3
        const val GREET_LUA = "if matches[2] == \"you\" then\n  send(\"wave back\")\nend"

        // What the walk types into the Lua, at its start
        const val TYPED = "echo(\"hello\")\n"
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 750L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
