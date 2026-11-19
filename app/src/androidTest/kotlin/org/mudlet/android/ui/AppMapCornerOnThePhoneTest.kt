package org.mudlet.android.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.EngineMessages.appMap
import org.mudlet.android.ui.EngineMessages.mapView

/**
 * The app's map in the console's corner on the phone, where the screen's size changes none of it: none while a
 * script's mapper, the map in front or the map behind the text shows, a script's window is where it would be, or the
 * game has no rooms; hidden and shown again from its panel, which is kept; and a tap on it bringing the map to the front,
 * but none on the button over it that shows its panel.
 */
class AppMapCornerOnThePhoneTest : AppMapCornerFixture(AppMapCornerFixture.PHONE) {
    @Test
    fun noMapIsInTheCornerWhileAnotherMapOrAScriptsWindowIsThereOrTheGameHasNone() {
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
        val prefs = compose.activity.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        waitFor(corner)

        openControls()
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
        openControls()
        activate(button(R.string.app_map_corner_hide))
        waitForNone(corner)
        compose.pickFromGameMenu(string(R.string.settings_title))
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
        session.send(appMap(FRAME_W, FRAME_H, view = mapView(FRAME_W, FRAME_H)))
        waitFor(corner)
        // The frame is white all over, the corner's backing the console's black
        compose.waitForIdle()
        val drawn = compose.onNode(corner).captureToImage().toPixelMap()
        assertEquals(Color.White, drawn[drawn.width / 2, drawn.height / 2])

        // The button over the map shows its panel, not the map in front
        compose.onNode(button(R.string.map_controls_show)).performClick()
        waitFor(button(R.string.map_zoom_in))
        assertTrue("the controls' button brought the map to the front", compose.onAllNodes(mapInFront).fetchSemanticsNodes().isEmpty())
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
}
