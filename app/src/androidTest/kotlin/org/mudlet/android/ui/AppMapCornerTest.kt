package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.rule.GrantPermissionRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.MapCorner
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.theme.MudletTheme

/**
 * The app's own map in the console's corner (docs/architecture.md, "The map in the corner") on the screens of [screen],
 * against a fake engine: where the console has room, the map at its top right, grown as a script's small mapper is,
 * none of its panel over it, each button touched within 48 dp, TalkBack reading it after the console, then its panel,
 * and the text of the console's newest line clear of the map and its panel; none while a script's mapper, the map in
 * front or the map behind the text shows, a script's window is where it would be, or the game has no rooms; hidden and
 * shown again, which is kept; and a tap on it bringing the map to the front. The tests of what the screen's size does not change run on the phone's alone.
 */
@RunWith(Parameterized::class)
class AppMapCornerTest(private val screen: Screen) {
    class Screen(private val name: String, val widthDp: Float, val heightDp: Float, val fontScale: Float) {
        override fun toString() = name
    }

    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    // The map in the corner, read as the area and the player's room of the frame mapView lays out
    private val corner: SemanticsMatcher get() = hasContentDescription(string(R.string.app_map_corner_room, AREA, ROOM)) and hasClickAction()

    // The map in front, which has the frame from the first
    private val mapInFront: SemanticsMatcher get() = hasContentDescription(string(R.string.map_description))

    // A dp of the screen wanted, in the device's pixels
    private var dp = 1f

