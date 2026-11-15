package org.mudlet.android.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
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
import org.mudlet.android.protocol.Profile
import java.io.ByteArrayOutputStream

/**
 * The button bars and commands a profile's scripts and packages add (docs/architecture.md, "Button bars and commands"),
 * against a fake engine: a bar's buttons as 48 dp buttons read after the script windows, whose tap sends `pressButton`,
 * a push-down button as a switch, a folder's menu, where bars go beside or above the console, an unnamed icon button,
 * and the Extensions menu of commands, whose tap sends `activateCommand`.
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
        bars(bar(1, "Moves", "top", listOf(button(10, "North"), button(11, "South"))))
        val north = hasText("North") and hasClickAction()
        waitFor(north)
        compose.onNode(north).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val group = hasContentDescription(string(R.string.bar_description, "Moves"))
        compose.onNode(group).assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f))
        compose.onNode(group).assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))

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
        bars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true))))
        val sneak = hasText("Sneak") and hasClickAction()
        waitFor(sneak)
        compose.onNode(sneak).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(sneak).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        activate(sneak)
        assertEquals(20, session.receive("pressButton").getInt("button"))
        // It is down once the engine says so
        bars(bar(1, "Modes", "top", listOf(button(20, "Sneak", pushDown = true, checked = true))))
        waitFor(sneak and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun aFoldersMenuRunsTheEntryPickedAndThenTheFolder() {
        val ice = button(33, "Ice", pushDown = true, checked = true)
        bars(bar(1, "Spells", "top", listOf(button(30, "Cast", children = listOf(button(31, "Fireball"), button(32, "Shields", children = listOf(ice)))))))
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
        bars(bar(2, "Float", "floating", listOf(button(40, "Tools", children = listOf(inner)))))
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
        bars(bar(3, "Side", "left", listOf(button(50, "Look"), button(51, "Inv")), vertical = true))
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
        bars(bar(4, "Grid", "top", (1..12).map { button(70 + it, "B$it") }, columns = 12))
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
        bars(bar(1, "Moves", "top", listOf(button(60, "", icon = "/icons/look.png"))))
        val look = hasContentDescription(string(R.string.bar_button_unnamed, 1, "Moves")) and hasClickAction()
        waitFor(look)
        compose.waitForIdle()
        val request = session.receive("getImage")
        assertEquals("/icons/look.png", request.getString("path"))
        val px = with(compose.density) { 24.dp.roundToPx() }
        assertEquals(px, request.getInt("w"))
        assertEquals(px, request.getInt("h"))

        session.image("/icons/look.png", png(px, px), px, px, request.get("id"))
        compose.waitUntil(WAIT_MS) { ScriptWindowsClient.images[ScriptWindowsClient.ImageRequest("/icons/look.png", px, px)] != null }
        compose.onRoot().tryPerformAccessibilityChecks()
        activate(look)
        assertEquals(60, session.receive("pressButton").getInt("button"))
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

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun bars(vararg bars: JSONObject) = session.send(JSONObject().put("t", "buttonBars").put("bars", JSONArray(bars.toList())))

    private fun bar(id: Int, name: String, place: String, buttons: List<JSONObject>, vertical: Boolean = false, columns: Int = 1) = JSONObject()
        .put("id", id).put("name", name).put("styleSheet", "").put("place", place).put("vertical", vertical).put("columns", columns)
        .put("fillerOffset", 0).put("buttons", JSONArray(buttons))

    private fun button(
        id: Int,
        name: String,
        icon: String = "",
        pushDown: Boolean = false,
        checked: Boolean = false,
        children: List<JSONObject> = emptyList(),
    ) = JSONObject().put("id", id).put("name", name).put("icon", icon).put("styleSheet", "").put("pushDown", pushDown).put("checked", checked)
        .put("menu", children.isNotEmpty()).put("children", JSONArray(children))

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
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 240_000L
    }
}
