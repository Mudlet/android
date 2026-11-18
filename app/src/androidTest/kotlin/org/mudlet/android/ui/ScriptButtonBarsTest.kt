package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
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
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.bar
import org.mudlet.android.ui.EngineMessages.button
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The button bars and commands a profile's scripts and packages add (docs/architecture.md, "Button bars and commands"),
 * against a fake engine: a bar's buttons as 48 dp buttons read after the script windows, whose tap sends `pressButton`,
 * a push-down button as a switch, a folder's menu, where bars go beside or above the console, an unnamed icon button,
 * the style sheets of a bar and its buttons drawn (docs/phase-f.md, "Button bars and commands") with a 48 dp button,
 * a switch's state and a down button's tick kept whatever they ask, a button no bigger than the game area whatever
 * its sheet's lengths or its name ask, a name too long for it giving way to its tick and arrow, a horizontal bar's
 * columns each as wide as its widest button and a vertical bar's places all as wide as the bar's widest, and the
 * Extensions menu of commands, whose tap sends `activateCommand`.
 *
 * A menu is a popup, a root of its own, so the checks on the whole screen run only while none is open.
 */
@RunWith(AndroidJUnit4::class)
class ScriptButtonBarsTest {
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
        // A new engine instance for each test, so no bar or command of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("the main console").assertIsDisplayed()
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun aTopBarsButtonIsAButtonAboveTheConsoleReadAfterTheWindowsWhoseTapTheEngineGets() {
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "North"), button(11, "South"))))
        val north = hasText("North") and hasClickAction()
        waitFor(north)
        compose.onNode(north).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val group = hasContentDescription(string(R.string.bar_description, "Moves"))
        compose.onNode(group).assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        // Read after the windows: the index is on the bars' row, beside the console, the input line and the windows,
        // not inside a scroll, which is a traversal group of its own where the index would order only the bars
        val afterWindows = SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f)
        compose.onNode(afterWindows and hasAnyDescendant(group)).assert(!hasAnyAncestor(hasScrollAction()))

        val button = bounds(north)
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("width ${button.width} < $min", button.width >= min - 1)
        assertTrue("height ${button.height} < $min", button.height >= min - 1)
        assertTrue("the bar at ${button.bottom} is not above the console at ${consoleArea().top}", button.bottom <= consoleArea().top + PIXELS)
        // One column of one button each: South is to the right of North
        assertTrue(bounds(hasText("South") and hasClickAction()).left >= button.right - PIXELS)

        activate(north)
        assertEquals(10, session.receive("pressButton").getInt("button"))
    }

    @Test
    fun aPushDownButtonIsASwitchTheEngineTurnsOn() {
        session.buttonBars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true))))
        val sneak = hasText("Sneak") and hasClickAction()
        waitFor(sneak)
        compose.onNode(sneak).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(sneak).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        activate(sneak)
        assertEquals(20, session.receive("pressButton").getInt("button"))
        // It is down once the engine says so
        session.buttonBars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true, checked = true))))
        waitFor(sneak and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aFoldersMenuRunsTheEntryPickedAndThenTheFolder() {
        val ice = button(33, "Ice", pushDown = true, checked = true)
        session.buttonBars(bar(1, "Spells", "top", listOf(button(30, "Cast", children = listOf(button(31, "Fireball"), button(32, "Shields", children = listOf(ice)))))))
        val cast = hasText("Cast") and hasClickAction()
        waitFor(cast)
        compose.onNode(cast).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals(string(R.string.bar_open_menu), compose.onNode(cast).fetchSemanticsNode().config[SemanticsActions.OnClick].label)

        activate(cast)
        waitFor(hasText("Fireball") and hasClickAction())
        // A folder in the menu opens its own in place, with a way back
        pick(hasText("Shields"))
        waitFor(hasText(string(R.string.menu_back)) and hasClickAction())
        val iceEntry = hasText("Ice") and hasClickAction()
        compose.onNode(iceEntry).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        pick(hasText("Ice"))
        assertEquals(33, session.receive("pressButton").getInt("button"))
        assertEquals(30, session.receive("pressButton").getInt("button"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(iceEntry).fetchSemanticsNodes().isEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aFloatingBarsFolderListsItsButtonsAndRunsWhenItsMenuIsClosed() {
        // As upstream gives a floating bar's folder: one entry, the folder itself, holding its buttons
        val inner = button(40, "Tools", children = listOf(button(41, "Map"), button(42, "Log")))
        session.buttonBars(bar(2, "Float", "floating", listOf(button(40, "Tools", children = listOf(inner)))))
        val tools = hasText("Tools") and hasClickAction()
        waitFor(tools)

        activate(tools)
        waitFor(hasText("Map") and hasClickAction())
        assertEquals(1, compose.onAllNodes(hasText("Tools")).fetchSemanticsNodes().size)
        compose.onNode(hasText("Log") and hasClickAction()).assertIsDisplayed()
        // Closing the menu without a pick still runs the folder's script, as the GUI's does
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Map")).fetchSemanticsNodes().isEmpty() }
        assertEquals(40, session.receive("pressButton").getInt("button"))
        compose.onNodeWithText("the main console").assertIsDisplayed()
    }

    @Test
    fun aLeftBarIsAStripBesideTheConsoleWhenItFitsAndARowAboveItWhenNot() {
        session.buttonBars(bar(3, "Side", "left", listOf(button(50, "Look"), button(51, "Inv")), vertical = true))
        val look = hasText("Look") and hasClickAction()
        val inv = hasText("Inv") and hasClickAction()
        waitFor(look)
        assertTrue("the strip at ${bounds(look).right} is not left of the console at ${consoleArea().left}", bounds(look).right <= consoleArea().left + PIXELS)
        assertTrue("Inv is not below Look", bounds(inv).top >= bounds(look).bottom - PIXELS)
        compose.onRoot().tryPerformAccessibilityChecks()

        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        compose.waitUntil(WAIT_MS) { bounds(look).bottom <= consoleArea().top + PIXELS }
        // Lying along its row
        assertTrue("Inv is not right of Look", bounds(inv).left >= bounds(look).right - PIXELS)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aTallBarTakesAQuarterOfTheAreaAtMostAndScrolls() {
        // One column of twelve buttons, far taller than a quarter of the area
        session.buttonBars(bar(4, "Grid", "top", (1..12).map { button(70 + it, "B$it") }, columns = 12))
        val group = hasContentDescription(string(R.string.bar_description, "Grid"))
        waitFor(group)
        val top = bounds(group).top
        val console = consoleArea()
        assertTrue("the row is ${console.top - top} of ${console.bottom - top}", console.top - top <= (console.bottom - top) / 4 + PIXELS)

        val last = hasText("B12") and hasClickAction()
        compose.onNode(last).performScrollTo().assertIsDisplayed()
        activate(last)
        assertEquals(82, session.receive("pressButton").getInt("button"))
    }

    @Test
    fun anUnnamedIconButtonIsNamedByItsPlaceAndAsksForItsIconAtIconSize() {
        session.buttonBars(bar(1, "Moves", "top", listOf(button(60, "", icon = "/icons/look.png"))))
        val look = hasContentDescription(string(R.string.bar_button_unnamed, 1, "Moves")) and hasClickAction()
        waitFor(look)
        compose.waitForIdle()
        val request = session.receive("getImage")
        assertEquals("/icons/look.png", request.getString("path"))
        val px = with(compose.density) { 24.dp.roundToPx() }
        assertEquals(px, request.getInt("w"))
        assertEquals(px, request.getInt("h"))

        session.image("/icons/look.png", png(px, px), px, px, request.get("id"))
        compose.waitUntil(WAIT_MS) { EngineClient.front.windows.images[ScriptWindowsClient.ImageRequest("/icons/look.png", px, px)] != null }
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(look)
        assertEquals(60, session.receive("pressButton").getInt("button"))
    }

    @Test
    fun aBarsStyleSheetColoursItsButtonsAndAButtonsOwnIsOverIt() {
        // Its `*` rule is the bar's, and its buttons' below their QPushButton rule, which ranks over it
        val sheet = "* { background-color: #202020; } QPushButton { background-color: #000080; color: #ffff00; }"
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "North"), button(11, "South", styleSheet = "background-color: #800000")), styleSheet = sheet))
        val north = hasText("North") and hasClickAction()
        val south = hasText("South") and hasClickAction()
        waitFor(north)
        waitFor(south)

        assertEdgeColour(NAVY, north)
        assertTextIn(YELLOW, north)
        // Its own sheet's background over the bar's, the bar's colour where its own sets none
        assertEdgeColour(MAROON, south)
        assertTextIn(YELLOW, south)
        // In the button's margin, the bar's own
        val bar = compose.onNode(hasContentDescription(string(R.string.bar_description, "Moves"))).captureToImage().toPixelMap()
        assertColour(DARK_GREY, bar[1, 1])
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aDownPushDownButtonDrawsItsCheckedStyleAndStaysOn() {
        val sheet = "QPushButton:checked { background-color: #006400; } QPushButton { background-color: #000080; color: #ffffff; }"
        session.buttonBars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true, styleSheet = sheet))))
        val sneak = hasText("Sneak") and hasClickAction()
        waitFor(sneak)
        compose.onNode(sneak).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertEdgeColour(NAVY, sneak)

        session.buttonBars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true, checked = true, styleSheet = sheet))))
        // Whatever its colours, it is said to be on
        waitFor(sneak and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertEdgeColour(GREEN, sneak)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aStyleSheetThatAsksForLessStillLeavesA48DpButton() {
        val sheet = "padding: 0px; margin: 0px; border: none; max-height: 16px; background-color: #000080; color: #ffffff"
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "N", styleSheet = sheet))))
        val n = hasText("N") and hasClickAction()
        waitFor(n)
        val button = bounds(n)
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("width ${button.width} < $min", button.width >= min - 1)
        assertTrue("height ${button.height} < $min", button.height >= min - 1)
        // Filled to its corners, as a sheet that sets a background and no radius has it, all of its 48 dp
        compose.waitForIdle()
        val image = compose.onNode(n).captureToImage().toPixelMap()
        assertColour(NAVY, image[1, 1])
        assertColour(NAVY, image[image.width - 2, image.height - 2])
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aSheetsOwnFrameShowsTheBarThroughAndItsTextReadsOnIt() {
        // The button's own sheet takes back the background the bar's QWidget rule gives it, and draws a frame of its own
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "North", styleSheet = "background: none; border: none")), styleSheet = "QWidget { background-color: #202020; }"))
        val north = hasText("North") and hasClickAction()
        waitFor(north)
        // As on the desktop, no button's colour under it: the bar's dark grey, and white text on that
        assertEdgeColour(DARK_GREY, north)
        assertTextIn(WHITE, north)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aDownButtonWithNothingButItsTickStillShowsIt() {
        val sheet = "background-color: #000080; color: #ffffff"
        session.buttonBars(bar(1, "Modes", "top", listOf(button(20, "", pushDown = true, checked = true, styleSheet = sheet))))
        val sneak = hasContentDescription(string(R.string.bar_button_unnamed, 1, "Modes")) and hasClickAction()
        waitFor(sneak and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The tick is all there is to draw in the sheet's colour
        assertTextIn(WHITE, sneak)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aSheetsPaddingBorderAndMarginAreTheButtonsRoom() {
        val sheet = "padding: 0px 40px; border: 10px solid #ffffff; margin: 20px; background-color: #000080; color: #ffffff"
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "N", styleSheet = sheet), button(11, "S"))))
        val n = hasText("N") and hasClickAction()
        val south = hasText("S") and hasClickAction()
        waitFor(n)
        waitFor(south)
        // Its padding inside its border, which is drawn at its edge
        val padded = with(compose.density) { 100.dp.toPx() }
        assertTrue("width ${bounds(n).width} < $padded", bounds(n).width >= padded - PIXELS)
        assertEdgeColour(WHITE, n)
        // Its margin, then the next button's own of 2 dp
        val gap = with(compose.density) { 22.dp.toPx() }
        assertTrue("gap ${bounds(south).left - bounds(n).right} < $gap", bounds(south).left - bounds(n).right >= gap - PIXELS)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aButtonIsPressedWhileAFingerIsOnIt() {
        val sheet = "QPushButton:pressed { background-color: #006400; } QPushButton { background-color: #000080; color: #ffffff; }"
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "North", styleSheet = sheet))))
        val north = hasText("North") and hasClickAction()
        waitFor(north)
        assertEdgeColour(NAVY, north)

        compose.onNode(north).performTouchInput { down(center) }
        // After the wait that tells a press from a scroll of the row; the app's ripple tints it, so not the green
        // itself
        compose.waitUntil(WAIT_MS) { edgeColour(north).let { it.green > it.blue } }
        compose.onNode(north).performTouchInput { up() }
        assertEquals(10, session.receive("pressButton").getInt("button"))
        compose.waitUntil(WAIT_MS) { edgeColour(north).let { it.blue > it.green } }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aSheetsLargeFontMakesItsButtonTallEnoughForItsLetters() {
        val sheet = "font-size: 60px; background-color: #000080; color: #ffffff"
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "N", styleSheet = sheet))))
        val n = hasText("N") and hasClickAction()
        waitFor(n)
        // The line is as high as the sheet's font, not the theme's 24 sp, at which the button's 48 dp would cut the
        // letters off
        val font = with(compose.density) { 60.sp.toPx() }
        val height = compose.onNode(n).fetchSemanticsNode().size.height
        assertTrue("height $height < $font", height >= font)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun lengthsNoScreenCouldShowLeaveABarOfButtonsToPress() {
        // Its buttons' margin and padding at their longest are thousands of dp across, and seventeen such places in a
        // row of a vertical bar are wider than Compose can measure
        val sheet = "margin: 0px 99999px; padding: 0px 99999px; background-color: #000080; color: #ffffff"
        session.buttonBars(bar(1, "Wide", "top", (1..17).map { button(100 + it, "W$it", styleSheet = sheet) }, vertical = true, columns = 17))
        val first = hasText("W1") and hasClickAction()
        waitFor(first)
        compose.onNodeWithText("the main console").assertIsDisplayed()
        // Cut down to the game area, of which the button keeps its 48 dp
        val size = compose.onNode(first).fetchSemanticsNode().size
        val min = with(compose.density) { 48.dp.toPx() }
        val screen = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("width ${size.width} < $min", size.width >= min - 1)
        assertTrue("height ${size.height} < $min", size.height >= min - 1)
        assertTrue("width ${size.width} > $screen", size.width <= screen)
        activate(first)
        assertEquals(101, session.receive("pressButton").getInt("button"))
    }

    @Test
    fun aButtonIsNoWiderThanTheGameAreaHoweverLongItsName() {
        val name = (1..60).joinToString(" ") { "North" }
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, name))))
        // TalkBack has all of the name; on the screen it is cut to the area's width
        val north = hasText(name) and hasClickAction()
        waitFor(north)
        val width = compose.onNode(north).fetchSemanticsNode().size.width
        val screen = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("width $width > $screen", width <= screen)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aNameTooLongForItsButtonGivesWayToItsTickAndArrow() {
        val sheet = "background-color: #000080; color: #ffffff"
        val sneak = (1..60).joinToString(" ") { "Sneak" }
        val cast = (1..60).joinToString(" ") { "Cast" }
        // Each in a row of one line, above and below the console, which the row's quarter of the area does not cut
        session.buttonBars(
            bar(1, "Modes", "top", listOf(button(20, sneak, pushDown = true, checked = true, styleSheet = sheet))),
            bar(2, "Spells", "floating", listOf(button(30, cast, styleSheet = sheet, children = listOf(button(31, "Fireball"))))),
        )
        // TalkBack has all of each name
        val down = hasText(sneak) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
        val folder = hasText(cast) and hasClickAction()
        waitFor(down)
        waitFor(folder)
        // An icon's 24 dp and the 8 dp between it and the name
        val room = with(compose.density) { 32.dp.toPx() }

        // The tick before the name, on the screen, and the name cut short with an ellipsis
        assertOnScreen(down)
        val tick = name(sneak).positionInRoot.x - left(down)
        assertTrue("the name starts $tick px into its button, < $room", tick >= room - PIXELS)
        assertDrawnIn(WHITE, down, EDGE_PX until tick.toInt())
        assertTrue("'$sneak' is not cut short", nameLayout(sneak).isLineEllipsized(0))

        // The arrow after it
        assertOnScreen(folder)
        val folderWidth = width(folder)
        val arrow = left(folder) + folderWidth - name(cast).let { it.positionInRoot.x + it.size.width }
        assertTrue("the name ends $arrow px before its button's end, < $room", arrow >= room - PIXELS)
        assertDrawnIn(WHITE, folder, (folderWidth - arrow).toInt() until folderWidth.toInt() - EDGE_PX)
        assertTrue("'$cast' is not cut short", nameLayout(cast).isLineEllipsized(0))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aHorizontalBarsColumnIsAsWideAsItsWidestButton() {
        // Two buttons to a column: N above Go north west, then Go south above S
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "N"), button(11, "Go north west"), button(12, "Go south"), button(13, "S")), columns = 2))
        val n = hasText("N") and hasClickAction()
        val northWest = hasText("Go north west") and hasClickAction()
        val south = hasText("Go south") and hasClickAction()
        val s = hasText("S") and hasClickAction()
        waitFor(s)
        assertEquals("N is not above Go north west", left(northWest), left(n), PIXELS)
        assertEquals("S is not below Go south", left(south), left(s), PIXELS)
        // A shorter button fills its column, which is as wide as the widest in it asks
        assertSameWidth(northWest, n)
        assertSameWidth(south, s)
        assertNameWhole("Go north west")
        assertNameWhole("Go south")
        // Each column its own widest's: the second, to the right of the first, is narrower
        assertTrue("Go south is not right of Go north west", left(south) >= left(northWest) + width(northWest) - PIXELS)
        assertTrue("Go south's column ${width(south)} is not narrower than ${width(northWest)}", width(south) < width(northWest) - PIXELS)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aVerticalBarsPlacesAreAllAsWideAsItsWidestButton() {
        // Two buttons to a row: N beside Go north west, then S beside E
        session.buttonBars(bar(1, "Moves", "top", listOf(button(10, "N"), button(11, "Go north west"), button(12, "S"), button(13, "E")), vertical = true, columns = 2))
        val n = hasText("N") and hasClickAction()
        val northWest = hasText("Go north west") and hasClickAction()
        val s = hasText("S") and hasClickAction()
        val e = hasText("E") and hasClickAction()
        waitFor(e)
        // Each place of the grid, in any row or column, as wide as the widest button of the bar asks
        for (other in listOf(n, s, e)) assertSameWidth(northWest, other)
        assertNameWhole("Go north west")
        assertEquals("S is not below N", left(n), left(s), PIXELS)
        assertEquals("E is not below Go north west", left(northWest), left(e), PIXELS)
        assertTrue("Go north west is not right of N", left(northWest) >= left(n) + width(n) - PIXELS)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aFolderIsPressedWhileItsMenuIsOpen() {
        val sheet = "QPushButton:pressed { background-color: #006400; } QPushButton { background-color: #000080; color: #ffffff; }"
        session.buttonBars(bar(1, "Spells", "top", listOf(button(30, "Cast", styleSheet = sheet, children = listOf(button(31, "Fireball"))))))
        val cast = hasText("Cast") and hasClickAction()
        waitFor(cast)
        assertEdgeColour(NAVY, cast)

        activate(cast)
        waitFor(hasText("Fireball") and hasClickAction())
        assertEdgeColour(GREEN, cast)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Fireball")).fetchSemanticsNodes().isEmpty() }
        assertEquals(30, session.receive("pressButton").getInt("button"))
        assertEdgeColour(NAVY, cast)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theExtensionsMenuListsTheCommandsByMenuPathAndTheEngineGetsTheOnePicked() {
        val extensions = hasContentDescription(string(R.string.commands_menu)) and hasClickAction()
        // No command, no button
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(extensions).fetchSemanticsNodes().isEmpty())

        commands(
            command(1, "Speak", menuPath = "Tools", tooltip = "Read lines aloud", checkable = true, checked = true),
            command(2, "Mapper", onMenu = false, onToolbar = true),
            command(3, "Broken", enabled = false),
            command(4, "Hidden", onMenu = false),
        )
        waitFor(extensions)
        activate(extensions)
        waitFor(hasText("Mapper") and hasClickAction())
        compose.onNode(hasText("Mapper") and hasClickAction()).assertIsEnabled()
        compose.onNode(hasText("Broken")).assertIsNotEnabled()
        assertTrue(compose.onAllNodes(hasText("Hidden")).fetchSemanticsNodes().isEmpty())

        pick(hasText("Tools"))
        val speak = hasText("Speak") and hasText("Read lines aloud") and hasClickAction()
        waitFor(speak)
        compose.onNode(speak).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        pick(speak)
        assertEquals(1, session.receive("activateCommand").getInt("command"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(speak).fetchSemanticsNodes().isEmpty() }

        // A command that wants attention is said on the button
        commands(command(2, "Mapper", onMenu = false, onToolbar = true, pulse = true))
        waitFor(hasContentDescription(string(R.string.commands_menu_attention)) and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    /** The colour [node] is drawn in at its start edge, clear of its content. */
    private fun edgeColour(node: SemanticsMatcher): Color {
        compose.waitForIdle()
        val image = compose.onNode(node).captureToImage().toPixelMap()
        return image[EDGE_PX, image.height / 2]
    }

    private fun assertEdgeColour(expected: Color, node: SemanticsMatcher) = assertColour(expected, edgeColour(node))

    private fun assertColour(expected: Color, drawn: Color) = assertEquals("ARGB %08x, then %08x".format(expected.toArgb(), drawn.toArgb()), expected.toArgb(), drawn.toArgb())

    /** That some of [node] is drawn in [text]'s colour, as the middle of its letters' strokes are. */
    private fun assertTextIn(text: Color, node: SemanticsMatcher) = assertDrawnIn(text, node, 0 until Int.MAX_VALUE)

    /** That some of [node] in its [columns] of pixels from its left is drawn in [colour], as the middle of a stroke is. */
    private fun assertDrawnIn(colour: Color, node: SemanticsMatcher, columns: IntRange) {
        compose.waitForIdle()
        val image = compose.onNode(node).captureToImage().toPixelMap()
        val drawn = (columns.first.coerceAtLeast(0)..columns.last.coerceAtMost(image.width - 1)).any { x ->
            (0 until image.height).any { y ->
                val c = image[x, y]
                abs(c.red - colour.red) < NEAR && abs(c.green - colour.green) < NEAR && abs(c.blue - colour.blue) < NEAR
            }
        }
        assertTrue("no pixel of %08x in columns $columns".format(colour.toArgb()), drawn)
    }

    // Where a button is and how wide, as laid out: a row of a bar that the area's quarter cuts short still has them
    private fun left(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.x

    private fun width(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().size.width.toFloat()

    private fun assertSameWidth(widest: SemanticsMatcher, other: SemanticsMatcher) = assertEquals("${other.description}'s width", width(widest), width(other), PIXELS)

    /** That all of [matcher]'s node is within the screen's width. */
    private fun assertOnScreen(matcher: SemanticsMatcher) {
        val screen = compose.onRoot().fetchSemanticsNode().size.width
        val left = left(matcher)
        val right = left + width(matcher)
        assertTrue("${matcher.description} from $left to $right is not within the screen's $screen", left >= -PIXELS && right <= screen + PIXELS)
    }

    /** The name [text] of a button, on its own: the button's node has it merged in. */
    private fun name(text: String): SemanticsNode = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()

    /** The name [text]'s layout, laid out again in the width it was given, which is as cut short as the one drawn. */
    private fun nameLayout(text: String): TextLayoutResult {
        val layouts = ArrayList<TextLayoutResult>()
        name(text).config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun assertNameWhole(text: String) = assertFalse("'$text' is cut short", nameLayout(text).isLineEllipsized(0))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun commands(vararg commands: JSONObject) = session.send(JSONObject().put("t", "commands").put("commands", JSONArray(commands.toList())))

    private fun command(
        id: Int,
        name: String,
        menuPath: String = "",
        tooltip: String = "",
        onMenu: Boolean = true,
        onToolbar: Boolean = false,
        enabled: Boolean = true,
        checkable: Boolean = false,
        checked: Boolean = false,
        pulse: Boolean = false,
    ) = JSONObject().put("id", id).put("package", "smoke").put("name", name).put("icon", "").put("tooltip", tooltip).put("menuPath", menuPath)
        .put("shortcut", "").put("onMenu", onMenu).put("onToolbar", onToolbar).put("enabled", enabled).put("checkable", checkable)
        .put("checked", checked).put("pinned", false).put("pulse", pulse)

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // The main console: the one list that is not in a script window
    private fun consoleArea(): Rect = bounds(hasScrollToIndexAction() and !hasAnyAncestor(hasContentDescription(string(R.string.window_description, ""), substring = true)))

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        pick(label)
    }

    /** A double tap on [entry], without the checks on the whole screen: they cannot run while a menu, a root of its own, is open. */
    private fun pick(entry: SemanticsMatcher) {
        compose.onNode(entry and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-buttons"

        // Rounding to whole pixels
        const val PIXELS = 2f

        // In from a button's start, clear of its content, which starts 12 dp in
        const val EDGE_PX = 4

        // How far from a colour a pixel of text drawn in it may be, of each channel's 1
        const val NEAR = 0.1f

        // Each far from the others and from the theme's own, and text on them reads
        val NAVY = Color(0xFF000080)
        val MAROON = Color(0xFF800000)
        val GREEN = Color(0xFF006400)
        val DARK_GREY = Color(0xFF202020)
        val YELLOW = Color(0xFFFFFF00)
        val WHITE = Color(0xFFFFFFFF)
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
