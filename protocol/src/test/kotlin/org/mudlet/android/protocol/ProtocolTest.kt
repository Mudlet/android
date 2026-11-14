package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

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

    @Test
    fun profilesParseInTheEnginesOrder() {
        val message = FrameReader.parse(
            JSONObject(
                """{"t":"profiles","re":"r1","profiles":[""" +
                    """{"name":"Beta","host":"b.example","port":4000,"tls":true,"login":"hero","autoReconnect":true},""" +
                    """{"name":"Alpha","host":"a.example","port":23,"tls":false,"login":"","autoReconnect":false}]}""",
            ),
        )
        assertEquals(
            EngineMessage.Profiles(
                listOf(Profile("Beta", "b.example", 4000, true, "hero", true), Profile("Alpha", "a.example", 23, false, "", false)),
                "r1",
            ),
            message,
        )
        assertEquals(EngineMessage.Error("openProfile: no", 5), FrameReader.parse(JSONObject("""{"t":"error","message":"openProfile: no","re":5}""")))
    }

    @Test
    fun theProfileRequestsCarryWhatTheEngineReads() {
        val saved = Requests.saveProfile(Profile("Alpha", "a.example", 4000, true, "hero", false), 1)
        val fields = mapOf("t" to "saveProfile", "name" to "Alpha", "host" to "a.example", "port" to 4000, "tls" to true, "login" to "hero")
        assertEquals(fields + mapOf("autoReconnect" to false, "id" to 1L), saved.toMap())
        assertEquals(mapOf("t" to "openProfile", "name" to "Alpha", "connect" to false, "id" to 2L), Requests.openProfile("Alpha", null, false, 2).toMap())
        assertEquals("pw", Requests.openProfile("Alpha", "pw", true, 3).getString("password"))
        assertEquals(mapOf("t" to "deleteProfile", "name" to "Alpha", "id" to 4L), Requests.deleteProfile("Alpha", 4).toMap())
        assertEquals(mapOf("t" to "listProfiles", "id" to 5L), Requests.listProfiles(5).toMap())
    }

    @Test
    fun theInputLineMessagesParse() {
        assertEquals(EngineMessage.HiddenInput(true), FrameReader.parse(JSONObject("""{"t":"hiddenInput","wanted":true}""")))
        assertEquals(EngineMessage.HiddenInput(false), FrameReader.parse(JSONObject("""{"t":"hiddenInput","wanted":false}""")))
        assertEquals(
            EngineMessage.History(listOf("north", "say hi")),
            FrameReader.parse(JSONObject("""{"t":"history","lines":["north","say hi"]}""")),
        )
        assertEquals(EngineMessage.History(emptyList()), FrameReader.parse(JSONObject("""{"t":"history","lines":[]}""")))
        assertEquals(EngineMessage.CommandLine("x"), FrameReader.parse(JSONObject("""{"t":"commandLine","text":"x"}""")))
        assertEquals(
            EngineMessage.Status("completion", "say hello", 6),
            FrameReader.parse(JSONObject("""{"t":"status","key":"completion","value":"say hello","re":6}""")),
        )
    }

    @Test
    fun theInputLineRequestsCarryWhatTheEngineReads() {
        assertEquals(mapOf("t" to "sendHidden", "text" to "pw"), Requests.sendHidden("pw").toMap())
        assertEquals(mapOf("t" to "dismissHidden"), Requests.dismissHidden().toMap())
        assertEquals(mapOf("t" to "complete", "text" to "say he", "fromHistory" to false, "id" to 6L), Requests.complete("say he", false, 6).toMap())
        assertEquals(true, Requests.complete("say", true, 7).getBoolean("fromHistory"))
    }

    @Test
    fun aFrameIsOneWriteAndNeverFlushed() {
        val writes = ArrayList<ByteArray>()
        val out = object : OutputStream() {
            override fun write(b: Int) = throw AssertionError("a frame written byte by byte")
            override fun write(b: ByteArray, off: Int, len: Int) {
                writes.add(b.copyOfRange(off, off + len))
            }

            override fun flush() = throw AssertionError("flushed: a LocalSocket's flush() waits for the engine to read")
        }
        val message = Requests.ping(7)
        FrameWriter(out).write(message)
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(byteArrayOf(0, 0, 0, payload.size.toByte()) + payload, writes.single())
    }
}
