package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.scripts.SCRIPT_ITEM_TAG
import org.mudlet.android.ui.scripts.TRIGGER_FROM_LINE_TAG

/**
 * A trigger made from a line of the game's text (docs/architecture.md, "The script library") against a fake engine: a
 * long press on a line of the console selects it, with Android's floating text toolbar offering it, and TalkBack has it as the line's action, but not on
 * a blank line; the page that opens has the line's words, each of which a tap makes captured, then optional, then fixed
 * again, and shows the pattern that comes of them, what it captures from the line and how many of the game's lines it
 * matches; its button makes the trigger and saves it with the pattern, and the trigger's page then opens in the
 * builder's place; and what the engine refuses is said. Controls are found by the labels TalkBack reads and activated
 * through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class LineTriggerTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("line trigger ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)

    private val builder = hasTestTag(TRIGGER_FROM_LINE_TAG)
    private val make by lazy { hasText(string(R.string.line_trigger_make)) and hasClickAction() }

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no line of an earlier test is kept
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "You hit the rat.")
        session.line(2, "A bat bites you.")
        session.line(3, LINE)
        session.synced(3)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        activate(hasContentDescription(string(R.string.profile_connect_to, game.name)))
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        waitFor(hasText(LINE))
    }

    @Test
    fun aLongPressOnALineOffersATriggerInAndroidsToolbarWhichIsMadeWithThePatternAndOpened() {
        compose.onNode(hasText(LINE)).performTouchInput { longClick(center) }
        FloatingToolbar.tap(string(R.string.console_selection_trigger))
        waitFor(builder)
        // Every word fixed: the line as a substring
        compose.onNode(hasText(string(R.string.pattern_substring)) and hasAnyAncestor(builder)).assertExists()
        compose.onNode(hasText(matched(1))).assertExists()

        activate(word("rat"))
        waitFor(word("(rat)"))
        compose.onNode(hasText(string(R.string.pattern_regex)) and hasAnyAncestor(builder)).assertExists()
        compose.onNode(hasText(PATTERN)).assertExists()
        compose.onNode(hasText(string(R.string.line_trigger_match, 2, "rat"))).assertExists()
        // The bat's line too, with the word captured
        compose.onNode(hasText(matched(2))).assertExists()

        activate(make)
        val add = received("addScriptItem")
        assertEquals("trigger", add.getString("kind"))
        assertEquals(0, add.getInt("parent"))
        assertFalse(add.getBoolean("folder"))
        assertEquals(LINE, add.getString("name"))
        // Not made twice while the engine makes it
        compose.onNode(make).assertIsNotEnabled()
        session.send(fields(trigger(9, LINE, JSONArray()).put("active", false), add))
        val edit = received("editScriptItem")
        assertEquals(9, edit.getInt("item"))
        val patterns = edit.getJSONObject("fields").getJSONArray("patterns")
        assertEquals(1, patterns.length())
        assertEquals(PATTERN, patterns.getJSONObject(0).getString("text"))
        assertEquals("regex", patterns.getJSONObject(0).getString("type"))
        val saved = trigger(9, LINE, patterns)
        session.send(fields(saved, edit))

        // The trigger's page, with the pattern in it, and the game behind it
        val open = received("scriptItem")
        assertEquals(9, open.getInt("item"))
        session.send(fields(saved, open))
        val page = hasTestTag(SCRIPT_ITEM_TAG)
        waitFor(hasText(LINE) and hasSetTextAction() and hasAnyAncestor(page))
        compose.onNode(hasText(PATTERN) and hasSetTextAction() and hasAnyAncestor(page)).assertExists()
        back()
        waitFor(hasTestTag(GAME_TITLE_TAG))
        compose.onNode(builder).assertDoesNotExist()
    }

    @Test
    fun talkBacksActionOnALineOpensThePageWhoseWordsSayWhatTheyAreAndARefusalIsSaid() {
        val label = string(R.string.console_make_trigger)
        // Only here: a blank line shown as the game sent it is a row with no text, which the checks before a touch report
        session.line(4, "")
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.lines.any { it.text.isEmpty() } } }
        // Each line with text, and not the blank one
        compose.onAllNodes(SemanticsMatcher("offers a trigger") { node -> node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label } }).assertCountEquals(3)
        val action = compose.onNode(hasText(LINE)).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        waitFor(builder)

        // A word says what it is, which its brackets and question mark only show
        fun says(shown: String, @StringRes state: Int) = waitFor(word(shown) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(state)))
        says("bites", R.string.line_trigger_fixed)
        activate(word("bites"))
        says("(bites)", R.string.line_trigger_capture)
        activate(word("(bites)"))
        says("bites?", R.string.line_trigger_optional)
        compose.onNode(hasText("""A rat(?: bites)? you\.""")).assertExists()
        activate(word("bites?"))
        says("bites", R.string.line_trigger_fixed)

        // Each word optional leaves nothing to match
        for (each in listOf("A", "rat", "bites", "you")) {
            activate(word(each))
            activate(word("($each)"))
        }
        waitFor(hasText(string(R.string.line_trigger_unusable)))
        compose.onNode(make).assertIsNotEnabled()
        activate(word("you?"))
        waitFor(word("you"))

        // The whole line, once its words are fixed again, is an exact match
        for (each in listOf("A", "rat", "bites")) activate(word("$each?"))
        activate(hasText(string(R.string.line_trigger_whole)))
        waitFor(hasText(string(R.string.pattern_exact)) and hasAnyAncestor(builder))

        activate(make)
        session.refuse(received("addScriptItem"), "a trigger is running")
        waitFor(hasText(string(R.string.scripts_refused_add, "a trigger is running")))
        // To try again
        waitFor(make and isEnabled())
        compose.onNode(builder).assertIsDisplayed()
        checkAccessibility()
    }

    /** What the page says when [count] of the game's lines match: the three the engine sent, and none of the app's own. */
    private fun matched(count: Int) = compose.activity.resources.getQuantityString(R.plurals.line_trigger_scrollback, count, count, LINES)

    /** A word of the line on the builder's page, as it shows there. */
    private fun word(shown: String) = hasText(shown) and hasClickAction() and hasAnyAncestor(builder)

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** The next request of [type] the app sends, once its screens have settled. */
    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun trigger(id: Int, name: String, patterns: JSONArray): JSONObject = JSONObject()
        .put("id", id).put("parent", 0).put("name", name).put("folder", false).put("enabled", true).put("active", true)
        .put("package", "").put("error", "").put("summary", "")
        .put("script", "").put("patterns", patterns)
        .put("command", "").put("lineDelta", -1).put("matchAll", false).put("filter", false).put("fireLength", 0).put("sound", false)
        .put("soundFile", "").put("highlight", false).put("highlightFg", "").put("highlightBg", "")

    private fun fields(fields: JSONObject, request: JSONObject): JSONObject = JSONObject().put("t", "scriptItem").put("kind", "trigger").put("fields", fields).put("re", request.get("id"))

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // Each window's: a menu is a window of its own over the activity's
    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /**
     * What a double tap does under TalkBack: the node's accessibility click, after the checks on every window. What is
     * on the builder's page is scrolled to first, as TalkBack does.
     */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(builder)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(node).performScrollTo()
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-line-trigger"
        const val LINE = "A rat bites you."
        const val PATTERN = """A (\w+) bites you\."""
        const val LINES = 3
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
