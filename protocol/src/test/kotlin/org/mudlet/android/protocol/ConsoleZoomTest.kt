package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text size the player pinches a script console to, against the size its scripts give it. */
class ConsoleZoomTest {
    @Test
    fun thePlayersSizesStayWithinTheRange() {
        assertEquals(16, ConsoleZoom.clamp(16, 8))
        assertEquals(ConsoleZoom.MAX_SIZE, ConsoleZoom.clamp(90, 30))
        assertEquals(ConsoleZoom.MIN_SIZE, ConsoleZoom.clamp(1, 8))
        // A script's size outside the range is not pulled into it by a pinch that has barely begun
        assertEquals(60, ConsoleZoom.clamp(60, 60))
        assertEquals(2, ConsoleZoom.clamp(2, 2))
        assertEquals(54, ConsoleZoom.clamp(54, 60))
        assertEquals(60, ConsoleZoom.clamp(70, 60))
    }

    @Test
    fun theEnginesSizeInWholePoints() {
        assertEquals(8, ConsoleZoom.points(8.0))
        assertEquals(11, ConsoleZoom.points(10.5))
        assertEquals(0, ConsoleZoom.points(0.0))
        assertEquals(0, ConsoleZoom.points(-1.0))
    }

    @Test
    fun aSizeAScriptSetsLaterIsSetBackToThePlayers() {
        var zoom = ConsoleZoom(chosen = 12, scripts = 8)
        val (afterScript, send) = zoom.reported(10)
        assertEquals(12, send)
        assertEquals(10, afterScript.scripts)
        zoom = afterScript

        // The engine then reports the player's size: nothing more to send
        val (settled, none) = zoom.reported(12)
        assertNull(none)
        assertEquals(ConsoleZoom(12, 10), settled)
    }

    @Test
    fun aConsoleMadeAgainGetsThePlayersSize() {
        // Geyser makes its consoles at 8 points
        val (zoom, send) = ConsoleZoom(14, 8).reported(8)
        assertEquals(14, send)
        assertEquals(8, zoom.scripts)
    }

    @Test
    fun aScriptThatSetsItsSizeAgainInAnswerKeepsIt() {
        val (sent, send) = ConsoleZoom(14, 8).reported(10)
        assertEquals(14, send)
        // Its handler of sysFontChangeEvent sets 10 again, in the same turn, so the engine never reports 14
        val (afterAnswer, again) = sent.reported(10)
        assertNull(again)
        assertEquals(sent, afterAnswer)
        // A size it sets later is fought once more
        assertEquals(14, afterAnswer.reported(14).first.reported(12).second)
    }

    @Test
    fun theAnswerToAnOlderSendIsNoScriptsSize() {
        // 12 then 14 went before the engine answered either
        val zoom = ConsoleZoom(10, 8).chosen(12).chosen(14)
        assertEquals(setOf(12, 14), zoom.inFlight)
        val (afterOld, send) = zoom.reported(12)
        assertNull(send)
        assertEquals(8, afterOld.scripts)
        val (settled, none) = afterOld.reported(14)
        assertNull(none)
        assertEquals(ConsoleZoom(14, 8), settled)
    }

    @Test
    fun aFontSizedInPixelsIsLeftAlone() {
        val zoom = ConsoleZoom(12, 8)
        assertEquals(zoom to null, zoom.reported(0))
    }

    @Test
    fun choosingTheSameSizeSendsNothing() {
        val zoom = ConsoleZoom(12, 8)
        assertEquals(zoom, zoom.chosen(12))
    }

    @Test
    fun theLuaSetsTheSizeAsAScriptWould() {
        val lua = ConsoleZoom.setFontSizeLua("chat \"room\"\n", 14)
        assertTrue(lua, lua.contains("local name, size = \"chat \\\"room\\\"\\n\", 14"))
        assertTrue(lua, lua.contains("if setFontSize(name, size)"))
        // Auto wrap is redone, never Geyser's setFontSize(), which can resize the window
        assertTrue(lua, lua.contains("pcall(w.resetAutoWrap, w)"))
        assertTrue(lua, !lua.contains(":setFontSize"))
    }
}
