package org.mudlet.android.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import org.mudlet.android.ui.game.LINE_SELECTION_BAR_TAG

/**
 * The bar of a selection of the console's lines (LineSelectTest) where the player looks, on the screens of [screen]
 * with the app's map in the console's corner where there is room for it: each of its buttons in sight, under the title
 * bar, above the input line and clear of the map; the newest line, selected, scrolled out from under it; and a touch on
 * Done, where it is drawn, ending the selection.
 */
@RunWith(Parameterized::class)
class LineSelectionBarPlaceTest(screen: AppMapCornerFixture.Screen) : AppMapCornerFixture(screen) {
    @Test
    fun theBarIsInSightAboveTheInputLineAndClearOfTheMapInTheCorner() {
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        compose.waitUntil(WAIT_MS) { EngineClient.front.appMap.map != null }
        // The map at the console's top end, which would cover a bar's buttons there
        if (screen === PHONE) waitFor(corner)
        compose.waitForIdle()
        val map = compose.onAllNodes(corner).fetchSemanticsNodes().singleOrNull()?.boundsInRoot

        perform(newest, string(R.string.console_select_from_here))
        val bar = hasTestTag(LINE_SELECTION_BAR_TAG)
        val done = hasText(string(R.string.console_selection_done)) and hasClickAction()
        waitFor(done)
        compose.onRoot().tryPerformAccessibilityChecks()

        val title = bounds(hasTestTag(GAME_TITLE_TAG))
        val input = bounds(hasSetTextAction())
        val buttons = compose.onAllNodes(hasClickAction() and hasAnyAncestor(bar)).fetchSemanticsNodes()
        assertEquals("$screen: the bar's buttons, Copy, Share and Done", 3, buttons.size)
        for (button in buttons) {
            val at = button.touchBoundsInRoot
            if (map != null) assertFalse("$screen: a button of the bar, touched within $at, is under the map $map", at.overlaps(map))
            assertTrue("$screen: a button of the bar, touched within $at, is out of the screen", at.left >= -1 && at.right <= screen.widthDp * dp + 1)
            assertTrue("$screen: a button of the bar, touched within $at, is over the title bar's $title", at.top >= title.bottom - 1)
            assertTrue("$screen: a button of the bar, touched within $at, is over the input line $input", at.bottom <= input.top + 1)
        }
        val drawn = bounds(bar)
        val line = drawnText(NEWEST_LINE)
        assertFalse("$screen: the newest line $line is under the bar $drawn", line.overlaps(drawn))

        compose.onNode(done).performTouchInput { click(center) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(bar).fetchSemanticsNodes().isEmpty() }
    }

    // The newest line's node, with the lines' actions
    private val newest = hasText(NEWEST_LINE) and SemanticsMatcher("a line") { it.config.getOrNull(SemanticsActions.CustomActions) != null }

    private fun perform(node: SemanticsMatcher, label: String) {
        compose.waitUntil(WAIT_MS) { compose.onNode(node).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label } }
        compose.onRoot().tryPerformAccessibilityChecks()
        val action = compose.onNode(node).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            PHONE,
            Screen("320x640 dp, text twice as large", 320f, 640f, 2f),
            Screen("640x320 dp", 640f, 320f, 1f),
            Screen("800x1280 dp", 800f, 1280f, 1f),
        )
    }
}
