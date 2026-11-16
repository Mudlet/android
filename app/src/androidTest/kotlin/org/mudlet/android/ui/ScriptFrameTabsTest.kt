package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile

/**
 * An MXP frame's tab header (docs/phase-f.md, "Mini consoles and user windows"), against a fake engine: a tab for each
 * page, by its title, read as a tab and as selected or not, at least 48 dp; a tap on one sends `selectFrameTab` with the
 * page's name, and the engine's new page in front is the tab selected and the page drawn; stacked, where the frame is a
 * page, called by its tab's title, the tabs are at the top of the pane, which stays open on the page brought forward,
 * and the switcher has that page selected.
 */
@RunWith(AndroidJUnit4::class)
class ScriptFrameTabsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        // A new engine instance for each test, so no window of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        compose.onRoot().tryPerformAccessibilityChecks()
        val connect = hasContentDescription(string(R.string.profile_connect_to, GAME.name)) and hasClickAction()
        compose.onNode(connect).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("the main console").assertIsDisplayed()
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun eachPageIsATabByItsTitleAndATapBringsItForward() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
        // As wide as the main window, so the three tabs fit a phone's width
        session.windows(frame(current = 1, y = 330, w = 800), full = true)
        session.windowText("chat", listOf("said in chat"))
        session.windowText("log", listOf("written in the log"))
        awaitNode(tab("Chat") and isSelected())
        awaitNode(hasText("said in chat"))

        for (title in listOf("Tabs", "Chat", "log")) {
            compose.onNode(tab(title)).performScrollTo().assertIsDisplayed()
            val bounds = compose.onNode(tab(title)).fetchSemanticsNode().boundsInRoot
            val min = with(compose.density) { 48.dp.toPx() }
            assertTrue("$title width ${bounds.width} < $min", bounds.width >= min - 1)
            assertTrue("$title height ${bounds.height} < $min", bounds.height >= min - 1)
        }
        compose.onNode(tab("Tabs")).assertIsNotSelected()
        compose.onNode(tab("log")).assertIsNotSelected()
        compose.onNode(hasContentDescription(string(R.string.frame_tabs_description, "Tabs"))).assertIsDisplayed()
        compose.onNodeWithText("said in chat").assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        // The engine is asked, and the page it brings forward is the one selected and drawn
        compose.onNode(tab("log")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("log", session.receive("selectFrameTab").getString("name"))
        session.windows(frame(current = 2, y = 330, w = 800))
        awaitNode(tab("log") and isSelected())
        compose.onNode(tab("Chat")).assertIsNotSelected()
        awaitNode(hasText("written in the log"))
        assertTrue("the page behind is not drawn", compose.onAllNodes(hasText("said in chat")).fetchSemanticsNodes().isEmpty())

        // The tab in front asks for nothing
        compose.onNode(tab("log")).performSemanticsAction(SemanticsActions.OnClick)
        session.assertNoRequest("selectFrameTab", QUIET_MS)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun stackedTheTabsAreAtTheTopOfThePaneWhichStaysOpenOnThePageBroughtForward() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        // The frame in the left border, so a page
        session.mainArea(1000, 500, 100, 25, 300, 50, 600, 400)
        session.windows(frame(current = 1, y = 80), full = true)
        session.windowText("chat", listOf("said in chat"))
        session.windowText("log", listOf("written in the log"))
        // The page is called by its tab's title
        awaitNode(chip("Chat"))
        assertTrue("the tabs show only with their page", compose.onAllNodes(tab("Chat")).fetchSemanticsNodes().isEmpty())

        compose.onNode(chip("Chat")).performSemanticsAction(SemanticsActions.OnClick)
        awaitNode(tab("Chat") and isSelected())
        awaitNode(hasText("said in chat"))
        compose.onNodeWithText("said in chat").assertIsDisplayed()
        compose.onNode(hasContentDescription(string(R.string.window_back_to_console, "Chat")) and hasClickAction()).assertIsDisplayed()
        val tabs = compose.onNode(tab("Chat")).fetchSemanticsNode().boundsInRoot
        val page = compose.onNode(hasContentDescription(string(R.string.window_description, "Chat"))).fetchSemanticsNode().boundsInRoot
        assertTrue("the tabs ${tabs.bottom} above the page ${page.top}", tabs.bottom <= page.top + 1)
        compose.onRoot().tryPerformAccessibilityChecks()

        compose.onNode(tab("log")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("log", session.receive("selectFrameTab").getString("name"))
        session.windows(frame(current = 2, y = 80))
        awaitNode(tab("log") and isSelected())
        awaitNode(hasText("written in the log"))
        compose.onNode(hasContentDescription(string(R.string.window_back_to_console, "log")) and hasClickAction()).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        // The switcher has the page brought forward selected, so a tap on it closes the pane
        compose.onNode(chip("log")).assertIsSelected().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tab("log")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(chip("log")).assertIsNotSelected()
    }

    // A titled MXP frame at [y] in Mudlet's main window, [w] by 270 below its tabs: its own console "tabs", "chat"
    // and "log", which has no title, with the page at [current] in front, the only one shown
    private fun frame(current: Int, y: Int, w: Int = 200): List<JSONObject> {
        val names = listOf("tabs", "chat", "log")
        return names.mapIndexed { i, name ->
            val window = FakeEngine.window(name, kind = "mxpFrame", x = 0, y = y, w = w, h = 270).put("shown", i == current)
            if (i == 0) window.put("tabs", JSONArray(names)).put("tabTitles", JSONArray(listOf("Tabs", "Chat", ""))).put("tabCurrent", current) else window
        }
    }

    private fun tab(title: String): SemanticsMatcher = hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // The window switcher's chip for a page, which a frame's tab of the same title is not
    private fun chip(title: String): SemanticsMatcher = hasText(title) and hasClickAction() and !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun awaitNode(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-frame-tabs"
        const val WAIT_MS = 10_000L

        // How long the UI stays quiet for it to have sent nothing, in real time
        const val QUIET_MS = 1_000
        const val TIMEOUT_MS = 240_000L
    }
}
