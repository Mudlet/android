package org.mudlet.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import kotlin.random.Random

/** LineFrames reads every frame it takes as JSONObject does, and leaves every other one to JSONObject. */
class LineFramesTest {
    @Test
    fun framesAsLineJsonWritesThemAreReadWithoutJsonObjectAndAsItReadsThem() {
        for (frame in LINE_JSON_FRAMES) {
            val payload = frame.toByteArray()
            val fast = LineFrames.parse(payload)
            assertNotNull("read without JSONObject: $frame", fast)
            assertEquals(frame, described(viaJsonObject(payload)), described(fast!!))
        }
    }

    @Test
    fun framesOutsideWhatLineJsonWritesAreLeftToJsonObject() {
        val frames = listOf(
            """{ "prompt":false,"runs":[],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"x","time":0,"png":""}""",
            """{"prompt":false,"prompt":true,"runs":[],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1.5,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1e3,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":01,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":-0,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1234567890123456789,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[[0,1,1234567890]],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[[0,1]],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[[0,1,null]],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":7,"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":"true","runs":[],"seq":1,"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"a${'\n'}b","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"\x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"\u+041","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"\u00","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"li\u006ee","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","text":"x","time":0}{}""",
            """{"prompt":false,"runs":[],"t":"line","text":"x","time":0}""",
            """{"prompt":false,"runs":[],"seq":1,"t":"line","time":0}""",
            """{"prompt":false,"runs":[],"t":"lineUpdated","text":"x"}""",
            """{"prompt":false,"runs":[],"seq":1,"text":"x","time":0}""",
            """{"seq":1,"t":"synced"}""",
            """{"t":"hello","v":1,"pid":2,"instance":"i"}""",
            """{}""",
            "",
        )
        for (frame in frames) {
            val payload = frame.toByteArray()
            assertNull("left to JSONObject: $frame", LineFrames.parse(payload))
            assertEquals(frame, outcome { viaJsonObject(payload) }, outcome { FrameReader.parse(payload) })
        }
    }

    @Test
    fun aFrameItTakesFromAnyChangeToOneItReadsIsReadAsJsonObjectReadsIt() {
        val rng = Random(9011)
        val seeds = (1L..200L).map { fightFrame(it, rng) } + LINE_JSON_FRAMES.map { it.toByteArray() } + """{"prompt":false,"runs":[[0,2,7,1,2,3]],"seq":5,"t":"line","text":"é\u00e9\ud834\udd1e","time":0}""".toByteArray()
        val alphabet = "{}[]\",:-.0123456789eE truefalsnul\\/bfnrtu\u0001".toByteArray() + byteArrayOf(0xc3.toByte(), 0xa9.toByte(), 0xe2.toByte(), 0x80.toByte())
        var taken = 0
        repeat(20_000) {
            val payload = seeds[rng.nextInt(seeds.size)].copyOf()
            val changes = 1 + rng.nextInt(3)
            val mutated = (0 until changes).fold(payload) { bytes, _ ->
                val at = rng.nextInt(bytes.size + 1)
                when (rng.nextInt(3)) {
                    0 -> bytes.copyOf().also { if (at < it.size) it[at] = alphabet[rng.nextInt(alphabet.size)] }
                    1 -> bytes.copyOfRange(0, at) + alphabet[rng.nextInt(alphabet.size)] + bytes.copyOfRange(at, bytes.size)
                    else -> if (at < bytes.size) bytes.copyOfRange(0, at) + bytes.copyOfRange(at + 1, bytes.size) else bytes
                }
            }
            val fast = LineFrames.parse(mutated) ?: return@repeat
            taken++
            val text = String(mutated, Charsets.ISO_8859_1)
            assertEquals(text, outcome { viaJsonObject(mutated) }, described(fast))
        }
        assertTrue("some changed frames are still LineJson's and read without JSONObject ($taken)", taken > 1_000)
    }

