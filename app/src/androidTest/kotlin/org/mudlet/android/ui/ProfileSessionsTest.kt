package org.mudlet.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mudlet.android.protocol.Profile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Two profiles open at once, each on its own socket (docs/multi-profile.md, "Per-profile UI state"): what EngineClient
 * holds of one stays that profile's, and what the UI asks of one goes to that profile's socket alone.
 */
@RunWith(AndroidJUnit4::class)
class ProfileSessionsTest {
    private val fake = FakeEngineRule(open = listOf(ALPHA, BETA))

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)

    private lateinit var alpha: FakeEngine.Session
    private lateinit var beta: FakeEngine.Session

    @Before
    fun attach() {
        onMain { EngineClient.attach() }
        // The one opened last is the front
        beta = fake.engine.nextSession()
        alpha = beta.forProfile(ALPHA)
        // The fake has answered the hellos before the links have read them, and a request sent sooner is dropped
        waitUntil("both profiles attached") { listOf(ALPHA, BETA).all { EngineClient.session(it)?.linkState?.startsWith("attached") == true } }
        for (session in listOf(alpha, beta)) {
            session.clear()
            session.synced(0)
        }
    }

    @After
    fun detach() = onMain { EngineClient.detach() }

    @Test
    fun eachProfileHasASessionAndTheLastOpenedIsTheFront() {
        onMain {
            assertEquals(listOf(ALPHA, BETA), EngineClient.sessions.map { it.name })
            assertEquals(BETA, EngineClient.front.name)
        }
    }

    @Test
    fun eachProfilesLinesStatusesAndInputStateStayItsOwn() {
        alpha.line(1, "in the alpha game")
        alpha.tail("alpha> ")
        alpha.status("connection", "connected")
        alpha.history(listOf("north"))
        beta.line(1, "in the beta game")
        beta.status("connection", "disconnected")
        beta.history(listOf("south"))
        // Before hiding input, which drops what a script sets the line to
        beta.commandLine("typed by a beta script")
        beta.hiddenInput(true)
        waitUntil("both profiles' messages arrived") {
            val a = EngineClient.session(ALPHA)!!
            val b = EngineClient.session(BETA)!!
            a.tail != null && a.status["connection"] != null && b.status["connection"] != null && b.lines.isNotEmpty() && b.hiddenInput
        }
        onMain {
            val a = EngineClient.session(ALPHA)!!
            val b = EngineClient.session(BETA)!!
            assertEquals(listOf("in the alpha game"), a.lines.map { it.text })
            assertEquals(listOf("in the beta game"), b.lines.map { it.text })
            assertEquals("alpha> ", a.tail?.text)
            assertNull(b.tail)
            assertEquals("connected", a.status["connection"])
            assertEquals("disconnected", b.status["connection"])
            assertEquals(listOf("north"), a.history)
            assertEquals(listOf("south"), b.history)
            assertFalse(a.hiddenInput)
            assertTrue(b.hiddenInput)
            assertNull(a.commandLine)
            assertEquals("typed by a beta script", b.commandLine)
            // The engine's own statuses are neither's
            assertNull(EngineClient.engineStatus["connection"])
        }
    }

    @Test
    fun eachProfilesScriptWindowsStayItsOwn() {
        alpha.windows(listOf(FakeEngine.window("alphaChat")), full = true)
        beta.windows(listOf(FakeEngine.window("betaChat")), full = true)
        waitUntil("both profiles' windows arrived") { listOf(ALPHA, BETA).all { EngineClient.session(it)!!.windows.state.windows.isNotEmpty() } }
        onMain {
            assertEquals(listOf("alphaChat"), EngineClient.session(ALPHA)!!.windows.state.windows.map { it.name })
            assertEquals(listOf("betaChat"), EngineClient.session(BETA)!!.windows.state.windows.map { it.name })
        }
    }

    @Test
    fun whatTheUiAsksOfAProfileGoesToItsSocketAlone() {
        assertTrue(onMain { EngineClient.session(ALPHA)!!.sendCommand("north") })
        assertEquals("north", alpha.receive("send").getString("text"))
        beta.assertNoRequest("send", QUIET_MS)
        onMain { assertEquals(listOf("north"), EngineClient.session(ALPHA)!!.history) }

        assertTrue(onMain { EngineClient.session(BETA)!!.sendCommand("south") })
        assertEquals("south", beta.receive("send").getString("text"))
        alpha.assertNoRequest("send", QUIET_MS)
        onMain { assertEquals(listOf("south"), EngineClient.session(BETA)!!.history) }
    }

    @Test
    fun anAnswerCountsOnlyOnTheSocketItsRequestWentTo() {
        val completed = LinkedBlockingQueue<String>()
        assertTrue(onMain { EngineClient.session(ALPHA)!!.complete("no") { completed.add(it) } })
        val request = alpha.receive("complete")
        // The same id on the other profile's socket answers none of this one's requests
        beta.reply("completion", "from beta", request.get("id"))
        assertNull("beta's answer completed alpha's line", completed.poll(QUIET_MS.toLong(), TimeUnit.MILLISECONDS))
        alpha.reply("completion", "north", request.get("id"))
        assertEquals("north", completed.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun eachProfileReconnectsToItsOwnAddress() {
        beta.profiles(listOf(Profile(ALPHA, "alpha.example", 4000, false, "", false), Profile(BETA, "beta.example", 5000, true, "", false)))
        waitUntil("the profile list arrived") { EngineClient.profiles?.any { it.host == "beta.example" } == true }
        onMain {
            // A profile nothing has connected keeps no address of its own: the engine's list gives it
            assertEquals(GameAddress("alpha.example", 4000, false), EngineClient.session(ALPHA)!!.lastAddress)
            assertEquals(GameAddress("beta.example", 5000, true), EngineClient.session(BETA)!!.lastAddress)
        }
        assertTrue(onMain { EngineClient.session(ALPHA)!!.reconnect() })
        assertEquals("alpha.example", alpha.receive("connect").getString("host"))
        beta.assertNoRequest("connect", QUIET_MS)
    }

    @Test
    fun setFrontTellsTheEngineAndMovesTheFront() {
        assertTrue(onMain { EngineClient.setFront(ALPHA) })
        assertEquals(ALPHA, beta.receive("setFront").getString("name"))
        onMain { assertEquals(ALPHA, EngineClient.front.name) }
        // Until the engine says otherwise, its last word does not undo it
        beta.openProfiles()
        Thread.sleep(QUIET_MS.toLong())
        onMain { assertEquals(ALPHA, EngineClient.front.name) }
        assertFalse(onMain { EngineClient.setFront("Gamma") })
    }

    @Test
    fun showProfileBringsAnOpenOneToTheFront_andWaitsForNoneTheEngineHasNotOpen() {
        val shown = LinkedBlockingQueue<String>()
        onMain { EngineClient.showProfile(ALPHA) { shown.add(ALPHA) } }
        assertEquals(ALPHA, beta.receive("setFront").getString("name"))
        assertEquals(ALPHA, shown.poll(10, TimeUnit.SECONDS))
        onMain { assertEquals(ALPHA, EngineClient.front.name) }

        onMain { EngineClient.showProfile("Gamma") { shown.add("Gamma") } }
        // Not even once the engine has opened it
        fake.engine.openAlongside("Gamma")
        beta.openProfiles()
        beta.assertNoRequest("setFront", QUIET_MS)
        assertNull(shown.poll(QUIET_MS.toLong(), TimeUnit.MILLISECONDS))
    }

    @Test
    fun showProfileWhileDetachedWaitsForTheEnginesListOnAttaching() {
        val shown = LinkedBlockingQueue<String>()
        onMain {
            EngineClient.detach()
            EngineClient.showProfile(ALPHA) { shown.add(ALPHA) }
        }
        assertNull(shown.poll(QUIET_MS.toLong(), TimeUnit.MILLISECONDS))
        onMain { EngineClient.attach() }
        val reattached = fake.engine.nextSession()
        assertEquals(ALPHA, reattached.receive("setFront").getString("name"))
        assertEquals(ALPHA, shown.poll(10, TimeUnit.SECONDS))
        onMain { assertEquals(ALPHA, EngineClient.front.name) }
    }

    @Test
    fun aProfileThatClosesLosesItsSessionAndTheOtherTakesTheFront() {
        beta.line(1, "beta's last line")
        waitUntil("beta's line arrived") { EngineClient.session(BETA)?.lines?.isNotEmpty() == true }
        fake.engine.closeProfile(BETA)
        alpha.openProfiles()
        waitUntil("beta's session went") { EngineClient.session(BETA) == null }
        onMain {
            assertEquals(listOf(ALPHA), EngineClient.sessions.map { it.name })
            assertEquals(ALPHA, EngineClient.front.name)
            assertTrue(EngineClient.front.lines.none { it.text == "beta's last line" })
        }
    }

    @Test
    fun aProfileReopenedOnAnotherSocketFollowsIt() {
        fake.engine.closeProfile(ALPHA)
        fake.engine.openAlongside(ALPHA)
        beta.openProfiles()
        val reopened = beta.forProfile(ALPHA)
        reopened.line(1, "on the new socket")
        waitUntil("the line on the new socket arrived") { EngineClient.session(ALPHA)!!.lines.any { it.text == "on the new socket" } }
        assertTrue(onMain { EngineClient.session(ALPHA)!!.sendCommand("look") })
        assertEquals("look", reopened.receive("send").getString("text"))
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

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val ALPHA = "Alpha"
        const val BETA = "Beta"

        // Long enough for a message that went astray to have arrived
        const val QUIET_MS = 500
    }
}
