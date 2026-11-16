package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.game.Announcer
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlin.math.min

/**
 * The app's own map (docs/architecture.md, "The map behind the text"), against a fake engine: asked for at the
 * console's size and drawn faintly behind its text, which TalkBack reads and touches reach as before; let go when the
 * setting is off or a script's mapper window shows the map; brought to the front by the title bar's button, over the
 * input line and the quick bar, where a tap selects, a double tap walks, the buttons zoom and TalkBack's actions do the
 * same, and Back sends it back; and the room search, whose results centre the map on a room; and the download of the map the game offers.
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
        val size = lastRequest("setAppMapSize", readPast = true)
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
        checkAccessibility()
        // A tap on the console is the console's, not the map's
        compose.onNode(console).performTouchInput { click(center) }
        noRequest("appMapPointer")
    }

    @Test
    fun noMapIsDrawnBehindTheTextWhenTheSettingIsOffOrAScriptsMapperShowsIt() {
        val size = lastRequest("setAppMapSize", readPast = true)
        assertTrue("asked for at $size", size.getInt("w") > 0)
        compose.runOnIdle { Settings.mapBehindText.set(false) }
        assertNone(lastRequest("setAppMapSize", "the setting off lets go of the map behind the text"))
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        noRequest("setAppMapSize")
        assertBackground(Color.Black)

        compose.runOnIdle { Settings.mapBehindText.set(true) }
        assertEquals(size.toString(), lastRequest("setAppMapSize").toString())

        // A Geyser.Mapper shows the map: one map on the screen
        val mapper = FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = 400, h = 300)
        session.windows(listOf(mapper), full = true)
        assertNone(lastRequest("setAppMapSize"))
        // Hidden by its script, the map goes back behind the text
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 400, y = 0, w = 400, h = 300).put("shown", false)), full = true)
        assertTrue(lastRequest("setAppMapSize").getInt("w") > 0)
    }

    @Test
    fun aMapperWindowThatIsOnlyATabLeavesTheMapBehindTheTextUntilItsPageIsOpen() {
        assertTrue(lastRequest("setAppMapSize", readPast = true).getInt("w") > 0)
        // The starter UI's map beside the text: in the stacked layout a page, of which only its tab is on the screen
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.STACKED }
        session.mainArea(1000, 500, 100, 25, 100, 50, 800, 400)
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 900, y = 50, w = 100, h = 400)), full = true)
        val tab = hasText(string(R.string.map_title)) and hasClickAction()
        waitFor("the map's tab shows") { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        val size = lastRequest("setAppMapSize", "a mapper that is only a tab leaves the map behind the text")
        assertTrue("asked for at $size", size.getInt("w") > 0)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }

        // Its page open, the mapper's map is on the screen: one map
        activate(tab)
        assertNone(lastRequest("setAppMapSize", "the mapper's page open lets go of the map behind the text"))
        activate(tab)
        assertTrue(lastRequest("setAppMapSize", "the page closed, the map is behind the text again").getInt("w") > 0)
    }

    @Test
    fun theSettingsSwitchTurnsTheMapBehindTheTextOff() {
        assertTrue(lastRequest("setAppMapSize", readPast = true).getInt("w") > 0)
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
        assertNone(lastRequest("setAppMapSize"))
    }

    @Test
    fun theMapIsAsFaintAsTheConsolesColoursNeedAndNoneWhereTheyLeaveNoRoom() {
        val size = lastRequest("setAppMapSize", readPast = true)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }
        // Dimmer text, as a profile may set: a fainter map
        session.status("consoleColors", "#808080 #000000")
        val dim = MapBehindText.alpha(0x808080, 0x000000)
        assertTrue("fainter than 30%: $dim", dim > 0f && dim < 0.25f)
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - dim) < 0.03f }
        // Text too faint on its own background already: no map at all, nor any asked for
        session.status("consoleColors", "#000080 #000000")
        assertNone(lastRequest("setAppMapSize"))
        assertBackground(Color.Black)
    }

    @Test
    fun theEngineIsAskedForTheMapAgainAtEachAttachAndAnEmptyFrameClearsIt() {
        val size = lastRequest("setAppMapSize", readPast = true)
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        compose.waitUntil(WAIT_MS) { abs(backgroundPixel().red - 0.3f) < 0.03f }

        // The UI went and came back to the same engine, which forgot the size as the UI went: it is asked again
        session.close()
        session = fake.engine.nextSession()
        assertEquals(size.toString(), lastRequest("setAppMapSize", "the same engine is asked again when the UI comes back").toString())

        // Android restarted the engine: the new one draws nothing until asked, which the UI does without the player
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        session.close()
        session = fake.engine.nextSession()
        assertEquals(size.toString(), lastRequest("setAppMapSize", "a restarted engine is asked").toString())
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
        val behind = lastRequest("setAppMapSize", readPast = true)
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertTouchTarget(button)
        val (w, h) = openMap()
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        val map = drawnMap()
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_viewing)))
        // What the map covers is not read
        assertTrue(compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isEmpty())
        checkAccessibility()

        val drawn = bounds(map)
        compose.onNode(map).performTouchInput { click(at(drawn, w, h, w * 0.25, h * 0.75)) }
        val press = request("appMapPointer", "a tap on the map in front is its press")
        assertEquals("press", press.getString("type"))
        assertEquals(POINTER_LEFT, press.getInt("button"))
        assertPoint(w * 0.25, h * 0.75, press)
        noRequest("mapPointer")

        compose.onNode(map).performTouchInput { doubleClick(at(drawn, w, h, w * 0.75, h * 0.25)) }
        assertEquals("press", request("appMapPointer", "a double tap on the map in front presses first").getString("type"))
        val walk = request("appMapPointer", "and then walks")
        assertEquals("doubleClick", walk.getString("type"))
        assertPoint(w * 0.75, h * 0.25, walk)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isEmpty() }
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNode(hasText("the main console")).assertIsDisplayed()
        // Behind the text again, at the console's size
        assertEquals(behind.toString(), lastRequest("setAppMapSize").toString())

        // The button sends it back too
        activate(button)
        drawnMap()
        assertEquals(w.toInt(), lastRequest("setAppMapSize").getInt("w"))
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(map).fetchSemanticsNodes().isEmpty() }
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNode(hasText("the main console")).assertIsDisplayed()
        assertEquals(behind.toString(), lastRequest("setAppMapSize").toString())
    }

    @Test
    fun theMapInFrontCoversTheInputLineAndQuickBarAndTheInputLineHasItsFocusBackAfter() {
        val input = hasSetTextAction() and hasText(string(R.string.input_label))
        val quickBar = hasText(string(R.string.history_open)) and hasClickAction()
        val connectionBar = hasText(string(R.string.connection_disconnect)) and hasClickAction()
        // Where they are with no keyboard up, as the map in front has none
        val quickBarAt = bounds(quickBar)

        // Without the focus, the input line does not take it when the map goes
        activate(button)
        waitFor("the map comes to the front") { compose.onAllNodes(mapShown()).fetchSemanticsNodes().isNotEmpty() }
        assertCovered(input, quickBar, quickBarAt, connectionBar)
        checkAccessibility()
        activate(button)
        waitFor("the input line is back") { compose.onAllNodes(input).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(input).assertIsDisplayed()
        compose.onNode(quickBar).assertIsDisplayed()
        compose.waitForIdle()
        assertTrue("the input line stays without the focus. ${screen()}", compose.onAllNodes(input and isFocused()).fetchSemanticsNodes().isEmpty())

        // With it, the keyboard goes while the map is in front, and the focus is the input line's again after Back
        withScreen { compose.onNode(input).performSemanticsAction(SemanticsActions.RequestFocus) }
        keyboardUp()
        activate(button)
        waitFor("the map comes to the front") { compose.onAllNodes(mapShown()).fetchSemanticsNodes().isNotEmpty() }
        waitFor("the input line's keyboard goes") { !keyboardShown() }
        assertCovered(input, quickBar, quickBarAt, connectionBar)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("the input line is back, with the focus") { compose.onAllNodes(input and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(input).assertIsDisplayed()
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
        noRequest("setAppMapSize")
    }

    @Test
    fun theZoomButtonsTheEditSwitchAndTalkBacksActionsWorkOnTheMapInFront() {
        val (w, h) = openMap()
        val map = drawnMap()
        val zoomIn = hasContentDescription(string(R.string.map_zoom_in)) and hasClickAction()
        // None over the map, whose top line is Mudlet's name of the room
        for (id in listOf(R.string.map_zoom_out, R.string.map_zoom_in, R.string.map_edit)) {
            val at = bounds(hasContentDescription(string(id)) and hasClickAction())
            assertFalse("${string(id)} at $at is over the map at ${bounds(map)}", at.overlaps(bounds(map)))
        }
        assertTouchTarget(zoomIn)
        activate(zoomIn)
        val wheel = request("appMapPointer", "the zoom button zooms the map in front")
        assertEquals("wheel", wheel.getString("type"))
        assertEquals(360, wheel.getInt("wheelY"))
        assertPoint(w / 2.0, h / 2.0, wheel)

        val edit = hasContentDescription(string(R.string.map_edit)) and hasClickAction()
        activate(edit)
        assertFalse(request("setMapViewOnly", "the edit switch asks to edit").getBoolean("viewOnly"))
        session.send(appMap(w.toInt(), h.toInt(), viewOnly = false))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(edit and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(map).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.map_editing)))
        // Its refusal is shown with this map, not the mapper window's
        activate(edit)
        val view = request("setMapViewOnly", "the edit switch asks to view again")
        assertTrue(view.getBoolean("viewOnly"))
        session.refuse(view, "no profile running")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "no profile running"))).fetchSemanticsNodes().isNotEmpty() }
        activate(hasContentDescription(string(R.string.map_refusal_dismiss)))

        checkAccessibility()
        val actions = compose.onNode(map).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == string(R.string.map_walk_to_middle) }.action() }
        val walk = request("appMapPointer", "TalkBack's action walks to the middle")
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
        search("hall")
        val find = request("findRooms", "the search is sent")
        assertEquals("hall", find.getString("text"))
        waitFor("the search says it is searching") { compose.onAllNodes(hasText(string(R.string.app_map_searching))).fetchSemanticsNodes().isNotEmpty() }

        session.send(rooms(find, "hall", listOf(Triple(1, "Great Hall", "Castle"), Triple(3, "hallway", "Castle"))))
        val hallway = hasText(string(R.string.app_map_room, "hallway", "Castle", 3))
        waitFor("the rooms found are listed") { compose.onAllNodes(hallway).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(string(R.string.app_map_room, "Great Hall", "Castle", 1))).assertIsDisplayed()
        assertTouchTarget(hallway)
        assertResultsFit()
        checkAccessibility()

        activate(hallway)
        val show = request("showRoom", "a room found is shown on the map")
        assertEquals(3, show.getInt("room"))
        waitFor("the list goes once a room is shown") { compose.onAllNodes(hallway).fetchSemanticsNodes().isEmpty() }
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
        val wheel = request("appMapPointer", "a finger that moves a little still taps the zoom button")
        assertEquals("wheel", wheel.getString("type"))
        assertEquals(360, wheel.getInt("wheelY"))
        assertPoint(w / 2.0, h / 2.0, wheel)

        search("hall")
        session.send(rooms(request("findRooms", "the search is sent"), "hall", listOf(Triple(1, "Great Hall", "Castle"), Triple(3, "hallway", "Castle"))))
        val hallway = hasText(string(R.string.app_map_room, "hallway", "Castle", 3))
        waitFor("the rooms found are listed") { compose.onAllNodes(hallway).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hallway and hasClickAction()).performTouchInput(jitter)
        assertEquals(3, request("showRoom", "a finger that moves a little still taps a room found").getInt("room"))
    }

    @Test
    fun aSearchSaysWhenNothingOrTooMuchMatchesAndWhyTheEngineRefusedIt() {
        openMap()
        val field = search("cellar")
        session.send(rooms(request("findRooms", "the search is sent"), "cellar", emptyList()))
        val none = hasText(string(R.string.app_map_no_rooms, "cellar"))
        waitFor("the search says nothing matched") { compose.onAllNodes(none).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(none).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        assertResultsFit()
        activate(hasContentDescription(string(R.string.app_map_results_close)))
        waitFor("the results close") { compose.onAllNodes(none).fetchSemanticsNodes().isEmpty() }

        searchAgain(field)
        session.send(rooms(request("findRooms", "the search is sent again"), "cellar", listOf(Triple(7, "Wine cellar", "Keep")), more = true))
        waitFor("the search says there were more") { compose.onAllNodes(hasText(string(R.string.app_map_more_rooms))).fetchSemanticsNodes().isNotEmpty() }

        // A refused search is over: a late answer to it is not shown. While the search is edited, its last results go
        searchAgain(field) {
            waitFor("the last results go while the search is edited") { compose.onAllNodes(resultsClose()).fetchSemanticsNodes().isEmpty() }
            checkAccessibility()
        }
        val refused = request("findRooms", "the search is sent a third time")
        session.refuse(refused, "no profile running")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(string(R.string.map_refused, "no profile running"))).fetchSemanticsNodes().isNotEmpty() }
        session.send(rooms(refused, "cellar", listOf(Triple(7, "Wine cellar", "Keep"))))
        // Time for the answer to arrive, were it to show
        noRequest("findRooms")
        compose.waitForIdle()
        assertTrue(
            "a late answer to a refused search is not shown",
            compose.onAllNodes(hasText(string(R.string.app_map_room, "Wine cellar", "Keep", 7))).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theMapTheGameOffersIsDownloadedWithItsProgressAndHowItEnded() {
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.map_not_drawn))).fetchSemanticsNodes().isNotEmpty() }
        val download = hasText(string(R.string.map_download, HOST)) and hasClickAction()
        // Where it starts, with no map offered: no button
        assertTrue("no download without a map offered", compose.onAllNodes(download).fetchSemanticsNodes().isEmpty())

        // Offered, with no map drawn: the offer is in the map's middle, its button a touch target TalkBack can press
        session.send(mapDownload())
        val offered = hasText(string(R.string.map_download_offered, HOST))
        waitFor("the offer shows where no map is drawn") { compose.onAllNodes(offered).fetchSemanticsNodes().isNotEmpty() }
        assertTouchTarget(download)
        activate(download)
        request("downloadMap", "the button downloads the map")
        // Until the engine answers, a second tap sends no second downloadMap
        compose.onNode(download).assertIsNotEnabled()

        // Mudlet's progress, the bar's a quarter of the way, and Cancel while it can be stopped; the offer waits
        val label = "Downloading map file for use in Game..."
        session.send(mapDownload("downloading", label = label, value = 1_000_000, maximum = 4_000_000, cancelable = true))
        waitFor("the download's progress shows") { compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        val bar = hasContentDescription(string(R.string.map_download_progress))
        compose.onNode(bar).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)))
        // Past Mudlet's guess at the size, how far is not known
        session.send(mapDownload("downloading", label = label, value = 5_000_000, maximum = 4_000_000, cancelable = true))
        waitFor("a download past the guessed size has no fraction") {
            compose.onAllNodes(bar and SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("no second download while one runs", compose.onAllNodes(download).fetchSemanticsNodes().isEmpty())
        val cancel = hasText(string(R.string.map_download_cancel)) and hasClickAction()
        assertTouchTarget(cancel)
        activate(cancel)
        request("cancelMapDownload", "Cancel stops the download")
        session.send(mapDownload("cancelled"))
        val cancelled = hasText(string(R.string.map_download_cancelled))
        waitFor("the download says it was cancelled") { compose.onAllNodes(cancelled).fetchSemanticsNodes().isNotEmpty() }
        // Told last, though an announcement just before it may share its turn (Announcer's MERGE_MS)
        compose.runOnIdle { assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_download_cancelled))) }

        // A failure says Mudlet's reason, read out at once, until dismissed
        activate(download)
        request("downloadMap", "the button downloads the map again")
        session.send(mapDownload("downloading", label = label, cancelable = true))
        waitFor("the download's progress shows again") { compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        val reason = "Map download encountered an error: Error transferring http://$HOST - server replied: Not Found"
        session.send(mapDownload("failed", error = reason))
        val failed = hasText(string(R.string.map_download_failed, reason))
        waitFor("the download says why it failed") { compose.onAllNodes(failed).fetchSemanticsNodes().isNotEmpty() }
        // Told once, by the Announcer, and not again by the text as a live region
        compose.onNode(failed).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        val told = compose.runOnIdle {
            assertTrue("TalkBack is told: ${Announcer.current?.text}", Announcer.current!!.text.endsWith(string(R.string.map_download_failed, reason)))
            assertTrue("a failure interrupts TalkBack", Announcer.current!!.assertive)
            Announcer.current!!.serial
        }
        checkAccessibility()

        // The UI went and came back to the same engine, which says the same again: shown, but not told again. The
        // refusal after it shows once the UI has read the frames before it.
        session.close()
        session = fake.engine.nextSession()
        val again = lastRequest("setAppMapSize", "the same engine is asked again when the UI comes back")
        session.send(mapDownload("failed", error = reason))
        session.refuse(again, "read")
        waitFor("the UI read what the engine said again") { compose.onAllNodes(hasText(string(R.string.map_refused, "read"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(failed).assertIsDisplayed()
        compose.runOnIdle { assertEquals("a UI that comes back is not told again", told, Announcer.current!!.serial) }
        // The refusal is at the map's bottom, which the offer in its middle could reach: the offer waits in the bar
        assertTrue("with a refusal shown the offer is not in the map's middle", compose.onAllNodes(offered).fetchSemanticsNodes().isEmpty())
        compose.onNode(download).assertIsDisplayed()
        activate(hasContentDescription(string(R.string.map_download_dismiss)))
        waitFor("the failure goes once dismissed") { compose.onAllNodes(failed).fetchSemanticsNodes().isEmpty() }

        // The engine's refusal is shown with the map
        activate(download)
        session.refuse(request("downloadMap", "the button downloads the map once more"), "A map download, import or export is running; wait for it to end.")
        val refused = hasText(string(R.string.map_refused, "A map download, import or export is running; wait for it to end."))
        waitFor("the refusal shows") { compose.onAllNodes(refused).fetchSemanticsNodes().isNotEmpty() }

        // Loaded: the map is drawn, and says so for a moment; nothing offers the map any more
        activate(download)
        request("downloadMap", "the button downloads the map at last")
        session.send(mapDownload("downloading", cancelable = false))
        val running = hasText(string(R.string.map_download_running, HOST))
        waitFor("the progress shows, with no label nor size known") { compose.onAllNodes(running).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(cancel).assertIsNotEnabled()
        val size = lastRequest("setAppMapSize", readPast = true)
        session.send(mapDownload("loaded"))
        session.send(appMap(size.getInt("w"), size.getInt("h"), scale = size.getDouble("scale")))
        drawnMap()
        val loaded = hasText(string(R.string.map_download_loaded, HOST))
        waitFor("the download says the map loaded") { compose.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no download is offered over a drawn map", compose.onAllNodes(download).fetchSemanticsNodes().isEmpty())
        assertTrue("nor in its middle", compose.onAllNodes(offered).fetchSemanticsNodes().isEmpty())
        checkAccessibility()
        waitFor("that the map loaded goes by itself") { compose.onAllNodes(loaded).fetchSemanticsNodes().isEmpty() }

        // A new UI, as after the app restarted, hears the load from the engine but does not show it, nor offers the map
        // over the loaded one: the map shows it loaded
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        session.close()
        session = fake.engine.nextSession()
        val asked = lastRequest("setAppMapSize", "a new engine instance is asked for the map")
        session.send(mapDownload("loaded"))
        session.send(appMap(asked.getInt("w"), asked.getInt("h"), scale = asked.getDouble("scale")))
        session.refuse(asked, "read again")
        waitFor("the UI read what the new engine said") { compose.onAllNodes(hasText(string(R.string.map_refused, "read again"))).fetchSemanticsNodes().isNotEmpty() }
        drawnMap()
        assertTrue("a new UI does not say the last load", compose.onAllNodes(loaded).fetchSemanticsNodes().isEmpty())
        assertTrue("nor offers the map over the loaded one", compose.onAllNodes(download).fetchSemanticsNodes().isEmpty())
    }

    /** Types [text] in the map's search field and sends it as [searchAgain] does; returns the field. */
    private fun search(text: String): SemanticsMatcher {
        val field = hasSetTextAction() and hasText(string(R.string.app_map_search))
        // The map's height before the keyboard takes its share
        val height = bounds(mapShown()).height
        withScreen { compose.onNode(field).performTextInput(text) }
        keyboardUp()
        sendSearch(field, height)
        return field
    }

    /**
     * Sends again what the search [field] holds, from the field as a player comes back to it: a tap, the keyboard,
     * [whileEditing], then its Search key, after which the map has its [height] back.
     */
    private fun searchAgain(field: SemanticsMatcher, height: Float = bounds(mapShown()).height, whileEditing: () -> Unit = {}) {
        withScreen { compose.onNode(field).performSemanticsAction(SemanticsActions.RequestFocus) }
        keyboardUp()
        whileEditing()
        sendSearch(field, height)
    }

    /**
     * Sends what the focused search [field] holds with the keyboard's Search key. The field lets go of the focus, the keyboard
     * goes, and the map has its [height] back, which on a small screen the keyboard took near all of, results and buttons with
     * it; and they stay so, with no keyboard coming back for the field.
     */
    private fun sendSearch(field: SemanticsMatcher, height: Float) {
        withScreen { compose.onNode(field).performImeAction() }
        var since = 0L
        waitFor("the search field lets go of the focus, the keyboard goes and the map is $height high again, for $STEADY_MS ms") {
            val back = compose.onAllNodes(field and isFocused()).fetchSemanticsNodes().isEmpty() && !keyboardShown() && bounds(mapShown()).height == height
            val now = System.currentTimeMillis()
            since = when {
                !back -> 0L
                since == 0L -> now
                else -> since
            }
            back && now - since >= STEADY_MS
        }
    }

    /**
     * Waits for the keyboard a focused field asks for, which comes in its own time: a Search key pressed before it has would
     * hide it before it shows, to show after all. A device with no keyboard on the screen shows none, and the test goes on.
     */
    private fun keyboardUp() {
        try {
            compose.waitUntil(KEYBOARD_MS) { keyboardShown() }
        } catch (_: ComposeTimeoutException) {
        }
    }

    // The results, their head at least, are whole below the map's buttons, which they would hide from touch and TalkBack,
    // and above the map's bottom, past which they would be cut off: the accessibility check sees either only on API 34 and up
    private fun assertResultsFit() {
        assertTrue("the results are whole under the map's buttons, in the map. ${screen()}", resultsFit())
    }

    // The results' head is their close button's: true when they are not shown
    private fun resultsFit(): Boolean {
        val results = compose.onAllNodes(resultsClose()).fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return true
        val buttons = bounds(hasContentDescription(string(R.string.map_edit)) and hasClickAction())
        return results.top >= buttons.bottom && results.bottom <= bounds(mapShown()).bottom
    }

    // The offer in the map's middle, its card whole, covers no control but its own: over one it would take its touches and
    // cover it for TalkBack, which the accessibility check sees only on API 34 and up, and then only where the part left
    // uncovered is the smaller. Only controls in the merged tree count: the unmerged one keeps what the map in front clears
    // from TalkBack, such as the input line under it. True with no offer in the middle
    private fun offerCoversNothing(): Boolean {
        val text = compose.onAllNodes(hasText(string(R.string.map_download_offered, HOST)), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        val card = text?.parent ?: return true
        fun inCard(node: SemanticsNode): Boolean = generateSequence(node) { it.parent }.any { it.id == card.id }
        val reachable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.id }.toSet()
        return compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            .none { it.id in reachable && !inCard(it) && it.boundsInRoot.overlaps(card.boundsInRoot) }
    }

    /**
     * The map in front covers the [input] line and the [quickBar], which was at [quickBarAt]: neither is read, and the
     * map reaches from above them down to the [connectionBar], which it leaves shown.
     */
    private fun assertCovered(input: SemanticsMatcher, quickBar: SemanticsMatcher, quickBarAt: Rect, connectionBar: SemanticsMatcher) {
        for ((name, matcher) in listOf("the input line" to input, "the quick bar" to quickBar)) {
            assertTrue("$name is not read. ${screen()}", compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())
        }
        compose.onNode(connectionBar).assertIsDisplayed()
        val map = bounds(mapShown())
        val connectionTop = bounds(connectionBar).top
        assertTrue("the map, at $map, reaches past the quick bar's foot, ${quickBarAt.bottom}. ${screen()}", map.bottom >= quickBarAt.bottom - EDGE_PX)
        assertTrue("the map, at $map, ends above the connection bar, at $connectionTop. ${screen()}", map.bottom <= connectionTop + EDGE_PX)
    }

    private fun resultsClose() = hasContentDescription(string(R.string.app_map_results_close)) and hasClickAction()

    private fun mapShown() = hasContentDescription(string(R.string.map_description)) or hasContentDescription(string(R.string.map_not_drawn))

    /** The last [type] the UI sent before it went quiet, read while the test waits on Compose: see [read]. */
    private fun lastRequest(type: String, what: String = "the UI asks for the map", readPast: Boolean = false): JSONObject = read(type, what) { session.receiveLast(type, readPast = readPast) }

    /** The next [type] the UI sends, read while the test waits on Compose: see [read]. */
    private fun request(type: String, what: String): JSONObject = read(type, what) { session.receive(type) }

    /**
     * What [receive] reads of the UI's [type] requests, read as [whileComposing] does. When the UI sends none, the read
     * times out as the socket is quiet; as the UI was composing all that time, the UI did not send it, which the failure
     * says as [what] did not happen, with what the UI sent instead.
     */
    private fun read(type: String, what: String, receive: () -> JSONObject): JSONObject {
        val before = session.received.size
        val start = System.currentTimeMillis()
        try {
            return whileComposing(what, receive)
        } catch (e: AssertionError) {
            val quiet = e.cause?.cause.let { it is IOException && it !is EOFException }
            if (!quiet) throw e
            val sent = session.received.drop(before).map { it.optString("t") }
            throw AssertionError("$what: the UI sent no $type in the ${System.currentTimeMillis() - start} ms it composed, only $sent")
        }
    }

    /** Fails if the UI sends a [type] for a while the test waits on Compose: see [whileComposing]. */
    private fun noRequest(type: String) = whileComposing("the UI sends no $type") { session.assertNoRequest(type, QUIET_MS) }

    /**
     * What [read] reads from the engine's socket, read on another thread while this one waits on Compose; [what] it shows,
     * which a failure says. The test's clock lets the UI compose, and so make a request it owes a change of state, only while
     * the test waits on Compose: a read alone would wait out the socket's timeout for a request the UI could not make yet, or
     * see none that it would.
     */
    private fun <T> whileComposing(what: String, read: () -> T): T {
        val result = CompletableFuture.supplyAsync { read() }
        waitFor("$what: the socket read ends", READ_MS) { result.isDone }
        return try {
            result.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw if (cause is AssertionError) AssertionError("$what: ${cause.message}", cause) else cause
        }
    }

    /** Waits for [condition]; a failure says [what] did not happen, and what is on the screen instead: see [screen]. */
    private fun waitFor(what: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what: not within $timeoutMs ms. ${screen()}", e)
        }
    }

    /** Runs [action]; a failure of it, such as its accessibility checks', says what is on the screen too: see [screen]. */
    private fun <T> withScreen(action: () -> T): T = try {
        action()
    } catch (e: Throwable) {
        throw AssertionError("${e.message}\n${screen()}", e)
    }

    private fun checkAccessibility() = withScreen {
        assertTrue("the offer covers no other control", offerCoversNothing())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    /** What is on the screen, for a failure to say: the window and its keyboard, the map's bounds, what has the focus, and every node. */
    private fun screen(): String {
        val view = compose.activity.window.decorView
        val window = compose.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(view)
            "window ${view.width}x${view.height}, keyboard shown ${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
                "its insets ${insets?.getInsets(WindowInsetsCompat.Type.ime())}"
        }
        val map = runCatching { compose.onAllNodes(mapShown()).fetchSemanticsNodes().map { it.boundsInRoot } }.getOrElse { "$it" }
        // First, as a long tree's end can be cut off: the accessibility check's bounds are these, less what covers them
        val targets = runCatching {
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)
                "$label at ${node.touchBoundsInRoot}"
            }
        }.getOrElse { "$it" }
        val focused = runCatching { compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { it.config } }.getOrElse { "$it" }
        val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "$it" }
        return "On the screen: touch targets $targets; $window; the map at $map; focused $focused; the tree:\n$tree"
    }

    private fun keyboardShown(): Boolean {
        val view = compose.activity.window.decorView
        return compose.runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    }

    // Opens the map in front, and returns the size it is asked at, once the engine has drawn it
    private fun openMap(): Pair<Double, Double> {
        activate(button)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(string(R.string.map_not_drawn))).fetchSemanticsNodes().isNotEmpty() }
        val size = lastRequest("setAppMapSize")
        val w = size.getInt("w")
        val h = size.getInt("h")
        session.send(appMap(w, h, scale = size.getDouble("scale")))
        return w.toDouble() to h.toDouble()
    }

    private fun rooms(request: JSONObject, text: String, found: List<Triple<Int, String, String>>, more: Boolean = false): JSONObject {
        val list = JSONArray(found.map { (id, name, area) -> JSONObject().put("id", id).put("name", name).put("area", area) })
        return JSONObject().put("t", "rooms").put("text", text).put("rooms", list).put("more", more).put("re", request.get("id"))
    }

    private fun mapDownload(
        state: String = "none",
        offered: Boolean = true,
        host: String = HOST,
        label: String = "",
        value: Int = 0,
        maximum: Int = 0,
        cancelable: Boolean = false,
        error: String = "",
    ): JSONObject = JSONObject().put("t", "mapDownload").put("offered", offered).put("host", host).put("state", state).put("label", label)
        .put("value", value).put("maximum", maximum).put("cancelable", cancelable).put("error", error)

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

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        checkAccessibility()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-app-map"

        // The host the game offers its map from
        const val HOST = "maps.example.org"

        // A frame's pixels a touch may land from where it was aimed, rounding to the screen's
        const val POINTS = 1.5

        // Pixels in from the console's bottom right corner, clear of the frame's edge, which may round off a pixel
        const val BACKGROUND_INSET = 8
        const val QUIET_MS = 500
        const val WAIT_MS = 10_000L

        // A read of the engine's socket: a receive's 10 s, a frame that comes just before their end, and the quiet that
        // ends a receiveLast
        const val READ_MS = 30_000L

        // How long the keyboard a focused field asks for may take to show
        const val KEYBOARD_MS = 5_000L

        // How long the screen stays as a search leaves it before the test goes on: no keyboard comes back
        const val STEADY_MS = 1_000L

        // How far apart two edges that meet may be found, in pixels
        const val EDGE_PX = 1f

        const val TIMEOUT_MS = 240_000L
    }
}
