package org.mudlet.android.ui

import android.Manifest
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * What the tests of the app's map in the console's corner (docs/architecture.md, "The map in the corner") share: the
 * game screen of [screen] drawn on the device, against a fake engine with a game's lines in its console.
 */
abstract class AppMapCornerFixture(protected val screen: Screen) {
    class Screen(private val name: String, val widthDp: Float, val heightDp: Float, val fontScale: Float) {
        override fun toString() = name
    }

    protected val fake = FakeEngineRule()
    protected val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    protected lateinit var session: FakeEngine.Session

    // The map in the corner, read as the area and the player's room of the frame mapView lays out
    protected val corner: SemanticsMatcher get() = hasContentDescription(string(R.string.app_map_corner_room, AREA, ROOM)) and hasClickAction()

    // The map in front, which has the frame from the first
    protected val mapInFront: SemanticsMatcher get() = hasContentDescription(string(R.string.map_description))

    // A dp of the screen wanted, in the device's pixels
    protected var dp = 1f

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

    protected fun button(@StringRes label: Int) = hasContentDescription(string(label)) and hasClickAction()

    /** The map's panel, which shows only once the small button over the map is pressed. */
    protected fun openControls() {
        activate(button(R.string.map_controls_show))
        waitFor(button(R.string.map_controls_hide))
    }

    /** Where the one-line text [text] is drawn, not the console's whole width its line takes. */
    protected fun drawnText(text: String): Rect {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val layouts = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val at = node.boundsInRoot
        return Rect(at.left + layout.getLineLeft(0), at.top, at.left + layout.getLineRight(0), at.top + layout.size.height)
    }

    protected fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    protected fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitForNone(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    protected fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    protected fun activate(label: SemanticsMatcher) {
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
        internal val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        internal const val INSTANCE = "fake-app-map-corner"

        // A frame of the map behind the text, of any size: the corner shows the middle of whichever came
        internal const val FRAME_W = 400
        internal const val FRAME_H = 700

        // As EngineMessages.mapView names them
        internal const val AREA = "Castle"
        internal const val ROOM = "Room 1"

        // The stored names: players' apps have them
        internal const val APP_PREFS = "settings"
        internal const val MAP_IN_CORNER = "mapInCorner"

        // More than the screen holds, so the newest is at the console's bottom
        internal const val LINES = 60
        internal const val NEWEST_LINE = "the newest line"

        internal const val WAIT_MS = 10_000L
        internal const val TIMEOUT_MS = 240_000L

        internal val PHONE = Screen("412x915 dp", 412f, 915f, 1f)
    }
}

/**
 * The app's own map in the console's corner (docs/architecture.md, "The map in the corner") on the screens of [screen],
 * against a fake engine: where the console has room, the map at its top right, grown as a script's small mapper is,
 * its panel shown once the small button at the map's top right is pressed, none of it over the map, each button
 * touched within 48 dp, TalkBack reading the map after the console, then the button, then the panel, and the text of
 * the console's newest line clear of the map and its panel; none while a script's mapper, the map in
 * front or the map behind the text shows, a script's window is where it would be, or the game has no rooms; hidden and
 * shown again, which is kept; and a tap on it bringing the map to the front, which the screen's size does not change
 * and [AppMapCornerOnThePhoneTest] tests.
 */
@RunWith(Parameterized::class)
class AppMapCornerTest(screen: AppMapCornerFixture.Screen) : AppMapCornerFixture(screen) {
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

        val reach = 48 * dp - 1
        val toggle = compose.onNode(button(R.string.map_controls_show)).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$screen: the controls' button is touched within $toggle", toggle.width >= reach && toggle.height >= reach)
        assertEquals("$screen: the controls' button $toggle is not at the top of the map $drawn", drawn.top, toggle.top, 1f)
        assertEquals("$screen: the controls' button $toggle is not at the right of the map $drawn", drawn.right, toggle.right, 1f)
        assertTrue("$screen: the panel shows before it is asked for", compose.onAllNodes(button(R.string.map_zoom_out)).fetchSemanticsNodes().isEmpty())
        openControls()

        val panel = listOf(
            "zoom out" to button(R.string.map_zoom_out),
            "zoom in" to button(R.string.map_zoom_in),
            "centre on the player" to button(R.string.map_follow),
            "hide" to button(R.string.app_map_corner_hide),
            "area" to (hasText(string(R.string.map_area, AREA)) and hasClickAction()),
        )
        assertTrue("$screen: the map in the corner is not edited", compose.onAllNodes(button(R.string.map_edit)).fetchSemanticsNodes().isEmpty())
        val covering = ArrayList<Rect>()
        for ((name, button) in panel) {
            compose.onNode(button).performScrollTo()
            val touch = compose.onNode(button).fetchSemanticsNode().touchBoundsInRoot
            assertFalse("$screen: $name, touched within $touch, is over the map $drawn", touch.overlaps(drawn))
            assertTrue("$screen: $name is touched within $touch", touch.width >= reach && touch.height >= reach)
            covering.add(touch)
        }
        // What of the panel shows, as it scrolls: as wide as the map, or as three buttons where the map is narrower
        val shown = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(button(R.string.map_zoom_out))).fetchSemanticsNodes().minBy { it.size.width * it.size.height }.boundsInRoot
        assertTrue("$screen: the panel $shown is wider than the map $drawn", shown.width <= maxOf(drawn.width, MapCorner.PANEL_WIDTH_DP * dp) + 1)
        covering.add(shown)
        compose.onNode(hasText(NEWEST_LINE)).assertIsDisplayed()
        val newest = drawnText(NEWEST_LINE)
        for (rect in covering + drawn) assertFalse("$screen: the newest line $newest is under $rect", newest.overlaps(rect))

        // Where a script's mapper would be read: after the console, then its button and its panel. A panel taller than its room
        // scrolls, and TalkBack scrolls it to reach the rest: read at its top, then its end
        val expected = listOf("the title bar's map button" to button(R.string.app_map_show), "the newest line" to hasText(NEWEST_LINE), "the map in the corner" to corner, "the controls' button" to button(R.string.map_controls_hide)) + panel
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

    companion object {
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
