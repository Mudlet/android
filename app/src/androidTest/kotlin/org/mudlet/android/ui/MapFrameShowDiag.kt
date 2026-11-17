package org.mudlet.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.MapBehindText
import org.mudlet.android.protocol.TextBehindMap

/** DIAG, not for merging: what a map frame costs the app once it is read, a stage at a time. Always fails, with the times. */
@RunWith(AndroidJUnit4::class)
class MapFrameShowDiag {
    private fun median(times: List<Double>) = times.sorted()[times.size / 2]

    private class Stages {
        val times = linkedMapOf<String, MutableList<Double>>()
        var keep = false

        inline fun <T> time(name: String, block: () -> T): T {
            val start = System.nanoTime()
            val result = block()
            val took = (System.nanoTime() - start) / 1e6
            if (keep) times.getOrPut(name) { mutableListOf() } += took
            return result
        }
    }

    @Test
    fun theStagesOfShowingAMapFrame() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, medians of 9, ms\n")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            val png = assets.open("diag/$picture-own.png").use { it.readBytes() }
            val same = png.copyOf()
            val other = png.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
            val message = EngineMessage.AppMap(png, 411, 892, viewOnly = true)
            val stages = Stages()
            var reused: Bitmap? = null
            var notes = ""
            repeat(12) { round ->
                stages.keep = round >= 3
                val bitmap = stages.time("decode (BitmapFactory)") { BitmapFactory.decodeByteArray(png, 0, png.size) }
                val width = bitmap.width
                val height = bitmap.height
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                    inBitmap = reused
                }
                reused = stages.time("decode into the bitmap before the last (inBitmap)") { BitmapFactory.decodeByteArray(png, 0, png.size, options) }
                stages.time("tones (128 rows read)") { MapBehindText.tones(width, height) { y, row -> bitmap.getPixels(row, 0, width, 0, y, width, 1) } }
                val pixels = IntArray(width * height)
                stages.time("text behind: all pixels read") { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                val ground = stages.time("text behind: ground") { TextBehindMap.ground(pixels, width, height) }
                val background = ground?.background
                if (background != null) stages.time("text behind: cut out") { TextBehindMap.cutOut(pixels, background) }
                stages.time("text behind: bitmap of the pixels") { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }
                AppMapClient.textBehind = false
                stages.time("AppMapClient.decode, map behind the text") { AppMapClient.decode(message) }
                AppMapClient.textBehind = true
                stages.time("AppMapClient.decode, text behind the map") { AppMapClient.decode(message) }
                AppMapClient.textBehind = false
                val equal = stages.time("PNG bytes told the same") { png.contentEquals(same) }
                val differ = stages.time("PNG bytes told apart at the end") { png.contentEquals(other) }
                val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                stages.time("drawn in software (a copy of its pixels)") { Canvas(target).drawBitmap(bitmap, 0f, 0f, null) }
                val hardware = try {
                    stages.time("copied to a hardware bitmap (upload)") { bitmap.copy(Bitmap.Config.HARDWARE, false) }
                } catch (e: Exception) {
                    notes = "hardware copy threw $e"
                    null
                }
                if (hardware == null && notes.isEmpty()) notes = "no hardware copy"
                if (!equal || differ) notes += " COMPARE WRONG"
                notes = notes.ifEmpty { "${width}x$height, ground ${ground != null}, background ${background != null}" }
            }
            report.append("$picture: png ${png.size} B; $notes\n")
            for ((name, times) in stages.times) report.append("  %-52s %7.2f\n".format(name, median(times)))
        }
        fail(report.toString())
    }
}
