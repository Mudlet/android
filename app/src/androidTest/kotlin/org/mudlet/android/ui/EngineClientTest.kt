package org.mudlet.android.ui

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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    fun theMapSearchsRefusalIsNoProfileRequestsAnswer() {
        // The fake has answered the hellos before the links have read them, and a request sent sooner is dropped
        waitUntil("the links attached") { EngineClient.linkState.startsWith("attached to pid") && EngineClient.front.linkState.startsWith("attached") }
        val answers = LinkedBlockingQueue<String>()
        fun askProfile(): JSONObject {
            onMain { EngineClient.deleteProfile("Nobody") { answers.add(it ?: "done") } }
            return session.receive("deleteProfile")
        }
        fun askSearch(): JSONObject {
            assertTrue("the search was not sent", onMain { EngineClient.front.appMap.findRooms("hall") })
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
            EngineClient.front.appMap.closeSearch()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
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
        const val PROFILE = "Client"
    }
}
