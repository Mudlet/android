package org.mudlet.android.ui

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.protocol.EngineLink
import org.mudlet.android.protocol.EngineMessage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What EngineClient holds for the UI from the engine's messages, read on the main thread as the UI reads it. */
@RunWith(AndroidJUnit4::class)
class EngineClientTest {
    private val fake = FakeEngineRule(open = listOf(PROFILE))

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)

    private lateinit var session: FakeEngine.Session

    @Before
    fun attach() {
        onMain { EngineClient.attach() }
        session = fake.engine.nextSession()
        // EngineClient lives as long as the process, and keeps the session of a profile that stays open
        onMain { EngineClient.front.typedLineMoved() }
        session.synced(0)
    }

    @After
    fun detach() = onMain { EngineClient.detach() }

    @Test
    fun moveTypedLineHoldsUntilTheLineHasMovedIt() {
        assertFalse(onMain { EngineClient.front.moveTypedLine })
        session.send(JSONObject().put("t", "moveTypedLine"))
        waitUntil("moveTypedLine set") { EngineClient.front.moveTypedLine }
        onMain { EngineClient.front.typedLineMoved() }
        assertFalse(onMain { EngineClient.front.moveTypedLine })
    }

    @Test
    fun aNewEngineDropsAMoveTheLineHasNotMade() {
        session.send(JSONObject().put("t", "moveTypedLine"))
        waitUntil("moveTypedLine set") { EngineClient.front.moveTypedLine }
        fake.engine.instance = "after-move"
        session.close()
        fake.engine.nextSession().synced(0)
        waitUntil("moveTypedLine dropped by the new engine") { !EngineClient.front.moveTypedLine }
    }

    @Test
    fun aStoppedLinkIsNotSaidToBeAttached() {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        val state = onMain {
            EngineClient.detach()
            EngineClient.front.linkState
        }
        assertFalse(state, state.startsWith("attached"))
    }

    @Test
    fun aLinkMovedToAnotherSocketIsNotSaidToBeAttachedBeforeItIs() {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        // Read in the main-thread task that moves it, before any attach or failure the link posts can run
        val state = onMain {
            EngineClient.front.moveTo(fake.engine.unservedPath, running = true)
            EngineClient.front.linkState
        }
        assertFalse(state, state.startsWith("attached"))
    }

    @Test
    fun aLinkBeingMovedKeepsItsOldThreadOffTheNewSocket() {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        val front = onMain { EngineClient.front }
        val served = fake.engine.openProfilesMessage().getJSONArray("profiles").getJSONObject(0).getString("socket")
        val unserved = fake.engine.unservedPath
        val tried = LinkedBlockingQueue<String>()
        val connect = EngineClient.connector
        onMain {
            EngineClient.connector = { path ->
                tried.add(path)
                connect(path)
            }
            // As a kept session's link, which keeps trying the socket of an earlier engine until openProfiles moves it
            front.moveTo(unserved, running = true)
        }
        assertEquals(unserved, tried.poll(WAIT_S, TimeUnit.SECONDS))
        // stop() is synchronized on the link: held, it keeps the main thread inside moveTo(), where a busy device may
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
        val tries = try {
            assertTrue(held.await(WAIT_S, TimeUnit.SECONDS))
            val main = Looper.getMainLooper()
            Handler(main).post {
                moving.countDown()
                front.moveTo(served, running = true)
            }
            assertTrue(moving.await(WAIT_S, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + WAIT_S * 1000
            while (main.thread.state != Thread.State.BLOCKED) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("moveTo() did not wait for the link")
                Thread.sleep(1)
            }
            tried.clear()
            // Woken as the control link's attach wakes it, just before openProfiles moves the session
            List(2) {
                front.wake()
                tried.poll(WAIT_S, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            holder.join()
        }
        assertEquals("what the link's thread tried while it was being moved", listOf<String?>(unserved, unserved), tries)
        val moved = session.forProfile(PROFILE)
        moved.send(JSONObject().put("t", "moveTypedLine"))
        waitUntil("moveTypedLine set by the first connection to the new socket") { EngineClient.front.moveTypedLine }
        onMain { EngineClient.front.typedLineMoved() }
    }

    @Test
    fun aLinkMovedWhileItsOldThreadSaysAFailureEndsAttached() {
        waitUntil("the profile's link attached") { EngineClient.front.linkState.startsWith("attached") }
        val front = onMain { EngineClient.front }
        val served = fake.engine.openProfilesMessage().getJSONArray("profiles").getJSONObject(0).getString("socket")
        val link = ProfileSession::class.java.getDeclaredField("link").apply { isAccessible = true }.get(front) as EngineLink
        val listenerField = EngineLink::class.java.getDeclaredField("listener").apply { isAccessible = true }
        val listener = listenerField.get(link) as EngineLink.Listener
        val failing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val told = CountDownLatch(1)
        val attached = CountDownLatch(1)
        val hold = AtomicBoolean(true)
        // The old thread's first failure waits before ProfileSession posts it, as a thread descheduled there would
        listenerField.set(
            link,
            object : EngineLink.Listener by listener {
                override fun onAttached(hello: EngineMessage.Hello, sameInstance: Boolean) {
                    listener.onAttached(hello, sameInstance)
                    attached.countDown()
                }

                override fun onConnectFailed(attempt: Int, error: IOException) {
                    if (!hold.getAndSet(false)) return listener.onConnectFailed(attempt, error)
                    failing.countDown()
                    awaitThroughInterrupts(release)
                    listener.onConnectFailed(attempt, error)
                    told.countDown()
                }
            },
        )
        try {
            onMain { front.moveTo(fake.engine.unservedPath, running = true) }
            assertTrue("the link's thread failed on the socket it moved to", failing.await(WAIT_S, TimeUnit.SECONDS))
            val main = Looper.getMainLooper()
            Handler(main).post { front.moveTo(served, running = true) }
            // Let go once the next thread has attached, or once moveTo() waits in stop() for the failure to be told
            val deadline = System.currentTimeMillis() + WAIT_S * 1000
            while (attached.count > 0 && !(main.thread.state == Thread.State.BLOCKED && main.thread.stackTrace.any { it.className == EngineLink::class.java.name && it.methodName == "stop" })) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("the next thread did not attach, nor did moveTo() wait")
                Thread.sleep(1)
            }
            release.countDown()
            assertTrue("the old thread told its failure", told.await(WAIT_S, TimeUnit.SECONDS))
            waitUntil("the moved link attached") { front.linkState.startsWith("attached") }
        } finally {
            release.countDown()
            listenerField.set(link, listener)
        }
    }

    @Test
    fun theMapSearchsRefusalIsNoProfileRequestsAnswer() {
        // The fake has answered the hellos before the links have read them, and a request sent sooner is dropped
        waitUntil("the links attached") { EngineClient.linkState.startsWith("attached to pid") && EngineClient.front.linkState.startsWith("attached") }
        val answers = LinkedBlockingQueue<String>()
        fun askProfile(): JSONObject {
            onMain { EngineClient.deleteProfile("Nobody") { answers.add(it ?: "done") } }
            return session.receive("deleteProfile")
        }
        fun askSearch(): JSONObject {
            assertTrue("the search was not sent", onMain { EngineClient.front.appMap.rooms.find("hall") })
            return session.receive("findRooms")
        }
        // The request whose id is behind is asked again until its id reaches the other's, as ids from separate
        // counters do, or passes it, as ids from one do
        var profile = askProfile()
        var search = askSearch()
        while (search.getLong("id") < profile.getLong("id")) search = askSearch()
        while (profile.getLong("id") < search.getLong("id")) {
            session.refuse(profile, "no such profile")
            assertEquals("deleteProfile: no such profile", answers.poll(10, TimeUnit.SECONDS))
            profile = askProfile()
        }
        session.refuse(search, "no profile is open")
        session.refuse(profile, "no such profile")
        assertEquals("deleteProfile: no such profile", answers.poll(10, TimeUnit.SECONDS))
        onMain {
            EngineClient.front.appMap.dismissRefusal()
            EngineClient.front.appMap.rooms.closeSearch()
        }
    }

    @Test
    fun aSearchsRefusalShowsOnTheMapThatAskedItWhicheverSentLast() {
        waitUntil("the links attached") { EngineClient.front.linkState.startsWith("attached") }
        val appMap = EngineClient.front.appMap
        val windows = EngineClient.front.windows
        try {
            assertTrue("the app's search was not sent", onMain { appMap.rooms.find("hall") })
            val appSearch = session.receive("findRooms")
            assertTrue("the mapper's roomInfo was not sent", onMain { windows.mapRooms.ask(3) })
            session.receive("roomInfo")
            session.refuse(appSearch, "no map is loaded")
            waitUntil("the app's search refused on the app's map") { appMap.rooms.search == null && appMap.refusal == "no map is loaded" }
            assertEquals("the mapper's refusal", null, onMain { windows.mapRefusal })

            assertTrue("the mapper's search was not sent", onMain { windows.mapRooms.find("hall") })
            val mapperSearch = session.receive("findRooms")
            assertTrue("the app's roomInfo was not sent", onMain { appMap.rooms.ask(3) })
            session.receive("roomInfo")
            session.refuse(mapperSearch, "no map is open")
            waitUntil("the mapper's search refused on the mapper") { windows.mapRooms.search == null && windows.mapRefusal == "no map is open" }
            assertEquals("the app's refusal", null, onMain { appMap.refusal })
        } finally {
            onMain {
                appMap.rooms.close()
                windows.mapRooms.close()
                appMap.dismissRefusal()
                windows.dismissMapRefusal()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    // stop() interrupts the thread, which must still tell its failure once let go
    private fun awaitThroughInterrupts(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!onMain(condition)) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("not within 10 s: $what")
            Thread.sleep(20)
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val WAIT_S = 5L
        const val PROFILE = "Client"
    }
}
