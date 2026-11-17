package org.mudlet.android.ui

import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mudlet.android.protocol.TextBehindMap
import java.nio.IntBuffer

/** DIAG, not for merging: what the ground of a frame costs, as main works it out and with each change. Always fails, with the times. */
@RunWith(AndroidJUnit4::class)
class MapFrameShowDiag {
    private fun median(times: List<Double>) = times.sorted()[times.size / 2]

    @Test
    fun theGroundOfAFrame() {
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
            val expectedGround = TextBehindMap.ground(source, width, height, abgr)!!
            val background = expectedGround.background!!
            val expected = source.copyOf().also { TextBehindMap.cutOut(it, background, abgr) }
            // Whether the pixels are to be cut afterwards, and whether the ground is the frame's
            class Variant(val name: String, val cuts: Boolean, val grounds: Boolean, val scan: (IntArray) -> Scan)
            val variants = listOf(
                Variant("main: ground + cutOut", true, true) { p -> TextBehindMap.ground(p, width, height, abgr)!!.let { TextBehindMap.cutOut(p, background, abgr); Scan(it, -1) } },
                Variant("main: ground alone", false, true) { p -> Scan(TextBehindMap.ground(p, width, height, abgr)!!, -1) },
                Variant("main: cutOut alone", true, false) { p -> TextBehindMap.cutOut(p, background, abgr); Scan(expectedGround, -1) },
                Variant("the test's copy of main + cutOut", true, true) { p -> VPlain.scan(p, width, height, abgr)!!.also { TextBehindMap.cutOut(p, background, abgr) } },
                Variant("cut in the pass, pixel by pixel", true, true) { p -> VCut.scan(p, width, height, abgr)!! },
                Variant("cut in the pass, pixel by pixel, shortcut", true, true) { p -> VCutShortcut.scan(p, width, height, abgr)!! },
                Variant("runs of background, cut in the pass", true, true) { p -> VRunsPlain.scan(p, width, height, abgr)!! },
                Variant("runs of background, cut in the pass, shortcut", true, true) { p -> VRuns.scan(p, width, height, abgr)!! },
                Variant("ABL runs, nothing weighed", false, false) { p -> AblSkipOnly.scan(p, width, height, abgr)!! },
                Variant("ABL runs, no blend maths", false, false) { p -> AblNoMaths.scan(p, width, height, abgr)!! },
            )
            val times = variants.map { mutableListOf<Double>() }
            val weighed = IntArray(variants.size)
            val whole = mutableListOf<Double>()
            var notes = ""
            repeat(40) { round ->
                // Each in turn first, so that none always runs after the same one
                for (v in if (round % 2 == 0) variants.indices.toList() else variants.indices.reversed()) {
                    System.arraycopy(source, 0, work, 0, source.size)
                    val start = System.nanoTime()
                    val scan = variants[v].scan(work)
                    val took = (System.nanoTime() - start) / 1e6
                    if (round >= 15) times[v] += took
                    weighed[v] = scan.weighed
                    if (variants[v].grounds && scan.ground != expectedGround) notes = "THE GROUND OF ${variants[v].name} DIFFERS "
                    if (variants[v].cuts && variants[v].grounds && !work.contentEquals(expected)) notes = "THE PIXELS OF ${variants[v].name} DIFFER "
                }
                val start = System.nanoTime()
                val over = AppMapClient.overText(bitmap)
                if (round >= 15) whole += (System.nanoTime() - start) / 1e6
                if (over?.ground != expectedGround) notes = "overText's GROUND DIFFERS "
                over?.frame?.asAndroidBitmap()?.recycle()
            }
            val cut = source.indices.count { expected[it] != source[it] }
            report.append("$picture: ${width}x$height ${bitmap.config}, ${expectedGround.solid.size} colours, background ${cut * 100L / source.size} %; $notes\n")
            report.append("  %-50s %7.2f\n".format("main: the whole cut-out (AppMapClient.overText)", median(whole)))
            for (v in variants.indices) report.append("  %-50s %7.2f   weighed %d\n".format(variants[v].name, median(times[v]), weighed[v]))
            bitmap.recycle()
        }
        fail(report.toString())
    }
}
