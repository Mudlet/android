package org.mudlet.android.ui

import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mudlet.android.R
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.mapView
import org.mudlet.android.ui.game.SELECTION_END_HANDLE_TAG
import org.mudlet.android.ui.game.SELECTION_START_HANDLE_TAG

/**
 * A selection of the console's lines (LineSelectTest) where the player looks, on the screens of [screen] with the app's
 * map in the console's corner where there is room for it: Android's floating text toolbar beside the selected newest
 * line, not on it; the handles under it, above the input line and clear of the map, with the line scrolled out from over
 * them; and a tap on another line ending the selection, the toolbar going with it.
 */
@RunWith(Parameterized::class)
class SelectionToolbarPlaceTest(screen: AppMapCornerFixture.Screen) : AppMapCornerFixture(screen) {
    @Test
    fun theToolbarIsBesideTheSelectedLineAndItsHandlesAboveTheInputLineClearOfTheMap() {
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        compose.waitUntil(WAIT_MS) { EngineClient.front.appMap.map != null }
        if (screen === PHONE) waitFor(corner)
        compose.waitForIdle()
        val map = compose.onAllNodes(corner).fetchSemanticsNodes().singleOrNull()?.boundsInRoot

        compose.onNode(newest).performTouchInput { longClick(center) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(newest).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true }
        val copy = string(R.string.console_selection_copy)
        val toolbar = FloatingToolbar.bounds(copy)
        compose.onRoot().tryPerformAccessibilityChecks()

        val line = drawnText(NEWEST_LINE)
        val onScreen = onScreen(line)
        assertFalse("$screen: the toolbar $toolbar is over the selected line $onScreen", toolbar.top < onScreen.bottom && toolbar.bottom > onScreen.top)
        val gap = maxOf(onScreen.top - toolbar.bottom, toolbar.top - onScreen.bottom)
        // Android draws its toolbar in the device's own dp, not the screen's scaled ones
        val near = NEAR_DP * compose.activity.resources.displayMetrics.density
        assertTrue("$screen: the toolbar $toolbar is $gap px from the selected line $onScreen", gap <= near)

        val input = bounds(hasSetTextAction())
        for (tag in listOf(SELECTION_START_HANDLE_TAG, SELECTION_END_HANDLE_TAG)) {
            val handle = bounds(hasTestTag(tag))
            if (map != null) assertFalse("$screen: the handle $handle is under the map $map", handle.overlaps(map))
            assertTrue("$screen: the handle $handle is over the input line $input", handle.bottom <= input.top + 1)
            assertTrue("$screen: the handle $handle is out of the screen", handle.left >= -1 && handle.right <= screen.widthDp * dp + 1)
            assertTrue("$screen: the selected line $line is over the handle $handle", line.bottom <= handle.top + 1)
        }

        compose.onNode(hasText("line ${LINES - 1}")).performTouchInput { click(center) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(SELECTION_END_HANDLE_TAG)).fetchSemanticsNodes().isEmpty() }
        FloatingToolbar.assertGone(copy, "$screen: the toolbar is still there after the tap")
    }

    // The newest line's node, with the lines' actions
    private val newest = hasText(NEWEST_LINE) and SemanticsMatcher("a line") { it.config.getOrNull(SemanticsActions.CustomActions) != null }

    // Where UiAutomator would report [inRoot], a place in the activity's composition
    private fun onScreen(inRoot: Rect): android.graphics.Rect {
        val at = IntArray(2)
        compose.runOnUiThread { compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).getLocationOnScreen(at) }
        return android.graphics.Rect((inRoot.left + at[0]).toInt(), (inRoot.top + at[1]).toInt(), (inRoot.right + at[0]).toInt(), (inRoot.bottom + at[1]).toInt())
    }

    companion object {
        // Android puts its toolbar this near the text it is over, a margin and the toolbar's own height at most
        const val NEAR_DP = 96

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
