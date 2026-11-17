package org.mudlet.android.protocol

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.random.Random

/** A frame's picture is read from the frame's own bytes, and reads as it does from the frame's JSON. */
class FramePictureTest {
    private val none = ByteArray(0)

    private fun picture(m: EngineMessage): ByteArray? = when (m) {
        is EngineMessage.Map -> m.png
        is EngineMessage.AppMap -> m.png
        is EngineMessage.Image -> m.png
        else -> null
    }

    // A data class compares its arrays by which they are, not by what they hold
    private fun withoutPicture(m: EngineMessage): EngineMessage = when (m) {
        is EngineMessage.Map -> m.copy(png = none)
        is EngineMessage.AppMap -> m.copy(png = none)
        is EngineMessage.Image -> m.copy(png = none)
        else -> m
    }

    private fun readsTheSameBothWays(frame: String): EngineMessage {
        val fromBytes = FrameReader.parse(frame.toByteArray())
        val fromJson = FrameReader.parse(JSONObject(frame))
        assertArrayEquals(frame, picture(fromJson), picture(fromBytes))
        assertEquals(frame, withoutPicture(fromJson), withoutPicture(fromBytes))
        return fromBytes
    }

    @Test
    fun aFrameAsTheEngineWritesItGivesItsPicture() {
        val png = Random(7).nextBytes(1000)
        val base64 = Base64.getEncoder().encodeToString(png)
        // Text that takes more than a byte a character on both sides of the picture, as the picture's place is counted in bytes
        val around = """"info":{"lines":[{"text":"Überwald – 北"}]},"png":"$base64","view":{"area":2,"areaName":"Ünder","level":-1,"zoom":12.5},"viewOnly":false,"w":400"""
        val map = readsTheSameBothWays("""{"h":300,$around,"t":"map"}""") as EngineMessage.Map
        assertArrayEquals(png, map.png)
        assertEquals("Überwald – 北", map.info?.lines?.single()?.text)
        assertEquals("Ünder", map.view?.areaName)
        assertEquals(false, map.viewOnly)
        val appMap = readsTheSameBothWays("""{"h":300,$around,"t":"appMap"}""") as EngineMessage.AppMap
        assertArrayEquals(png, appMap.png)
        assertEquals(400 to 300, appMap.w to appMap.h)
        val image = readsTheSameBothWays("""{"h":3,"path":"/data/ä.png","png":"$base64","re":9,"t":"image","w":4}""") as EngineMessage.Image
        assertArrayEquals(png, image.png)
        assertEquals("/data/ä.png", image.path)
        assertEquals(9, image.replyTo)
    }

    @Test
    fun aFrameWrittenAnotherWayReadsAsItsJsonDoes() {
        for (length in 0..5) {
            val png = Random(length).nextBytes(length)
            val padded = Base64.getEncoder().encodeToString(png)
            assertArrayEquals(png, picture(readsTheSameBothWays("""{"png":"$padded","t":"map"}""")))
            assertArrayEquals(png, picture(readsTheSameBothWays("""{"png":"${padded.trimEnd('=')}","t":"map"}""")))
        }
        val slashes = byteArrayOf(-1, -1, -1)
        assertArrayEquals(slashes, picture(readsTheSameBothWays("""{"png":"////","t":"map"}""")))
        assertArrayEquals("an escape is not base64's own", slashes, picture(readsTheSameBothWays("""{"png":"\/\/\/\/","t":"map"}""")))
        assertArrayEquals("space around the colon", slashes, picture(readsTheSameBothWays("""{ "png" : "////" , "t" : "map" }""")))
        for (broken in listOf("not base64!", "QUJD\nQUJD", "QUJDQ", "QUJD=", "QQ=", "QQ==QUJD", "Ünder")) {
            assertEquals(broken, 0, picture(readsTheSameBothWays(JSONObject().put("t", "appMap").put("png", broken).toString()))?.size)
        }
        for (other in listOf("""{"png":5,"t":"map"}""", """{"png":null,"t":"map"}""", """{"png":["QUJD"],"t":"map"}""", """{"t":"map","w":1}""")) {
            readsTheSameBothWays(other)
        }
        assertEquals(EngineMessage.Clear, readsTheSameBothWays("""{"png":"QUJD","t":"clear"}"""))
    }

    @Test
    fun onlyTheFramesOwnPngIsItsPicture() {
        val own = byteArrayOf(68, 69, 70)
        for (frame in listOf(
            """{"info":{"png":"QUJD","lines":[]},"png":"REVG","t":"map"}""",
            """{"info":{"lines":[{"text":"\"png\":\"QUJD\""}]},"png":"REVG","t":"map"}""",
            """{"info":{"lines":[{"text":"a brace } and a bracket ] and \\"}]},"png":"REVG","t":"map"}""",
            """{"path":"png","png":"REVG","t":"image"}""",
            """{"levels":[["png"]],"png":"REVG","t":"appMap"}""",
        )) {
            assertArrayEquals(frame, own, picture(readsTheSameBothWays(frame)))
        }
        for (frame in listOf("""{"info":{"png":"QUJD"},"t":"map"}""", """{"info":{"a":"\"","b":"}\"","png":"QUJD"},"t":"map"}""", """{"t":"map"}{"png":"QUJD"}""")) {
            assertEquals(frame, 0, picture(readsTheSameBothWays(frame))?.size)
        }
    }

    @Test
    fun aPayloadThatIsNotJsonIsStillRefused() {
        for (junk in listOf("""{"png":"QUJD","t":"map"""", """{"png":"QUJD""", """{"png":"QUJD\""", """x{"png":"QUJD","t":"map"}""", """["png":"QUJD"]""", """{"t":"map","a":"\""", "", "\"")) {
            assertThrows(junk, JSONException::class.java) { FrameReader.parse(junk.toByteArray()) }
        }
    }

    @Test
    fun aPictureIsNotCopiedOnItsWayOutOfItsFrame() {
        fun frame(png: ByteArray): ByteArray {
            val payload = """{"h":2342,"info":{"lines":[{"text":"Area: The Shire"}]},"png":"${Base64.getEncoder().encodeToString(png)}","t":"appMap","viewOnly":true,"w":1079}""".toByteArray()
            return ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(payload.size) }.write(payload) }.toByteArray()
        }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        // The first read loads the classes, which is not the frame's own cost
        FrameReader(ByteArrayInputStream(frame(ByteArray(16)))).read()
        val png = Random(1).nextBytes(1024 * 1024)
        val bytes = frame(png)
        val reader = FrameReader(ByteArrayInputStream(bytes))
        val before = threads.currentThreadAllocatedBytes
        val map = reader.read() as EngineMessage.AppMap
        val allocated = threads.currentThreadAllocatedBytes - before
        assertArrayEquals(png, map.png)
        // The frame once and the picture once: read as part of the JSON's text, the picture is held several times more
        val needed = bytes.size + png.size
        assertTrue("reading a frame of ${bytes.size} bytes allocated $allocated", allocated < needed + 256 * 1024)
    }
}
