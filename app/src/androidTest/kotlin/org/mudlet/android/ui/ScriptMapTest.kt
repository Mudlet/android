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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.min

/**
 * The map (docs/architecture.md, "The map"), against a fake engine: the engine's frame drawn in the mapper window,
 * a tap, a double tap (not two taps apart) and a pinch sent as `mapPointer` at the point of the frame under them, the zoom buttons and
 * TalkBack's actions that do the same without a gesture, the switch between viewing and editing it, a refusal shown
 * until dismissed, a frame of another size not drawn, and openMapWidget()'s docked map as a page.
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
    fun thePinchAndTheZoomButtonsTurnTheWheelWhichTheEngineZoomsBy() {
        showMap()
        val map = drawnMap()
        val zoomIn = hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()
        val zoomOut = hasContentDescription(string(R.string.map_zoom_out)) and hasClickAction()
        val min = with(compose.density) { 48.dp.toPx() }
        for (button in listOf(zoomIn, zoomOut)) {
            val touch = touchBounds(button)
            assertTrue("a zoom button is ${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)
        }

        // Three notches about the map's middle, with no button
        activate(zoomIn)
        val wheelIn = session.receive("mapPointer")
        assertEquals("wheel", wheelIn.getString("type"))
        assertEquals(360, wheelIn.getInt("wheelY"))
        assertFalse(wheelIn.has("button"))
        assertPoint(200.0, 150.0, wheelIn)
        activate(zoomOut)
        assertEquals(-360, session.receive("mapPointer").getInt("wheelY"))

        // Fingers spread across the middle zoom in about it, in whole notches, and select nothing
        val read = session.received.size
        compose.onNode(map).performTouchInput {
            val apart = Offset(width * 0.35f, 0f)
            val near = Offset(width * 0.05f, 0f)
            pinch(center - near, center - apart, center + near, center + apart, durationMillis = 400)
        }
        session.receiveLast("mapPointer")
        val pinch = session.received.drop(read).filter { it.optString("t") == "mapPointer" }
        assertTrue("no wheel", pinch.isNotEmpty())
        for (event in pinch) {
            assertEquals("wheel", event.getString("type"))
            assertTrue("wheelY ${event.getInt("wheelY")}", event.getInt("wheelY") > 0 && event.getInt("wheelY") % 120 == 0)
            assertPoint(200.0, 150.0, event, PINCH_POINTS)
        }
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

    private fun mapper() = FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = MAP_W, h = MAP_H)

    // The mapper in the top right quarter of Mudlet's main window, and its map
    private fun showMap() {
        session.windows(listOf(mapper()), full = true)
        session.send(mapFrame(MAP_W, MAP_H))
    }

    private fun mapFrame(w: Int, h: Int, viewOnly: Boolean = true): JSONObject = JSONObject().put("t", "map")
        .put("png", Base64.getEncoder().encodeToString(png(w, h))).put("w", w).put("h", h).put("viewOnly", viewOnly)

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

        // A frame's pixels a touch may land from where it was aimed, rounding to the screen's
        const val POINTS = 1.5

        // The middle of two fingers, each moved a step at a time
        const val PINCH_POINTS = 4.0
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
