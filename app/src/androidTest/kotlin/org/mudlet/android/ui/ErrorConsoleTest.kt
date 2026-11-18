package org.mudlet.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.errors.ERROR_CONSOLE_TAG
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.GAME_TITLE_TAG

/** The engine's `errorConsole`, all it holds when [full], else what it added. */
internal fun FakeEngine.Session.errorConsole(full: Boolean, vararg entries: JSONObject) = send(JSONObject().put("t", "errorConsole").put("full", full).put("entries", JSONArray(entries.toList())))

/** An entry of the error console, raised by the [kind] [item] (none for empty) at [line] of its Lua. */
internal fun errorEntry(n: Long, message: String, kind: String = "", item: Int = 0, obj: String = "", function: String = "", event: String = "", line: Int = 0, debug: Boolean = false): JSONObject = JSONObject()
    .put("n", n).put("at", ERROR_AT).put("debug", debug).put("object", obj).put("function", function).put("event", event)
    .put("message", message).put("kind", kind).put("item", item).put("line", line)

// 2026-11-17 21:04:13 UTC: when the tests' entries came
internal const val ERROR_AT = 1_795_381_453_000L

/**
 * The error console (docs/architecture.md, "The error console") against a fake engine: the game's Scripts button and
 * the library's Errors button count the errors not yet seen, debugc() lines aside, on their badge and in what TalkBack
 * reads; the console lists every entry with what raised it, an entry with an item opens that item's page, a new error
 * is said by TalkBack once, Copy all puts the entries on the clipboard and Clear asks the engine to empty it. Controls
 * are found by the labels TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class ErrorConsoleTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("errors ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val console = hasTestTag(ERROR_CONSOLE_TAG)

    private val greet = errorEntry(1, TRIGGER_ERROR, "trigger", 3, "Greet", "Trigger3", line = 2)
    private val debug = errorEntry(2, "just saying", obj = "Greet", debug = true)
    private val handler = errorEntry(3, HANDLER_ERROR, obj = "onLoad", event = "sysLoadEvent")

    @Before
    fun openTheGame() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine instance for each test, so no entry of an earlier test counts as seen
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
        waitFor(hasTestTag(GAME_TITLE_TAG))
        session.errorConsole(true, greet, debug, handler)
    }

    @Test
    fun theButtonsCountTheUnseenErrorsAndTheConsoleShowsEachWithWhatRaisedIt() {
        activate(hasContentDescription(plural(R.plurals.errors_unseen, 2, string(R.string.scripts_open, game.name), 2)))
        openTheErrors(2)
        val greetSource = listOf("Greet", "Trigger3", string(R.string.errors_line, 2)).joinToString(string(R.string.errors_separator))
        waitFor(hasText(greetSource))
        waitFor(hasText(listOf("onLoad", string(R.string.errors_event, "sysLoadEvent")).joinToString(string(R.string.errors_separator))))
        waitFor(hasText("just saying"))
        assertNotSaid(TRIGGER_ERROR)

        // Seen once shown: neither button counts them, and a new error alone counts after
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasContentDescription(string(R.string.errors_open)))
        session.errorConsole(false, errorEntry(4, "late", "alias", 1, "kick", "Alias1"))
        waitFor(hasContentDescription(plural(R.plurals.errors_unseen, 1, string(R.string.errors_open), 1)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(hasContentDescription(plural(R.plurals.errors_unseen, 1, string(R.string.scripts_open, game.name), 1)))
        checkAccessibility()
    }

    @Test
    fun anEntryWithAnItemOpensItsPage() {
        openTheLibrary()
        openTheErrors(2)
        compose.onNode(hasText(HANDLER_ERROR) and hasClickAction()).assertDoesNotExist()
        activate(hasText(TRIGGER_ERROR) and hasAnyAncestor(console))
        compose.waitForIdle()
        val open = session.receive("scriptItem")
        assertEquals("trigger", open.getString("kind"))
        assertEquals(3, open.getInt("item"))
    }

    @Test
    fun aNewErrorIsSaidOnceAndDebugLinesAreNot() {
        openTheLibrary()
        openTheErrors(2)
        session.errorConsole(false, errorEntry(4, "quiet", debug = true))
        waitFor(hasText("quiet"))
        assertNotSaid("quiet")
        session.errorConsole(false, errorEntry(5, NEW_ERROR))
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == string(R.string.errors_announce, NEW_ERROR) }
        val said = Announcer.current!!
        // The same list again, as a reattach sends it, is not new
        session.errorConsole(true, greet, debug, handler, errorEntry(4, "quiet", debug = true), errorEntry(5, NEW_ERROR))
        session.errorConsole(false, errorEntry(6, "x", debug = true))
        waitFor(hasText("x"))
        assertEquals(said, Announcer.current)

        // Said within a moment of the last, the Announcer adds it to that one
        session.errorConsole(false, errorEntry(7, "one"), errorEntry(8, "two"))
        compose.waitUntil(WAIT_MS) { Announcer.current?.text?.substringAfterLast('\n') == plural(R.plurals.errors_announce_many, 2, 2, "two") }
    }

    @Test
    fun copyAllPutsTheEntriesOnTheClipboardAndClearAsksTheEngine() {
        openTheLibrary()
        openTheErrors(2)
        activate(hasContentDescription(string(R.string.errors_copy)))
        compose.waitForIdle()
        var copied = ""
        compose.runOnUiThread { copied = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString() }
        val entries = copied.split("\n\n")
        assertEquals(3, entries.size)
        assertTrue(copied, entries[0].endsWith("\n" + TRIGGER_ERROR))
        assertTrue(copied, entries[2].endsWith("\n" + HANDLER_ERROR))

        activate(hasContentDescription(string(R.string.errors_clear)))
        compose.waitForIdle()
        session.receive("clearErrorConsole")
        session.errorConsole(true)
        waitFor(hasText(string(R.string.errors_none)))
        compose.onNode(hasContentDescription(string(R.string.errors_clear))).assertDoesNotExist()
        checkAccessibility()
    }

    @Test
    fun copyAllTakesTheNewestEntriesThatFitTheClipboard() {
        openTheLibrary()
        openTheErrors(2)
        val long = (4L..7L).map { errorEntry(it, "$it" + "x".repeat(LONG_MESSAGE)) }
        session.errorConsole(true, *long.toTypedArray())
        // Any of them: each is taller than the screen, so which shows depends on its size
        waitFor(hasText("x".repeat(LONG_MESSAGE), substring = true))
        activate(hasContentDescription(string(R.string.errors_copy)))
        waitFor(hasText(plural(R.plurals.errors_copied_newest, 3, 3)))
        var copied = ""
        compose.runOnUiThread { copied = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString() }
        assertEquals(listOf("5", "6", "7"), copied.split("\n\n").map { it.substringAfterLast('\n').take(1) })
    }

    private fun openTheLibrary() {
        activate(hasContentDescription(plural(R.plurals.errors_unseen, 2, string(R.string.scripts_open, game.name), 2)))
    }

    /** From the script library, which asks for each kind's items as it opens, the error console with [unseen] errors. */
    private fun openTheErrors(unseen: Int) {
        compose.waitForIdle()
        repeat(KINDS) {
            val ask = session.receive("scriptItems")
            session.send(JSONObject().put("t", "scriptItems").put("kind", ask.getString("kind")).put("items", JSONArray()).put("re", ask.get("id")))
        }
        activate(hasContentDescription(plural(R.plurals.errors_unseen, unseen, string(R.string.errors_open), unseen)))
        waitFor(console)
    }

    private fun assertNotSaid(text: String) {
        compose.waitForIdle()
        assertTrue("said: ${Announcer.current?.text}", Announcer.current?.text?.contains(text) != true)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = compose.activity.resources.getQuantityString(id, count, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun checkAccessibility() {
        val roots = compose.onAllNodes(isRoot())
        for (i in roots.fetchSemanticsNodes().indices) roots[i].tryPerformAccessibilityChecks()
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks, scrolled to first. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(console)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(console).performScrollToNode(node)
        checkAccessibility()
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-errors"
        const val TRIGGER_ERROR = "[string \"Trigger: Greet\"]:2: attempt to index a nil value"
        const val HANDLER_ERROR = "attempt to call a nil value"
        const val NEW_ERROR = "attempt to compare number with nil"

        // Four of these pass the 100000 characters copied at most, three do not
        const val LONG_MESSAGE = 30_000

        // The script library asks for triggers, aliases, scripts, timers, keys and buttons
        const val KINDS = 6
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
