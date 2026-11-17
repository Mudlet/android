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
import org.mudlet.android.protocol.TextBehindMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    // overText with a pixel array kept from frame to frame, read and written as the bitmap keeps them
    private fun keptOverText(bitmap: Bitmap, pixels: IntArray): Pair<TextBehindMap.Ground, Bitmap>? {
        bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        val ground = TextBehindMap.ground(pixels, bitmap.width, bitmap.height, TextBehindMap.Layout.ABGR) ?: return null
        val background = ground.background ?: return ground to bitmap
        TextBehindMap.cutOut(pixels, background, TextBehindMap.Layout.ABGR)
        return ground to Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(pixels)) }
    }

    // overText with a pixel array kept from frame to frame, converted as main converts them
    private fun keptConvertedOverText(bitmap: Bitmap, pixels: IntArray): Pair<TextBehindMap.Ground, Bitmap>? {
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val ground = TextBehindMap.ground(pixels, bitmap.width, bitmap.height) ?: return null
        val background = ground.background ?: return ground to bitmap
        TextBehindMap.cutOut(pixels, background)
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
            val stages = Stages()
            var notes = ""
            var keptArgb = IntArray(0)
            var keptOwn = IntArray(0)
            var keptWhole = IntArray(0)
            var keptWholeArgb = IntArray(0)
            var direct = ByteBuffer.allocateDirect(0)
            var keptBitmap: Bitmap? = null
            repeat(30) { round ->
                stages.keep = round >= 15
                val bitmap = stages.time("decode (BitmapFactory)") { BitmapFactory.decodeByteArray(png, 0, png.size) }
                val width = bitmap.width
                val height = bitmap.height
                val count = width * height
                if (round == 0) {
                    keptArgb = IntArray(count)
                    keptOwn = IntArray(count)
                    keptWhole = IntArray(count)
                    keptWholeArgb = IntArray(count)
                    direct = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                    keptBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val keptOut = checkNotNull(keptBitmap)

                stages.time("a new IntArray, each Int written") { IntArray(count).fill(1) }
                stages.time("a kept IntArray, each Int written") { keptArgb.fill(1) }

                val pixels = IntArray(count)
                stages.time("main: getPixels into a new IntArray") { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                stages.time("getPixels into a kept IntArray") { bitmap.getPixels(keptArgb, 0, width, 0, 0, width, height) }
                val own = IntArray(count)
                stages.time("copyPixelsToBuffer into a new IntArray") { bitmap.copyPixelsToBuffer(IntBuffer.wrap(own)) }
                stages.time("copyPixelsToBuffer into a kept IntArray") { bitmap.copyPixelsToBuffer(IntBuffer.wrap(keptOwn)) }
                stages.time("copyPixelsToBuffer into a kept direct buffer") {
                    direct.rewind()
                    bitmap.copyPixelsToBuffer(direct)
                }
                val fromDirect = IntArray(count)
                stages.time("a kept direct buffer's Ints into a kept IntArray") {
                    direct.rewind()
                    direct.asIntBuffer().get(keptWhole)
                }
                direct.rewind()
                direct.asIntBuffer().get(fromDirect)

                val mainGround = stages.time("main: ground") { MainTextBehindMap.ground(pixels, width, height) }
                val ground = stages.time("branch: ground") { TextBehindMap.ground(keptOwn, width, height, TextBehindMap.Layout.ABGR) }
                val background = mainGround?.background
                if (background != null) {
                    stages.time("main: cut out") { MainTextBehindMap.cutOut(pixels, background) }
                    stages.time("branch: cut out") { TextBehindMap.cutOut(keptOwn, background, TextBehindMap.Layout.ABGR) }
                }
                val mainCut = stages.time("main: createBitmap(pixels)") { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }
                val cut = stages.time("createBitmap, copyPixelsFromBuffer") {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(keptOwn)) }
                }
                val empty = stages.time("createBitmap alone") { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                stages.time("copyPixelsFromBuffer into a kept bitmap") { keptOut.copyPixelsFromBuffer(IntBuffer.wrap(keptOwn)) }
                val keptSame = mainCut.sameAs(keptOut)
                stages.time("setPixels into a kept bitmap") { keptOut.setPixels(pixels, 0, width, 0, 0, width, height) }
                val copy = stages.time("bitmap.copy(ARGB_8888, mutable)") { bitmap.copy(Bitmap.Config.ARGB_8888, true) }

                val mainWhole = stages.time("main: the whole cut-out") { mainOverText(bitmap) }
                val whole = stages.time("branch as measured before (new array, copies): the whole cut-out") { AppMapClient.overText(bitmap) }
                val keptCopied = stages.time("kept array, copies: the whole cut-out") { keptOverText(bitmap, keptWhole) }
                val keptConverted = stages.time("kept array, getPixels and createBitmap(pixels): the whole cut-out") { keptConvertedOverText(bitmap, keptWholeArgb) }

                val what = "${width}x$height ${bitmap.config} ${bitmap.colorSpace} premultiplied ${bitmap.isPremultiplied} hasAlpha ${bitmap.hasAlpha()}"
                val same = mainGround == ground && mainGround == whole?.ground && mainGround == mainWhole?.first && mainGround == keptCopied?.first &&
                    mainGround == keptConverted?.first && own.contentEquals(fromDirect) && keptSame && mainCut.sameAs(cut) && whole != null &&
                    mainCut.sameAs(whole.frame.asAndroidBitmap()) && keptCopied != null && mainCut.sameAs(keptCopied.second) && keptConverted != null &&
                    mainCut.sameAs(keptConverted.second)
                if (!same) notes = "THE CUT-OUTS DIFFER "
                if (round == 29) notes += "$what, ${ground?.solid?.size} colours, background $background, same $same"
                // Rounds of frames of 10 MB each
                whole?.frame?.asAndroidBitmap()?.recycle()
                for (used in listOf(mainCut, cut, empty, copy, mainWhole?.second, keptCopied?.second, keptConverted?.second, bitmap)) used?.recycle()
            }
            report.append("$picture: png ${png.size} B; $notes\n")
            for ((name, times) in stages.times) report.append("  %-74s %7.2f\n".format(name, median(times)))
        }
        fail(report.toString())
    }
}
