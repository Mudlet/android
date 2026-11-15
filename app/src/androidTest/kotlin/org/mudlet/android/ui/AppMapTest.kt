package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
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
import org.mudlet.android.protocol.MapBehindText
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.Profile
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * The app's own map (docs/architecture.md, "The map behind the text"), against a fake engine: asked for at the
 * console's size and drawn faintly behind its text, which TalkBack reads and touches reach as before; let go when the
 * setting is off or a script's mapper window shows the map; brought to the front by the title bar's button, where a
 * tap selects, a double tap walks, the buttons zoom and TalkBack's actions do the same, and Back sends it back; and the
 * room search, whose results centre the map on a room.
 */
@RunWith(AndroidJUnit4::class)
class AppMapTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val console = hasScrollToIndexAction()

    // The title bar's map button, once the activity's strings can be read
    private lateinit var button: SemanticsMatcher

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        compose.runOnIdle { Settings.mapBehindText.reset() }
        button = hasContentDescription(string(R.string.app_map_show)) and hasClickAction()
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
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
    }

    @After
    fun defaults() {
        compose.runOnIdle {
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.mapBehindText.reset()
        }
    }

    @Test
    fun theMapIsDrawnFaintlyBehindTheTextWhichTalkBackAndTouchesStillReach() {
        // The first ask may come before the game screen's openProfile, which the test reads past
        val size = session.receiveLast("setAppMapSize", readPast = true)
        val bounds = bounds(console)
        val density = compose.density.density
        // The console's size in dp, drawn as sharp as the screen
        assertEquals("w of $size", bounds.width / density, size.getInt("w").toFloat(), 1f)
        assertEquals("h of $size", bounds.height / density, size.getInt("h").toFloat(), 1f)
        assertEquals(density.toDouble().coerceIn(1.0, 4.0), size.getDouble("scale"), 0.01)
        assertBackground(Color.Black)

        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        // White rooms behind the default grey on black, as faint as keeps it readable: 30% of white
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }
        val pixel = backgroundPixel()
        assertTrue("the map is grey behind the text: $pixel", abs(pixel.green - pixel.red) < 0.02f && abs(pixel.blue - pixel.red) < 0.02f)

        // Not read, and the console's text is, as before
        assertTrue(compose.onAllNodes(hasContentDescription(string(R.string.map_description)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription(string(R.string.map_not_drawn)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasText("the main console")).assertIsDisplayed()
        compose.onRoot().tryPerformAccessibilityChecks()
        // A tap on the console is the console's, not the map's
        compose.onNode(console).performTouchInput { click(center) }
        session.assertNoRequest("appMapPointer", QUIET_MS)
    }

    @Test
    fun noMapIsDrawnBehindTheTextWhenTheSettingIsOffOrAScriptsMapperShowsIt() {
        val size = session.receiveLast("setAppMapSize", readPast = true)
        assertTrue("asked for at $size", size.getInt("w") > 0)
        compose.runOnIdle { Settings.mapBehindText.set(false) }
        assertNone(session.receiveLast("setAppMapSize"))
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        session.assertNoRequest("setAppMapSize", QUIET_MS)
        assertBackground(Color.Black)

        compose.runOnIdle { Settings.mapBehindText.set(true) }
        assertEquals(size.toString(), session.receiveLast("setAppMapSize").toString())

        // A Geyser.Mapper shows the map: one map on the screen
        val mapper = FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = 400, h = 300)
        session.windows(listOf(mapper), full = true)
        assertNone(session.receiveLast("setAppMapSize"))
        // Hidden by its script, the map goes back behind the text
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = 400, h = 300).put("shown", false)), full = true)
        assertTrue(session.receiveLast("setAppMapSize").getInt("w") > 0)
    }

    @Test
    fun theSettingsSwitchTurnsTheMapBehindTheTextOff() {
        assertTrue(session.receiveLast("setAppMapSize", readPast = true).getInt("w") > 0)
        activate(hasContentDescription(string(R.string.settings_title)))
        val row = hasText(string(R.string.settings_map_behind_text)) and hasClickAction()
        // The settings screen scrolls, and TalkBack scrolls to what it moves to
        compose.onNode(row).performScrollTo()
        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.runOnIdle { assertFalse(Settings.mapBehindText.value) }
        // Back on the game screen, no map is asked for
        activate(hasContentDescription(string(R.string.navigate_back)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
        assertNone(session.receiveLast("setAppMapSize"))
    }

    @Test
    fun theMapIsAsFaintAsTheConsolesColoursNeedAndNoneWhereTheyLeaveNoRoom() {
        val size = session.receiveLast("setAppMapSize", readPast = true)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }
        // Dimmer text, as a profile may set: a fainter map
        session.status("consoleColors", "#808080 #000000")
        val dim = MapBehindText.alpha(0x808080, 0x000000)
        assertTrue("fainter than 30%: $dim", dim > 0f && dim < 0.25f)
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - dim) < 0.03f }
        // Text too faint on its own background already: no map at all, nor any asked for
        session.status("consoleColors", "#000080 #000000")
        assertNone(session.receiveLast("setAppMapSize"))
        assertBackground(Color.Black)
    }

    @Test
    fun theEngineIsAskedForTheMapAgainAtEachAttachAndAnEmptyFrameClearsIt() {
        val size = session.receiveLast("setAppMapSize", readPast = true)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }

        // The UI went and came back to the same engine, which forgot the size as the UI went: it is asked again
        session.close()
        session = fake.engine.nextSession()
        assertEquals(size.toString(), session.receiveLast("setAppMapSize").toString())

        // Android restarted the engine: the new one draws nothing until asked, which the UI does without the player
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        session.close()
        session = fake.engine.nextSession()
        assertEquals(size.toString(), session.receiveLast("setAppMapSize").toString())
        assertBackground(Color.Black)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }

        // Nothing to draw, as when the profile has no rooms: no map behind the text, nor in front
        session.send(JSONObject().put("t", "appMap").put("png", "").put("w", 0).put("h", 0).put("viewOnly", true))
        compose.waitUntil(WAIT_MS) { backgroundPixel() == Color.Black }
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.map_not_drawn))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theButtonBringsTheMapToTheFrontWhereATapSelectsADoubleTapWalksAndBackSendsItBack() {
        val behind = session.receiveLast("setAppMapSize", readPast = true)
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertTouchTarget(button)
        val (w, h) = openMap()
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        val map = drawnMap()
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_viewing)))
        // What the map covers is not read
        assertTrue(compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isEmpty())
        compose.onRoot().tryPerformAccessibilityChecks()

        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { click(at(drawn, w, h, w * 0.25, h * 0.75)) }
        val press = session.receive("appMapPointer")
        assertEquals("press", press.getString("type"))
        assertEquals(POINTER_LEFT, press.getInt("button"))
        assertPoint(w * 0.25, h * 0.75, press)
        session.assertNoRequest("mapPointer", QUIET_MS)

        compose.onNode(map).performTouchInput { doubleClick(at(drawn, w, h, w * 0.75, h * 0.25)) }
        assertEquals("press", session.receive("appMapPointer").getString("type"))
        val walk = session.receive("appMapPointer")
        assertEquals("doubleClick", walk.getString("type"))
        assertPoint(w * 0.75, h * 0.25, walk)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isEmpty() }
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNode(hasText("the main console")).assertIsDisplayed()
        // Behind the text again, at the console's size
        assertEquals(behind.toString(), session.receiveLast("setAppMapSize").toString())

        // The button sends it back too
        activate(button)
        drawnMap()
        assertEquals(w.toInt(), session.receiveLast("setAppMapSize").getInt("w"))
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isEmpty() }
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNode(hasText("the main console")).assertIsDisplayed()
        assertEquals(behind.toString(), session.receiveLast("setAppMapSize").toString())
    }

    @Test
    fun aBackGestureLetGoOfBeforeItEndsLeavesTheMapInFront() {
        openMap()
        val map = drawnMap()
        val back = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNode(map).assertIsDisplayed()
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // Still asked for at the size in front, not sent back behind the text
        session.assertNoRequest("setAppMapSize", QUIET_MS)
    }

    @Test
    fun theZoomButtonsTheEditSwitchAndTalkBacksActionsWorkOnTheMapInFront() {
        val (w, h) = openMap()
        val map = drawnMap()
        val zoomIn = hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()
        assertTouchTarget(zoomIn)
        activate(zoomIn)
        val wheel = session.receive("appMapPointer")
        assertEquals("wheel", wheel.getString("type"))
        assertEquals(360, wheel.getInt("wheelY"))
        assertPoint(w / 2.0, h / 2.0, wheel)

        val edit = hasContentDescription(string(R.string.map_edit)) and hasClickAction()
        activate(edit)
        assertFalse(session.receive("setMapViewOnly").getBoolean("viewOnly"))
        session.send(appMap(w.toInt(), h.toInt(), viewOnly = false))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(edit and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_editing)))
        // Its refusal is shown with this map, not the mapper window's
        activate(edit)
        val view = session.receive("setMapViewOnly")
        assertTrue(view.getBoolean("viewOnly"))
        session.refuse(view, "no profile running")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "no profile running"))).fetchSemanticsNodes().isNotEmpty() }
        activate(hasContentDescription(string(R.string.map_refusal_dismiss)))

        compose.onRoot().tryPerformAccessibilityChecks()
        val actions = compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == string(R.string.map_walk_to_middle) }.action() }
        val walk = session.receive("appMapPointer")
        assertEquals("doubleClick", walk.getString("type"))
        assertPoint(w / 2.0, h / 2.0, walk)

        // The engine's refusal of a request of this map's is shown with it
        session.send(JSONObject().put("t", "error").put("message", "appMapPointer: no profile running"))
        val refusal = hasText(string(R.string.map_refused, "no profile running"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(refusal).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRoomFoundByNameIsWhereTheMapGoes() {
        openMap()
        drawnMap()
        val field = hasSetTextAction() and hasText(string(R.string.app_map_search))
        compose.onNode(field).performTextInput("hall")
        compose.onNode(field).performImeAction()
        val find = session.receive("findRooms")
        assertEquals("hall", find.getString("text"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.app_map_searching))).fetchSemanticsNodes().isNotEmpty() }

        session.send(rooms(find, "hall", listOf(Triple(1, "Great Hall", "Castle"), Triple(3, "hallway", "Castle"))))
        val hallway = hasText(string(R.string.app_map_room, "hallway", "Castle", 3))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hallway).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(string(R.string.app_map_room, "Great Hall", "Castle", 1))).assertIsDisplayed()
        assertTouchTarget(hallway)
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(hallway)
        val show = session.receive("showRoom")
        assertEquals(3, show.getInt("room"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hallway).fetchSemanticsNodes().isEmpty() }
        // The map in front stays
        drawnMap()
        // And says why, when the room is gone
        session.refuse(show, "There is no room 3 in the map.")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "There is no room 3 in the map."))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aFingerThatMovesALittleStillTapsTheMapInFrontsControls() {
        val (w, h) = openMap()
        drawnMap()
        // A finger moves a little between down and up, under the touch slop: still a tap
        val jitter: TouchInjectionScope.() -> Unit = {
            down(center)
            moveBy(Offset(3f, 2f))
            moveBy(Offset(-2f, 1f))
            up()
        }
        compose.onNode(hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()).performTouchInput(jitter)
        val wheel = session.receive("appMapPointer")
        assertEquals("wheel", wheel.getString("type"))
        assertEquals(360, wheel.getInt("wheelY"))
        assertPoint(w / 2.0, h / 2.0, wheel)

        val field = hasSetTextAction() and hasText(string(R.string.app_map_search))
        compose.onNode(field).performTextInput("hall")
        compose.onNode(field).performImeAction()
        session.send(rooms(session.receive("findRooms"), "hall", listOf(Triple(1, "Great Hall", "Castle"), Triple(3, "hallway", "Castle"))))
        val hallway = hasText(string(R.string.app_map_room, "hallway", "Castle", 3))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hallway).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hallway and hasClickAction()).performTouchInput(jitter)
        assertEquals(3, session.receive("showRoom").getInt("room"))
    }

    @Test
    fun aSearchSaysWhenNothingOrTooMuchMatchesAndWhyTheEngineRefusedIt() {
        openMap()
        val field = hasSetTextAction() and hasText(string(R.string.app_map_search))
        compose.onNode(field).performTextInput("cellar")
        compose.onNode(field).performImeAction()
        session.send(rooms(session.receive("findRooms"), "cellar", emptyList()))
        val none = hasText(string(R.string.app_map_no_rooms, "cellar"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(none).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(none).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        activate(hasContentDescription(string(R.string.app_map_results_close)))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(none).fetchSemanticsNodes().isEmpty() }

        compose.onNode(field).performImeAction()
        session.send(rooms(session.receive("findRooms"), "cellar", listOf(Triple(7, "Wine cellar", "Keep")), more = true))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.app_map_more_rooms))).fetchSemanticsNodes().isNotEmpty() }

        // A refused search is over: a late answer to it is not shown
        compose.onNode(field).performImeAction()
        val refused = session.receive("findRooms")
        session.refuse(refused, "no profile running")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "no profile running"))).fetchSemanticsNodes().isNotEmpty() }
        session.send(rooms(refused, "cellar", listOf(Triple(7, "Wine cellar", "Keep"))))
        // Time for the answer to arrive, were it to show
        session.assertNoRequest("findRooms", QUIET_MS)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText(string(R.string.app_map_room, "Wine cellar", "Keep", 7))).fetchSemanticsNodes().isEmpty())
    }

    // Opens the map in front, and returns the size it is asked at, once the engine has drawn it
    private fun openMap(): Pair<Double, Double> {
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.map_not_drawn))).fetchSemanticsNodes().isNotEmpty() }
        val size = session.receiveLast("setAppMapSize")
        val w = size.getInt("w")
        val h = size.getInt("h")
        session.send(appMap(w, h, scale = size.getDouble("scale")))
        return w.toDouble() to h.toDouble()
    }

    private fun appMap(w: Int, h: Int, scale: Double = 1.0, viewOnly: Boolean = true): JSONObject = JSONObject().put("t", "appMap")
        .put("png", Base64.getEncoder().encodeToString(png((w * scale).toInt(), (h * scale).toInt()))).put("w", w).put("h", h).put("viewOnly", viewOnly)

    private fun rooms(request: JSONObject, text: String, found: List<Triple<Int, String, String>>, more: Boolean = false): JSONObject {
        val list = JSONArray(found.map { (id, name, area) -> JSONObject().put("id", id).put("name", name).put("area", area) })
        return JSONObject().put("t", "rooms").put("text", text).put("rooms", list).put("more", more).put("re", request.get("id"))
    }

    private fun assertNone(size: JSONObject) {
        assertEquals("$size", 0, size.getInt("w"))
        assertEquals("$size", 0, size.getInt("h"))
    }

    // A pixel of the console away from its text, where the map shows behind it
    private fun backgroundPixel(): Color {
        val image = compose.onNode(console).captureToImage().toPixelMap()
        return image[image.width - BACKGROUND_INSET, image.height - BACKGROUND_INSET]
    }

    private fun assertBackground(expected: Color) {
        compose.waitForIdle()
        assertEquals(expected, backgroundPixel())
    }

    private fun drawnMap(): SemanticsMatcher {
        val map = hasContentDescription(string(R.string.map_description))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isNotEmpty() }
        return map
    }

    /** Where the point [x], [y] of a [w] by [h] frame is in the map's node of [bounds]: the frame fits it alike both ways, centred. */
    private fun at(bounds: Rect, w: Double, h: Double, x: Double, y: Double): Offset {
        val scale = min(bounds.width / w, bounds.height / h)
        val left = (bounds.width - w * scale) / 2
        val top = (bounds.height - h * scale) / 2
        return Offset((left + x * scale).toFloat(), (top + y * scale).toFloat())
    }

    private fun assertPoint(x: Double, y: Double, event: JSONObject) {
        assertEquals("x of $event", x, event.getDouble("x"), POINTS)
        assertEquals("y of $event", y, event.getDouble("y"), POINTS)
    }

    private fun assertTouchTarget(matcher: SemanticsMatcher) {
        val touch = compose.onNode(matcher).fetchSemanticsNode().touchBoundsInRoot
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("${touch.width} by ${touch.height}", touch.width >= min - 1 && touch.height >= min - 1)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-app-map"

        // A frame's pixels a touch may land from where it was aimed, rounding to the screen's
        const val POINTS = 1.5

        // Pixels in from the console's bottom right corner, clear of the frame's edge, which may round off a pixel
        const val BACKGROUND_INSET = 8
        const val QUIET_MS = 500
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
