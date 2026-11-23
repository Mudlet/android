package org.mudlet.android.ui

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.scripts.LUA_EDITOR_TAG
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.SCRIPT_LIBRARY_TAG

/** The script editor in [design] for a test, inside FakeEngineRule, which loads the settings, and the default again after it. */
internal class ScriptEditorDesignRule(private val design: Settings.ScriptEditorDesign) : ExternalResource() {
    override fun before() = InstrumentationRegistry.getInstrumentation().runOnMainSync { Settings.scriptEditorDesign.set(design) }

    override fun after() = InstrumentationRegistry.getInstrumentation().runOnMainSync { Settings.scriptEditorDesign.reset() }
}

/**
 * What the tests of the script editor's designs (docs/architecture.md, "The script editor's designs") share: a game on
 * a fake engine with an item or two of every kind, the library opened from the game, the engine's answers, and the way
 * TalkBack reaches a control, as ScriptLibraryTest has them for the current design.
 */
internal class ScriptDesignHarness(private val compose: AndroidComposeTestRule<*, MainActivity>, private val fake: FakeEngineRule) {
    lateinit var session: FakeEngine.Session

    // Short, as the title bar has it in every design, beside up to four buttons
    val game = Profile("d${System.nanoTime() % 1000}", "127.0.0.1", 4000, false, "", false)

    val library = hasTestTag(SCRIPT_LIBRARY_TAG)
    val page = hasTestTag(SCRIPT_ITEM_TAG)
    val editor = hasTestTag(LUA_EDITOR_TAG)
    val name by lazy { hasSetTextAction() and hasText(string(R.string.scripts_name)) }
    val apply by lazy { hasText(string(R.string.scripts_apply)) and hasClickAction() }

    /** What the engine lists of each kind, as it would answer now: Combat/"You hit" then Greet, and the toolbar Bar/Sword. */
    val lists = linkedMapOf(
        "trigger" to mutableListOf(node(1, 0, "Combat", folder = true), node(2, 1, "You hit"), node(3, 0, "Greet")),
        "alias" to mutableListOf(node(1, 0, "kick")),
        "script" to mutableListOf(node(1, 0, "Login")),
        "timer" to mutableListOf(node(1, 0, "Heal", summary = "00:00:02.500")),
        "key" to mutableListOf(node(1, 0, "North", summary = "F1")),
        "button" to mutableListOf(node(1, 0, "Bar", folder = true), node(2, 1, "Sword")),
    )

    /** Connects to [game] on a new engine instance, so that nothing of an earlier test is kept, and waits for the game screen. */
    fun openGame() {
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-designs-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
    }

    /** Opens the library from the game's title bar, answers its six lists, and waits for Greet in it. */
    fun openLibrary() {
        activate(hasContentDescription(string(R.string.scripts_open, game.name)))
        answerLists()
        waitFor(row("Greet"))
    }

    /** Answers the six lists a library asks for as it comes, with [lists]. */
    fun answerLists() {
        val asked = (1..lists.size).map { received("scriptItems") }
        assertEquals(lists.keys.toList(), asked.map { it.getString("kind") })
        for (ask in asked) items(ask.getString("kind"), ask)
    }

    /** Answers the next request for an item's fields with them, and returns it. */
    fun answerOpen(): JSONObject {
        val ask = received("scriptItem")
        val kind = ask.getString("kind")
        val listed = lists.getValue(kind).single { it.getInt("id") == ask.getInt("item") }
        session.send(fields(kind, fieldsOf(kind, listed), ask))
        return ask
    }

    /** The engine's answer to [request], an edit or an add, which leaves [kind]'s item [id] with [name] at the end of [lists]'s, then to the list asked after it. */
    fun saved(kind: String, id: Int, parent: Int, name: String, request: JSONObject) {
        val list = lists.getValue(kind)
        val at = list.indexOfFirst { it.getInt("id") == id }
        val item = node(id, parent, name)
        if (at >= 0) list[at] = item else list += item
        session.send(fields(kind, fieldsOf(kind, item), request))
        items(kind, received("scriptItems"))
    }

    /** The engine's answer to [check], that the Lua has [message] wrong on [line], and the problem as the item's page then shows it. */
    fun luaProblem(check: JSONObject, line: Int, message: String): SemanticsMatcher {
        session.send(JSONObject().put("t", "luaCheck").put("ok", false).put("line", line).put("message", message).put("re", check.get("id")))
        return hasText(string(R.string.lua_problem_at, line, message))
    }

