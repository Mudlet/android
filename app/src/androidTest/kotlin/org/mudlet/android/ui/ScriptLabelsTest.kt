package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.LayoutMode
import org.mudlet.android.protocol.POINTER_LEFT
import org.mudlet.android.protocol.POINTER_RIGHT
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The labels a profile's scripts draw (docs/phase-f.md, "Labels" and "Gauges"), against a fake engine: a label's text
 * at its scaled place, a clickable label as a button whose tap reaches the engine as `pointer` events, its 48 dp touch
 * bounds, a long press as the right button with the tool tip, a drag, a gauge read as its progress, a link's command,
 * and the images the engine is asked for.
 */
@RunWith(AndroidJUnit4::class)
class ScriptLabelsTest {
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
        // A new engine instance for each test, so no window or label of an earlier test is left
        val first = fake.engine.nextSession()
        fake.engine.instance = "$INSTANCE-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.line(1, "the main console")
        session.synced(1)
        activate(hasText(string(R.string.profiles_open_game)))
        compose.onNodeWithText("the main console").assertIsDisplayed()
        // Mudlet's 800 by 600 main window is all text, so the console's area is Mudlet's main window, scaled
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.FITTED }
        session.mainArea(800, 600, 100, 40, 0, 0, 800, 600)
    }

    @After
    fun automaticLayout() {
        compose.runOnIdle { ScriptWindowsClient.layoutMode = LayoutMode.AUTO }
    }

    @Test
    fun aLabelsTextIsDrawnWhereTheScriptPutItAndReadAfterTheConsole() {
        session.windows(listOf(label("status", x = 400, y = 300, w = 400, h = 300)), full = true)
        session.label("status", text = "<b>HP</b> full", styleSheet = "qproperty-alignment: 'AlignLeft | AlignTop'")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("HP full")).fetchSemanticsNodes().isNotEmpty() }

        // The bottom right quarter of Mudlet's main window is the console area's
        val area = consoleArea()
        val text = bounds(hasText("HP full"))
        assertNear("left", area.left + area.width / 2, text.left)
        assertNear("top", area.top + area.height / 2, text.top)
        assertNear("width", area.width / 2, text.width)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aClickableLabelIsAButtonWhoseClickTheEngineGetsAsATap() {
        session.windows(listOf(label("go", x = 0, y = 0, w = 200, h = 100)), full = true)
        session.label("go", text = "North", accessibleName = "Go north", accessibleButton = true)
        val button = hasContentDescription("Go north") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNode(button).assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f))

        // TalkBack's double tap clicks the label's middle, in its own pixels
        activate(button)
        val tap = tap()
        assertEquals(listOf("enter", "press", "release"), tap.map { it.getString("type") })
        for (event in tap) {
            assertEquals("go", event.getString("window"))
            assertEquals(100.0, event.getDouble("x"), 0.0)
            assertEquals(50.0, event.getDouble("y"), 0.0)
        }
        assertEquals(POINTER_LEFT, tap[1].getInt("button"))
        assertEquals(POINTER_LEFT, tap[1].getInt("buttons"))
        assertEquals(0, tap[2].optInt("buttons"))
    }

    @Test
    fun aSmallClickableLabelIsTouchedWithin48dpAroundItAndTheEngineGetsAPointInsideIt() {
        session.windows(listOf(label("tiny", x = 395, y = 295, w = 10, h = 10)), full = true)
        session.label("tiny", accessibleName = "Tiny", accessibleButton = true, styleSheet = "background-color: red")
        val button = hasContentDescription("Tiny") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }

        val touch = bounds(button)
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue("touch width ${touch.width} < $min", touch.width >= min - 1)
        assertTrue("touch height ${touch.height} < $min", touch.height >= min - 1)
        compose.onRoot().tryPerformAccessibilityChecks()

        // A finger at the edge of the touch bounds, outside the label, still reaches it at a point inside it
        compose.onNode(button).performTouchInput { click(topLeft) }
        val tap = tap()
        assertEquals(listOf("press", "release"), tap.map { it.getString("type") }.filter { it != "enter" })
        for (event in tap) {
            assertTrue("x ${event.getDouble("x")}", event.getDouble("x") in 0.0..9.0)
            assertTrue("y ${event.getDouble("y")}", event.getDouble("y") in 0.0..9.0)
        }
    }

    @Test
    fun aLongPressIsTheRightButtonAndShowsAToolTipSetAfterTheLabelWasDrawn() {
        session.windows(listOf(label("menu", w = 200, h = 100)), full = true)
        session.label("menu", accessibleButton = true, styleSheet = "background-color: navy")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("menu") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        // The tool tip names the button once it comes
        session.label("menu", toolTip = "Open the menu", accessibleButton = true, styleSheet = "background-color: navy")
        val button = hasContentDescription("Open the menu") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(button).performTouchInput { longClick(center) }
        val press = tap()
        assertEquals(listOf("press", "release"), press.map { it.getString("type") }.filter { it != "enter" })
        assertEquals(POINTER_RIGHT, press.single { it.getString("type") == "press" }.getInt("button"))
        assertEquals(POINTER_RIGHT, press.last().getInt("button"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Open the menu")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aDragAcrossALabelHoldsTheLeftButtonAsItMoves() {
        session.windows(listOf(label("volume", w = 200, h = 100)), full = true)
        session.label("volume", accessibleName = "Volume", accessibleButton = true, styleSheet = "background-color: teal")
        val slider = hasContentDescription("Volume") and hasClickAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(slider).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(slider).performTouchInput {
            down(centerLeft + Offset(10f, 0f))
            moveTo(center)
            moveTo(centerRight - Offset(10f, 0f))
            up()
        }
        val drag = tap().filter { it.getString("type") != "enter" }
        assertEquals("press", drag.first().getString("type"))
        assertEquals(POINTER_LEFT, drag.first().getInt("button"))
        val moves = drag.drop(1).dropLast(1)
        assertTrue("no move in ${drag.map { it.getString("type") }}", moves.isNotEmpty())
        for (move in moves) {
            assertEquals("move", move.getString("type"))
            assertEquals(POINTER_LEFT, move.getInt("buttons"))
        }
        // From near the label's left edge to near its right, in its own 200 pixels
        assertTrue("press x ${drag.first().getDouble("x")}", drag.first().getDouble("x") < 50.0)
        assertTrue("release x ${drag.last().getDouble("x")}", drag.last().getDouble("x") > 150.0)
        assertEquals(POINTER_LEFT, drag.last().getInt("button"))
    }

    @Test
    fun aGaugeIsReadAsItsProgressWithItsTextAsItsState() {
        session.windows(
            listOf(label("hp_back", w = 200, h = 20), label("hp_front", w = 50, h = 20), label("hp_text", w = 200, h = 20)),
            full = true,
        )
        session.label("hp_back", styleSheet = "background-color: #400000")
        session.label("hp_front", styleSheet = "background-color: red")
        session.label("hp_text", text = "HP 25/100")
        val gauge = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(25f, 0f..100f))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(gauge and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "HP 25/100"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(gauge).assert(hasContentDescription("hp"))
        // The text label is the gauge's state, not a node of its own
        assertTrue(compose.onAllNodes(hasText("HP 25/100")).fetchSemanticsNodes().isEmpty())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aLinkInALabelIsAnActionThatHasTheEngineRunItsCommand() {
        session.windows(listOf(label("nav", w = 300, h = 40)), full = true)
        session.label("nav", text = """Go <a href="send:north">north</a>""", accessibleName = "Directions")
        val follow = string(R.string.label_follow_link, "north")
        val nav = hasContentDescription("Directions")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(nav).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()

        val action = compose.onNode(nav).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == follow }
        compose.runOnIdle { action.action() }
        // Mudlet's send: link expands aliases as typed input does
        assertEquals("expandAlias(\"north\")", session.receive("runLua").getString("code"))
    }

    @Test
    fun anImageLabelAsksTheEngineForItsImageOnce() {
        session.windows(listOf(label("key", w = 100, h = 100)), full = true)
        session.label("key", image = "/data/key.png", accessibleName = "Map key")
        val request = session.receive("getImage")
        assertEquals("/data/key.png", request.getString("path"))
        assertTrue(!request.has("w") && !request.has("frame"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("Map key")).fetchSemanticsNodes().isNotEmpty() }

        session.image("/data/key.png", png(8, 4), 8, 4, request.get("id"))
        compose.waitUntil(WAIT_MS) { ScriptWindowsClient.images[ScriptWindowsClient.ImageRequest("/data/key.png")]?.width == 8 }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    // The pointer events of one tap, up to its release
    private fun tap(): List<JSONObject> {
        val events = ArrayList<JSONObject>()
        do {
            events.add(session.receive("pointer"))
        } while (events.last().getString("type") != "release")
        return events
    }

    private fun label(name: String, x: Int = 0, y: Int = 0, w: Int, h: Int) = FakeEngine.window(name, kind = "label", x = x, y = y, w = w, h = h)

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // The main console: the one list that is not in a script window, which the fitted layout gives all of Mudlet's
    // main window here
    private fun consoleArea(): Rect = bounds(hasScrollToIndexAction() and !hasAnyAncestor(hasContentDescription(string(R.string.window_description, ""), substring = true)))

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun assertNear(what: String, expected: Float, actual: Float) = assertTrue("$what: $actual, not $expected", abs(expected - actual) <= PIXELS)

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNode(label and hasClickAction()).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        const val INSTANCE = "fake-labels"

        // Rounding to whole pixels, and the text's own padding
        const val PIXELS = 3f
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
