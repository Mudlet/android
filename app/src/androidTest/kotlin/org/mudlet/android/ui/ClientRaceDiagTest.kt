package org.mudlet.android.ui

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.protocol.LinkTrace
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Diagnostic only, never merged: what EngineClientTest's attach() does after an earlier test left the session's link on
 * a socket nothing serves, looped, and once with the main thread held inside moveTo(). Each test ends in a failure
 * that carries its report, as the job's log shows only failures.
 */
@RunWith(AndroidJUnit4::class)
class ClientRaceDiagTest {
    private val fake = FakeEngineRule(open = listOf(PROFILE))

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)

    private lateinit var session: FakeEngine.Session

    @Before
    fun attach() {
        onMain { EngineClient.attach() }
        session = fake.engine.nextSession()
        onMain { EngineClient.front.typedLineMoved() }
        session.synced(0)
    }

    @After
    fun detach() = onMain { EngineClient.detach() }

    @Test
    fun forced() {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        val front = onMain { EngineClient.front }
        val served = servedPath()
        val unserved = fake.engine.unservedPath
        val tried = ConcurrentLinkedQueue<String>()
        val connect = EngineClient.connector
        onMain {
            EngineClient.connector = { path ->
                tried.add("thread ${System.identityHashCode(Thread.currentThread())} ${name(path, served, unserved)}")
                connect(path)
            }
            front.moveTo(unserved, running = true)
        }
        waitFor("the link tried the unserved socket") { tried.isNotEmpty() }
        val before = fake.engine.stats(PROFILE)
        val deadBefore = LinkTrace.deadAttaches.get()
        val attachesBefore = onMain { front.attaches }
        val link = ProfileSession::class.java.getDeclaredField("link").apply { isAccessible = true }.get(front)!!
        val held = CountDownLatch(1)
        val moving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            synchronized(link) {
                held.countDown()
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        val report = StringBuilder()
        try {
            held.await(10, TimeUnit.SECONDS)
            val main = Looper.getMainLooper()
            Handler(main).post {
                moving.countDown()
                front.moveTo(served, running = true)
            }
            moving.await(10, TimeUnit.SECONDS)
            waitFor("moveTo() waits for the link") { main.thread.state == Thread.State.BLOCKED }
            tried.clear()
            front.wake()
            val reached = runCatching { waitFor("the old thread tried the served socket", 3_000) { tried.any { it.endsWith("served") && !it.endsWith("unserved") } } }.isSuccess
            // Time for the old thread to say hello and come to wait for the link, as the main thread does
            Thread.sleep(300)
            report.append("while moveTo() was held: oldThreadTriedTheServedSocket=$reached tried=${tried.toList()}; ")
        } finally {
            release.countDown()
            holder.join()
        }
        val moved = try {
            session.forProfile(PROFILE)
        } catch (e: Throwable) {
            throw AssertionError("DIAG forced: $report no connection said hello: $e")
        }
        // As the hop to the main thread between nextSession() and synced() in EngineClientTest.attach()
        onMain { EngineClient.front.typedLineMoved() }
        report.append("then on the first connection that said hello: synced -> ${attempt { moved.synced(0) }}; ")
        Thread.sleep(200)
        report.append("moveTypedLine -> ${attempt { moved.send(JSONObject().put("t", "moveTypedLine")) }}, ${if (delivered(front)) "delivered" else "not delivered"}; ")
        val after = fake.engine.stats(PROFILE)
        report.append(
            "the socket took ${after[0] - before[0]} connections, ${after[1] - before[1]} said hello, ${after[2] - before[2]} greeted; " +
                "stopped threads that attached: ${LinkTrace.deadAttaches.get() - deadBefore}; the session's attaches: " +
                "${onMain { front.attaches } - attachesBefore}; linkState=${onMain { front.linkState }}; tried=${tried.toList()}",
        )
        throw AssertionError("DIAG forced: $report")
    }

    @Test
    fun naturalQuiet() = natural("quiet", 0)

    @Test
    fun naturalLoaded() = natural("loaded", 8)

    // The engine goes and comes back while the session's link is on a socket nothing serves: the control link attaches,
    // wakes the session's link and openProfiles moves it, as in EngineClientTest.attach() after another test
    private fun natural(label: String, spinners: Int) {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        val front = onMain { EngineClient.front }
        val served = servedPath()
        val unserved = fake.engine.unservedPath
        val servedBy = ConcurrentLinkedQueue<Int>()
        val connect = EngineClient.connector
        onMain {
            EngineClient.connector = { path ->
                if (path == served) servedBy.add(System.identityHashCode(Thread.currentThread()))
                connect(path)
            }
        }
        val stop = AtomicBoolean()
        repeat(spinners) {
            thread(isDaemon = true) {
                var x = 0L
                while (!stop.get()) x += System.nanoTime() and 1
            }
        }
        LinkTrace.lateNs.clear()
        val deadBefore = LinkTrace.deadAttaches.get()
        var iterations = 0
        var gaveUp = 0
        var twoHellos = 0
        var greetFailed = 0
        var brokenPipe = 0
        var otherFailures = 0
        val details = ArrayList<String>()
        var aborted = "no"
        val started = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - started < LOOP_MS) {
                iterations++
                onMain { front.moveTo(unserved, running = true) }
                val before = fake.engine.stats(PROFILE)
                servedBy.clear()
                session.close()
                session = fake.engine.nextSession()
                var outcome = "ok"
                try {
                    session.synced(0)
                    session.send(JSONObject().put("t", "moveTypedLine"))
                    if (delivered(front)) onMain { front.typedLineMoved() } else outcome = "moveTypedLine not delivered"
                } catch (e: IOException) {
                    outcome = "IOException: ${e.message}"
                }
                val after = fake.engine.stats(PROFILE)
                val accepted = after[0] - before[0]
                val hellos = after[1] - before[1]
                val greeted = after[2] - before[2]
                if (hellos >= 2) {
                    twoHellos++
                } else if (accepted >= 2) {
                    gaveUp++
                }
                if (hellos > greeted) greetFailed++
                if (outcome == "IOException: Broken pipe") {
                    brokenPipe++
                } else if (outcome != "ok") {
                    otherFailures++
                }
                if ((outcome != "ok" || hellos >= 2 || hellos > greeted) && details.size < 8) {
                    details.add(
                        "iteration $iterations: $outcome; accepted=$accepted hellos=$hellos greeted=$greeted connectingThreads=${servedBy.toList()} " +
                            "linkState=${onMain { front.linkState }}",
                    )
                }
            }
        } catch (e: Throwable) {
            aborted = e.toString()
        }
        stop.set(true)
        val late = LinkTrace.lateNs.toList().sorted()
        val lateness = if (late.isEmpty()) "none" else "n=${late.size} min=${late.first() / 1000}us median=${late[late.size / 2] / 1000}us max=${late.last() / 1000}us"
        throw AssertionError(
            "DIAG natural $label: iterations=$iterations in ${System.currentTimeMillis() - started} ms, spinners=$spinners, aborted=$aborted; " +
                "oldThreadConnectedToTheNewSocketAndGaveUp=$gaveUp (its look came after stop() by: $lateness); " +
                "oldThreadSaidHelloOnTheNewSocket=$twoHellos; greetFailed=$greetFailed; brokenPipeOnTheFirstConnection=$brokenPipe; " +
                "otherFailuresOnTheFirstConnection=$otherFailures; stoppedThreadsThatAttached=${LinkTrace.deadAttaches.get() - deadBefore}; " +
                "details=$details",
        )
    }

    private fun servedPath(): String = fake.engine.openProfilesMessage().getJSONArray("profiles").getJSONObject(0).getString("socket")

    private fun name(path: String, served: String, unserved: String) = when (path) {
        served -> "served"
        unserved -> "unserved"
        else -> "control"
    }

    private fun attempt(block: () -> Unit): String = try {
        block()
        "ok"
    } catch (e: IOException) {
        "IOException: ${e.message}"
    }

    private fun delivered(front: ProfileSession): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (!onMain { front.moveTypedLine }) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(2)
        }
        return true
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, ms: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("not within $ms ms: $what")
            Thread.sleep(1)
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) = waitFor(what) { onMain(condition) }

    companion object {
        const val TIMEOUT_MS = 280_000L
        const val LOOP_MS = 100_000L
        const val PROFILE = "Client"
    }
}
