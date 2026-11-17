package org.mudlet.android.ui

import android.graphics.BitmapFactory
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
import java.io.DataOutputStream
import java.util.Base64

/** DIAG, not for merging: what the app's side of one map frame costs for Qt's PNG and for the engine's own. Always fails, with the times. */
@RunWith(AndroidJUnit4::class)
class MapFrameCostDiag {
    @Test
    fun theTimesOfAFrameOfEachWriter() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, medians of 9, ms\n")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            for (writer in listOf("qt", "own")) {
                val png = assets.open("diag/$picture-$writer.png").use { it.readBytes() }
                val json = JSONObject().put("t", "appMap").put("png", Base64.getEncoder().encodeToString(png)).put("w", 411).put("h", 892).put("viewOnly", true)
                val payload = json.toString().toByteArray()
                val frame = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(payload.size) }.write(payload) }.toByteArray()
                val read = mutableListOf<Double>()
                val decode = mutableListOf<Double>()
                var size = ""
                repeat(12) {
                    val start = System.nanoTime()
                    val message = FrameReader(ByteArrayInputStream(frame)).read() as EngineMessage.AppMap
                    val parsed = System.nanoTime()
                    val bitmap = BitmapFactory.decodeByteArray(message.png, 0, message.png.size)
                    val decoded = System.nanoTime()
                    size = "${bitmap.width}x${bitmap.height}"
                    bitmap.recycle()
                    if (it >= 3) {
                        read += (parsed - start) / 1e6
                        decode += (decoded - parsed) / 1e6
                    }
                }
                report.append("%-12s %-3s %7d B png %s: frame read (JSON + base64) %6.1f, BitmapFactory %6.1f\n".format(picture, writer, png.size, size, read.sorted()[4], decode.sorted()[4]))
            }
        }
        fail(report.toString())
    }
}
