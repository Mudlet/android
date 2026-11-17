package org.mudlet.android.ui

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.FrameReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.concurrent.thread

/** DIAG, not for merging: where the app's read of one map frame goes, and what two other ways to read it would cost. Always fails, with the times. */
@RunWith(AndroidJUnit4::class)
class MapFrameReadDiag {
    private val quote = '"'.code.toByte()
    private val backslash = '\\'.code.toByte()

    private fun skipSpace(b: ByteArray, from: Int): Int {
        var at = from
        while (at < b.size && (b[at] == ' '.code.toByte() || b[at] == '\n'.code.toByte() || b[at] == '\t'.code.toByte() || b[at] == '\r'.code.toByte())) at++
        return at
    }

    /** The closing quote of the string whose first byte is at [from]; null if it does not close. */
    private fun stringEnd(b: ByteArray, from: Int): Int? {
        var at = from
        while (at < b.size) {
            val c = b[at]
            if (c == quote) return at
            at += if (c == backslash) 2 else 1
        }
        return null
    }

    private fun valueEnd(b: ByteArray, from: Int): Int? {
        var at = from
        if (at >= b.size) return null
        if (b[at] == quote) return stringEnd(b, at + 1)?.plus(1)
        if (b[at] == '{'.code.toByte() || b[at] == '['.code.toByte()) {
            var depth = 0
            while (at < b.size) {
                when (b[at].toInt().toChar()) {
                    '"' -> at = stringEnd(b, at + 1) ?: return null
                    '{', '[' -> depth++
                    '}', ']' -> if (--depth == 0) return at + 1
                }
                at++
            }
            return null
        }
        while (at < b.size && b[at] != ','.code.toByte() && b[at] != '}'.code.toByte()) at++
        return at
    }

    /** Inside the quotes of the top-level string value of "png": its first byte and its closing quote. */
    private fun pngValue(b: ByteArray): Pair<Int, Int>? {
        var at = skipSpace(b, 0)
        if (at >= b.size || b[at] != '{'.code.toByte()) return null
        at = skipSpace(b, at + 1)
        while (at < b.size && b[at] == quote) {
            val keyEnd = stringEnd(b, at + 1) ?: return null
            val isPng = keyEnd - at == 4 && b[at + 1] == 'p'.code.toByte() && b[at + 2] == 'n'.code.toByte() && b[at + 3] == 'g'.code.toByte()
            at = skipSpace(b, keyEnd + 1)
            if (at >= b.size || b[at] != ':'.code.toByte()) return null
            at = skipSpace(b, at + 1)
            if (isPng) {
                if (at >= b.size || b[at] != quote) return null
                var end = at + 1
                while (end < b.size && b[end] != quote) {
                    if (b[end] == backslash) return null
                    end++
                }
                return if (end < b.size) (at + 1) to end else null
            }
            at = skipSpace(b, valueEnd(b, at) ?: return null)
            if (at >= b.size || b[at] != ','.code.toByte()) return null
            at = skipSpace(b, at + 1)
        }
        return null
    }