    @Test
    fun aFightsLinesTakeLittleMoreToReadThanTheirOwnText() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assertTrue("this JVM counts what each thread allocates", threads != null && threads.isThreadAllocatedMemorySupported)
        val rng = Random(9011)
        val n = 20_000
        val stream = ByteArrayOutputStream()
        repeat(n) {
            val p = fightFrame(it + 1L, rng)
            stream.write(byteArrayOf((p.size ushr 24).toByte(), (p.size ushr 16).toByte(), (p.size ushr 8).toByte(), p.size.toByte()))
            stream.write(p)
        }
        val bytes = stream.toByteArray()
        val id = Thread.currentThread().id
        var perLine = Long.MAX_VALUE
        // The fewest of three rounds: the first also loads and compiles the reader
        repeat(3) {
            val reader = FrameReader(ByteArrayInputStream(bytes))
            val before = threads!!.getThreadAllocatedBytes(id)
            repeat(n) { reader.read() }
            perLine = minOf(perLine, (threads.getThreadAllocatedBytes(id) - before) / n)
        }
        // The frame's bytes, its text, its runs and the Line come to about 570; through JSONObject, 4,500 and more
        assertTrue("$perLine bytes allocated a line", perLine < MAX_BYTES_A_LINE)
    }

    private fun viaJsonObject(payload: ByteArray) = FrameReader.parse(JSONObject(String(payload, Charsets.UTF_8)))

    private fun outcome(read: () -> EngineMessage): String = try {
        described(read())
    } catch (e: Exception) {
        "threw ${e.javaClass.simpleName}"
    }

    private fun described(m: EngineMessage): String = when (m) {
        is EngineMessage.Line -> "Line(${m.seq}, ${m.text.escaped()}, ${m.runs.toList()}, ${m.prompt}, ${m.time}, ${m.replay})"
        is EngineMessage.Tail -> "Tail(${m.text.escaped()}, ${m.runs.toList()}, ${m.prompt}, ${m.time})"
        is EngineMessage.LineUpdated -> "LineUpdated(${m.seq}, ${m.text.escaped()}, ${m.runs.toList()}, ${m.prompt}, ${m.deleted})"
        else -> m.toString()
    }

    private fun String.escaped() = map { if (it.code < 0x20 || it.isSurrogate()) "\\u%04x".format(it.code) else "$it" }.joinToString("")

    private companion object {
        const val MAX_BYTES_A_LINE = 1_500

        // Each kind of frame and run LineJson writes, and a line with only the keys a line must have
        val LINE_JSON_FRAMES = listOf(
            """{"prompt":false,"runs":[[0,5,7]],"seq":1,"t":"line","text":"Hello","time":1764000000000}""",
            """{"prompt":true,"replay":true,"runs":[[0,3,2,-1,1],[3,4,-1,4,0,9]],"seq":9007199254740993,"t":"line","text":"H:1 M:2","time":5}""",
            """{"prompt":false,"runs":[],"seq":2,"t":"line","text":"","time":0}""",
            """{"prompt":false,"runs":[[0,8,7]],"seq":3,"t":"line","text":"tab\there \"q\" \\ \/ \b\f\n\r","time":0}""",
            """{"prompt":false,"runs":[[0,6,7]],"seq":4,"t":"line","text":"café ☕ 𝄞 日本","time":0}""",
            """{"prompt":false,"runs":[[0,2,7]],"seq":5,"t":"line","text":"\u001b[\ud800 lone\udfff\u0000","time":0}""",
            """{"prompt":true,"runs":[[0,6,3,0,0,2147483]],"t":"tail","text":"Name? ","time":7}""",
            """{"prompt":false,"runs":[[0,1,7]],"t":"tail","time":7}""",
            """{"prompt":false,"runs":[[0,4,1]],"seq":6,"t":"lineUpdated","text":"Edit"}""",
            """{"deleted":true,"seq":6,"t":"lineUpdated"}""",
            """{"seq":7,"t":"line","text":"no runs, prompt or time"}""",
        )
        val WORDS = "You slash a goblin with your longsword dealing critical damage The orc parries and ripostes".split(" ")

        // As LineJson writes native/tests/spam_bench.py's fight: a prompt every sixth line, else a bold coloured head
        fun fightFrame(seq: Long, rng: Random): ByteArray {
            val k = seq.toInt()
            val text: String
            val runs: String
            if (k % 6 == 5) {
                val health = "H:${rng.nextInt(2400)}/2400"
                val rest = " M:${rng.nextInt(1800)}/1800 [eb] "
                text = health + rest
                runs = "[[0,${health.length},2],[${health.length},${rest.length},-1]]"
            } else {
                val words = (0 until 6 + rng.nextInt(12)).joinToString(" ") { WORDS[rng.nextInt(WORDS.size)] }
                val head = words.take(12)
                val mid = words.drop(12) + " "
                val tag = "[$k]"
                text = head + mid + tag
                runs = "[[0,${head.length},${9 + k % 7},-1,1],[${head.length},${mid.length},-1],[${head.length + mid.length},${tag.length},3]]"
            }
            return """{"prompt":false,"runs":$runs,"seq":$seq,"t":"line","text":"$text","time":1764000000000}""".toByteArray()
        }
    }
}
