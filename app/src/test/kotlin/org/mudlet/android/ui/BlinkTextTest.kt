package org.mudlet.android.ui

import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.RUN_BLINK
import org.mudlet.android.protocol.RUN_FAST_BLINK

/** Blinking text as desktop Mudlet draws it: pulsing with "Blinking text" on, steady in italics with it off */
class BlinkTextTest {
    private val line = UiLine("slow fast plain", intArrayOf(0, 4, -1, -1, RUN_BLINK, 0, 5, 4, -1, -1, RUN_FAST_BLINK, 0), prompt = false)

    @Test
    fun aPulseRunsFromWholeToFaintAndBack() {
        assertEquals(1f, blinkOpacity(0, fast = false), 1e-6f)
        assertEquals(0.4f, blinkOpacity(1000, fast = false), 1e-6f)
        assertEquals(0.7f, blinkOpacity(500, fast = false), 1e-6f)
        assertEquals(1f, blinkOpacity(2000, fast = false), 1e-6f)
        assertEquals(0.4f, blinkOpacity(500, fast = true), 1e-6f)
        assertEquals(1f, blinkOpacity(1000, fast = true), 1e-6f)
    }

    @Test
    fun onlyALineWithABlinkingRunBlinks() {
        assertTrue(blinks(line))
        assertFalse(blinks(UiLine("plain", intArrayOf(0, 5, -1, -1, 0, 0), prompt = false)))
    }

    @Test
    fun pulsingTextFadesAndSteadyTextIsItalic() {
        val pulsing = ansiText(line, blinkMs = 500)
        assertEquals(0.7f, styleAt(pulsing, 0).color.alpha, 0.01f)
        assertEquals(0.4f, styleAt(pulsing, 5).color.alpha, 0.01f)
        assertNull(styleAt(pulsing, 0).fontStyle)

        val steady = ansiText(line)
        assertEquals(1f, styleAt(steady, 0).color.alpha)
        assertEquals(FontStyle.Italic, styleAt(steady, 0).fontStyle)
        assertEquals(FontStyle.Italic, styleAt(steady, 5).fontStyle)
    }

    private fun styleAt(text: androidx.compose.ui.text.AnnotatedString, index: Int) = text.spanStyles.single { index >= it.start && index < it.end }.item
}
