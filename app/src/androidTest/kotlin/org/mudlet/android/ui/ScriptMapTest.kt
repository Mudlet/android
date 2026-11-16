package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.exit
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.EngineMessages.room
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.min

/**
 * The map (docs/architecture.md, "The map"), against a fake engine: the engine's frame drawn in the mapper window,
 * a tap and a double tap (not two taps apart) sent as `mapPointer` at the room nearest them or the point of the frame
 * under them, a drag and a pinch sent as `moveMap`, the zoom buttons and TalkBack's actions that do the same without a
 * gesture, the button that centres the map on the player, the switch between viewing and editing it, the room a tap
 * picks with its exits and a walk there, and on an edited map what can be done to it, TalkBack's moves between levels,
 * a refusal shown until dismissed, a frame of another size not drawn, and openMapWidget()'s docked map as a page.
 */
@RunWith(AndroidJUnit4::class)
class ScriptMapTest {
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
        // A new engine instance for each test, so no map of an earlier test is left
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
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theMapIsDrawnInTheMapperWindowAndATapSelectsTheRoomUnderIt() {
        showMap()
        val map = drawnMap()
        compose.onNode(map).assert(hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f)))
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_viewing)))
        compose.onRoot().tryPerformAccessibilityChecks()

        // The top right quarter of Mudlet's main window, scaled
        val console = bounds(hasText("the main console"))
        val drawn = bounds(map)
        assertTrue("the map's left ${drawn.left} is not in the right half from ${console.left}", drawn.left > console.left + 100)
        assertTrue("the map is ${drawn.width} by ${drawn.height}", drawn.width > 0 && drawn.height > 0)

        compose.onNode(map).performTouchInput { click(at(drawn, 300.0, 250.0)) }
        val press = session.receive("mapPointer")
        assertEquals("press", press.getString("type"))
        assertEquals(POINTER_LEFT, press.getInt("button"))
        assertPoint(300.0, 250.0, press)
    }

    @Test
    fun theMapIsAskedForAtTheScaleItIsShownAtSoItIsNotBlurred() {
        // A small docked mapper, as the starter UI's: a page, which fills the pane, many times Mudlet's pixels
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", w = SMALL_W, h = SMALL_H).put("inDock", true)), full = true)
        session.send(mapFrame(SMALL_W, SMALL_H))
        val tab = hasText(string(R.string.map_title)) and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        activate(tab)
        val map = drawnMap()
        assertScaleAsked(bounds(map), SMALL_W, SMALL_H)

        // The UI went and came back to the same engine, which went back to the window's pixels as the UI went
        session.close()
        session = fake.engine.nextSession()
        assertScaleAsked(bounds(map), SMALL_W, SMALL_H)
    }

    @Test
    fun aDoubleTapWalksToTheRoomUnderItAfterSelectingItAndTwoTapsApartSelectEach() {
        showMap()
        val map = drawnMap()
        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { doubleClick(at(drawn, 80.0, 200.0)) }
        val press = session.receive("mapPointer")
        val walk = session.receive("mapPointer")
        assertEquals(listOf("press", "doubleClick"), listOf(press.getString("type"), walk.getString("type")))
        for (event in listOf(press, walk)) {
            assertEquals(POINTER_LEFT, event.getInt("button"))
            assertPoint(80.0, 200.0, event)
        }

        // A second tap as soon, but far from the first, selects the room there instead
        val near = at(drawn, 20.0, 20.0)
        val far = at(drawn, 380.0, 280.0)
        assertTrue("the taps are ${(far - near).getDistance()} apart", (far - near).getDistance() > with(compose.density) { 100.dp.toPx() })
        compose.onNode(map).performTouchInput {
            click(near)
            advanceEventTime(100)
            click(far)
        }
        val first = session.receive("mapPointer")
        val second = session.receive("mapPointer")
        assertEquals(listOf("press", "press"), listOf(first.getString("type"), second.getString("type")))
        assertPoint(380.0, 280.0, second)
    }

    @Test
    fun theZoomButtonsZoomAboutTheMiddleAndAPinchAboutTheFingersWithinTheEnginesBounds() {
        showMap(mapView(MAP_W, MAP_H))
        val map = drawnMap()
        val zoomIn = hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()
        val zoomOut = hasContentDescription(string(R.string.map_zoom_out)) and hasClickAction()
        val min = with(compose.density) { 48.dp.toPx() }
        for (button in listOf(zoomIn, zoomOut)) {
            val touch = touchBounds(button)
            assertTrue("a zoom button is ${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)
        }

        // A quarter about the map's middle, which still follows the player
        activate(zoomIn)
        val moveIn = session.receive("moveMap")
        assertEquals("mapper", moveIn.getString("map"))
        assertEquals(BUTTON_ZOOM, moveIn.getDouble("zoom"), 1e-9)
        assertEquals(0.0, moveIn.getDouble("dx"), 1e-9)
        assertEquals(0.0, moveIn.getDouble("dy"), 1e-9)
        assertPoint(200.0, 150.0, moveIn)
        activate(zoomOut)
        val moveOut = session.receive("moveMap")
        assertEquals(1 / BUTTON_ZOOM, moveOut.getDouble("zoom"), 1e-9)
        assertEquals(moveIn.getInt("seq") + 1, moveOut.getInt("seq"))
        assertTrue("following, no button to centre it", compose.onAllNodes(followButton()).fetchSemanticsNodes().isEmpty())

        // Fingers spread across the middle zoom in about it, as far as 3 rooms across of the frame's 20, and select nothing
        val read = session.received.size
        compose.onNode(map).performTouchInput {
            val apart = Offset(width * 0.45f, 0f)
            val near = Offset(width * 0.02f, 0f)
            pinch(center - near, center - apart, center + near, center + apart, durationMillis = 400)
        }
        session.receiveLast("moveMap")
        val sent = session.received.drop(read)
        val pinch = sent.filter { it.optString("t") == "moveMap" }
        assertTrue("no move", pinch.isNotEmpty())
        val zoom = pinch.fold(1.0) { all, move -> all * move.getDouble("zoom") }
        assertTrue("zoomed by $zoom", zoom > 1.5 && zoom <= 20.0 / 3 + 1e-6)
        for (move in pinch) assertPoint(200.0, 150.0, move, PINCH_POINTS)
        assertTrue("the pinch pressed: $sent", sent.none { it.optString("t") == "mapPointer" })
    }

    @Test
    fun aDragPansTheMapUntilTheButtonCentresItOnThePlayerAgain() {
        showMap(mapView(MAP_W, MAP_H))
        val map = drawnMap()
        val scale = shownScale(bounds(map))
        val read = session.received.size
        // Slowly, and still before it is let go, so it does not glide on
        compose.onNode(map).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset((DRAG_STEP * scale).toFloat(), 0f)) }
            advanceEventTime(300)
            up()
        }
        session.receiveLast("moveMap")
        val sent = session.received.drop(read)
        val drag = sent.filter { it.optString("t") == "moveMap" }
        assertTrue("the drag pressed: $sent", sent.none { it.optString("t") == "mapPointer" })
        val panned = drag.sumOf { it.getDouble("dx") }
        // Less the touch slop, after which a touch is a drag
        assertTrue("panned by $panned of ${DRAG_STEPS * DRAG_STEP}", panned > DRAG_STEPS * DRAG_STEP / 2 && panned <= DRAG_STEPS * DRAG_STEP + POINTS)
        for (move in drag) {
            assertEquals("mapper", move.getString("map"))
            assertEquals(0.0, move.getDouble("dy"), 1e-6)
            assertEquals(1.0, move.getDouble("zoom"), 1e-9)
        }
        assertEquals((1..drag.size).map { drag[0].getInt("seq") + it - 1 }, drag.map { it.getInt("seq") })

        // Off the player: the map says so, and a button centres it on them, until the engine's frame follows them again
        val notFollowing = string(R.string.map_not_following, string(R.string.map_viewing))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(followButton()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, notFollowing))
        val min = with(compose.density) { 48.dp.toPx() }
        val touch = touchBounds(followButton())
        assertTrue("the button is ${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)
        session.send(mapFrame(MAP_W, MAP_H, view = mapView(MAP_W, MAP_H, following = false, moved = drag.last().getInt("seq"))))
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(followButton())
        assertEquals("mapper", session.receive("followPlayer").getString("map"))
        session.send(mapFrame(MAP_W, MAP_H, view = mapView(MAP_W, MAP_H, moved = drag.last().getInt("seq"))))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(followButton()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_viewing)))
    }

    @Test
    fun aTapGoesToTheNearestRoomWithinAFingersReach() {
        showMap(mapView(MAP_W, MAP_H, rooms = mapOf(1 to (200.0 to 150.0), 2 to (230.0 to 150.0))))
        val map = drawnMap()
        val drawn = bounds(map)
        val reach = with(compose.density) { 24.dp.toPx() } / shownScale(drawn)
        assertTrue("a finger reaches $reach of the frame's pixels", reach > 11)
        // Off room 2's square of 15, but within a finger of it, and far from any room, clear of the buttons at the top end
        compose.onNode(map).performTouchInput {
            click(at(drawn, 236.5, 156.5))
            advanceEventTime(1_000)
            click(at(drawn, 60.0, 60.0))
        }
        assertPoint(230.0, 150.0, session.receive("mapPointer"))
        assertPoint(60.0, 60.0, session.receive("mapPointer"))

        // Long enough after that tap that the double tap's first is not its second
        compose.onNode(map).performTouchInput {
            advanceEventTime(1_000)
            doubleClick(at(drawn, 196.0, 155.0))
        }
        val press = session.receive("mapPointer")
        val walk = session.receive("mapPointer")
        assertEquals(listOf("press", "doubleClick"), listOf(press.getString("type"), walk.getString("type")))
        for (event in listOf(press, walk)) assertPoint(200.0, 150.0, event)
    }

    @Test
    fun talkBackScrollsTheMapAndCentresItOnThePlayerAndARefusalIsShownWithIt() {
        showMap(mapView(MAP_W, MAP_H))
        val map = drawnMap()
        fun actions() = compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions]

        // Found off the main thread, which may not wait for the screen to settle, and run on it
        fun act(label: Int) {
            val action = actions().single { it.label == string(label) }
            compose.runOnIdle { action.action() }
        }
        assertTrue("no centring while it follows", actions().none { it.label == string(R.string.map_follow) })
        // A third of the narrow side, 300: to show what is left of it, the map moves right
        act(R.string.map_pan_left)
        val left = session.receive("moveMap")
        assertEquals(100.0, left.getDouble("dx"), 1e-6)
        assertEquals(0.0, left.getDouble("dy"), 1e-6)
        act(R.string.map_pan_up)
        assertEquals(100.0, session.receive("moveMap").getDouble("dy"), 1e-6)
        compose.waitUntil(WAIT_MS) { actions().any { it.label == string(R.string.map_follow) } }
        act(R.string.map_follow)
        assertEquals("mapper", session.receive("followPlayer").getString("map"))

        // The engine's refusal of a move is this map's, which then shows as the engine last drew it
        act(R.string.map_pan_right)
        val right = session.receive("moveMap")
        assertEquals(-100.0, right.getDouble("dx"), 1e-6)
        session.refuse(right, "The map drawn last shows no area to move.")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "The map drawn last shows no area to move."))).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(followButton()).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theSwitchEditsTheMapAndReadsWhatTheEngineSaysItIs() {
        showMap()
        val map = drawnMap()
        val edit = hasContentDescription(string(R.string.map_edit)) and hasClickAction()
        compose.onNode(edit).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        val touch = touchBounds(edit)
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("the switch is ${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)

        activate(edit)
        assertFalse(session.receive("setMapViewOnly").getBoolean("viewOnly"))
        // The switch follows the engine, not the tap
        session.send(mapFrame(MAP_W, MAP_H, viewOnly = false))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(edit and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_editing)))
        compose.onRoot().tryPerformAccessibilityChecks()

        // Its refusal is shown with this map, not the app's
        activate(edit)
        val back = session.receive("setMapViewOnly")
        assertTrue(back.getBoolean("viewOnly"))
        session.refuse(back, "no profile running")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "no profile running"))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRefusalIsShownWithTheMapUntilDismissed() {
        showMap()
        drawnMap()
        session.send(JSONObject().put("t", "error").put("message", "mapPointer: no room there"))
        val refusal = hasText(string(R.string.map_refused, "no room there"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(refusal).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(refusal).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(hasContentDescription(string(R.string.map_refusal_dismiss)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(refusal).fetchSemanticsNodes().isEmpty() }
        // Another request's refusal is not the map's: by the next map, it is not shown
        session.send(JSONObject().put("t", "error").put("message", "pressButton: no such button"))
        session.send(mapFrame(MAP_W, MAP_H, viewOnly = false))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_editing))).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText(string(R.string.map_refused, "no such button"))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun talkBackSelectsWalksAndZoomsAboutTheMiddleOfTheMap() {
        showMap()
        val map = drawnMap()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(map).performSemanticsAction(SemanticsActions.OnClick)
        val press = session.receive("mapPointer")
        assertEquals("press", press.getString("type"))
        assertPoint(200.0, 150.0, press)

        val actions = compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val walk = actions.single { it.label == string(R.string.map_walk_to_middle) }
        compose.runOnIdle { walk.action() }
        val doubleClick = session.receive("mapPointer")
        assertEquals("doubleClick", doubleClick.getString("type"))
        assertEquals(POINTER_LEFT, doubleClick.getInt("button"))
        assertPoint(200.0, 150.0, doubleClick)

        val zoomOut = actions.single { it.label == string(R.string.map_zoom_out) }
        compose.runOnIdle { zoomOut.action() }
        assertEquals(-360, session.receive("mapPointer").getInt("wheelY"))
    }

    @Test
    fun aFrameOfAnotherSizeIsNotDrawnAndTakesNoTouch() {
        session.windows(listOf(mapper()), full = true)
        val undrawn = hasContentDescription(string(R.string.map_not_drawn))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(undrawn).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasContentDescription(string(R.string.map_edit)) and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().tryPerformAccessibilityChecks()

        // One from before the mapper took its size
        session.send(mapFrame(MAP_W / 2, MAP_H / 2))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasContentDescription(string(R.string.map_edit)) and hasClickAction()).fetchSemanticsNodes().any {
                SemanticsProperties.Disabled !in it.config
            }
        }
        compose.onNode(undrawn).assertIsDisplayed().performTouchInput { click(center) }
        compose.onNode(hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()).assertIsNotEnabled()

        // The tap sent nothing: the first request is the zoom once the frame fits
        session.send(mapFrame(MAP_W, MAP_H))
        drawnMap()
        activate(hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction())
        assertEquals("wheel", session.receive("mapPointer").getString("type"))
    }

    @Test
    fun theDockedMapIsAPageThatOpensOverTheConsole() {
        // openMapWidget(): a dock of the main window's size
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", w = 800, h = 600).put("inDock", true)), full = true)
        session.send(mapFrame(800, 600))
        val tab = hasText(string(R.string.map_title)) and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a page shows only when its tab is chosen", compose.onAllNodes(hasContentDescription(string(R.string.map_description))).fetchSemanticsNodes().isEmpty())

        activate(tab)
        val map = drawnMap()
        compose.onNode(map).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(map).performSemanticsAction(SemanticsActions.OnClick)
        assertPoint(400.0, 300.0, session.receive("mapPointer"))
    }

    @Test
    fun aTappedRoomIsShownWithHowFarItIsItsExitsAndAWalkThere() {
        showMap(mapView(MAP_W, MAP_H, rooms = mapOf(1 to (200.0 to 150.0), 2 to (230.0 to 150.0))))
        val map = drawnMap()
        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { click(at(drawn, 230.0, 150.0)) }
        assertPoint(230.0, 150.0, session.receive("mapPointer"))
        val ask = session.receive("roomInfo")
        assertEquals(2, ask.getInt("room"))
        session.send(room(ask, 2, "Kitchen", steps = 1, exits = listOf(exit("w", 1, "Great Hall"), exit("climb rope", 6, "Loft", locked = true))))
        val kitchen = hasText("Kitchen")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(kitchen).fetchSemanticsNodes().isNotEmpty() }
        // Its name, area, number and distance read as one, as it comes
        compose.onNode(kitchen).assertIsDisplayed()
            .assert(hasText(string(R.string.room_place, "Castle", 2)))
            .assert(hasText(compose.activity.resources.getQuantityString(R.plurals.room_steps, 1, 1)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        val locked = hasText(string(R.string.room_exit_locked, "climb rope", "Loft")) and hasClickAction()
        assertTrue("a special exit is named by its command, and marked locked", compose.onAllNodes(locked).fetchSemanticsNodes().isNotEmpty())

        val walk = hasText(string(R.string.room_walk)) and hasClickAction()
        compose.onNode(walk).performScrollTo()
        activate(walk)
        assertEquals(2, session.receive("walkToRoom").getInt("room"))

        // An exit shows the room it leads to, which the player is in: no walk there
        val west = hasText(string(R.string.room_exit, string(R.string.exit_west), "Great Hall")) and hasClickAction()
        compose.onNode(west).performScrollTo()
        activate(west)
        val next = session.receive("roomInfo")
        assertEquals(1, next.getInt("room"))
        session.send(room(next, 1, "Great Hall", steps = 0))
        val here = hasText(string(R.string.room_here))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(here).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(walk).assertIsNotEnabled()

        // Once the player is in another room, it is asked for again, as its steps changed; an answer to an earlier ask,
        // which the UI reads before that frame, is not shown over it
        session.send(room(ask, 2, "Kitchen", steps = 1))
        session.send(mapFrame(MAP_W, MAP_H, view = mapView(MAP_W, MAP_H, rooms = mapOf(1 to (200.0 to 150.0), 2 to (230.0 to 150.0)), player = 2)))
        assertEquals(1, session.receive("roomInfo").getInt("room"))
        compose.onNode(here).assertIsDisplayed()

        activate(hasContentDescription(string(R.string.room_close)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(here).fetchSemanticsNodes().isEmpty() }
        // A tap on no room shows none
        compose.onNode(map).performTouchInput { click(at(drawn, 60.0, 60.0)) }
        session.receive("mapPointer")
        session.assertNoRequest("roomInfo", QUIET_MS)
    }

    @Test
    fun talkBackShowsTheLevelsAboveAndBelowAndARefusedRoomIsSaidWithTheMap() {
        showMap(mapView(MAP_W, MAP_H, level = 0, levels = listOf(-1, 0, 3)))
        val map = drawnMap()
        fun act(label: Int) {
            val action = compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == string(label) }
            compose.runOnIdle { action.action() }
        }
        act(R.string.map_level_up)
        val up = session.receive("showMapLevel")
        assertEquals("mapper" to 3, up.getString("map") to up.getInt("level"))
        act(R.string.map_level_down)
        assertEquals(-1, session.receive("showMapLevel").getInt("level"))
        // On the top level, none above
        session.send(mapFrame(MAP_W, MAP_H, view = mapView(MAP_W, MAP_H, following = false, level = 3, levels = listOf(-1, 0, 3))))
        compose.waitUntil(WAIT_MS) {
            compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions].none { it.label == string(R.string.map_level_up) }
        }

        // TalkBack's select is a tap on the room in the middle, which is shown; the engine's refusal of it is this map's
        compose.onNode(map).performSemanticsAction(SemanticsActions.OnClick)
        val ask = session.receive("roomInfo")
        session.refuse(ask, "There is no room 1 in the map.")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "There is no room 1 in the map."))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anEditedMapsRoomHasItsDetailsAndExitsEditedAndAnExitLedToARoomTappedNext() {
        val rooms = mapOf(1 to (200.0 to 150.0), 2 to (230.0 to 150.0))
        session.windows(listOf(mapper()), full = true)
        session.send(mapFrame(MAP_W, MAP_H, viewOnly = false, view = mapView(MAP_W, MAP_H, rooms = rooms)))
        val map = drawnMap()
        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { click(at(drawn, 230.0, 150.0)) }
        val ask = session.receive("roomInfo")
        session.send(room(ask, 2, "Kitchen", steps = 1, exits = listOf(exit("w", 1, "Great Hall"))))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Kitchen")).fetchSemanticsNodes().isNotEmpty() }

        // Its details: what changed goes as one editRoom, the colour picked from the map's environments
        tapInCard(R.string.room_edit_properties)
        val envs = session.receive("mapEnvs")
        session.send(EngineMessages.envs(envs, 257 to "#ff0000", 200 to "#0a141e"))
        val swatch = hasContentDescription(string(R.string.room_edit_env_item, 200))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(swatch).fetchSemanticsNodes().isNotEmpty() }
        // The room's own environment, not among the map's, leads the list, picked
        compose.onNode(hasContentDescription(string(R.string.room_edit_env_item, -1))).assertIsSelected()
        activateInDialog(swatch)
        field(R.string.room_edit_name).performTextReplacement("Great Kitchen")
        field(R.string.room_edit_weight).performTextReplacement("3")
        activateInDialog(hasText(string(R.string.room_edit_locked)))
        activateInDialog(hasText(string(R.string.room_edit_save)))
        val edit = session.receive("editRoom")
        assertEquals(
            mapOf("room" to 2, "name" to "Great Kitchen", "env" to 200, "weight" to 3, "locked" to true),
            edit.keys().asSequence().filter { it !in setOf("t", "id") }.associateWith { edit.get(it) },
        )
        session.send(room(edit, 2, "Great Kitchen", steps = 1, exits = listOf(exit("w", 1, "Great Hall")), env = 200, weight = 3, locked = true))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Great Kitchen")).fetchSemanticsNodes().isNotEmpty() }

        // Its exits: one led to a room by number both ways, which closes the dialog for the room shown anew, then locked
        tapInCard(R.string.room_edit_exits)
        activateInDialog(hasText(string(R.string.exit_north)))
        field(R.string.room_edit_exit_to).performTextReplacement("5")
        activateInDialog(hasText(string(R.string.room_edit_exit_set)))
        val led = session.receive("setRoomExit")
        assertEquals(listOf(2, "n", 5, true), listOf(led.getInt("room"), led.getString("direction"), led.getInt("to"), led.getBoolean("bothWays")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        session.send(room(led, 2, "Great Kitchen", steps = 1, exits = listOf(exit("n", 5, "Tower"), exit("w", 1, "Great Hall"))))
        tapInCard(R.string.room_edit_exits)
        val tower = hasText(string(R.string.room_exit, string(R.string.exit_north), "Tower"))
        activateInDialog(hasText(string(R.string.exit_north)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tower and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        activateInDialog(hasText(string(R.string.room_edit_exit_lock)))
        val lock = session.receive("lockRoomExit")
        assertEquals(listOf(2, "n", true), listOf(lock.getInt("room"), lock.getString("direction"), lock.getBoolean("locked")))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

        // Or to the room tapped next on the map, which picks no room and presses nothing
        tapInCard(R.string.room_edit_exits)
        activateInDialog(hasText(string(R.string.exit_east)))
        activateInDialog(hasText(string(R.string.room_edit_exit_pick)))
        val prompt = hasText(string(R.string.room_edit_link_prompt, string(R.string.exit_east)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(prompt).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(prompt).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        val read = session.received.size
        compose.onNode(map).performTouchInput { click(at(drawn, 200.0, 150.0)) }
        val linked = session.receive("setRoomExit")
        assertEquals(listOf(2, "e", 1, true), listOf(linked.getInt("room"), linked.getString("direction"), linked.getInt("to"), linked.getBoolean("bothWays")))
        assertTrue("the tap pressed or asked: ${session.received.drop(read)}", session.received.drop(read).none { it.optString("t") in setOf("mapPointer", "roomInfo") })
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(prompt).fetchSemanticsNodes().isEmpty() }

        // Viewing the map: no edits, and an exit waiting for its room no longer waits
        tapInCard(R.string.room_edit_exits)
        activateInDialog(hasText(string(R.string.exit_up)))
        activateInDialog(hasText(string(R.string.room_edit_exit_pick)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.room_edit_link_prompt, string(R.string.exit_up)))).fetchSemanticsNodes().isNotEmpty() }
        session.send(mapFrame(MAP_W, MAP_H, viewOnly = true, view = mapView(MAP_W, MAP_H, rooms = rooms)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.room_edit_exits))).fetchSemanticsNodes().isEmpty() }
        compose.onNode(map).performTouchInput { click(at(drawn, 200.0, 150.0)) }
        assertEquals(1, session.receive("roomInfo").getInt("room"))
        session.assertNoRequest("setRoomExit", QUIET_MS)
    }

    @Test
    fun aRoomIsAddedMovedDraggedPlacedAndDeletedOnAnEditedMapAndItsAreaRenamed() {
        // Rooms 15 of the frame's pixels apart: 1 in the middle, 3 drawn above it
        val rooms = mapOf(1 to (200.0 to 150.0), 3 to (200.0 to 90.0))
        session.windows(listOf(mapper()), full = true)
        session.send(mapFrame(MAP_W, MAP_H, viewOnly = false, view = mapView(MAP_W, MAP_H, rooms = rooms)))
        val map = drawnMap()
        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { click(at(drawn, 200.0, 150.0)) }
        val ask = session.receive("roomInfo")
        session.send(room(ask, 1, "Great Hall", steps = 0))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Great Hall")).fetchSemanticsNodes().isNotEmpty() }
        val placeMe = hasText(string(R.string.room_edit_player_here)) and hasClickAction()
        compose.onNode(placeMe).performScrollTo().assertIsNotEnabled()

        // A room beside it, a way no exit goes, which shows once made
        tapInCard(R.string.room_edit_add)
        activateInDialog(hasText(string(R.string.exit_north)))
        val add = session.receive("addRoom")
        assertEquals(1 to "n", add.getInt("room") to add.getString("direction"))
        session.send(room(add, 3, "", steps = 1, exits = listOf(exit("s", 1, "Great Hall")), y = 1))
        val added = hasText(string(R.string.room_unnamed, 3))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(added).fetchSemanticsNodes().isNotEmpty() }

        // Moved a place east, as the dialog then says
        tapInCard(R.string.room_edit_move)
        activateInDialog(hasText(string(R.string.exit_east)))
        val nudge = session.receive("editRoom")
        assertEquals(listOf(3, 1, 1, 0), listOf("room", "x", "y", "z").map { nudge.getInt(it) })
        session.send(room(nudge, 3, "", steps = 1, x = 1, y = 1))
        val position = hasText(string(R.string.room_edit_position, 1, 1, 0))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(position).fetchSemanticsNodes().isNotEmpty() }
        activateInDialog(hasText(string(R.string.room_edit_done)))

        // A drag of the room picked moves it by the whole rooms it went: two east and one south, and the map stays
        val scale = shownScale(drawn)
        val read = session.received.size
        compose.onNode(map).performTouchInput {
            down(at(drawn, 200.0, 90.0))
            repeat(DRAG_STEPS) { moveBy(Offset((3.0 * scale).toFloat(), (1.5 * scale).toFloat())) }
            advanceEventTime(300)
            up()
        }
        val dragged = session.receive("editRoom")
        assertEquals(listOf(3, 3, 0, 0), listOf("room", "x", "y", "z").map { dragged.getInt(it) })
        assertTrue("the drag moved the map: ${session.received.drop(read)}", session.received.drop(read).none { it.optString("t") == "moveMap" })
        session.send(room(dragged, 3, "", steps = 1, x = 3))

        // A drag off the room picked, above the card, pans the map as when it is viewed
        compose.onNode(map).performTouchInput {
            down(at(drawn, 80.0, 60.0))
            repeat(DRAG_STEPS) { moveBy(Offset((3.0 * scale).toFloat(), 0f)) }
            advanceEventTime(300)
            up()
        }
        session.receive("moveMap")
        session.assertNoRequest("editRoom", QUIET_MS)

        tapInCard(R.string.room_edit_player_here)
        assertEquals(3, session.receive("setPlayerRoom").getInt("room"))

        // Its area renamed, and the room asked for again, as its area's name changed
        tapInCard(R.string.room_edit_rename_area)
        field(R.string.room_edit_area_name).performTextReplacement("  Keep ")
        activateInDialog(hasText(string(R.string.room_edit_save)))
        val renamed = session.receive("renameArea")
        assertEquals(1 to "Keep", renamed.getInt("area") to renamed.getString("name"))
        assertEquals(3, session.receive("roomInfo").getInt("room"))

        // Deleted once the player says so, and no longer shown
        tapInCard(R.string.room_edit_delete)
        activateInDialog(hasText(string(R.string.room_edit_delete_confirm)) and hasAnyAncestor(isDialog()))
        assertEquals(3, session.receive("deleteRoom").getInt("room"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(added).fetchSemanticsNodes().isEmpty() }
    }

    private fun mapper() = FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = MAP_W, h = MAP_H)

    // A button of the room picked, scrolled to in its card
    private fun tapInCard(@StringRes label: Int) {
        val button = hasText(string(label)) and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(button).performScrollTo()
        activate(button)
    }

    // A text field of the dialog open, by its label
    private fun field(@StringRes label: Int) = compose.onNode(hasSetTextAction() and hasText(string(label)) and hasAnyAncestor(isDialog()))

    /** As [activate], in the dialog open over the screen: the checks are of the dialog's window. */
    private fun activateInDialog(label: SemanticsMatcher) {
        val node = label and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(node).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        compose.onNode(node).performScrollTo().assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    }

    // The mapper in the top right quarter of Mudlet's main window, and its map
    private fun showMap(view: JSONObject? = null) {
        session.windows(listOf(mapper()), full = true)
        session.send(mapFrame(MAP_W, MAP_H, view = view))
    }

    private fun mapFrame(w: Int, h: Int, viewOnly: Boolean = true, view: JSONObject? = null): JSONObject = JSONObject().put("t", "map")
        .put("png", Base64.getEncoder().encodeToString(png(w, h))).put("w", w).put("h", h).put("viewOnly", viewOnly)
        .apply { if (view != null) put("view", view) }

    private fun followButton() = hasContentDescription(string(R.string.map_follow)) and hasClickAction()

    // The screen's pixels to each of the frame's, in the map's node of [bounds]
    private fun shownScale(bounds: Rect): Double = min(bounds.width / MAP_W, bounds.height / MAP_H).toDouble()

    // The map once it is drawn; the test's clock recomposes only while the test waits on Compose
    private fun drawnMap(): SemanticsMatcher {
        val map = hasContentDescription(string(R.string.map_description))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isNotEmpty() }
        return map
    }

    /** Where the point [x], [y] of the frame is in the map's node of [bounds]: the frame fits it alike both ways, centred. */
    private fun at(bounds: Rect, x: Double, y: Double): Offset {
        val scale = min(bounds.width / MAP_W, bounds.height / MAP_H)
        val left = (bounds.width - MAP_W * scale) / 2
        val top = (bounds.height - MAP_H * scale) / 2
        return Offset(left + (x * scale).toFloat(), top + (y * scale).toFloat())
    }

    /** The engine was last asked to draw the map at the scale a [w] by [h] frame is shown at in [bounds], at most the engine's 8. */
    private fun assertScaleAsked(bounds: Rect, w: Int, h: Int) {
        val shown = min(bounds.width / w, bounds.height / h).toDouble()
        assertTrue("the map is shown at $shown, not larger than Mudlet's pixels", shown > 1.1)
        val asked = session.receiveLast("setMapScale", readPast = true).getDouble("scale")
        // Hundredths, down, of a box that rounds to the screen's pixels
        assertEquals("the map shown at $shown is asked for at $asked", shown.coerceAtMost(8.0), asked, 0.02)
    }

    private fun assertPoint(x: Double, y: Double, event: JSONObject, within: Double = POINTS) {
        assertEquals("x of $event", x, event.getDouble("x"), within)
        assertEquals("y of $event", y, event.getDouble("y"), within)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    // Where a touch reaches it: a Material icon button is drawn at 40 dp and touched within 48
    private fun touchBounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().touchBoundsInRoot

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-map"
        const val MAP_W = 400
        const val MAP_H = 300

        // Small enough to be drawn larger in a pane on any screen
        const val SMALL_W = 200
        const val SMALL_H = 150

        // A frame's pixels a touch may land from where it was aimed, rounding to the screen's
        const val POINTS = 1.5

        // The middle of two fingers, each moved a step at a time
        const val PINCH_POINTS = 4.0

        // As ScriptMap's buttons zoom
        const val BUTTON_ZOOM = 1.25

        // A drag of 10 steps of 10 of the frame's pixels
        const val DRAG_STEPS = 10
        const val DRAG_STEP = 10.0
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L

        // Long enough for a request the UI would send to have come
        const val QUIET_MS = 1_000
    }
}
