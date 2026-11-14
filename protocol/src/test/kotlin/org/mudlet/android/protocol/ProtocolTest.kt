package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The run encoding and message types of native/enginehost/SocketBridge.cpp, without an engine. */
class ProtocolTest {
    @Test
    fun runsOfEachLengthFlattenToTheSameStride() {
        val line = FrameReader.parse(
            JSONObject("""{"t":"line","seq":7,"text":"abcdef","prompt":false,"runs":[[0,1,9],[1,2,-1,4,5],[3,3,${RUN_RGB or 0x123456},-1,0,42]]}"""),
        ) as EngineMessage.Line
        assertArrayEquals(
            intArrayOf(0, 1, 9, -1, 0, 0, 1, 2, -1, 4, RUN_BOLD or RUN_UNDERLINE, 0, 3, 3, RUN_RGB or 0x123456, -1, 0, 42),
            line.runs,
        )
        assertEquals(3 * RUN_STRIDE, line.runs.size)
    }

    @Test
    fun theNewStylesHaveBitsOfTheirOwn() {
        val bits = listOf(RUN_BOLD, RUN_ITALIC, RUN_UNDERLINE, RUN_STRIKE, RUN_REVERSE, RUN_OVERLINE, RUN_BLINK, RUN_FAST_BLINK, RUN_CONCEALED)
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256), bits)
    }

    @Test
    fun tailAndClearParse() {
        val tail = FrameReader.parse(JSONObject("""{"t":"tail","text":"HP> ","runs":[[0,2,1,-1,1]],"prompt":true}""")) as EngineMessage.Tail
        assertEquals("HP> ", tail.text)
        assertTrue(tail.prompt)
        assertArrayEquals(intArrayOf(0, 2, 1, -1, 1, 0), tail.runs)
        assertEquals(EngineMessage.Clear, FrameReader.parse(JSONObject("""{"t":"clear"}""")))
    }
}
