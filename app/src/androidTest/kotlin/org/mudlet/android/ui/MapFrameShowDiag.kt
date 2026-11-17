package org.mudlet.android.ui

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.TextBehindMap
import java.nio.IntBuffer

/** DIAG, not for merging: the cut-out for the text behind the map, as main makes it and as the branch does. Always fails, with the times. */
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

    // overText as main has it
    private fun mainOverText(bitmap: Bitmap): Pair<TextBehindMap.Ground, Bitmap>? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val ground = MainTextBehindMap.ground(pixels, bitmap.width, bitmap.height) ?: return null
        val background = ground.background ?: return ground to bitmap
        MainTextBehindMap.cutOut(pixels, background)
        return ground to Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun theStagesOfTheCutOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val debuggable = instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, debuggable $debuggable, medians of 15 after 15, ms\n")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            val png = assets.open("diag/$picture-own.png").use { it.readBytes() }
            val message = EngineMessage.AppMap(png, 411, 892, viewOnly = true)
            val stages = Stages()
            var notes = ""
            repeat(30) { round ->
                stages.keep = round >= 15
                val bitmap = stages.time("decode (BitmapFactory)") { BitmapFactory.decodeByteArray(png, 0, png.size) }
                val width = bitmap.width
                val height = bitmap.height

                val pixels = stages.time("an IntArray of its pixels made") { IntArray(width * height) }
                stages.time("main: all pixels read (getPixels)") { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                val mainGround = stages.time("main: ground") { MainTextBehindMap.ground(pixels, width, height) }
                val shortcutArgb = stages.time("ground with the shortcut, of getPixels' pixels") { TextBehindMap.ground(pixels, width, height) }
                val background = mainGround?.background
                if (background != null) stages.time("main: cut out") { MainTextBehindMap.cutOut(pixels, background) }
                val mainCut = stages.time("main: bitmap of the pixels (createBitmap(pixels))") { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }

                val kept = IntArray(width * height)
                stages.time("branch: all pixels copied (copyPixelsToBuffer)") { bitmap.copyPixelsToBuffer(IntBuffer.wrap(kept)) }
                val plainGround = stages.time("branch without the shortcut: ground") { NoShortcutTextBehindMap.ground(kept, width, height, TextBehindMap.Layout.ABGR) }
                val ground = stages.time("branch: ground") { TextBehindMap.ground(kept, width, height, TextBehindMap.Layout.ABGR) }
                if (background != null) stages.time("branch: cut out") { TextBehindMap.cutOut(kept, background, TextBehindMap.Layout.ABGR) }
                val cut = stages.time("branch: bitmap of the pixels (createBitmap, copyPixelsFromBuffer)") {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(kept)) }
                }

                val mainWhole = stages.time("main: the whole cut-out (overText)") { mainOverText(bitmap) }
                val whole = stages.time("branch: the whole cut-out (overText)") { AppMapClient.overText(bitmap) }
                AppMapClient.textBehind = false
                val behind = stages.time("branch: AppMapClient.decode, map behind the text") { AppMapClient.decode(message) }
                AppMapClient.textBehind = true
                val front = stages.time("branch: AppMapClient.decode, text behind the map") { AppMapClient.decode(message) }
                AppMapClient.textBehind = false

                val what = "${width}x$height ${bitmap.config} ${bitmap.colorSpace} premultiplied ${bitmap.isPremultiplied} hasAlpha ${bitmap.hasAlpha()}"
                val same = mainGround == ground && mainGround == plainGround && mainGround == shortcutArgb && mainGround == whole?.ground && mainGround == mainWhole?.first &&
                    mainCut.sameAs(cut) && whole != null && mainCut.sameAs(whole.frame.asAndroidBitmap())
                if (!same) notes = "THE CUT-OUTS DIFFER "
                if (round == 29) notes += "$what, ${ground?.solid?.size} colours, background $background, same $same"
                // Ninety rounds of frames of 10 MB each
                for (used in listOf(behind.bitmap, front.bitmap, front.overText?.frame, whole?.frame)) used?.asAndroidBitmap()?.recycle()
                for (used in listOf(mainCut, cut, mainWhole?.second, bitmap)) used?.recycle()
            }
            report.append("$picture: png ${png.size} B; $notes\n")
            for ((name, times) in stages.times) report.append("  %-66s %7.2f\n".format(name, median(times)))
        }
        fail(report.toString())
    }
}
