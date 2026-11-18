package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.mudlet.android.engine.EngineService
import org.mudlet.android.protocol.ConsoleZoom
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_BOLD
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.protocol.RUN_UNDERLINE
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.SCROLL_THUMB_TAG
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
            EngineClient.front.consoleZoom.reset("chat", 0)
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.blankLines.reset()
            EngineClient.sessions.forEach { it.settings.clear() }
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
    fun theBordersAreDrawnInTheColourAScriptGaveThem() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        // Borders of 50 above and below the text, which setBorderColor() coloured
        session.send(
            JSONObject().put("t", "mainArea").put("w", 800).put("h", 400).put("columns", MUDLET_COLUMNS).put("rows", 30)
                .put("textX", 0).put("textY", 50).put("textW", MUDLET_TEXT_WIDTH).put("textH", 300).put("borderColor", RUN_RGB or MAROON),
        )
        // In the middle of the band above the text
        assertDrawnIn(MAROON) {
            val text = bounds(console)
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().toPixelMap()
            image[text.center.x.toInt(), (text.top - 25 * text.width / 800).toInt().coerceIn(0, image.height - 1)]
        }
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

        // In a game that keeps the command sent, a window's line keeps its own too, selected: typing replaces it
        compose.runOnIdle { EngineClient.front.settings[Settings.clearInputAfterSend.key] = false }
        compose.onNode(field).performTextInput("hello")
        compose.onNode(field).performImeAction()
        assertEquals("tell bob hello", session.receive("sendWindowCommandLine").getString("text"))
        val kept = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.input_kept))
        compose.onNode(field).assert(editableText("tell bob hello")).assert(kept)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(14)))
        compose.onRoot().tryPerformAccessibilityChecks()
        // A script still sets the line: nothing of Mudlet's own is awaited after a send that keeps it
        session.windowCommandLine("chat", "")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(field and editableText("")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(field).performTextInput("say hi")
        compose.onNode(field).performImeAction()
        session.receive("sendWindowCommandLine")
        compose.onNode(field).performTextInput("n")
        compose.onNode(field).assert(editableText("n")).assert(!kept)
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

    @Test
    fun aTransparentMiniConsoleShowsTheLabelUnderItAndAnOpaqueOneHidesIt() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // A Geyser HUD: a label, and over it a mini console whose colour the script gave an alpha
        val back = FakeEngine.window("back", kind = "label", x = 400, y = 0, w = 400, h = 300).put("bg", RUN_RGB or MAROON)
        fun chat(alpha: Int?) = FakeEngine.window("chat", x = 400, y = 0, w = 400, h = 300, columns = 50).put("stack", 1)
            .put("bg", RUN_RGB or GREEN).apply { if (alpha != null) put("bgAlpha", alpha) }
        session.windows(listOf(back, chat(0)), full = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(windowNamed("chat")).fetchSemanticsNodes().isNotEmpty() }
        assertMiddleDrawnIn(MAROON, windowNamed("chat"))
        // Qt draws rgba() over what is under it, so half see-through mixes the two
        session.windows(listOf(chat(128)))
        assertMiddleDrawnIn(0x404000, windowNamed("chat"))
        session.windows(listOf(chat(null)))
        assertMiddleDrawnIn(GREEN, windowNamed("chat"))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aScrollBoxScrollsALabelPastItsEdgeIntoViewWithTalkBacksActionAndATapOnItReachesIt() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // A Geyser.ScrollBox with one label in it and one below its edge, each tall enough to need no larger touch
        // bounds, whose middle would move as the scroll box cuts them
        fun inBox(name: String, y: Int) = FakeEngine.window(name, kind = "label", x = 0, y = y, w = 400, h = 200, parent = "box", parentKind = "scrollBox")
        val box = FakeEngine.window("box", kind = "scrollBox", x = 0, y = 0, w = 400, h = 200)
        session.windows(listOf(box, inBox("near", 0), inBox("far", 400)), full = true)
        session.label("near", accessibleName = "Near", accessibleButton = true, styleSheet = "background-color: navy")
        session.label("far", accessibleName = "Far", accessibleButton = true, styleSheet = "background-color: teal")
        val near = hasContentDescription("Near") and hasClickAction()
        val far = hasContentDescription("Far") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(far).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(near).assertIsDisplayed()
        compose.onNode(far).assertIsNotDisplayed()

        // TalkBack's scroll forward, which is the box's scroll by: down to the end, which brings the far label in
        val scrollsDown = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(far)
        compose.onNode(scrollsDown).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitUntil(WAIT_MS) { isShown(far) && !isShown(near) }
        compose.onRoot().tryPerformAccessibilityChecks()

        // A finger on it reaches it at its middle, in its own pixels, wherever the box has scrolled it to
        compose.onNode(far).performTouchInput { click(center) }
        val events = ArrayList<JSONObject>()
        do {
            events.add(session.receive("pointer"))
        } while (events.last().getString("type") != "release")
        assertEquals(listOf("far"), events.map { it.getString("window") }.distinct())
        val press = events.single { it.getString("type") == "press" }
        assertNear("x", 200f, press.getDouble("x").toFloat())
        assertNear("y", 100f, press.getDouble("y").toFloat())
    }

    @Test
    fun aDragOnAClickableLabelInAScrollBoxScrollsTheBoxAndSendsTheLabelNothing() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // A clickable label filling a box, with another below its edge, so the box scrolls down
        fun inBox(name: String, y: Int) = FakeEngine.window(name, kind = "label", x = 0, y = y, w = 400, h = 200, parent = "box", parentKind = "scrollBox")
        val box = FakeEngine.window("box", kind = "scrollBox", x = 0, y = 0, w = 400, h = 200)
        session.windows(listOf(box, inBox("near", 0), inBox("far", 400)), full = true)
        session.label("near", accessibleName = "Near", accessibleButton = true, styleSheet = "background-color: navy")
        session.label("far", accessibleName = "Far", accessibleButton = true, styleSheet = "background-color: teal")
        val near = hasContentDescription("Near") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(near).fetchSemanticsNodes().isNotEmpty() }
        val scrollsDown = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(near)
        fun range() = compose.onNode(scrollsDown).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.waitUntil(WAIT_MS) { range().maxValue() > 0f }
        assertEquals(0f, range().value(), 0f)

        // A finger dragged up the label scrolls the box, and the label hears nothing of it: no click, not even enter
        compose.onNode(near).performTouchInput { swipeUp() }
        compose.waitUntil(WAIT_MS) { range().value() > 0f }
        session.assertNoRequest("pointer", 1000)
    }

    @Test
    fun aLabelTappedBeforeItsScrollBoxCameToScrollLeavesTheNextDragToTheBox() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // A clickable label filling a box, alone in it: the box has nothing to scroll to yet
        fun inBox(name: String, x: Int) = FakeEngine.window(name, kind = "label", x = x, y = 0, w = 400, h = 200, parent = "box", parentKind = "scrollBox")
        val box = FakeEngine.window("box", kind = "scrollBox", x = 0, y = 0, w = 400, h = 200)
        session.windows(listOf(box, inBox("near", 0)), full = true)
        session.label("near", accessibleName = "Near", accessibleButton = true, styleSheet = "background-color: navy")
        val near = hasContentDescription("Near") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(near).fetchSemanticsNodes().isNotEmpty() }
        // A tap is the label's, as out of a box; the label then waits for its next touch
        compose.onNode(near).performTouchInput { click(center) }
        val types = ArrayList<String>()
        do {
            types.add(session.receive("pointer").getString("type"))
        } while (types.last() != "release")
        assertEquals(listOf("enter", "press", "release"), types)

        // The script puts a second label beside it, past the box's edge: the box scrolls across from now on
        session.windows(listOf(box, inBox("near", 0), inBox("far", 400)), full = true)
        session.label("far", accessibleName = "Far", accessibleButton = true, styleSheet = "background-color: teal")
        val scrollsAcross = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(near)
        fun range() = compose.onNode(scrollsAcross).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.waitUntil(WAIT_MS) { range().maxValue() > 0f }

        // The next drag on the label is the box's all the same, though the label began to wait for it before the box
        // scrolled
        compose.onNode(near).performTouchInput { swipeLeft() }
        compose.waitUntil(WAIT_MS) { range().value() > 0f }
        session.assertNoRequest("pointer", 1000)
    }

    @Test
    fun aNarrowClickableLabelInAScrollBoxKeepsTheTouchBoundsTheBoxShows() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // The box's content is as narrow as the one label in it, 10 of Mudlet's pixels: far less than 48 dp
        val box = FakeEngine.window("box", kind = "scrollBox", x = 200, y = 0, w = 400, h = 200)
        val thin = FakeEngine.window("thin", kind = "label", x = 0, y = 0, w = 10, h = 200, parent = "box", parentKind = "scrollBox")
        session.windows(listOf(box, thin), full = true)
        session.label("thin", accessibleName = "Thin", accessibleButton = true, styleSheet = "background-color: navy")
        val button = hasContentDescription("Thin") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }

        // Its touch bounds reach 24 dp right of its middle, as anywhere: only the box's left edge cuts them, not the
        // content's width
        val touch = bounds(button)
        val half = with(compose.density) { 24.dp.toPx() }
        assertTrue("touch bounds ${touch.width} px wide, under 24 dp ($half px)", touch.width >= half)
    }

    private fun isShown(node: SemanticsMatcher) = try {
        compose.onNode(node).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun showChat() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        // With a size in points, so the window takes a pinch beside its links' taps
        session.windows(listOf(FakeEngine.window("chat", x = 0, y = 300, w = 800, h = 300, columns = 40, fontSize = 8.0)), full = true)
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

    @Test
    fun aPinchSetsAMiniConsolesFontSizeWhichOutlastsAScriptsAndTheConsoleMadeAgain() {
        val textSize = compose.runOnIdle { Settings.textSize.value }
        fun chat(fontSize: Int) = FakeEngine.window("chat", x = 400, y = 0, w = 400, h = 300, columns = 30, fontSize = fontSize.toDouble())

        // The engine reports chat at [fontSize], or no chat for null. Compose's test clock, and with it the effect that
        // answers a new size, moves only while the test waits on Compose
        fun reportChat(fontSize: Int?) {
            if (fontSize == null) session.windows(emptyList(), removed = listOf("chat" to "miniConsole")) else session.windows(listOf(chat(fontSize)))
            compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.state.windows.firstOrNull { it.name == "chat" }?.fontSize == fontSize?.toDouble() } }
            compose.waitForIdle()
        }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        session.windows(listOf(chat(8)), full = true)
        session.windowText("chat", listOf("hello from chat"))
        waitUntilDisplayed("hello from chat")
        val window = windowNamed("chat")
        val before = textHeight("hello from chat")

        // Fingers 120 px apart moving to 240: twice the size, drawn as they move
        compose.onNode(window).performTouchInput {
            pinch(center - Offset(60f, 0f), center - Offset(120f, 0f), center + Offset(60f, 0f), center + Offset(120f, 0f))
        }
        val size = receiveFontSize("chat")
        assertTrue("a pinch outward makes it larger: $size", size in 12..ConsoleZoom.MAX_SIZE)
        assertTrue("drawn larger at once: $before, then ${textHeight("hello from chat")}", textHeight("hello from chat") > before * 1.3f)
        compose.runOnIdle { assertEquals("the main console's text size is the player's other setting", textSize, Settings.textSize.value) }
        reportChat(size)
        session.assertNoRequest("runLua", QUIET_MS.toInt())

        // A script's size, and the console made again at Geyser's 8, are set back to the player's
        reportChat(10)
        assertEquals(size, receiveFontSize("chat"))
        reportChat(size)
        reportChat(null)
        reportChat(8)
        assertEquals(size, receiveFontSize("chat"))
        reportChat(size)

        // TalkBack's actions change it a size at a time, and it is kept for the profile, whose name ignores case
        compose.onRoot().tryPerformAccessibilityChecks()
        customAction(window, R.string.window_text_larger)
        assertEquals(size + 1, receiveFontSize("chat"))
        reportChat(size + 1)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.consoleZoom.chosen("chat") } == size + 1 }
        val prefs = compose.activity.getSharedPreferences(EngineService.PREFS, Context.MODE_PRIVATE)
        assertEquals(size + 1, ConsoleZoomClient(GAME.name.uppercase(), { prefs }, { true }).chosen("chat"))

        // A two-finger double tap gives it back the size its script last gave it, and the reset action goes
        assertTrue(hasCustomAction(window, R.string.window_text_reset))
        compose.onNode(window).performTouchInput {
            repeat(2) {
                down(0, center - Offset(40f, 0f))
                down(1, center + Offset(40f, 0f))
                advanceEventTime(50)
                up(0)
                up(1)
                advanceEventTime(100)
            }
        }
        assertEquals(8, receiveFontSize("chat"))
        reportChat(8)
        compose.waitUntil(WAIT_MS) { !hasCustomAction(window, R.string.window_text_reset) }
        assertEquals(null, ConsoleZoomClient(GAME.name, { prefs }, { true }).chosen("chat"))
        // A script's size then holds
        reportChat(12)
        session.assertNoRequest("runLua", QUIET_MS.toInt())
    }

    @Test
    fun oneFingerStillScrollsAMiniConsoleThatTakesAPinch() {
        showChat()
        session.windowText("chat", (0 until 60).map { "row $it" })
        waitUntilDisplayed("row 59")
        compose.onNode(windowNamed("chat")).performTouchInput { swipeDown() }
        compose.waitUntil(WAIT_MS) { !displayed("row 59") }
        session.assertNoRequest("runLua", QUIET_MS.toInt())
    }

    // The chat window with 60 rows, more than it shows, the last of them in view; [scrollBar] as a script's
    // enableScrollBar("chat") (true) and disableScrollBar("chat") (false) leave it, null for a console never given one
    private fun showLongChat(scrollBar: Boolean? = true) {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, MUDLET_COLUMNS, 40, 0, 0, MUDLET_TEXT_WIDTH, 600)
        session.windows(listOf(chatWithScrollBar(scrollBar)), full = true)
        sendChatRows(59)
        waitUntilDisplayed("row 59")
    }

    private fun chatWithScrollBar(scrollBar: Boolean?) = FakeEngine.window("chat", x = 0, y = 300, w = 800, h = 300, columns = 40, fontSize = 8.0).apply {
        if (scrollBar != null) put("scrollBar", scrollBar)
    }

    private val scrollThumb = hasTestTag(SCROLL_THUMB_TAG)

    private fun hasScrollThumb() = compose.onAllNodes(scrollThumb).fetchSemanticsNodes().isNotEmpty()

    // A finger takes the thumb and moves it [dy] pixels along its track
    private fun dragScrollThumb(dy: Float) = compose.onNode(scrollThumb).performTouchInput {
        down(center)
        repeat(DRAG_STEPS) { moveBy(Offset(0f, dy / DRAG_STEPS)) }
        up()
    }

    // The chat console gets the rows 0 to [last], which it takes whether it shows them or not
    private fun sendChatRows(last: Int) {
        session.windowText("chat", (0..last).map { "row $it" })
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.state.text["chat"]?.lines?.size == last + 1 } }
        compose.waitForIdle()
    }

    @Test
    fun aMiniConsoleAScriptGaveAScrollBarShowsAThumbWhereItsRowsAre() {
        showLongChat()
        compose.waitUntil(WAIT_MS) { hasScrollThumb() }
        // At the bottom of the right edge, as the newest rows are in view, and under half the window, as far fewer
        // than half the rows are
        val chat = bounds(windowNamed("chat"))
        val thumb = bounds(scrollThumb)
        assertNear("right", chat.right, thumb.right)
        assertNear("bottom", chat.bottom, thumb.bottom)
        assertTrue("not under half the window: $thumb in $chat", thumb.height < chat.height / 2)
        // TalkBack scrolls the rows and reads them as without the bar, which is nothing to it
        compose.onNode(hasScrollToIndexAction() and inChat).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        // Rows that are all in view leave nothing to scroll
        session.windowText("chat", listOf("the only row"))
        compose.waitUntil(WAIT_MS) { !hasScrollThumb() }
        sendChatRows(59)
        compose.waitUntil(WAIT_MS) { hasScrollThumb() }
    }

    @Test
    fun aMiniConsoleHasNoScrollThumbUnlessAScriptGaveItAScrollBar() {
        // Never given one: none, though there are rows to scroll
        showLongChat(scrollBar = null)
        compose.waitForIdle()
        assertFalse(hasScrollThumb())

        session.windows(listOf(chatWithScrollBar(true)))
        compose.waitUntil(WAIT_MS) { hasScrollThumb() }
        // disableScrollBar(): gone, and the rows are as they were
        session.windows(listOf(chatWithScrollBar(false)))
        compose.waitUntil(WAIT_MS) { !hasScrollThumb() }
        waitUntilDisplayed("row 59")
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun draggingAMiniConsolesScrollThumbScrollsItsRows() {
        showLongChat()
        compose.waitUntil(WAIT_MS) { hasScrollThumb() }
        val chat = bounds(windowNamed("chat"))

        // To the top of its track: the oldest rows, and the thumb at the window's top
        dragScrollThumb(-chat.height)
        compose.waitUntil(WAIT_MS) { displayed("row 0") && !displayed("row 59") }
        assertNear("top", chat.top, bounds(scrollThumb).top)

        // Scrolled back, the console stays put as rows come
        sendChatRows(60)
        assertTrue(displayed("row 0"))
        assertFalse(displayed("row 60"))
        // Scrolling is the app's own: the engine is told nothing
        session.assertNoRequest("runLua", QUIET_MS.toInt())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aMiniConsoleFollowsNewRowsAgainOnceItsScrollThumbIsLetGoAtTheBottom() {
        showLongChat()
        compose.waitUntil(WAIT_MS) { hasScrollThumb() }
        val chat = bounds(windowNamed("chat"))

        // Away from the newest rows and back to them
        dragScrollThumb(-chat.height)
        sendChatRows(60)
        dragScrollThumb(chat.height)
        waitUntilDisplayed("row 60")
        assertNear("bottom", chat.bottom, bounds(scrollThumb).bottom)
        sendChatRows(61)
        waitUntilDisplayed("row 61")

        // Held at the bottom while rows come, which are not scrolled to under the finger: let go, it follows them too
        compose.onNode(scrollThumb).performTouchInput {
            down(center)
            repeat(2) { moveBy(Offset(0f, viewConfiguration.touchSlop)) }
        }
        sendChatRows(62)
        assertFalse(displayed("row 62"))
        compose.onNode(scrollThumb).performTouchInput { up() }
        waitUntilDisplayed("row 62")
        sendChatRows(63)
        waitUntilDisplayed("row 63")
    }

    // The size the app's next setFontSize() gives the console [name]
    private fun receiveFontSize(name: String): Int {
        val code = session.receive("runLua").getString("code")
        val match = Regex("""local name, size = "([^"]*)", (\d+)""").find(code)
        assertTrue("not a setFontSize(): $code", match != null && code.contains("setFontSize(name, size)"))
        assertEquals(name, match!!.groupValues[1])
        return match.groupValues[2].toInt()
    }

    private fun textHeight(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().let { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        layouts.single().size.height.toFloat()
    }

    private fun hasCustomAction(node: SemanticsMatcher, @StringRes label: Int): Boolean = compose.onNode(node).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == string(label) }

    private fun customAction(node: SemanticsMatcher, @StringRes label: Int) {
        val action = compose.onNode(node).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == string(label) }
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

    /** That the middle of [node] comes to be drawn in [rgb], near enough for mixed colours to round either way. */
    private fun assertMiddleDrawnIn(rgb: Int, node: SemanticsMatcher) = assertDrawnIn(rgb) { middleColour(node) }

    private fun assertDrawnIn(rgb: Int, colour: () -> Color) {
        val expected = Color(0xFF000000.toInt() or rgb)
        fun near(c: Color) = abs(c.red - expected.red) < NEAR && abs(c.green - expected.green) < NEAR && abs(c.blue - expected.blue) < NEAR
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var drawn = colour()
        while (!near(drawn) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
            drawn = colour()
        }
        assertTrue("drawn in %06x, not %06x".format(drawn.toArgb() and 0xFFFFFF, rgb), near(drawn))
    }

    private fun middleColour(node: SemanticsMatcher): Color {
        compose.waitForIdle()
        val image = compose.onNode(node).captureToImage().toPixelMap()
        return image[image.width / 2, image.height / 2]
    }

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
        const val POLL_MS = 50L

        // The moves a drag of the scroll thumb is made in
        const val DRAG_STEPS = 10

        // As runs' colours: rrggbb
        const val MAROON = 0x800000
        const val GREEN = 0x008000

        // Of a colour's channel, from 0 to 1: about 8 of 255
        const val NEAR = 0.03f
        const val TIMEOUT_MS = 240_000L
    }
}
