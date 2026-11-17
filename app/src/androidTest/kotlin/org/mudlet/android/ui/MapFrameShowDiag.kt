package org.mudlet.android.ui

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.KeptPixels
import org.mudlet.android.protocol.TextBehindMap
import java.nio.IntBuffer

/** DIAG, not for merging: the PR's whole cut-out against main's, rebuilt beside it. Always fails, with the times. */
@RunWith(AndroidJUnit4::class)
class MapFrameShowDiag {
    private fun median(times: List<Double>) = times.sorted()[times.size / 2]

    private val mainKept = KeptPixels()

    // main 58643d15's AppMapClient.cutOutIn for a decoded (own) bitmap: copy, ground, a second pass to cut, copy back
    private fun mainOverText(bitmap: Bitmap): Pair<TextBehindMap.Ground, Bitmap>? = mainKept.use(bitmap.width * bitmap.height) { pixels ->
        val width = bitmap.width
        val height = bitmap.height
        val abgr = TextBehindMap.Layout.ABGR
        bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        val ground = VPlain.scan(pixels, width, height, abgr)?.ground ?: return@use null
        val background = ground.background ?: return@use ground to bitmap
        val opaque = (0xFF shl 24) or ((background shr 16 and 0xFF) or (background and 0xFF00) or ((background and 0xFF) shl 16))
        for (i in pixels.indices) if (pixels[i] == opaque) pixels[i] = 0
        ground to createBitmap(width, height).apply { copyPixelsFromBuffer(IntBuffer.wrap(pixels)) }
    }

    @Test
    fun theWholeCutOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val debuggable = instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val abgr = TextBehindMap.Layout.ABGR
        val report = StringBuilder("API ${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.first()}, debuggable $debuggable, medians of 25 after 15, ms\n")
        for (picture in listOf("sparse", "dense", "dense-names")) {
            val png = assets.open("diag/$picture-own.png").use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            val width = bitmap.width
            val height = bitmap.height
            val source = IntArray(width * height)
            bitmap.copyPixelsToBuffer(IntBuffer.wrap(source))
            val work = IntArray(source.size)
            val names = listOf("main: the whole cut-out", "PR: the whole cut-out (AppMapClient.overText)", "main: ground + cut (two passes)", "PR: TextBehindMap.cutOut (one pass)")
            val times = names.map { mutableListOf<Double>() }
            var notes = ""
            repeat(40) { round ->
                // Each first in turn, so that neither always runs after the other
                val order = if (round % 2 == 0) listOf(0, 1) else listOf(1, 0)
                val wholes = arrayOfNulls<Pair<TextBehindMap.Ground, Bitmap>>(2)
                for (v in order) {
                    val start = System.nanoTime()
                    wholes[v] = if (v == 0) mainOverText(bitmap) else AppMapClient.overText(bitmap)?.let { it.ground to it.frame.asAndroidBitmap() }
                    val took = (System.nanoTime() - start) / 1e6
                    if (round >= 15) times[v] += took
                }
                val (mainGround, mainFrame) = wholes[0]!!
                val (prGround, prFrame) = wholes[1]!!
                if (mainGround != prGround) notes = "THE GROUNDS DIFFER "
                if (!mainFrame.sameAs(prFrame)) notes += "THE FRAMES DIFFER "
                mainFrame.recycle()
                prFrame.recycle()
                for (v in if (round % 2 == 0) listOf(2, 3) else listOf(3, 2)) {
                    System.arraycopy(source, 0, work, 0, source.size)
                    val start = System.nanoTime()
                    if (v == 2) {
                        val ground = VPlain.scan(work, width, height, abgr)!!.ground
                        val background = ground.background!!
                        val opaque = (0xFF shl 24) or ((background shr 16 and 0xFF) or (background and 0xFF00) or ((background and 0xFF) shl 16))
                        for (i in work.indices) if (work[i] == opaque) work[i] = 0
                    } else {
                        TextBehindMap.cutOut(work, width, height, abgr)
                    }
                    val took = (System.nanoTime() - start) / 1e6
                    if (round >= 15) times[v] += took
                }
            }
            report.append("$picture: ${width}x$height ${bitmap.config}; $notes\n")
            for (v in names.indices) report.append("  %-50s %7.2f\n".format(names[v], median(times[v])))
            bitmap.recycle()
        }
        fail(report.toString())
    }
}
