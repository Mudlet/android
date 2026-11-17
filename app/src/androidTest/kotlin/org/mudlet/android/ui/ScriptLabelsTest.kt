package org.mudlet.android.ui

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
import org.mudlet.android.protocol.Profile
import org.mudlet.android.protocol.RUN_RGB
import org.mudlet.android.ui.game.Announcer
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The labels a profile's scripts draw (docs/phase-f.md, "Labels" and "Gauges"), against a fake engine: a label's text
 * at its scaled place, a clickable label as a button whose tap reaches the engine as `pointer` events, its 48 dp touch
 * bounds, a long press as the right button with the tool tip, a drag, a gauge read as its progress and its live text
 * label's new text said, the accessible value of a gauge's back label or of a label as its progress, a live label with
 * one as its new text said, a live label as one live node, a link followed by the engine, by its action or a tap, with
 * its href as Qt reads it, a `prompt:` one giving the input line the focus, but a web link opened in the browser, a
 * click-through label's links neither tapped nor actions, also once a label already drawn is made click-through, and
 * both again once it no longer is, a label's own colour drawn only where its sheet draws no box of its own, and the
 * images the engine is asked for.
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

    // The game screen's one text field
    private val input = hasSetTextAction()

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
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("the main console")).fetchSemanticsNodes().isNotEmpty() }
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
        val before = compose.runOnIdle { Announcer.current }
        session.label("hp_text", text = "HP 25/100", liveRegion = "assertive")
        val gauge = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(25f, 0f..100f))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(gauge and hasStateDescription("HP 25/100")).fetchSemanticsNodes().isNotEmpty() }
        // The text it is first drawn with is not said, or a UI that attaches would read every live gauge
        compose.runOnIdle { assertSame("the first text drawn is not said", before, Announcer.current) }
        compose.onNode(gauge).assert(hasContentDescription("hp"))
        // The text label is the gauge's state, not a node of its own
        assertTrue(compose.onAllNodes(hasText("HP 25/100")).fetchSemanticsNodes().isEmpty())
        compose.onRoot().tryPerformAccessibilityChecks()

        // A live text label's new text is said, as on the desktop; the gauge's node is not live, as a live node is read
        // again at each change of its value too
        compose.onNode(gauge).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        session.label("hp_text", text = "HP 20/100", liveRegion = "assertive")
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == "HP 20/100" }
        val said = compose.runOnIdle { Announcer.current!! }
        assertTrue(said.assertive)
        // A new value alone is not said
        session.windows(listOf(label("hp_front", w = 100, h = 20)))
        val half = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(50f, 0f..100f))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(half and hasStateDescription("HP 20/100")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertSame("TalkBack is told: ${Announcer.current?.text}", said, Announcer.current) }
        // Nor the new text of a text label that is not live, though the back label is: it has no text to say
        session.label("hp_back", styleSheet = "background-color: #400000", liveRegion = "polite")
        session.label("hp_text", text = "HP 50/100")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(half and hasStateDescription("HP 50/100")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertSame("TalkBack is told: ${Announcer.current?.text}", said, Announcer.current) }
        // A polite one is said after what TalkBack is saying, not at once
        SystemClock.sleep(APART_MS)
        session.label("hp_text", text = "HP 10/100", liveRegion = "polite")
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == "HP 10/100" }
        compose.runOnIdle { assertFalse("said politely", Announcer.current!!.assertive) }
    }

    @Test
    fun anAccessibleValueIsTheProgressOfAGaugeOrOfALabel() {
        session.windows(
            listOf(
                label("mp_back", w = 200, h = 20),
                label("mp_front", w = 50, h = 20),
                label("xp", y = 100, w = 300, h = 40),
                label("sp", y = 200, w = 300, h = 40),
                label("go", y = 300, w = 200, h = 100),
            ),
            full = true,
        )
        // Geyser gives a gauge's back label its value out of the maximum it was given: 30 of 200, not the quarter its
        // front label's width shows
        session.label("mp_back", styleSheet = "background-color: #000040", value = 30.0, valueMin = 0.0, valueMax = 200.0)
        session.label("mp_front", styleSheet = "background-color: blue")
        // A label a script gave a value is a progress bar of its own, read by its name with its text and percentage as
        // its state, or by its text with its percentage
        session.label("xp", text = "Level 3", accessibleName = "Experience", value = 40.0)
        session.label("sp", text = "Spirit", value = -5.0, valueMin = -10.0, valueMax = 10.0)
        // A button stays a button, as on the desktop
        session.label("go", text = "North", accessibleButton = true, value = 50.0)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("North")).fetchSemanticsNodes().isNotEmpty() }

        val gauge = progress(30f, 0f..200f)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(gauge).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(gauge).assert(hasContentDescription("mp")).assert(hasStateDescription(string(R.string.gauge_percent, 15)))
        compose.onNode(hasContentDescription("Experience")).assert(progress(40f, 0f..100f)).assert(hasStateDescription(textAndPercent("Level 3", 40)))
        compose.onNode(hasContentDescription("Spirit")).assert(progress(-5f, -10f..10f)).assert(hasStateDescription(string(R.string.gauge_percent, 25)))
        assertTrue("its text is not a node of its own", compose.onAllNodes(hasText("Spirit")).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasContentDescription("North")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onRoot().tryPerformAccessibilityChecks()

        // A new value is read as it comes
        session.label("mp_back", styleSheet = "background-color: #000040", value = 150.0, valueMin = 0.0, valueMax = 200.0)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(progress(150f, 0f..200f)).fetchSemanticsNodes().isNotEmpty() }
        // Cleared, the gauge is read by its front label's width again, and the label as before it had a value
        session.label("mp_back", styleSheet = "background-color: #000040")
        session.label("xp", text = "Level 3", accessibleName = "Experience")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(progress(25f, 0f..100f) and hasContentDescription("mp")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Experience") and SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aLiveLabelWithAValueHasItsNewTextSaidAndIsNotALiveNode() {
        session.windows(listOf(label("xp", w = 300, h = 40)), full = true)
        val before = compose.runOnIdle { Announcer.current }
        session.label("xp", text = "Level 3", accessibleName = "Experience", value = 40.0, liveRegion = "assertive")
        val xp = hasContentDescription("Experience") and progress(40f, 0f..100f)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(xp and hasStateDescription(textAndPercent("Level 3", 40))).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertSame("the first text drawn is not said", before, Announcer.current) }
        // Not live, as a live node is read again at each change of the value too: the Announcer says its new text
        compose.onNode(xp).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onRoot().tryPerformAccessibilityChecks()

        session.label("xp", text = "Level 4", accessibleName = "Experience", value = 40.0, liveRegion = "assertive")
        compose.waitUntil(WAIT_MS) { Announcer.current?.text == "Level 4" }
        val said = compose.runOnIdle { Announcer.current!! }
        assertTrue("said at once", said.assertive)
        compose.onNode(xp).assert(hasStateDescription(textAndPercent("Level 4", 40)))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        // A new value alone is not said
        session.label("xp", text = "Level 4", accessibleName = "Experience", value = 50.0, liveRegion = "assertive")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(progress(50f, 0f..100f) and hasContentDescription("Experience")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertSame("TalkBack is told: ${Announcer.current?.text}", said, Announcer.current) }
    }

    @Test
    fun aLiveLabelIsOneLiveNodeThatReadsItsText() {
        session.windows(
            listOf(
                label("news", w = 300, h = 40),
                label("go", y = 100, w = 200, h = 100),
                label("status", y = 250, w = 300, h = 40),
                label("calm", y = 350, w = 300, h = 40),
            ),
            full = true,
        )
        // Blank, as Geyser makes a label before its first echo()
        session.label("news", liveRegion = "polite")
        session.label("go", text = "North", accessibleName = "Go north", accessibleButton = true, liveRegion = "assertive")
        session.label("status", text = "ok", accessibleName = "Status", liveRegion = "polite")
        // A live region this client does not know is none
        session.label("calm", text = "nothing new", liveRegion = "loud")
        // Labels are drawn in the order they came, so the last one drawn has the others drawn
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("nothing new")).fetchSemanticsNodes().isNotEmpty() }

        // A blank live label is a live node already, so its first text is a change of that node, which TalkBack says;
        // with no text, it is not a stop where TalkBack would say nothing (not the named "status", nor the Announcer's)
        val blank = compose.onNode(
            isLive(LiveRegionMode.Polite) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text) and
                SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription) and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        )
        blank.assertExists()
        compose.onRoot().tryPerformAccessibilityChecks()
        val news = blank.fetchSemanticsNode().id
        session.label("news", text = "a dragon!", liveRegion = "polite")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("a dragon!")).fetchSemanticsNodes().isNotEmpty() }
        val dragon = compose.onNode(hasText("a dragon!")).assert(isLive(LiveRegionMode.Polite))
        assertEquals("the node with the new text", news, dragon.fetchSemanticsNode().id)

        // One read by its name, a button too, has its text as its state, so its new text is said with its name
        compose.onNode(hasContentDescription("Status")).assert(isLive(LiveRegionMode.Polite)).assert(hasStateDescription("ok"))
        compose.onNode(hasContentDescription("Go north")).assert(isLive(LiveRegionMode.Assertive)).assert(hasStateDescription("North"))
        compose.onNode(hasText("nothing new")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onRoot().tryPerformAccessibilityChecks()
        session.label("status", text = "poisoned", accessibleName = "Status", liveRegion = "polite")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Status") and hasStateDescription("poisoned")).fetchSemanticsNodes().isNotEmpty()
        }

        // setLabelLiveRegion("news", "off") leaves the field out: read as drawn again, and not live; a named one, by its
        // name alone
        session.label("news", text = "a dragon!")
        session.label("status", text = "poisoned", accessibleName = "Status")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Status") and SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("a dragon!")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNode(hasContentDescription("Status")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun aLinkInALabelIsAnActionThatHasTheEngineFollowIt() {
        followNorth()
        val request = session.receive("followLabelLink")
        // The label's name, and the link as its text gives it, its space kept: the engine follows it as Mudlet's click does
        assertEquals("nav", request.getString("label"))
        assertEquals("send:north ", request.getString("href"))
        // Nor run as Lua by the app, before or after
        assertTrue(session.received.none { it.optString("t") == "runLua" })
        session.assertNoRequest("runLua", 500)
    }

    @Test
    fun aTapOnALinkInALabelHasTheEngineFollowItsHrefAsQtReadsIt() {
        // Quoted, the href keeps its quote of the other kind and its ">", and its entity is read as Qt reads it
        val text = """<a href="send:say 'caf&eacute; > tea'">order</a>"""
        // Two labels show that same link: each tap names the label tapped, not the other one the link was read or drawn for
        showLinks(label("menu", w = 300, h = 40) to text, label("menu2", y = 100, w = 300, h = 40) to text)
        tapLinkIn("menu2")
        val request = session.receive("followLabelLink")
        assertEquals("menu2", request.getString("label"))
        assertEquals("send:say 'caf\u00e9 > tea'", request.getString("href"))
        // Only a prompt: link puts text on the input line for the player to finish
        compose.onNode(input).assertIsNotFocused()
        tapLinkIn("menu")
        assertEquals("menu", session.receive("followLabelLink").getString("label"))
    }

    @Test
    fun aPromptLinkInALabelGivesTheInputLineTheFocus() {
        showLinks(label("say", w = 300, h = 40) to """<a href="prompt:say hello">say</a>""")
        compose.onNode(input).assertIsNotFocused()
        tapLinkIn("say")
        assertEquals("prompt:say hello", session.receive("followLabelLink").getString("href"))
        session.commandLine("say hello")
        // As Mudlet's label gives its command line the focus: the keyboard opens, and TalkBack moves there
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(input and isFocused() and hasText("say hello")).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aWebLinkInALabelOpensInTheBrowserAndIsNotSentToTheEngine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Blocking: the browser is not started, only the intent seen
        val browser = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, null, true)
        try {
            showLinks(label("site", w = 300, h = 40) to """<a href="https://mudlet.org/">Mudlet</a>""")
            tapLinkIn("site")
            compose.waitUntil(WAIT_MS) { browser.hits == 1 }
            // Not followed by the engine, which neither opens it nor marks it visited
            session.assertNoRequest("followLabelLink", 1_000)
            assertEquals(1, browser.hits)
        } finally {
            instrumentation.removeMonitor(browser)
        }
    }

    @Test
    fun aLabelLinkTheEngineRefusesIsNotFollowedAgain() {
        followNorth()
        val request = session.receive("followLabelLink")
        // The engine tried it: the label had lost the link, so the app must not run it either
        session.refuse(request, "The label \"nav\" has no link to \"send:north \".")
        // Neither sent to the engine again nor run some other way: one call, as a call for one type skips the other
        session.assertNoRequest(setOf("followLabelLink", "runLua"), 1_000)
    }

    @Test
    fun aClickThroughLabelsLinksAreNeitherTappedNorActions() {
        // One read by its text, whose link would be a node of its own, and one by its name, whose link would be its action
        val link = """Go <a href="send:north">north</a>"""
        val centred = "qproperty-alignment: 'AlignCenter'"
        session.windows(listOf(label("hint", w = 300, h = 40), label("nav", y = 100, w = 300, h = 40)), full = true)
        session.label("hint", text = link, styleSheet = centred, clickThrough = true)
        session.label("nav", text = link, styleSheet = centred, accessibleName = "Directions", clickThrough = true)
        val hint = hasText("Go north")
        val nav = hasContentDescription("Directions")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hint).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(nav).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
        // Still drawn as a link, as QLabel draws one whatever its text interaction
        val drawn = compose.onNode(hint).fetchSemanticsNode().config[SemanticsProperties.Text].single { it.text == "Go north" }
        assertTrue("'north' is drawn as a link: ${drawn.spanStyles}", drawn.spanStyles.any { it.start <= 3 && it.end >= 8 && it.item.textDecoration?.contains(TextDecoration.Underline) == true })

        // As on the desktop, where the label takes neither the mouse nor the keyboard a screen reader reaches links by
        compose.onAllNodes(hasClickAction() and (hint or hasAnyAncestor(hint)), useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(nav).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        compose.onNode(nav).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // A tap on the link passes through to what is under the label
        compose.onNode(hint).performTouchInput { click(center) }
        tapLinkIn("Directions")
        session.assertNoRequest("followLabelLink", 1_000)
    }

    @Test
    fun aLabelMadeClickThroughOnceDrawnFollowsNoLinkUntilItIsNoLonger() {
        session.windows(listOf(label("nav", w = 300, h = 40)), full = true)
        val nav = hasContentDescription("Directions")
        val follow = string(R.string.label_follow_link, "north")

        // As a script's enableClickthrough() or disableClickthrough() on the label showing its link: only that changes
        fun show(clickThrough: Boolean) {
            session.label("nav", text = """<a href="send:north">north</a>""", styleSheet = "qproperty-alignment: 'AlignCenter'", accessibleName = "Directions", clickThrough = clickThrough)
            compose.waitUntil(WAIT_MS) { compose.runOnIdle { EngineClient.front.windows.state.labels["nav"]?.clickThrough == clickThrough } }
            compose.onRoot().tryPerformAccessibilityChecks()
        }
        fun actions() = compose.onNode(nav).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

        show(clickThrough = false)
        assertEquals(listOf(follow), actions())
        show(clickThrough = true)
        assertEquals(emptyList<String>(), actions())
        // A tap on its centred link passes through to what is under the label
        tapLinkIn("Directions")
        session.assertNoRequest("followLabelLink", 1_000)

        show(clickThrough = false)
        assertEquals(listOf(follow), actions())
        tapLinkIn("Directions")
        assertEquals("nav", session.receive("followLabelLink").getString("label"))
    }

    // The label "nav", with one link, followed by its accessibility action
    private fun followNorth() {
        session.windows(listOf(label("nav", w = 300, h = 40)), full = true)
        session.label("nav", text = """Go <a href="send:north ">north</a>""", accessibleName = "Directions")
        val follow = string(R.string.label_follow_link, "north")
        val nav = hasContentDescription("Directions")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(nav).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()

        val action = compose.onNode(nav).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == follow }
        compose.runOnIdle { action.action() }
    }

    // Each label with its text, one link, centred, and named by its name for TalkBack
    private fun showLinks(vararg labels: Pair<JSONObject, String>) {
        session.windows(labels.map { it.first }, full = true)
        for ((window, text) in labels) {
            val name = window.getString("name")
            session.label(name, text = text, styleSheet = "qproperty-alignment: 'AlignCenter'", accessibleName = name)
        }
        compose.waitUntil(WAIT_MS) { labels.all { compose.onAllNodes(hasContentDescription(it.first.getString("name"))).fetchSemanticsNodes().isNotEmpty() } }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    // A tap in the middle of the label [name], on its centred link
    private fun tapLinkIn(name: String) = compose.onNode(hasContentDescription(name)).performTouchInput { click(center) }

    @Test
    fun aLabelsOwnColourIsDrawnOnlyWhereItsSheetDrawsNoBoxOfItsOwn() {
        // The colour setBackgroundColor() gives each, which the engine sends as the label's `bg` and writes after its sheet
        val labels = listOf("Framed" to 0, "Spaced" to 300, "Yellow" to 600)
        session.windows(labels.map { (name, x) -> label(name, x = x, y = 0, w = 200, h = 100).put("bg", RUN_RGB or 0xFF0000).put("bgAlpha", 255) }, full = true)
        val added = "\nbackground-color: rgba(255, 0, 0, 255);"
        session.label("Framed", accessibleName = "Framed", styleSheet = "QLabel { border: 10px solid white }$added")
        session.label("Spaced", accessibleName = "Spaced", styleSheet = "QLabel { margin: 20px }$added")
        session.label("Yellow", accessibleName = "Yellow", styleSheet = "QLabel { color: yellow }$added")
        for ((name, _) in labels) compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        fun pixels(name: String) = compose.onNode(hasContentDescription(name)).captureToImage().toPixelMap()
        val red = 0xFFFF0000.toInt()

        // A border of the sheet's own is drawn on what is behind the label, as on the desktop
        val framed = pixels("Framed")
        assertEquals("border", 0xFFFFFFFF.toInt(), framed[1, framed.height / 2].toArgb())
        assertNotEquals("inside the border", red, framed[framed.width / 2, framed.height / 2].toArgb())
        // Where the sheet draws no box, the colour fills the label, its margin too
        val spaced = pixels("Spaced")
        assertEquals("margin", red, spaced[1, 1].toArgb())
        assertEquals("middle", red, spaced[spaced.width / 2, spaced.height / 2].toArgb())
        val yellow = pixels("Yellow")
        assertEquals("middle", red, yellow[yellow.width / 2, yellow.height / 2].toArgb())
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun anImageLabelAsksTheEngineForItsImageOnce() {
        session.windows(listOf(label("key", w = 100, h = 100)), full = true)
        session.label("key", image = "/data/key.png", accessibleName = "Map key")
        // The test's clock recomposes only while the test waits on Compose, so the label is drawn, and asks, first
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("Map key")).fetchSemanticsNodes().isNotEmpty() }
        val request = session.receive("getImage")
        assertEquals("/data/key.png", request.getString("path"))
        assertTrue(!request.has("w") && !request.has("frame"))

        session.image("/data/key.png", png(8, 4), 8, 4, request.get("id"))
        compose.waitUntil(WAIT_MS) { EngineClient.front.windows.images[ScriptWindowsClient.ImageRequest("/data/key.png")]?.width == 8 }
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

    private fun isLive(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    private fun hasStateDescription(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    // A progress bar's state with the label's text before its percentage
    private fun textAndPercent(text: String, percent: Int) = string(R.string.label_text_and_value, text, string(R.string.gauge_percent, percent))

    private fun progress(current: Float, range: ClosedFloatingPointRange<Float>) = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(current, range))

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
        val GAME = Profile("Game", "127.0.0.1", 4000, false, "", false)

        const val INSTANCE = "fake-labels"

        // Rounding to whole pixels, and the text's own padding
        const val PIXELS = 3f
        const val WAIT_MS = 10_000L

        // Longer than the Announcer's MERGE_MS, so two announcements are not merged into one
        const val APART_MS = 300L
        const val TIMEOUT_MS = 240_000L
    }
}