    /**
     * The next request of [type] the app sends, once its screens have settled: what a screen asks for as it opens, or
     * sends as it goes, comes from its composition, which a test must wait on for it to go on.
     */
    fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    fun items(kind: String, request: JSONObject) = session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray(lists.getValue(kind))).put("re", request.get("id")))

    /** A row or card of the library: the item's name, which opens it or goes into the folder; not the search field, which may hold it. */
    fun row(name: String) = hasText(name) and hasClickAction() and !hasSetTextAction() and hasAnyAncestor(library)

    fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // Each window's: a menu or a dialog is a window of its own over the activity's
    fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /**
     * What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. What is
     * in the library's list or a page that scrolls is scrolled to first, as TalkBack does.
     */
    fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(library)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(library).performScrollToNode(node)
        } else if (compose.onAllNodes(node and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * PseudoLocaleTest's checks of what shows in [locale], [where]: no text is cut short, has more lines than it may
     * show, overflows its box or leaves the screen. Copied rather than shared with PseudoLocaleTest, so that the two do
     * not change together.
     */
    fun assertTextFits(locale: String, where: String) {
        compose.waitForIdle()
        checkAccessibility()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val width = root.size.width
        val problems = ArrayList<String>()
        var checked = 0
        fun visit(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val layouts = ArrayList<TextLayoutResult>()
            if (!text.isNullOrEmpty()) node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            checked += layouts.size
            for (layout in layouts) {
                if ((0 until layout.lineCount).any { layout.isLineEllipsized(it) }) problems += "\"$text\" is cut short"
                if (layout.multiParagraph.didExceedMaxLines) problems += "\"$text\" has more lines than it may show"
                if (layout.multiParagraph.height > layout.size.height + SLACK_PX) {
                    problems += "\"$text\" is ${layout.multiParagraph.height} px tall in its ${layout.size} box"
                }
            }
            val bounds = node.boundsInRoot
            if (layouts.isNotEmpty() && (bounds.left < -SLACK_PX || bounds.right > width + SLACK_PX)) {
                problems += "\"$text\" at $bounds is outside the screen's width $width"
            }
            node.children.forEach(::visit)
        }
        visit(root)
        // A screen with no text laid out would pass without being checked
        assertTrue("$locale, $where: no text on the screen gave its layout", checked > 0)
        assertTrue("$locale, $where: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    private fun fields(kind: String, fields: JSONObject, request: JSONObject): JSONObject = JSONObject().put("t", "scriptItem").put("kind", kind).put("fields", fields).put("re", request.get("id"))

    // The fields that take text have some: an empty one whose top alone shows at a page's foot has no name for the
    // checks (docs/roadmap.md, "A known gap, the toolkit's")
    private fun fieldsOf(kind: String, listed: JSONObject): JSONObject {
        val item = JSONObject(listed.toString())
        return when (kind) {
            "trigger" -> item.put("script", "echo(\"hi\")").put("patterns", JSONArray().put(JSONObject().put("text", "You wave").put("type", "substring")))
                .put("command", "wave").put("lineDelta", -1).put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false)
                .put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")

            "alias" -> item.put("script", "").put("pattern", "^kick$").put("command", "kick rat")

            "script" -> item.put("script", "echo(\"in\")").put("eventHandlers", JSONArray())

            "timer" -> item.put("script", "").put("command", "look").put("time", 2_500).put("offset", false)

            "key" -> item.put("script", "").put("command", "north").put("key", F1).put("modifiers", 0).put("keyName", "F1")

            else -> item.put("script", "").put("commandDown", "wield").put("commandUp", "").put("pushDown", false).put("location", "top")
                .put("orientation", "horizontal").put("columns", 1).put("css", "color: blue;")
        }
    }

    private fun node(id: Int, parent: Int, name: String, folder: Boolean = false, summary: String = ""): JSONObject = JSONObject()
        .put("id", id).put("parent", parent).put("name", name).put("folder", folder).put("enabled", true).put("active", true)
        .put("package", "").put("error", "").put("summary", summary)

    companion object {
        // Qt's number for F1, as docs/protocol.md gives a key binding's
        private const val F1 = 0x01000030

        // Rounding of the bounds to pixels
        private const val SLACK_PX = 1f

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
        const val WAIT_MS = 10_000L

        /** The changes an engine is asked to make to a profile's items. */
        val CHANGES = setOf("addScriptItem", "editScriptItem", "enableScriptItem", "moveScriptItem", "deleteScriptItem")
    }
}
