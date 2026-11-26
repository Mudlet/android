package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.R
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.FrameReader
import org.mudlet.android.protocol.Profile
import org.mudlet.android.ui.game.GAME_TITLE_TAG
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Report-only measurement, never a gate: a fake game's fight at set rates and flat out, sent to the console through
 * the profile's socket as the engine sends it, with frame timing, each thread's CPU, GC and how far the UI fell behind.
 * Logs one `PERF` line a stage for native/tests/android_instrumented.py to print.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleFloodBenchmark {
    private val fake = FakeEngineRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)
        .around(if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else RuleChain.emptyRuleChain())
        .around(compose)

    private lateinit var session: FakeEngine.Session
    private val game = Profile("flood ${System.nanoTime()}", "127.0.0.1", 4000, false, "", false)
    private var seq = 0L
    private val run = InstrumentationRegistry.getArguments().getString("perfRun") ?: "0"

    @Before
    fun openTheGame() {
        val first = fake.engine.nextSession()
        fake.engine.instance = "fake-flood-${System.nanoTime()}"
        first.close()
        session = fake.engine.nextSession()
        session.clear()
        for (i in 1..40) session.line(++seq, "Welcome line $seq.")
        session.synced(seq)
        session.profiles(listOf(game), session.receive("listProfiles"))
        compose.waitUntil(WAIT_MS) { EngineClient.profiles == listOf(game) }
        val connect = hasContentDescription(string(R.string.profile_connect_to, game.name)) and hasClickAction()
        waitFor(connect)
        compose.onNode(connect).performSemanticsAction(SemanticsActions.OnClick)
        session.profiles(listOf(game), session.receive("openProfile"))
        waitFor(hasTestTag(GAME_TITLE_TAG))
        waitFor(hasText("Welcome line $seq."))
    }

    @Test
    fun flood() {
        decodeOnArt()
        // Warm-up, not reported: the first lines JIT the path
        stage("warmup", 2000, 2.0, reported = false)
        stage("r1000", 1000, 4.0)
        stage("r5000", 5000, 4.0)
        stage("r20000", 20000, 4.0)
        stage("flat", 0, 0.0, flatLines = 60_000)
        // Scrolled to the top: the console no longer follows, as a player reading back in a fight
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        lists[lists.fetchSemanticsNodes().withIndex().maxBy { it.value.size.height }.index].performScrollToIndex(0)
        compose.waitForIdle()
        stage("r5000up", 5000, 4.0)
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        report("memory", "pssKb=${mem.totalPss} javaHeapKb=${(rt.totalMemory() - rt.freeMemory()) / 1024} nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024}")
    }

    // FrameReader.parse and ansiText on ART, on a quiet process, for the JVM's figures to be set beside
    private fun decodeOnArt() {
        val rng = Random(9011)
        val n = 20_000
        val frames = Array(n) { fightPayload(it + 1L, rng) }
        val lines = ArrayList<UiLine>(n)
        repeat(5) { round ->
            // Through JSONObject, as main reads every line, beside FrameReader.parse, in one process
            val j0 = allocated()
            val tj = System.nanoTime()
            var viaJson = 0L
            for (f in frames) viaJson += (FrameReader.parse(JSONObject(String(f, Charsets.UTF_8))) as EngineMessage.Line).seq
            val dtj = System.nanoTime() - tj
            val daj = allocated() - j0
            val a0 = allocated()
            val t0 = System.nanoTime()
            lines.clear()
            for (f in frames) {
                val m = FrameReader.parse(f) as EngineMessage.Line
                lines.add(UiLine(m.text, m.runs, m.prompt, time = m.time, seq = m.seq))
            }
            val dt = System.nanoTime() - t0
            val da = allocated() - a0
            check(viaJson == lines.sumOf { it.seq })
            val c0 = allocated()
            val t1 = System.nanoTime()
            var spans = 0
            for (l in lines) spans += ansiText(l, ConsoleColors()).spanStyles.size
            val dt1 = System.nanoTime() - t1
            val dc = allocated() - c0
            report("decode", "round=$round jsonObjectNs=${dtj / n} jsonObjectB=${daj / n} parseNs=${dt / n} parseB=${da / n} ansiTextNs=${dt1 / n} ansiTextB=${dc / n} spans=$spans")
        }
    }

    private fun stage(name: String, rate: Int, seconds: Double, flatLines: Int = 0, reported: Boolean = true) {
        val count = if (rate == 0) flatLines else (rate * seconds).toInt()
        val first = seq + 1
        val (bytes, ends) = framed(count)
        seq += count
        val target = EngineClient.front.linesAdded + count

        val frames = ArrayList<LongArray>()
        val metricsThread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val main = m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) + m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                m.getMetric(FrameMetrics.ANIMATION_DURATION) + m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                m.getMetric(FrameMetrics.DRAW_DURATION) + m.getMetric(FrameMetrics.SYNC_DURATION)
            val row = longArrayOf(
                m.getMetric(FrameMetrics.TOTAL_DURATION),
                main,
                m.getMetric(FrameMetrics.ANIMATION_DURATION),
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                m.getMetric(FrameMetrics.DRAW_DURATION),
            )
            synchronized(frames) { frames.add(row) }
        }
        compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
        val vsyncs = AtomicLong(0)
        val counting = AtomicBoolean(true)
        val tick = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                vsyncs.incrementAndGet()
                if (counting.get()) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        var state = ""
        compose.runOnUiThread {
            Choreographer.getInstance().postFrameCallback(tick)
            state = "hwAccel=${compose.activity.window.decorView.isHardwareAccelerated} lifecycle=${compose.activity.lifecycle.currentState}"
        }

        val cpu0 = threadCpu()
        val gc0 = gcStats()
        val sent = AtomicLong(0)
        val senderMs = AtomicLong(0)
        val t0 = System.nanoTime()
        val sender = thread(name = "flood-sender") {
            var done = 0
            var at = 0
            while (done < count) {
                val upTo = if (rate == 0) {
                    minOf(count, done + FLAT_BATCH)
                } else {
                    val due = ((System.nanoTime() - t0) / 1e9 * rate).toInt().coerceAtMost(count)
                    if (due <= done) {
                        Thread.sleep(1)
                        continue
                    }
                    due
                }
                val end = ends[upTo - 1]
                session.raw(bytes, at, end - at)
                at = end
                done = upTo
                sent.set(done.toLong())
            }
            senderMs.set(SystemClock.currentThreadTimeMillis())
        }
        var maxBacklog = 0L
        while (sender.isAlive) {
            maxBacklog = maxOf(maxBacklog, sent.get() - (EngineClient.front.linesAdded - (target - count)))
            Thread.sleep(10)
        }
        val sentAt = System.nanoTime()
        val deadline = sentAt + 60_000_000_000L
        while (EngineClient.front.linesAdded < target && System.nanoTime() < deadline) Thread.sleep(2)
        val inAt = System.nanoTime()
        // Two frames after the last line went in: the one that lays it out and draws it, and one to be sure
        val drawn = CountDownLatch(2)
        compose.runOnUiThread {
            Choreographer.getInstance().postFrameCallback {
                drawn.countDown()
                Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        drawn.await(10, TimeUnit.SECONDS)
        val shownAt = System.nanoTime()
        val cpu1 = threadCpu()
        val gc1 = gcStats()
        counting.set(false)
        compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
        metricsThread.quitSafely()
        if (!reported) return

        val wall = (shownAt - t0) / 1e9
        val rows = synchronized(frames) { frames.toList() }
        val totals = rows.map { it[0] / 1e6 }.sorted()
        val mains = rows.map { it[1] / 1e6 }.sorted()
        val jank = totals.count { it > FRAME_MS }
        val missed = totals.sumOf { (it / FRAME_MS).toInt() }
        fun pct(list: List<Double>, p: Double) = if (list.isEmpty()) -1.0 else list[((list.size - 1) * p).toInt()]
        fun cpuMs(names: (String) -> Boolean) = cpu1.filter { (k, _) -> names(k.second) }.entries.sumOf { (k, v) -> v - (cpu0[k] ?: 0L) } * 1000L / TICKS
        // The sender has ended by now, and with it its /proc entry: it counts its own time
        val sender0 = senderMs.get()
        val top = cpu1.map { (k, v) -> k.second.replace(' ', '_') to v - (cpu0[k] ?: 0L) }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() * 1000L / TICKS }.entries.sortedByDescending { it.value }.take(10)
            .joinToString(",") { "${it.key}:${it.value}" }
        val main = cpuMs { it == MAIN }
        val reader = cpuMs { it == "engine-link" }
        val render = cpuMs { it == "RenderThread" }
        val all = cpuMs { true }
        val perK = { ms: Long -> "%.1f".format(ms * 1000.0 / count) }
        report(
            name,
            "lines=$count first=$first rateAsked=$rate sendS=${"%.2f".format((sentAt - t0) / 1e9)} inMs=${(inAt - sentAt) / 1_000_000} " +
                "shownMs=${(shownAt - sentAt) / 1_000_000} linesPerSec=${(count / ((inAt - t0) / 1e9)).toInt()} maxBacklog=$maxBacklog " +
                "$state vsyncs=${vsyncs.get()} frames=${rows.size} fps=${"%.1f".format(rows.size / wall)} jank=$jank missedVsyncs=$missed " +
                "totalMs50/90/99/max=${"%.1f/%.1f/%.1f/%.1f".format(pct(totals, .5), pct(totals, .9), pct(totals, .99), totals.lastOrNull() ?: -1.0)} " +
                "mainMs50/90/99/max=${"%.1f/%.1f/%.1f/%.1f".format(pct(mains, .5), pct(mains, .9), pct(mains, .99), mains.lastOrNull() ?: -1.0)} " +
                "animMsSum=${rows.sumOf { it[2] } / 1_000_000} layoutMsSum=${rows.sumOf { it[3] } / 1_000_000} drawMsSum=${rows.sumOf { it[4] } / 1_000_000} " +
                "cpuMs main=$main reader=$reader render=$render process=$all sender=$sender0 " +
                "usPerLine main=${perK(main)} reader=${perK(reader)} process=${perK(all)} topThreadsMs=$top " +
                "gc=${gc1[0] - gc0[0]} gcMs=${gc1[1] - gc0[1]} blockingGc=${gc1[2] - gc0[2]} blockingGcMs=${gc1[3] - gc0[3]} " +
                "allocKbPerLine=${"%.2f".format((gc1[4] - gc0[4]) / 1024.0 / count)}",
        )
    }

    // The stage's frames, each with its length, end to end, and where each frame ends
    private fun framed(count: Int): Pair<ByteArray, IntArray> {
        val rng = Random(seq)
        val out = ByteArrayOutputStream(count * 200)
        val ends = IntArray(count)
        for (i in 0 until count) {
            val p = fightPayload(seq + 1 + i, rng)
            out.write(p.size ushr 24)
            out.write(p.size ushr 16)
            out.write(p.size ushr 8)
            out.write(p.size)
            out.write(p, 0, p.size)
            ends[i] = out.size()
        }
        return out.toByteArray() to ends
    }

    private fun report(stage: String, values: String) = Log.i(TAG, "PERF api=${Build.VERSION.SDK_INT} run=$run stage=$stage $values")

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        const val TAG = "ConsoleFloodBenchmark"
        const val WAIT_MS = 10_000L
        const val TIMEOUT_MS = 280_000L
        const val FRAME_MS = 1000.0 / 60
        const val FLAT_BATCH = 200
        const val TICKS = 100L
        const val MAIN = "<main>"
        val WORDS = (
            "You slash a goblin with your longsword dealing critical damage The orc parries and ripostes grazing your " +
                "arm Gandalf casts fireball at the troll It screams in agony Bob tells you to flee"
            ).split(" ")

        // Shaped as LineJson writes spam_bench.py's fight: a prompt every sixth line, else a bold coloured head and a tag
        fun fightPayload(seq: Long, rng: Random): ByteArray {
            val k = seq.toInt()
            val text: String
            val runs: String
            if (k % 6 == 5) {
                val a = "H:${rng.nextInt(2400)}/2400"
                val rest = " M:${rng.nextInt(1800)}/1800 [eb] "
                text = a + rest
                runs = "[[0,${a.length},2],[${a.length},${rest.length},-1]]"
            } else {
                val words = (0 until 6 + rng.nextInt(12)).joinToString(" ") { WORDS[rng.nextInt(WORDS.size)] }
                val head = words.take(12)
                val mid = words.drop(12) + " "
                val tag = "[$k]"
                text = head + mid + tag
                runs = "[[0,${head.length},${9 + k % 7},-1,1],[${head.length},${mid.length},-1],[${head.length + mid.length},${tag.length},3]]"
            }
            return "{\"prompt\":false,\"runs\":$runs,\"seq\":$seq,\"t\":\"line\",\"text\":\"$text\",\"time\":1764000000000}".toByteArray()
        }

        fun allocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L

        // gc count, gc ms, blocking gc count, blocking gc ms, bytes allocated
        fun gcStats(): LongArray = longArrayOf(
            Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L,
            Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L,
            Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull() ?: 0L,
            Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull() ?: 0L,
            allocated(),
        )

        // Each thread's utime + stime in clock ticks, by (tid, name); the main thread named MAIN
        fun threadCpu(): Map<Pair<Long, String>, Long> {
            val pid = Process.myPid().toLong()
            val out = HashMap<Pair<Long, String>, Long>()
            for (dir in File("/proc/self/task").listFiles().orEmpty()) {
                val tid = dir.name.toLongOrNull() ?: continue
                val stat = try {
                    File(dir, "stat").readText()
                } catch (_: Exception) {
                    continue
                }
                val name = if (tid == pid) MAIN else stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                // After the name: state is field 3, utime 14 and stime 15
                out[tid to name] = fields[11].toLong() + fields[12].toLong()
            }
            return out
        }
    }
}
