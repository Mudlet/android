package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
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
import org.mudlet.android.protocol.RUN_UNDERLINE

/**
 * The game's links in the main console (docs/architecture.md, "The main console's links"), against a fake engine: asked
 * about with no window, followed by a tap, a menu item or TalkBack's action, and asked again after `linksChanged`.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleLinksTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val row = hasText(LINE)

    @Before
    fun openTheGameScreenWithALink() {
        compose.enableAccessibilityChecksOfWhatShows()
        // A new engine to EngineClient, which lives as long as the process: no link answer of an earlier test stands
        val first = fake.engine.nextSession()
        fake.engine.instance = INSTANCE
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { displayed("the main console") }
        // Once the game's screen is up, so only its console asks
        session.line(2, LINE, listOf(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, -1, -1, RUN_UNDERLINE, LINK)))
        compose.waitUntil(WAIT_MS) { displayed(LINE) }
    }

    @Test
    fun aLinkIsAskedAboutTappedOpenedAndOfferedToTalkBack() {
        val asked = askedAbout()
        session.send(linkMessage("walk north", asked.get("id")))
        val follow = string(R.string.console_link_follow, "walk north")
        val openMenu = string(R.string.console_link_menu, "walk north")
        compose.waitUntil(WAIT_MS) { actions().map { it.label } == listOf(follow, openMenu) }
        compose.onRoot().tryPerformAccessibilityChecks()
        // Once asked, it is not asked again as the line is drawn again with the answer
        session.assertNoRequest("getLink", 1_000)

        // TalkBack's action, and a tap on the link, follow it; a tap beside it does not
        val onLink = characterCentre(6)
        val beside = characterCentre(1)
        runAction(follow)
        assertFollowed(item = null)
        compose.onNode(row).performTouchInput { click(onLink) }
        assertFollowed(item = null)
        compose.onNode(row).performTouchInput { click(beside) }
        session.assertNoRequest("followLink", 1_000)
        // Nor does one in the line's empty width past the link that ends it
        compose.onNode(row).performTouchInput { click(Offset(width - 1f, centerY)) }
        session.assertNoRequest("followLink", 1_000)

        // A long press opens its menu under its title; an item counts the separator before it, and a greyed-out one is off
        compose.onNode(row).performTouchInput { longClick(onLink) }
        compose.waitUntil(WAIT_MS) { displayed("Eat") }
        compose.onNodeWithText("Ways").assertIsDisplayed()
        compose.onNode(hasText("Drop") and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(hasText("Eat") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertFollowed(item = 2)
        compose.waitUntil(WAIT_MS) { !displayed("Eat") }
        // TalkBack's action opens it too
        runAction(openMenu)
        compose.waitUntil(WAIT_MS) { displayed("Look") }
        compose.onNode(hasText("Look") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertFollowed(item = 0)
    }

    @Test
    fun linksChangedAsksAgainAboutTheLinksItNamesAndALinkLetGoOfLosesItsAnswer() {
        session.send(linkMessage("walk north", askedAbout().get("id")))
        compose.waitUntil(WAIT_MS) { labels().contains(string(R.string.console_link_follow, "walk north")) }

        // Another link's change asks nothing; this one's asks again, and the new answer replaces the old
        session.send(linksChanged(LINK + 1))
        compose.waitForIdle()
        session.assertNoRequest("getLink", 1_000)
        session.send(linksChanged(LINK))
        session.send(linkMessage("run north", askedAbout().get("id"), menu = false))
        compose.waitUntil(WAIT_MS) { labels() == listOf(string(R.string.console_link_follow, "run north")) }

        // Any link may have changed: asked again. One the engine no longer holds is read by its own text, and not asked again
        session.send(linksChanged())
        val again = askedAbout()
        session.send(JSONObject().put("t", "error").put("message", "getLink: unknown").put("re", again.get("id")))
        compose.waitUntil(WAIT_MS) { labels() == listOf(string(R.string.console_link_follow, "north")) }
        session.assertNoRequest("getLink", 1_000)
        assertFalse("no menu without an answer", labels().contains(string(R.string.console_link_menu, "north")))
    }

    @Test
    fun aLineFromBeforeANewEngineInstanceOffersNoLinkAsItsIdNamesAnotherLink() {
        askedAbout()
        val onLink = characterCentre(6)
        fake.engine.instance = "$INSTANCE-2"
        session.close()
        session = fake.engine.nextSession()
        compose.waitUntil(WAIT_MS) { displayed(string(R.string.marker_new_engine, "$INSTANCE-2")) }
        compose.waitForIdle()
        session.assertNoRequest("getLink", 1_000)
        assertEquals(emptyList<String>(), labels())
        compose.onNode(row).performTouchInput { click(onLink) }
        session.assertNoRequest("followLink", 1_000)

        // The new instance's own line with the same id is its link
        session.line(1, "go: south", listOf(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, -1, -1, RUN_UNDERLINE, LINK)))
        compose.waitUntil(WAIT_MS) { displayed("go: south") }
        askedAbout()
    }

    // The test's clock recomposes only while the test waits on Compose, so the line is drawn, and asks, first
    private fun askedAbout(): JSONObject {
        compose.waitForIdle()
        val asked = session.receive("getLink")
        assertEquals(LINK, asked.getInt("link"))
        assertFalse("the main console's link is asked about with no window", asked.has("window"))
        return asked
    }

    private fun assertFollowed(item: Int?) {
        val followed = session.receive("followLink")
        assertEquals(LINK, followed.getInt("link"))
        assertFalse("the main console's link is followed with no window", followed.has("window"))
        assertEquals(item, if (followed.has("item")) followed.getInt("item") else null)
    }

    private fun linksChanged(vararg ids: Int) = JSONObject().put("t", "linksChanged").put("ids", JSONArray(ids.toList()))

    /** The answer to a getLink of [id]: with [menu], one titled Ways of Look, a separator, Eat and a greyed-out Drop. */
    private fun linkMessage(tooltip: String, id: Any, menu: Boolean = true) = JSONObject().put("t", "link").put("link", LINK)
        .put("tooltip", tooltip).put("menu", menu).put("menuTitle", "Ways")
        .put(
            "items",
            JSONArray(
                listOf(
                    JSONObject().put("label", "Look").put("enabled", true).put("separator", false),
                    JSONObject().put("label", "").put("enabled", true).put("separator", true),
                    JSONObject().put("label", "Eat").put("enabled", true).put("separator", false),
                    JSONObject().put("label", "Drop").put("enabled", false).put("separator", false),
                ),
            ),
        )
        .put("spoiler", false).put("revealed", false).put("disabled", false).put("concealed", false).put("visited", false).put("selected", false)
        .put("re", id)

    private fun actions() = compose.onNode(row).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun labels() = actions().map { it.label }

    // TalkBack's action [label]: found from the test thread, as a semantics lookup waits for idle, and run on the main one
    private fun runAction(label: String) {
        val action = actions().single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    private fun characterCentre(index: Int): Offset {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(row).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().getBoundingBox(index).center
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun displayed(text: String) = try {
        compose.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-console-links"
        const val LINE = "go: north"
        const val LINK = 7
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
