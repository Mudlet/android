package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
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
import org.mudlet.android.data.Settings.MovementPadDesign
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.exit
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.EngineMessages.room
import org.mudlet.android.ui.game.HardwareKeyboard
import org.mudlet.android.ui.game.MOVEMENT_PAD_TAG

/**
 * The movement pad's designs (MovementPadDesignsTest) in the debug build's pseudo-locales, as PseudoLocaleTest checks
 * the screens: each passes the accessibility checks; west stays left of east, and the pad at the edge of the side the
 * player chose, where the language is written from the right too; each control has a name of its own; and the list of
 * designs, the strip of the Swipe pad a player has from the start and the Room exits pad's word for a room with no way
 * out show their text whole.
 */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MovementPadDesignsPseudoLocaleTest(private val locale: String) {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(AppLocaleRule(locale))
        .around(compose)

    private lateinit var session: FakeEngine.Session

    private val pad = hasTestTag(MOVEMENT_PAD_TAG)

    @Before
    fun openTheGameScreen() {
        compose.enableAccessibilityChecksOfWhatShows()
        compose.runOnIdle {
            HardwareKeyboard.attachedOverride = false
            Settings.movementPad.reset()
        }
        // A new engine instance, so no map frame of an earlier test names the player's room
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-pad-designs-$locale-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        session.synced(0)
        session.profiles(listOf(GAME), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(GAME) }
        activate(hasContentDescription(compose.activity.getString(R.string.profile_connect_to, GAME.name)))
        session.profiles(listOf(GAME), session.receive("openProfile"))
        waitFor(pad)
    }

    // FakeEngineRule puts the settings back
    @After
    fun reset() = compose.runOnIdle { HardwareKeyboard.attachedOverride = null }

    @Test
    fun theListOfDesignsShowsWhole() {
        activate(key(R.string.settings_movement_pad_design))
        waitFor(hasText(compose.string(R.string.settings_movement_pad_exits_hint)))
        compose.onRoot().tryPerformAccessibilityChecks()
        for (id in DIALOG_TEXTS) assertFits(id)
    }

    @Test
    fun theThumbPadKeepsTheCompassAsOnAMapAtTheSideChosen() {
        design(MovementPadDesign.THUMB)
        compose.onRoot().tryPerformAccessibilityChecks()
        val names = (KEYS + listOf(R.string.movement_pad_fold, R.string.movement_pad_to_left, R.string.settings_movement_pad_design, R.string.movement_again_none)).map { compose.string(it) }
        assertEquals("$locale: each control has a name of its own: $names", names.size, names.toSet().size)
        assertCompass()
        // On the right in both directions of writing, as the hand that holds the phone is
        val fold = bounds(key(R.string.movement_pad_fold))
        assertTrue("$locale: the keys are right of their buttons $fold", bounds(key(R.string.movement_west)).left >= fold.right)
        assertAtEdge(right = true, key(R.string.movement_up))

        activate(key(R.string.movement_pad_to_left))
        waitFor(key(R.string.movement_pad_to_right))
        assertTrue("$locale: the keys are left of their buttons", bounds(key(R.string.movement_east)).right <= bounds(key(R.string.movement_pad_fold)).left)
        assertAtEdge(right = false, key(R.string.movement_in))
        compose.onRoot().tryPerformAccessibilityChecks()

        activate(key(R.string.movement_pad_fold))
        waitFor(key(R.string.movement_pad_unfold))
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theSwipePadAPlayerHasFromTheStartShowsItsStripsTextWhole() {
        compose.runOnIdle { assertEquals(MovementPadDesign.SWIPE, Settings.movementPadDesign.value) }
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.movement_swipe)
        val strip = hasText(compose.string(R.string.movement_swipe)) and hasClickAction()
        assertAtEdge(right = true, strip)
        activate(strip)
        waitFor(hasText(compose.string(R.string.movement_northwest)) and hasClickAction())
        compose.onRoot().tryPerformAccessibilityChecks()
        for (id in KEYS) assertFits(id)
    }

    @Test
    fun theRoomExitsPadKeepsTheCompassAndSaysWhenThereIsNoWayOut() {
        design(MovementPadDesign.EXITS)
        compose.onRoot().tryPerformAccessibilityChecks()
        assertCompass()

        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 7)))
        val asked = session.receive("roomInfo")
        session.send(room(asked, 7, "Crossroads", steps = 0, exits = listOf(exit("w", 8, "Gate"), exit("e", 9, "Square"))))
        waitFor(key(R.string.movement_exits_all))
        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        assertTrue("$locale: west $west is left of east $east, each where it is on the current pad", west.right <= east.left && west.top == east.top)
        val hide = bounds(key(R.string.movement_pad_hide))
        assertTrue("$locale: the keys are right of their buttons $hide", west.left >= hide.right)
        compose.onRoot().tryPerformAccessibilityChecks()

        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H, player = 12)))
        session.send(room(session.receive("roomInfo"), 12, "Pit", steps = 0))
        waitFor(hasText(compose.string(R.string.movement_exits_none)))
        compose.onRoot().tryPerformAccessibilityChecks()
        assertFits(R.string.movement_exits_none)
    }

    private fun design(design: MovementPadDesign) {
        compose.runOnIdle { Settings.movementPadDesign.set(design) }
        waitFor(pad)
        compose.waitForIdle()
    }

    private fun assertCompass() {
        val north = bounds(key(R.string.movement_north))
        val south = bounds(key(R.string.movement_south))
        val west = bounds(key(R.string.movement_west))
        val east = bounds(key(R.string.movement_east))
        assertTrue("$locale: west $west is left of east $east", west.right <= east.left && west.top == east.top)
        assertTrue("$locale: north $north is over south $south", north.bottom <= south.top && north.left == south.left)
    }

    /** [what] is at the pad's right edge, or its left, within a key's width, whichever way the language is written. */
    private fun assertAtEdge(right: Boolean, what: SemanticsMatcher) {
        val padBounds = bounds(pad)
        val at = bounds(what)
        val key = with(compose.density) { 48.dp.toPx() }
        val gap = if (right) padBounds.right - at.right else at.left - padBounds.left
        assertTrue("$locale: $at is at the pad's ${if (right) "right" else "left"} edge, $padBounds", gap in -0.5f..key)
    }

    private fun assertFits(@StringRes id: Int) {
        val text = compose.string(id)
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: throw AssertionError("$locale: \"$text\" is not shown")
        val layouts = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: throw AssertionError("$locale: no layout for \"$text\"")
        assertFalse("$locale: \"$text\" is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$locale: \"$text\" has more lines than it may show", layout.multiParagraph.didExceedMaxLines)
    }

    private fun key(@StringRes name: Int) = hasContentDescription(compose.string(name)) and hasClickAction()

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun activate(label: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(label and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    companion object {
        val GAME = Profile("Walk", "127.0.0.1", 4000, false, "", false)

        val KEYS = listOf(
            R.string.movement_north,
            R.string.movement_northeast,
            R.string.movement_east,
            R.string.movement_southeast,
            R.string.movement_south,
            R.string.movement_southwest,
            R.string.movement_west,
            R.string.movement_northwest,
            R.string.movement_up,
            R.string.movement_down,
            R.string.movement_in,
            R.string.movement_out,
        )

        val DIALOG_TEXTS = listOf(
            R.string.settings_movement_pad_design,
            R.string.settings_experimental,
            R.string.settings_movement_pad_current,
            R.string.settings_movement_pad_current_hint,
            R.string.settings_movement_pad_thumb,
            R.string.settings_movement_pad_thumb_hint,
            R.string.settings_movement_pad_swipe,
            R.string.settings_movement_pad_swipe_hint,
            R.string.settings_movement_pad_exits,
            R.string.settings_movement_pad_exits_hint,
            R.string.settings_choice_cancel,
        )

        const val FRAME_W = 200
        const val FRAME_H = 150

        const val WAIT_MS = 10_000L

        // Below the per-test timeout native/tests/android_instrumented.py gives the runner, so this one reports first
        const val TIMEOUT_MS = 240_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf("en-XA", "ar-XB")
    }
}
