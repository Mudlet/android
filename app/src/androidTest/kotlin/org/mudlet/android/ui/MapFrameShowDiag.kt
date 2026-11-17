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

    // The branch's overText without ground's shortcut
    private fun plainOverText(bitmap: Bitmap, pixels: IntArray): Pair<TextBehindMap.Ground, Bitmap>? {
        bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        val ground = NoShortcutTextBehindMap.ground(pixels, bitmap.width, bitmap.height, TextBehindMap.Layout.ABGR) ?: return null
        val background = ground.background ?: return ground to bitmap
        TextBehindMap.cutOut(pixels, background, TextBehindMap.Layout.ABGR)
        return ground to Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(pixels)) }
    }

    @Test
    fun theStagesOfTheCutOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val debuggable = instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, debuggable $debuggable, medians of 25 after 15, ms\n")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            val png = assets.open("diag/$picture-own.png").use { it.readBytes() }
            val message = EngineMessage.AppMap(png, 411, 892, viewOnly = true)
            val stages = Stages()
            var notes = ""
            var keptPlain = IntArray(0)
            repeat(40) { round ->
                stages.keep = round >= 15
                val bitmap = stages.time("decode (BitmapFactory)") { BitmapFactory.decodeByteArray(png, 0, png.size) }
                val width = bitmap.width
                val height = bitmap.height
                if (round == 0) keptPlain = IntArray(width * height)

                // Each in turn first, so that neither always has the memory the other let go
                var mainWhole: Pair<TextBehindMap.Ground, Bitmap>? = null
                var whole: AppMapClient.OverText? = null
                var plain: Pair<TextBehindMap.Ground, Bitmap>? = null
                for (which in if (round % 2 == 0) listOf(0, 1, 2) else listOf(2, 1, 0)) {
                    when (which) {
                        0 -> mainWhole = stages.time("main: the whole cut-out") { mainOverText(bitmap) }
                        1 -> whole = stages.time("branch: the whole cut-out (AppMapClient.overText)") { AppMapClient.overText(bitmap) }
                        else -> plain = stages.time("branch without ground's shortcut: the whole cut-out") { plainOverText(bitmap, keptPlain) }
                    }
                }
                AppMapClient.textBehind = false
                val behind = stages.time("branch: AppMapClient.decode, map behind the text") { AppMapClient.decode(message) }
                AppMapClient.textBehind = true
                val front = stages.time("branch: AppMapClient.decode, text behind the map") { AppMapClient.decode(message) }
                AppMapClient.textBehind = false

                val what = "${width}x$height ${bitmap.config} ${bitmap.colorSpace} premultiplied ${bitmap.isPremultiplied} hasAlpha ${bitmap.hasAlpha()}"
                val mainGround = mainWhole?.first
                val mainCut = mainWhole?.second
                val cut = whole?.frame?.asAndroidBitmap()
                val plainCut = plain?.second
                val frontCut = front.overText?.frame?.asAndroidBitmap()
                val same = mainCut != null && cut != null && plainCut != null && frontCut != null && mainGround == whole?.ground && mainGround == plain?.first &&
                    mainGround == front.overText?.ground && mainCut.sameAs(cut) && mainCut.sameAs(plainCut) && mainCut.sameAs(frontCut)
                if (!same) notes = "THE CUT-OUTS DIFFER "
                if (round == 39) notes += "$what, ${whole?.ground?.solid?.size} colours, background ${whole?.ground?.background}, same $same"
                // Rounds of frames of 10 MB each
                for (used in listOf(behind.bitmap, front.bitmap, front.overText?.frame, whole?.frame)) used?.asAndroidBitmap()?.recycle()
                for (used in listOf(mainCut, plainCut, bitmap)) used?.recycle()
            }
            report.append("$picture: png ${png.size} B; $notes\n")
            for ((name, times) in stages.times) report.append("  %-74s %7.2f\n".format(name, median(times)))
        }
        fail(report.toString())
    }
}
