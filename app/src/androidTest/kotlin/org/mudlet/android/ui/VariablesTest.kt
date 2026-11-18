package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import org.mudlet.android.ui.scripts.VARIABLES_TAG

/**
 * The Variables screen (docs/architecture.md, "The script library") against a fake engine: opened from the script
 * library's chip, it lists the globals each read as name, type and value, a table opening to its members, which are
 * asked for as it opens, a long table a page at a time, a long string to its whole value with the engine's cut said;
 * "Show hidden variables" asks again with the hidden ones; a search finds names among those read; and what the engine
 * refuses is said, in place of the list when it refuses the globals. Controls are found by the labels TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class VariablesTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("variables ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private val list = hasTestTag(VARIABLES_TAG)

    @Before
    fun openTheVariables() {
        compose.enableAccessibilityChecksOfWhatShows()
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
        activate(hasContentDescription(string(R.string.scripts_open, game.name)))
        for (kind in listOf("trigger", "alias", "script", "timer", "key", "button")) {
            val asked = received("scriptItems")
            session.send(JSONObject().put("t", "scriptItems").put("kind", kind).put("items", JSONArray()).put("re", asked.get("id")))
        }
        activate(hasText(string(R.string.variables_chip)))
    }

    @Test
    fun theGlobalsReadAsNameTypeAndValueAndATableOpensToItsMembers() {
        val asked = received("variables")
        assertEquals(0, asked.getJSONArray("path").length())
        assertFalse(asked.getBoolean("showHidden"))
        assertEquals(0, asked.getInt("from"))
        answer(asked, listOf(table("gmcp", 2), text("name", "Ana"), number("hp", "10"), function("onLoad")), hiddenLeftOut = 3)

        waitFor(variable(reading("name", R.string.variables_type_string, string(R.string.variables_quoted, "Ana"))))
        compose.onNode(variable(reading("hp", R.string.variables_type_number, "10"))).assertIsDisplayed()
        compose.onNode(variable(string(R.string.variables_reading_bare, "onLoad", string(R.string.variables_type_function)))).assertIsDisplayed()
        compose.onNode(hasText(plural(R.plurals.variables_hidden_count, 3, 3))).assertIsDisplayed()

        val gmcp = variable(plural(R.plurals.variables_reading_table, 2, "gmcp", 2))
        activate(gmcp)
        val inner = received("variables")
        val step = inner.getJSONArray("path").getJSONObject(0)
        assertEquals("gmcp" to "string", step.getString("name") to step.getString("key"))
        answer(inner, listOf(number("1", "5", keyType = "number"), text("Char", "x")))
        // A number key is shown as a script indexes with it
        waitFor(variable(reading("[1]", R.string.variables_type_number, "5")))
        assertEquals(string(R.string.variables_expanded), compose.onNode(gmcp).fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        activate(gmcp)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(variable(reading("[1]", R.string.variables_type_number, "5"))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLongTableComesAPageAtATimeAndALongStringShowsWholeWithTheCutSaid() {
        val long = "a".repeat(1000)
        answer(received("variables"), listOf(text("first", long, cut = true), number("second", "2")), total = 3)

        activate(hasText(plural(R.plurals.variables_more, 1, 1)))
        val more = received("variables")
        assertEquals(2, more.getInt("from"))
        answer(more, listOf(number("third", "3")), from = 2, total = 3)
        waitFor(variable(reading("third", R.string.variables_type_number, "3")))

        val preview = string(R.string.variables_quoted, "a".repeat(80) + "…")
        activate(variable(reading("first", R.string.variables_type_string, preview)))
        waitFor(hasText(plural(R.plurals.variables_cut, 1000, 1000)))
        compose.onNode(variable(reading("first", R.string.variables_type_string, long))).assertIsDisplayed()
    }

    @Test
    fun theHiddenVariablesAreAskedForAndASearchFindsNamesAmongThoseRead() {
        answer(received("variables"), listOf(text("hpBar", "x"), text("name", "Ana")), hiddenLeftOut = 1)
        waitFor(variable(reading("name", R.string.variables_type_string, string(R.string.variables_quoted, "Ana"))))

        activate(hasText(string(R.string.variables_show_hidden)))
        val hidden = received("variables")
        assertEquals(true, hidden.getBoolean("showHidden"))
        answer(hidden, listOf(text("hpBar", "x"), text("mudlet", "4.19", hidden = true), text("name", "Ana")), showHidden = true)
        val named = string(R.string.variables_name_hidden, "mudlet", string(R.string.variables_hidden))
        waitFor(variable(reading(named, R.string.variables_type_string, string(R.string.variables_quoted, "4.19"))))

        compose.onNode(hasSetTextAction() and hasAnyAncestor(list)).performTextInput("HP")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(variable(reading("name", R.string.variables_type_string, string(R.string.variables_quoted, "Ana")))).fetchSemanticsNodes().isEmpty() }
        compose.onNode(variable(reading("hpBar", R.string.variables_type_string, string(R.string.variables_quoted, "x")))).assertIsDisplayed()
    }

    @Test
    fun aTableTheEngineWillNotListClosesAndWhySaysSo() {
        answer(received("variables"), listOf(table("gone", 4)))
        activate(variable(plural(R.plurals.variables_reading_table, 4, "gone", 4)))
        session.refuse(received("variables"), "There is no such table.")
        waitFor(hasText(string(R.string.variables_refused, "There is no such table.")))
        assertEquals(
            string(R.string.variables_collapsed),
            compose.onNode(variable(plural(R.plurals.variables_reading_table, 4, "gone", 4))).fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun aRefusedListingSaysWhyAndIsNotLeftLoading() {
        session.refuse(received("variables"), "no profile running")
        waitFor(hasText(string(R.string.variables_refused, "no profile running")))
        compose.onNode(hasText(string(R.string.variables_loading)) and hasAnyAncestor(list)).assertDoesNotExist()
    }

    private fun answer(
        request: JSONObject,
        entries: List<JSONObject>,
        from: Int = 0,
        total: Int = from + entries.size,
        hiddenLeftOut: Int = 0,
        showHidden: Boolean = false,
    ) = session.send(
        JSONObject().put("t", "variables").put("path", request.getJSONArray("path")).put("showHidden", showHidden).put("from", from)
            .put("total", total).put("hiddenLeftOut", hiddenLeftOut).put("entries", JSONArray(entries)).put("re", request.get("id")),
    )

    private fun entry(name: String, type: String, keyType: String, hidden: Boolean) = JSONObject().put("name", name).put("key", keyType).put("type", type).put("hidden", hidden)

    private fun text(name: String, value: String, cut: Boolean = false, hidden: Boolean = false) = entry(name, "string", "string", hidden).put("value", value).put("cut", cut)

    private fun number(name: String, value: String, keyType: String = "string") = entry(name, "number", keyType, false).put("value", value).put("cut", false)

    private fun table(name: String, members: Int) = entry(name, "table", "string", false).put("members", members).put("indexed", false)

    private fun function(name: String) = entry(name, "function", "string", false)

    private fun reading(name: String, @StringRes type: Int, value: String) = string(R.string.variables_reading, name, string(type), value)

    /** A variable's row, by what TalkBack reads for it. */
    private fun variable(reading: String) = hasContentDescription(reading) and hasAnyAncestor(list)

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = compose.activity.resources.getQuantityString(id, count, *args)

    private fun received(type: String): JSONObject {
        compose.waitForIdle()
        return session.receive(type)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** What a double tap does under TalkBack, after the checks; what is in the list is scrolled to first, as TalkBack does. */
    private fun activate(label: SemanticsMatcher) {
        val node = label and hasClickAction()
        waitFor(node)
        if (compose.onAllNodes(node and hasAnyAncestor(list)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(list).performScrollToNode(node)
        compose.onNode(node).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private companion object {
        const val INSTANCE = "fake-variables"
        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
