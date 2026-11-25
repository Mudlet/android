package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.data.Settings.MovementPadDesign
import org.mudlet.android.data.Settings.MovementPadSide
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.exit
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.EngineMessages.room
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.game.MOVEMENT_PAD_TAG

/**
 * The movement pad's designs (docs/architecture.md, "The movement pad's designs") against a fake engine: a player has
 * the Swipe pad from the start; the pad's own button lists them as Settings does; the Thumb pad's middle key walks the last way again, it folds into a button at
 * the quick bar's start and moves to the other side; the Swipe pad walks a swipe's way and, from its list or TalkBack's
 * actions, the twelve; and the Room exits pad asks the engine for the player's room the map's frame names and has only
 * its ways. Controls are found by the names TalkBack reads and pressed through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class MovementPadDesignsTest {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val pad = hasTestTag(MOVEMENT_PAD_TAG)

    @Before
    fun openTheGameScreen() {
        // enableAccessibilityChecks() needs API 34: the API 36 emulator runs the checks, API 28 does not
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
        compose.runOnIdle {
            HardwareKeyboard.attachedOverride = false
            // The fake engine's rule turns the pad off for the tests that are not about it
            Settings.movementPad.reset()
        }
        // A new engine instance, so no map frame of an earlier test names the player's room
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-pad-designs-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        activate(hasContentDescription(string(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        session.line(1, CONSOLE_LINE)
        waitFor(hasText(CONSOLE_LINE))
        waitFor(pad)
    }

    // FakeEngineRule puts the settings back
    @After
    fun reset() = compose.runOnIdle { HardwareKeyboard.attachedOverride = null }

    @Test
    fun aPlayerHasTheSwipePadFromTheStart() {
        val strip = hasText(string(R.string.movement_swipe)) and hasClickAction()
        waitFor(strip)
        compose.runOnIdle { assertEquals(MovementPadDesign.SWIPE, Settings.movementPadDesign.value) }
        for ((name, _) in KEYS) compose.onNode(key(name)).assertDoesNotExist()
        checkAccessibility()
    }

    @Test
    fun thePadsButtonListsTheDesignsAsSettingsDoesAndCancelLeavesIt() {
        for ((from, to) in listOf(MovementPadDesign.SWIPE to MovementPadDesign.EXITS, MovementPadDesign.EXITS to MovementPadDesign.CURRENT, MovementPadDesign.CURRENT to MovementPadDesign.THUMB, MovementPadDesign.THUMB to MovementPadDesign.SWIPE)) {
            activate(key(R.string.settings_movement_pad_design))
            waitFor(listed(from))
            compose.onNode(hasText(string(R.string.settings_experimental)) and hasAnyAncestor(isDialog())).assertExists()
            compose.onNode(listed(from)).assertIsSelected()
            for (design in MovementPadDesign.entries) compose.onNode(listed(design)).assertExists()
            activate(listed(to))
            waitForNo(isDialog())
            compose.runOnIdle { assertEquals(to, Settings.movementPadDesign.value) }
            waitFor(pad)
            checkAccessibility()
        }
        // The design it has already: the list closes too, though the pad stays as it was
        activate(key(R.string.settings_movement_pad_design))
        waitFor(listed(MovementPadDesign.SWIPE))
        activate(listed(MovementPadDesign.SWIPE))
        waitForNo(isDialog())
        activate(key(R.string.settings_movement_pad_design))
        waitFor(listed(MovementPadDesign.SWIPE))
        activate(hasText(string(R.string.settings_choice_cancel)))
        waitForNo(isDialog())
        compose.runOnIdle { assertEquals(MovementPadDesign.SWIPE, Settings.movementPadDesign.value) }
    }

    @Test
    fun theThumbPadsMiddleKeyWalksTheLastWayAgain() {
        design(MovementPadDesign.THUMB)
        waitFor(key(R.string.movement_again_none))
        compose.onNode(key(R.string.movement_again_none)).assertIsNotEnabled()
        checkAccessibility()

        activate(key(R.string.movement_east))
        assertEquals("e", sent())
        val again = key(R.string.movement_again, string(R.string.movement_east))
        activate(again)
        assertEquals("e", sent())
        activate(again)
        assertEquals("e", sent())
        activate(key(R.string.movement_up))
        assertEquals("u", sent())
        activate(key(R.string.movement_again, string(R.string.movement_up)))
        assertEquals("u", sent())

        // In the gap between the compass's keys, under north
        val middle = bounds(key(R.string.movement_again, string(R.string.movement_up)))
        val north = bounds(key(R.string.movement_north))
        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        assertTrue("again $middle is under north $north, between west $west and east $east", middle.top >= north.bottom - 0.5f && middle.left >= west.right - 0.5f && middle.right <= east.left + 0.5f)
    }

    @Test
    fun theThumbPadFoldsIntoAButtonAndMovesToTheOtherSide() {
        design(MovementPadDesign.THUMB)
        val fold = key(R.string.movement_pad_fold)
        val unfold = key(R.string.movement_pad_unfold)
        val toLeft = key(R.string.movement_pad_to_left)
        val toRight = key(R.string.movement_pad_to_right)

        // On the right by default: the keys at the row's right end, their buttons inside them
        assertTrue("north ${bounds(key(R.string.movement_north))} is right of the fold button ${bounds(fold)}", bounds(key(R.string.movement_north)).left >= bounds(fold).right)
        activate(toLeft)
        waitFor(toRight)
        compose.runOnIdle { assertEquals(MovementPadSide.LEFT, Settings.movementPadSide.value) }
        assertTrue("north ${bounds(key(R.string.movement_north))} is left of the fold button ${bounds(fold)}", bounds(key(R.string.movement_north)).right <= bounds(fold).left)
        val padBounds = bounds(pad)
        assertTrue("in ${bounds(key(R.string.movement_in))} is at the pad's left $padBounds", bounds(key(R.string.movement_in)).left - padBounds.left < with(compose.density) { 48.dp.toPx() })
        checkAccessibility()

        activate(fold)
        waitForNo(pad)
        waitFor(unfold)
        compose.runOnIdle { assertEquals("folding is not putting it away", true, Settings.movementPad.value) }
        checkAccessibility()
        activate(unfold)
        waitFor(pad)
        waitForNo(unfold)
        activate(toRight)
        waitFor(toLeft)
        compose.runOnIdle { assertEquals(MovementPadSide.RIGHT, Settings.movementPadSide.value) }
    }

    @Test
    fun theSwipePadWalksASwipesWayAndTheTwelveFromItsListAndTalkBacksActions() {
        design(MovementPadDesign.SWIPE)
        val strip = hasText(string(R.string.movement_swipe)) and hasClickAction()
        waitFor(strip)
        checkAccessibility()

        compose.onNode(strip).performTouchInput { swipeUp() }
        assertEquals("n", sent())
        compose.onNode(strip).performTouchInput { swipeRight() }
        assertEquals("e", sent())
        compose.onNode(strip).performTouchInput { swipeLeft() }
        assertEquals("w", sent())

        // A tap lists the twelve, up, down, in and out first
        compose.onNode(strip).performTouchInput { click() }
        val up = hasText(string(R.string.movement_up)) and hasClickAction()
        waitFor(up)
        checkAccessibility()
        activate(up)
        assertEquals("u", sent())
        waitForNo(up)

        // TalkBack's double tap lists them too, and its actions walk each way
        activate(strip)
        waitFor(up)
        activate(hasText(string(R.string.movement_out)) and hasClickAction())
        assertEquals("out", sent())
        val actions = compose.onNode(strip).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(KEYS.map { string(it.first) }.toSet(), actions.map { it.label }.toSet())
        for ((name, word) in KEYS) {
            compose.runOnIdle { actions.single { it.label == string(name) }.action() }
            assertEquals(string(name), word, sent())
        }
        session.assertNoRequest("send", QUIET_MS)
    }

    @Test
    fun theRoomExitsPadHasOnlyTheWaysOutOfThePlayersRoomTheMapHas() {
        design(MovementPadDesign.EXITS)
        // No map has named the player's room: all twelve
        for ((name, _) in KEYS) waitFor(key(name))

        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 7)))
        val asked = session.receive("roomInfo")
        assertEquals(7, asked.getInt("room"))
        session.send(room(asked, 7, "Crossroads", steps = 0, exits = listOf(exit("n", 8, "Oak"), exit("e", 9, "Square"), exit("up", 10, "Loft"), exit("climb rope", 11, "Tree"))))
        val all = key(R.string.movement_exits_all)
        waitFor(all)
        for ((name, _) in KEYS) {
            val has = name in listOf(R.string.movement_north, R.string.movement_east, R.string.movement_up)
            if (has) waitFor(key(name)) else compose.onNode(key(name)).assertDoesNotExist()
        }
        checkAccessibility()
        activate(key(R.string.movement_north))
        assertEquals("n", sent())
        activate(key(R.string.movement_up))
        assertEquals("u", sent())

        activate(all)
        val room = key(R.string.movement_exits_room)
        waitFor(room)
        for ((name, _) in KEYS) waitFor(key(name))
        activate(key(R.string.movement_south))
        assertEquals("the player's choice: all twelve walk", "s", sent())
        activate(room)
        waitFor(all)
        compose.onNode(key(R.string.movement_south)).assertDoesNotExist()

        // A room the map has no way out of says so
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 12)))
        val next = session.receive("roomInfo")
        assertEquals(12, next.getInt("room"))
        session.send(room(next, 12, "Pit", steps = 0))
        waitFor(hasText(string(R.string.movement_exits_none)))
        compose.onNode(key(R.string.movement_north)).assertDoesNotExist()
        checkAccessibility()

        // Another design asks nothing of the engine as the player walks
        design(MovementPadDesign.CURRENT)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 13)))
        session.assertNoRequest("roomInfo", QUIET_MS)
    }

    @Test
    fun theRoomExitsPadHasAllTwelveWhileTheMapDoesNotHaveThePlayersRoom() {
        design(MovementPadDesign.EXITS)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 7)))
        val asked = session.receive("roomInfo")
        session.send(room(asked, 7, "Crossroads", steps = 0, exits = listOf(exit("n", 8, "Oak"))))
        waitFor(key(R.string.movement_exits_all))
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 0)))
        for ((name, _) in KEYS) waitFor(key(name))
        compose.onNode(key(R.string.movement_exits_room)).assertDoesNotExist()

        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 7)))
        session.send(room(session.receive("roomInfo"), 7, "Crossroads", steps = 0, exits = listOf(exit("n", 8, "Oak"))))
        waitFor(key(R.string.movement_exits_all))
        // A room the map does not have: the last room's ways stay till the refusal, which is the pad's alone
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 8)))
        session.refuse(session.receive("roomInfo"), "no room 8 in the map")
        for ((name, _) in KEYS) waitFor(key(name))
        compose.onNode(key(R.string.movement_exits_all)).assertDoesNotExist()
        compose.onNode(hasText("no room 8 in the map", substring = true)).assertDoesNotExist()
    }

    private fun design(design: MovementPadDesign) {
        compose.runOnIdle { Settings.movementPadDesign.set(design) }
        // Idle: the Room exits pad's watch has started or stopped before the test's next frame comes
        compose.waitForIdle()
        waitFor(pad)
    }

    private fun sent(): String = session.receive("send").getString("text")

    private fun checkAccessibility() = compose.onRoot().tryPerformAccessibilityChecks()

    /** [design]'s radio button in the pad's list of designs. */
    private fun listed(design: MovementPadDesign): SemanticsMatcher {
        val label = when (design) {
            MovementPadDesign.CURRENT -> R.string.settings_movement_pad_current
            MovementPadDesign.THUMB -> R.string.settings_movement_pad_thumb
            MovementPadDesign.SWIPE -> R.string.settings_movement_pad_swipe
            MovementPadDesign.EXITS -> R.string.settings_movement_pad_exits
        }
        return hasText(string(label)) and isSelectable() and hasAnyAncestor(isDialog())
    }

    private fun key(@StringRes name: Int, vararg args: Any) = hasContentDescription(string(name, *args)) and hasClickAction()

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNo(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** What a double tap does under TalkBack: the node's accessibility click, after the checks on the whole screen. */
    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        checkAccessibility()
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        val GAME = Profile("Walk", "127.0.0.1", 4000, false, "", false)

        val KEYS = listOf(
            R.string.movement_north to "n",
            R.string.movement_northeast to "ne",
            R.string.movement_east to "e",
            R.string.movement_southeast to "se",
            R.string.movement_south to "s",
            R.string.movement_southwest to "sw",
            R.string.movement_west to "w",
            R.string.movement_northwest to "nw",
            R.string.movement_up to "u",
            R.string.movement_down to "d",
            R.string.movement_in to "in",
            R.string.movement_out to "out",
        )

        const val CONSOLE_LINE = "You stand at a crossroads."
        const val FRAME_W = 200
        const val FRAME_H = 150

        const val WAIT_MS = 10_000L
        const val QUIET_MS = 500

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L
    }
}
