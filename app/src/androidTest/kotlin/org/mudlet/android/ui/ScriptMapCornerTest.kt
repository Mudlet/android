package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.theme.MudletTheme
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A script's small mapper (docs/architecture.md, "The map") on the screens of [screen], in the layout the app picks for
 * each, against a fake engine: the map drawn larger than its window; none of its buttons, its area and level or its map
 * info over the map, each button touched within 48 dp, and TalkBack reading the map, then them; and the text of the
 * console's newest line clear of the map and its panel, which cover the console as Mudlet's windows do.
 */
@RunWith(Parameterized::class)
class ScriptMapCornerTest(private val screen: Screen) {
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

    // A dp of the screen wanted, in the device's pixels
    private var dp = 1f

    @Before
    fun openTheGameScreen() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        val metrics = compose.activity.resources.displayMetrics
        // The device's dp scaled so that the screen wanted fits on it
        val scale = minOf(metrics.widthPixels / metrics.density / screen.widthDp, metrics.heightPixels / metrics.density / screen.heightDp)
        dp = metrics.density * scale
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale * screen.fontScale)) {
                    // The device's bars are not at the edges of the screen wanted, so they take none of it. The outer Box
                    // loosens the window's exact size, which a size of its own would otherwise be held to
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
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun theMapGrowsAndNothingOfItsPanelOrTheNewestLineIsUnderIt() {
        // An eighth of Mudlet's main window across, at its top right: a few dp on a phone
        session.windows(listOf(FakeEngine.window("mapper", kind = "mapper", x = 700, y = 0, w = MAP_W, h = MAP_H)), full = true)
        val info = JSONObject("""{"lines":[{"text":"$INFO","bold":false,"italic":false,"color":"#000080"}],"bg":"#969696","bgAlpha":120}""")
        // Off the player, so the button that centres the map on them shows, on a level of two
        session.send(mapFrame(mapView(MAP_W, MAP_H, following = false, levels = listOf(0, 1))).put("info", info))
        val map = hasContentDescription(string(R.string.map_description))
        val follow = button(R.string.map_follow)
        waitFor(map)
        waitFor(follow)
        compose.onRoot().tryPerformAccessibilityChecks()

        val console = bounds(hasScrollToIndexAction())
        val drawn = bounds(map)
        assertTrue("$screen: the map $drawn did not grow from an eighth of the console $console", drawn.width >= console.width / 8 * 1.5f)
        assertTrue("$screen: the map $drawn is wider than it grows", drawn.width <= maxOf(console.width * 0.4f, 120 * dp) + 1 && drawn.width <= 320 * dp + 1)

        val panel = listOf(
            "zoom out" to button(R.string.map_zoom_out),
            "zoom in" to button(R.string.map_zoom_in),
            "edit" to button(R.string.map_edit),
            "centre on the player" to follow,
            "area" to (hasText(string(R.string.map_area, AREA)) and hasClickAction()),
            "level down" to button(R.string.map_level_down),
            "level up" to button(R.string.map_level_up),
        )
        val reach = 48 * dp - 1
        val covering = ArrayList<Rect>()
        for ((name, button) in panel) {
            compose.onNode(button).performScrollTo()
            val touch = compose.onNode(button).fetchSemanticsNode().touchBoundsInRoot
            assertFalse("$screen: $name, touched within $touch, is over the map $drawn", touch.overlaps(drawn))
            assertTrue("$screen: $name is touched within $touch", touch.width >= reach && touch.height >= reach)
            covering.add(touch)
        }
        val infoBox = hasContentDescription(string(R.string.map_info, INFO))
        compose.onNode(infoBox).performScrollTo()
        assertFalse("$screen: the map info ${bounds(infoBox)} is over the map $drawn", bounds(infoBox).overlaps(drawn))

        // What of the panel shows, as it scrolls
        covering.add(compose.onAllNodes(hasScrollAction() and hasAnyDescendant(follow)).fetchSemanticsNodes().minBy { it.size.width * it.size.height }.boundsInRoot)
        compose.onNode(hasText(NEWEST_LINE)).assertIsDisplayed()
        val newest = drawnText(NEWEST_LINE)
        for (rect in covering + drawn) assertFalse("$screen: the newest line $newest is under $rect", newest.overlaps(rect))

        // A panel taller than its room scrolls, and TalkBack scrolls it to reach the rest: read at its top, then its end
        val expected = listOf("the map" to map) + panel + ("the map info" to infoBox)
        val readings = listOf(panel.first().second, infoBox).map { end ->
            compose.onNode(end).performScrollTo()
            val reading = TalkBackReading(compose)
            reading.assertInOrder(expected.filter { reading.reads(it.second) })
            reading
        }
        val unread = expected.filter { (_, matcher) -> readings.none { it.reads(matcher) } }.map { it.first }
        assertTrue("$screen: $unread not read, at the panel's top or its end: TalkBack reads ${readings.map { it.read }}", unread.isEmpty())
        readings.first().assertEveryControlRead(readings.drop(1))
    }

    private fun button(@StringRes label: Int) = hasContentDescription(string(label)) and hasClickAction()

    private fun mapFrame(view: JSONObject): JSONObject = JSONObject().put("t", "map")
        .put("png", Base64.getEncoder().encodeToString(png())).put("w", MAP_W).put("h", MAP_H).put("viewOnly", true).put("view", view)

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(MAP_W, MAP_H, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

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

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        waitFor(label)
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    companion object {
        private val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)
        private const val INSTANCE = "fake-map-corner"
        private const val MAP_W = 100
        private const val MAP_H = 100

        // As EngineMessages.mapView names it
        private const val AREA = "Castle"
        private const val INFO = "Great Hall / 7"

        // More than the screen holds, so the newest is at the console's bottom
        private const val LINES = 60
        private const val NEWEST_LINE = "the newest line"

        private const val WAIT_MS = 10_000L
        private const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            Screen("320x640 dp", 320f, 640f, 1f),
            Screen("320x640 dp, text twice as large", 320f, 640f, 2f),
            Screen("640x320 dp", 640f, 320f, 1f),
            Screen("640x320 dp, text twice as large", 640f, 320f, 2f),
            Screen("412x915 dp", 412f, 915f, 1f),
        )
    }
}