    private val values = IntArray(256) { -1 }.also { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> it[c.code] = i } }

    private fun ownBase64(src: ByteArray, from: Int, to: Int): ByteArray? {
        val length = to - from
        if (length % 4 != 0) return null
        if (length == 0) return ByteArray(0)
        val pad = if (src[to - 1] == '='.code.toByte()) (if (src[to - 2] == '='.code.toByte()) 2 else 1) else 0
        val out = ByteArray(length / 4 * 3 - pad)
        val whole = if (pad > 0) to - 4 else to
        var s = from
        var d = 0
        var bad = 0
        val t = values
        while (s < whole) {
            val v = (t[src[s].toInt() and 0xFF] shl 18) or (t[src[s + 1].toInt() and 0xFF] shl 12) or (t[src[s + 2].toInt() and 0xFF] shl 6) or t[src[s + 3].toInt() and 0xFF]
            bad = bad or v
            out[d] = (v shr 16).toByte()
            out[d + 1] = (v shr 8).toByte()
            out[d + 2] = v.toByte()
            s += 4
            d += 3
        }
        if (pad > 0) {
            val v = (t[src[s].toInt() and 0xFF] shl 18) or (t[src[s + 1].toInt() and 0xFF] shl 12) or (if (pad == 1) t[src[s + 2].toInt() and 0xFF] shl 6 else 0)
            bad = bad or v
            out[d] = (v shr 16).toByte()
            if (pad == 1) out[d + 1] = (v shr 8).toByte()
        }
        return if (bad < 0) null else out
    }

    private fun median(times: List<Double>) = times.sorted()[times.size / 2]

    @Test
    fun theStagesOfTheReadOfAMapFrame() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, medians of 9, ms\n")
        val server = LocalServerSocket("map-frame-read-diag")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            val png = assets.open("diag/$picture-own.png").use { it.readBytes() }
            // As QJsonDocument writes it: compact, the keys in order
            val text = """{"h":892,"info":{"lines":["Area: The Shire","Room: 1234"]},"png":"${Base64.getEncoder().encodeToString(png)}","t":"appMap","view":{"area":7,"x":1,"y":2,"z":0,"zoom":18.5},"viewOnly":true,"w":411}"""
            val payload = text.toByteArray()
            val frame = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(payload.size) }.write(payload) }.toByteArray()
            val names = listOf("now: socket to bytes", "now: bytes to String", "now: String to JSON", "now: base64 of a String", "now: all, no socket", "A: find png in bytes", "A: base64 of bytes (java.util)", "A: base64 of bytes (own)", "A: the rest to JSON", "A: all, no socket (own base64)", "B: raw png bytes, no socket")
            val times = names.associateWith { mutableListOf<Double>() }
            var same = true
            repeat(12) { round ->
                val marks = mutableListOf(System.nanoTime())
                fun mark() {
                    marks += System.nanoTime()
                }
                val writer = thread {
                    server.accept().use { it.outputStream.apply { write(frame) }.flush() }
                }
                val got = LocalSocket().use { socket ->
                    socket.connect(LocalSocketAddress("map-frame-read-diag"))
                    marks[0] = System.nanoTime()
                    val data = DataInputStream(socket.inputStream.buffered(64 * 1024))
                    ByteArray(data.readInt()).also(data::readFully)
                }
                mark()
                writer.join()
                val string = String(got, Charsets.UTF_8)
                mark()
                val json = JSONObject(string)
                mark()
                val fromString = Base64.getDecoder().decode(json.optString("png"))
                mark()
                val whole = FrameReader(ByteArrayInputStream(frame)).read() as EngineMessage.AppMap
                mark()
                val (from, to) = pngValue(got)!!
                mark()
                val javaUtil = Base64.getDecoder().decode(ByteBuffer.wrap(got, from, to - from))
                mark()
                val own = ownBase64(got, from, to)!!
                mark()
                val rest = JSONObject(String(got, 0, from, Charsets.UTF_8) + String(got, to, got.size - to, Charsets.UTF_8))
                mark()
                val start = System.nanoTime()
                val data = DataInputStream(ByteArrayInputStream(frame).buffered(64 * 1024))
                val bytes = ByteArray(data.readInt()).also(data::readFully)
                val range = pngValue(bytes)!!
                val picture2 = ownBase64(bytes, range.first, range.second)!!
                val rest2 = JSONObject(String(bytes, 0, range.first, Charsets.UTF_8) + String(bytes, range.second, bytes.size - range.second, Charsets.UTF_8))
                val allA = System.nanoTime() - start
                val rawStart = System.nanoTime()
                val raw = ByteArray(png.size).also(DataInputStream(ByteArrayInputStream(png).buffered(64 * 1024))::readFully)
                val rawTime = System.nanoTime() - rawStart
                same = same && fromString.contentEquals(png) && whole.png.contentEquals(png) && own.contentEquals(png) && picture2.contentEquals(png) && raw.contentEquals(png) &&
                    javaUtil.remaining() == png.size && javaUtil.array().size == png.size && javaUtil.array().contentEquals(png) &&
                    rest.optString("t") == "appMap" && rest.optString("png") == "" && rest2.optInt("w") == 411
                if (round >= 3) {
                    names.take(9).forEachIndexed { i, name -> times.getValue(name) += (marks[i + 1] - marks[i]) / 1e6 }
                    times.getValue(names[9]) += allA / 1e6
                    times.getValue(names[10]) += rawTime / 1e6
                }
            }
            report.append("$picture: png ${png.size} B, frame ${frame.size} B, all ways give the png: $same\n")
            for (name in names) report.append("  %-34s %6.2f\n".format(name, median(times.getValue(name))))
        }
        server.close()
        fail(report.toString())
    }
}