    @Before
    fun openTheGameScreen() {
        compose.runOnIdle {
            Settings.mapBehindText.reset()
            Settings.mapInCorner.reset()
        }
        val metrics = compose.activity.resources.displayMetrics
        // The device's dp scaled so that the screen wanted fits on it
        val scale = minOf(metrics.widthPixels / metrics.density / screen.widthDp, metrics.heightPixels / metrics.density / screen.heightDp)
        dp = metrics.density * scale
        if (Build.VERSION.SDK_INT >= 34) {
            // As ScriptMapCornerTest's: scaled down, a 48 dp target is fewer of the device's dp than the touch target
            // check allows, so the test checks each of the panel's targets in the screen's dp instead
            val validator = AccessibilityValidator().setRunChecksFromRootView(true)
            compose.enableAccessibilityChecks(if (scale < 1) validator.setSuppressingResultMatcher(TouchTargetOfAScreenScaledDown) else validator)
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale * screen.fontScale)) {
                    // As ScriptMapCornerTest's: the device's bars take none of the screen wanted
                    Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing)) {
                        Box(Modifier.size(screen.widthDp.dp, screen.heightDp.dp)) {
                            MudletTheme { AppNavigation(onKillUi = {}) }
                        }
                    }
                }
            }
        }
        // A new engine instance for each test, so no map of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        for (seq in 1..LINES) session.line(seq.toLong(), if (seq == LINES) NEWEST_LINE else "line $seq")
        session.synced(LINES.toLong())
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(hasText(NEWEST_LINE))
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
    }

    @After
    fun defaults() {
        compose.runOnIdle {
            ScriptWindowsClient.layoutMode = LayoutMode.AUTO
            Settings.mapBehindText.reset()
            Settings.mapInCorner.reset()
        }
    }

    @Test
    fun theMapIsInTheCornerWhereThereIsRoomAndNothingOfItsPanelOrTheNewestLineIsUnderIt() {
        // Off the player, so the button that centres the map on them shows
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, following = false)))
        compose.waitUntil(WAIT_MS) { EngineClient.front.appMap.map != null }
        val console = bounds(hasScrollToIndexAction())
        // Room for the least of the map and a row of its buttons under it
        val room = console.width >= MapCorner.MIN_DP * dp && console.height >= (MapCorner.MIN_DP + MapCorner.ROW_DP) * dp
        if (screen.heightDp > screen.widthDp) assertTrue("$screen: no room for the map in the console $console", room)
        if (!room) {
            compose.waitForIdle()
            assertTrue("$screen: a map in the corner of the console $console, with no room for it", compose.onAllNodes(corner).fetchSemanticsNodes().isEmpty())
            return
        }
        waitFor(corner)
        compose.onRoot().tryPerformAccessibilityChecks()

        val drawn = bounds(corner)
        assertEquals("$screen: the map $drawn is square", drawn.width, drawn.height, 1f)
        assertTrue("$screen: the map $drawn is smaller than it starts", drawn.width >= MapCorner.MIN_DP * dp - 1)
        val most = maxOf(minOf(console.width, console.height) * MapCorner.FRACTION, MapCorner.MIN_DP * dp)
        assertTrue("$screen: the map $drawn is larger than it grows in $console", drawn.width <= most + 1 && drawn.width <= MapCorner.MAX_DP * dp + 1)
        assertEquals("$screen: the map $drawn is at the top of $console", console.top, drawn.top, 1f)
        assertEquals("$screen: the map $drawn is at the right of $console", console.right, drawn.right, 1f)

        val panel = listOf(
            "zoom out" to button(R.string.map_zoom_out),
            "zoom in" to button(R.string.map_zoom_in),
            "centre on the player" to button(R.string.map_follow),
            "hide" to button(R.string.app_map_corner_hide),
            "area" to (hasText(string(R.string.map_area, AREA)) and hasClickAction()),
        )
        assertTrue("$screen: the map in the corner is not edited", compose.onAllNodes(button(R.string.map_edit)).fetchSemanticsNodes().isEmpty())
        val reach = 48 * dp - 1
        val covering = ArrayList<Rect>()
        for ((name, button) in panel) {
            compose.onNode(button).performScrollTo()
            val touch = compose.onNode(button).fetchSemanticsNode().touchBoundsInRoot
            assertFalse("$screen: $name, touched within $touch, is over the map $drawn", touch.overlaps(drawn))
            assertTrue("$screen: $name is touched within $touch", touch.width >= reach && touch.height >= reach)
            covering.add(touch)
        }
        // What of the panel shows, as it scrolls
        covering.add(compose.onAllNodes(hasScrollAction() and hasAnyDescendant(button(R.string.map_zoom_out))).fetchSemanticsNodes().minBy { it.size.width * it.size.height }.boundsInRoot)
        compose.onNode(hasText(NEWEST_LINE)).assertIsDisplayed()
        val newest = drawnText(NEWEST_LINE)
        for (rect in covering + drawn) assertFalse("$screen: the newest line $newest is under $rect", newest.overlaps(rect))

        // Where a script's mapper would be read: after the console, then its panel. A panel taller than its room
        // scrolls, and TalkBack scrolls it to reach the rest: read at its top, then its end
        val expected = listOf("the title bar's map button" to button(R.string.app_map_show), "the newest line" to hasText(NEWEST_LINE), "the map in the corner" to corner) + panel
        val readings = listOf(panel.first().second, panel.last().second).map { end ->
            compose.onNode(end).performScrollTo()
            val reading = TalkBackReading(compose)
            reading.assertInOrder(expected.filter { reading.reads(it.second) })
            reading
        }
        val unread = expected.filter { (_, matcher) -> readings.none { it.reads(matcher) } }.map { it.first }
        assertTrue("$screen: $unread not read, at the panel's top or its end: TalkBack reads ${readings.map { it.read }}", unread.isEmpty())
        readings.first().assertEveryControlRead(readings.drop(1))
    }

    @Test
    fun noMapIsInTheCornerWhileAnotherMapOrAScriptsWindowIsThereOrTheGameHasNone() {
        assumeTrue("once, on the phone", screen === PHONE)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        waitFor(corner)

        // A Geyser.Mapper shows the map: one map on the screen
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 700, y = 0, w = 100, h = 100)), full = true)
        waitFor(hasContentDescription(string(R.string.map_not_drawn)))
        waitForNone(corner)
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 700, y = 0, w = 100, h = 100).put("shown", false)), full = true)
        waitFor(corner)

        // A script's label where the map would be, which it would hide
        session.windows(listOf(FakeEngine.window("gauge", kind = "label", x = 700, y = 0, w = 100, h = 20)), full = true)
        waitForNone(corner)
        session.windows(listOf(FakeEngine.window("gauge", kind = "label", x = 700, y = 0, w = 100, h = 20).put("shown", false)), full = true)
        waitFor(corner)

        // The map in front, from the title bar's button
        activate(button(R.string.app_map_show))
        waitFor(mapInFront)
        waitForNone(corner)
        activate(button(R.string.app_map_show))
        waitFor(corner)

        // The map behind the text
        compose.runOnIdle { Settings.mapBehindText.set(true) }
        waitForNone(corner)
        compose.runOnIdle { Settings.mapBehindText.reset() }
        waitFor(corner)

        // A profile whose map has no rooms
        session.send(JSONObject().put("t", "appMap").put("png", "").put("w", 0).put("h", 0).put("viewOnly", true))
        waitForNone(corner)
    }

    @Test
    fun theMapIsHiddenAndShownAgainAndThatIsKept() {
        assumeTrue("once, on the phone", screen === PHONE)
        val prefs = compose.activity.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        waitFor(corner)

        activate(button(R.string.app_map_corner_hide))
        waitForNone(corner)
        compose.runOnIdle { assertFalse(Settings.mapInCorner.value) }
        assertFalse("hidden is kept as players' apps will have it", prefs.getBoolean(MAP_IN_CORNER, true))
        // Undone from the message that says where to show it again
        waitFor(hasText(string(R.string.app_map_corner_hidden, string(R.string.settings_map_in_corner))))
        activate(hasText(string(R.string.app_map_corner_undo)))
        waitFor(corner)
        assertTrue("shown is kept", prefs.getBoolean(MAP_IN_CORNER, false))

        // Hidden again, the settings' switch shows it
        activate(button(R.string.app_map_corner_hide))
        waitForNone(corner)
        activate(hasContentDescription(string(R.string.settings_title)))
        compose.scrollSettingsHubTo(hasText(string(R.string.settings_appearance)))
        activate(hasText(string(R.string.settings_appearance)))
        val row = hasText(string(R.string.settings_map_in_corner)) and hasClickAction()
        compose.onNode(row).performScrollTo()
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        activate(row)
        compose.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertTrue("shown from the settings is kept", prefs.getBoolean(MAP_IN_CORNER, false))
        activate(hasContentDescription(string(R.string.navigate_back)))
        activate(hasContentDescription(string(R.string.navigate_back)))
        waitFor(corner)
    }

    @Test
    fun theFrameIsDrawnInTheCornerAndATapOnItBringsTheMapToTheFrontButOneOnItsButtonsDoesNot() {
        assumeTrue("once, on the phone", screen === PHONE)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        waitFor(corner)
        // The frame is white all over, the corner's backing the console's black
        compose.waitForIdle()
        val drawn = compose.onNode(corner).captureToImage().toPixelMap()
        assertEquals(Color.White, drawn[drawn.width / 2, drawn.height / 2])

        compose.onNode(button(R.string.map_zoom_in)).performClick()
        compose.waitForIdle()
        assertTrue("a zoom button brought the map to the front", compose.onAllNodes(mapInFront).fetchSemanticsNodes().isEmpty())

        compose.onNode(corner).performTouchInput { click(center) }
        waitFor(mapInFront)
        waitForNone(corner)

        // And as TalkBack's double tap
        activate(button(R.string.app_map_show))
        waitFor(corner)
        activate(corner)
        waitFor(mapInFront)
    }

    private fun button(@StringRes label: Int) = hasContentDescription(string(label)) and hasClickAction()

    /** Where the one-line text [text] is drawn, not the console's whole width its line takes. */
    private fun drawnText(text: String): Rect {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val layouts = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val at = node.boundsInRoot
        return Rect(at.left + layout.getLineLeft(0), at.top, at.left + layout.getLineRight(0), at.top + layout.size.height)
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Any TouchTargetSizeCheck result: see [openTheGameScreen]. */
    private object TouchTargetOfAScreenScaledDown : BaseMatcher<Any>() {
        override fun matches(item: Any?): Boolean = (item as? AccessibilityViewCheckResult)?.sourceCheckClass == TouchTargetSizeCheck::class.java

        override fun describeTo(description: Description) {
            description.appendText("a touch target of a screen drawn smaller than the device's")
        }
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-app-map-corner"

        // A frame of the map behind the text, of any size: the corner shows the middle of whichever came
        private const val FRAME_W = 400
        private const val FRAME_H = 700

        // As EngineMessages.mapView names them
        private const val AREA = "Castle"
        private const val ROOM = "Room 1"

        // The stored names: players' apps have them
        private const val APP_PREFS = "settings"
        private const val MAP_IN_CORNER = "mapInCorner"

        // More than the screen holds, so the newest is at the console's bottom
        private const val LINES = 60
        private const val NEWEST_LINE = "the newest line"

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        private val PHONE = Screen("412x915 dp", 412f, 915f, 1f)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            Screen("320x640 dp", 320f, 640f, 1f),
            Screen("320x640 dp, text twice as large", 320f, 640f, 2f),
            Screen("640x320 dp", 640f, 320f, 1f),
            Screen("640x320 dp, text twice as large", 640f, 320f, 2f),
            PHONE,
        )
    }
}
