package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
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
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RUN_UNDERLINE
import org.mudlet.android.ui.game.Announcer
import kotlin.math.abs

/**
 * The windows a profile's scripts draw (docs/phase-f.md), against a fake engine: where the game screen puts them in
 * each layout, the size it gives Mudlet's main window, the window switcher, a window's command line and a user window's
 * close button. Controls are found by the labels TalkBack reads and activated through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class ScriptWindowsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private fun windowNamed(name: String) = hasContentDescription(string(R.string.window_description, name))

    // The main console: the one list that is not in a script window. A getter, as the strings need the activity
    private val console get() = hasScrollToIndexAction() and !hasAnyAncestor(hasContentDescription(string(R.string.window_description, ""), substring = true))

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        // The windows of an earlier test are gone as they would be with a new engine: EngineClient lives as long as
        // the process, and this engine is new to it
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
        waitUntilDisplayed("the main console")
        compose.onNodeWithText("the main console").assertIsDisplayed()
    }

    // The settings are stored and the Announcer lives as long as the process, so both would outlast the test
    @After
    fun automaticLayout() {
        compose.runOnIdle {
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.blankLines.reset()
            Announcer.touchExplorationOverride = null
        }
    }

    @Test
    fun fittedDrawsAMiniConsoleWhereTheScriptPutItAndGivesMudletTheConsolesGrid() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        // Mudlet's text fills its 800 by 600 main window, 100 columns of about 8 px; the chat is its top right quarter
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        session.windows(listOf(FakeEngine.window("chat", x = 400, y = 0, w = 400, h = 300, columns = 50)), full = true)
        session.windowText("chat", listOf("hello from chat"))
        waitUntilDisplayed("hello from chat")

        val area = bounds(console)
        val chat = bounds(windowNamed("chat"))
        assertNear("left", area.left + area.width / 2, chat.left)
        assertNear("top", area.top, chat.top)
        assertNear("width", area.width / 2, chat.width)
        assertNear("height", area.height / 2, chat.height)

        // Mudlet's grid as the console's: its main window as many of its characters wide as the console is of the app's;
        // the last size sent, as the first may be the one an earlier test left, which a new engine gets as it attaches
        val size = session.receiveLast("setMainWindowSize", readPast = true)
        val scale = appCharWidth() / mudletCharWidth()
        assertNear("Mudlet's width", area.width / scale, size.getInt("w").toFloat(), area.width / scale * TOLERANCE)
        assertNear("Mudlet's height", area.height / scale, size.getInt("h").toFloat(), area.height / scale * TOLERANCE)

        // Read after the console, the input line and the bar
        compose.onNode(windowNamed("chat")).assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f))
        compose.onNode(windowNamed("chat")).assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun stackedGivesMudletTheLandscapeSizeWithTheBordersAsBandsAndWindowsOverTheText() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        // Borders of 50 above and below the text
        session.mainArea(800, 400, MUDLET_COLUMNS, 30, 0, 50, MUDLET_TEXT_WIDTH, 300)
        session.windows(
            listOf(FakeEngine.window("title", x = 0, y = 0, w = 800, h = 50), FakeEngine.window("chat", x = 400, y = 50, w = 400, h = 100)),
            full = true,
        )
        session.windowText("title", listOf("the title bar"))
        session.windowText("chat", listOf("chat over the text"))
        waitUntilDisplayed("chat over the text")
        waitUntilDisplayed("the title bar")

        val text = bounds(console)
        // The bands at the width's scale
        val b = text.width / 800
        val title = bounds(windowNamed("title"))
        assertNear("band top", text.top - 50 * b, title.top)
        assertNear("band height", 50 * b, title.height)
        assertNear("band width", text.width, title.width)
        val chat = bounds(windowNamed("chat"))
        assertNear("over the text, from its top", text.top, chat.top)
        assertNear("over the text, at its scale", text.width / 2, chat.left - text.left)
        assertNear("over the text, its height", 100 * b, chat.height)

        // Mudlet's main window as the area would be turned on its side
        val area = Rect(text.left, text.top - 50 * b, text.right, text.bottom + 50 * b)
        val size = session.receiveLast("setMainWindowSize", readPast = true)
        val scale = appCharWidth() / mudletCharWidth()
        assertNear("Mudlet's width", area.height / scale, size.getInt("w").toFloat(), area.height / scale * TOLERANCE)
        assertNear("Mudlet's height", area.width / scale, size.getInt("h").toFloat(), area.width / scale * TOLERANCE)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aDockIsATabWhenStackedThatOpensItOverTheConsoleWhereItsCloseButtonClosesIt() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        session.mainArea(800, 400, MUDLET_COLUMNS, 30, 0, 0, MUDLET_TEXT_WIDTH, 400)
        session.windows(listOf(FakeEngine.window("side", kind = "userWindow", w = 200, h = 400, dockArea = "right")), full = true)
        session.windowText("side", listOf("in the dock"))
        val tab = hasText("side") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("a page shows only when its tab is chosen", displayed("in the dock"))

        activate(tab)
        waitUntilDisplayed("in the dock")
        // TalkBack does not read what the pane covers
        assertTrue("the console under the pane is not read", compose.onAllNodesWithText("the main console").fetchSemanticsNodes().isEmpty())
        val pane = bounds(windowNamed("side"))
        // The engine learns where the window is, in its pixels
        val placement = session.receive("placeUserWindow")
        assertEquals("side", placement.getString("name"))
        val scale = appCharWidth() / mudletCharWidth()
        assertNear("its width", pane.width / scale, placement.getInt("w").toFloat(), pane.width / scale * TOLERANCE)

        activate(hasContentDescription(string(R.string.window_close, "side")))
        assertEquals("side", session.receive("closeUserWindow").getString("name"))
        activate(hasContentDescription(string(R.string.window_back_to_console, "side")))
        compose.waitUntil(WAIT_MS) { !displayed("in the dock") }
        compose.onNodeWithText("the main console").assertIsDisplayed()
    }

    @Test
    fun aWindowsCommandLineSendsWhatIsTypedAndShowsWhatAScriptPutsThere() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        session.windows(
            listOf(
                FakeEngine.window("chat", x = 0, y = 300, w = 800, h = 300),
                FakeEngine.window("chat", kind = "commandLine", w = 0, h = 0, parent = "chat", parentKind = "miniConsole"),
            ),
            full = true,
        )
        val field = hasSetTextAction() and hasText(string(R.string.window_command_line, "chat"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(field).performTextInput("say hello")
        compose.onNode(field).performImeAction()
        val sent = session.receive("sendWindowCommandLine")
        assertEquals("chat", sent.getString("name"))
        assertEquals("say hello", sent.getString("text"))
        compose.onNode(field).assert(editableText(""))
        // The engine empties the line after a send, as Mudlet does; that is no script's text
        session.windowCommandLine("chat", "")

        session.windowCommandLine("chat", "tell bob ")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(field and editableText("tell bob ")).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aWindowsRowsAreReadAsTheirPlainTextNeverAloudAndBlankOnesAsTheSettingSays() {
        showChat()
        compose.runOnIdle { Announcer.touchExplorationOverride = true }
        val before = announced()
        windowRows("chat", "Red and bold, then plain" to listOf(listOf(0, 12, 1, -1, RUN_BOLD, 0)), "" to emptyList(), "below" to emptyList())
        waitUntilDisplayed("Red and bold, then plain")
        val shown = compose.onNode(hasText("Red and bold, then plain") and inChat).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue("a row is read without its styles: ${shown.spanStyles}", shown.spanStyles.isEmpty() && shown.paragraphStyles.isEmpty())
        // As the main console's lines are, the blank row is read as it is unless the setting says otherwise
        compose.onNode(hasText("") and inChat).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))

        compose.runOnIdle { Settings.blankLines.set(Settings.BlankLines.HIDE) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("") and inChat and isHidden).fetchSemanticsNodes().size == 1 }
        compose.runOnIdle { Settings.blankLines.set(Settings.BlankLines.SPACE) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(" ") and inChat).fetchSemanticsNodes().size == 1 }
        // A blank alone is nothing a screen reader can say, so the row is labelled with its name
        compose.onNode(hasText(" ") and inChat).assert(hasContentDescription(string(R.string.console_blank_line_space)))
        assertTrue("and still drawn, as the window's grid is the script's", displayed("below"))

        // Well past the Announcer's wait for more lines: a window's rows are not read aloud
        SystemClock.sleep(QUIET_MS)
        assertEquals("no announcement of a window's rows", before, announced())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aLinkInAWindowIsAskedAboutTappedAndOfferedToTalkBackByTheWindowsName() {
        showChat()
        windowRows("chat", "go: north" to listOf(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, -1, -1, RUN_UNDERLINE, 7)))
        // The test's clock recomposes only while the test waits on Compose, so the row is drawn, and asks, first
        waitUntilDisplayed("go: north")
        val asked = session.receive("getLink")
        assertEquals(7, asked.getInt("link"))
        assertEquals("chat", asked.getString("window"))
        session.send(linkMessage(7, "chat", "walk north", asked.get("id")))
        val row = hasText("go: north") and inChat
        val follow = string(R.string.console_link_follow, "walk north")
        val openMenu = string(R.string.console_link_menu, "walk north")
        compose.waitUntil(WAIT_MS) { actions(row).map { it.label } == listOf(follow, openMenu) }
        compose.onRoot().tryPerformAccessibilityChecks()
        // Once asked, it is not asked again as the row is drawn again with the answer
        session.assertNoRequest("getLink", 1_000)

        // TalkBack's action, and a tap on the link, follow it; a tap beside it does not
        val onLink = characterCentre(row, 6)
        val beside = characterCentre(row, 1)
        runAction(row, follow)
        assertFollowed(item = null)
        compose.onNode(row).performTouchInput { click(onLink) }
        assertFollowed(item = null)
        compose.onNode(row).performTouchInput { click(beside) }
        session.assertNoRequest("followLink", 1_000)
        // Nor does one in the row's empty width past the link that ends it
        compose.onNode(row).performTouchInput { click(Offset(width - 1f, centerY)) }
        session.assertNoRequest("followLink", 1_000)

        // A long press opens its menu, under its title; an item counts the separator before it, and a greyed-out one is off
        compose.onNode(row).performTouchInput { longClick(onLink) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Eat")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ways").assertIsDisplayed()
        compose.onNode(hasText("Drop") and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(hasText("Eat") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertFollowed(item = 2)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Eat")).fetchSemanticsNodes().isEmpty() }
        // TalkBack's action opens it too
        runAction(row, openMenu)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Look")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Look") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertFollowed(item = 0)
    }

    @Test
    fun aWindowsNewTextAsksAboutItsLinksAgainAndAnOpenMenuStaysWhileItsRowHasTheLink() {
        showChat()
        val goNorth = "go: north" to listOf(listOf(0, 4, -1, -1, 0, 0), listOf(4, 5, -1, -1, RUN_UNDERLINE, 7))
        windowRows("chat", goNorth)
        waitUntilDisplayed("go: north")
        session.send(linkMessage(7, "chat", "walk north", session.receive("getLink").get("id")))
        val row = hasText("go: north") and inChat
        val openMenu = string(R.string.console_link_menu, "walk north")
        compose.waitUntil(WAIT_MS) { actions(row).any { it.label == openMenu } }
        runAction(row, openMenu)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Look")).fetchSemanticsNodes().isNotEmpty() }

        // A new row below the link's (which keeps its place) asks about it again, and the open menu shows the new answer
        windowRows("chat", goNorth, "new line" to emptyList())
        waitUntilDisplayed("new line")
        val again = session.receive("getLink")
        assertEquals(7, again.getInt("link"))
        assertEquals("chat", again.getString("window"))
        session.send(linkMessage(7, "chat", "walk north", again.get("id"), menuTitle = "Other ways"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Other ways")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Look") and hasClickAction()).assertIsDisplayed()
        // and a row without it closes the menu
        windowRows("chat", "gone" to emptyList(), "new line" to emptyList())
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Look")).fetchSemanticsNodes().isEmpty() }
    }

    private fun showChat() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        session.windows(listOf(FakeEngine.window("chat", x = 0, y = 300, w = 800, h = 300, columns = 40)), full = true)
    }

    // In the chat window, and left out of what TalkBack reads
    private val inChat get() = hasAnyAncestor(windowNamed("chat"))
    private val isHidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)

    /** The rows of the console [window], each with its runs (docs/protocol.md, "Runs"). */
    private fun windowRows(window: String, vararg rows: Pair<String, List<List<Int>>>) = session.send(
        JSONObject().put("t", "windowText").put("window", window).put("lineCount", rows.size).put("first", 0).put(
            "lines",
            JSONArray(rows.map { (text, runs) -> JSONObject().put("text", text).put("runs", JSONArray(runs.map(::JSONArray))).put("prompt", false).put("wrapped", false) }),
        ),
    )

    /** The answer to a getLink of [id]: a menu titled [menuTitle] of Look, a separator, Eat and a greyed-out Drop. */
    private fun linkMessage(link: Int, window: String, tooltip: String, id: Any, menuTitle: String = "Ways") = JSONObject().put("t", "link").put("link", link)
        .put("window", window).put("tooltip", tooltip).put("menu", true).put("menuTitle", menuTitle)
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

    private fun assertFollowed(item: Int?) {
        val followed = session.receive("followLink")
        assertEquals(7, followed.getInt("link"))
        assertEquals("chat", followed.getString("window"))
        assertEquals(item, if (followed.has("item")) followed.getInt("item") else null)
    }

    private fun actions(node: SemanticsMatcher) = compose.onNode(node).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    // TalkBack's action [label] of [node]: found from the test thread, as a semantics lookup waits for idle, and run on the main one
    private fun runAction(node: SemanticsMatcher, label: String) {
        val action = actions(node).single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    // Where the character [index] of the line [node] is drawn, in the line's own pixels
    private fun characterCentre(node: SemanticsMatcher, index: Int): Offset {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(node).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().getBoundingBox(index).center
    }

    private fun announced() = compose.runOnIdle { Announcer.current?.serial ?: 0L }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun editableText(text: String) = SemanticsMatcher("EditableText = '$text'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun displayed(text: String) = try {
        compose.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun waitUntilDisplayed(text: String) = compose.waitUntil(WAIT_MS) { displayed(text) }

    private fun assertNear(what: String, expected: Float, actual: Float, within: Float = PIXELS) = assertTrue("$what: $actual, not $expected", abs(expected - actual) <= within)

    // The main console's character: Android's monospace font at the console's text size, 0.6 em wide, and Material's
    // 0.5 sp between
    private fun appCharWidth(): Float {
        val textSizeSp = compose.runOnIdle { Settings.textSize.value }
        return with(Density(compose.activity)) { (textSizeSp * 0.6f + 0.5f).sp.toPx() }
    }

    // What the app makes of MUDLET_COLUMNS in MUDLET_TEXT_WIDTH: the middle of (800/101, 800/100]
    private fun mudletCharWidth(): Float = (MUDLET_TEXT_WIDTH.toFloat() / (MUDLET_COLUMNS + 1) + MUDLET_TEXT_WIDTH.toFloat() / MUDLET_COLUMNS) / 2

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-windows"
        const val MUDLET_COLUMNS = 100
        const val MUDLET_TEXT_WIDTH = 800

        // Rounding to whole pixels, here and in Mudlet's sizes
        const val PIXELS = 2f

        // The app's character width is worked out here from the font's proportions, not measured
        const val TOLERANCE = 0.1f
        const val WAIT_MS = 10_000L

        // Well past the Announcer's wait for more lines
        const val QUIET_MS = 1_000L
        const val TIMEOUT_MS = 240_000L
    }
}
