package org.mudlet.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** What EngineClient holds for the UI from the engine's messages, read on the main thread as the UI reads it. */
@RunWith(AndroidJUnit4::class)
class EngineClientTest {
    private val fake = FakeEngineRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainThreadTimeout(TIMEOUT_MS)).around(fake)

    private lateinit var session: FakeEngine.Session

    @Before
    fun attach() {
        onMain {
            // EngineClient lives as long as the process
            EngineClient.typedLineMoved()
            EngineClient.attach()
        }
        session = fake.engine.nextSession()
        session.synced(0)
    }

    @After
    fun detach() = onMain { EngineClient.detach() }

    @Test
    fun moveTypedLineHoldsUntilTheLineHasMovedIt() {
        assertFalse(onMain { EngineClient.moveTypedLine })
        session.send(JSONObject().put("t", "moveTypedLine"))
        waitUntil("moveTypedLine set") { EngineClient.moveTypedLine }
        onMain { EngineClient.typedLineMoved() }
        assertFalse(onMain { EngineClient.moveTypedLine })
    }

    @Test
    fun aNewEngineDropsAMoveTheLineHasNotMade() {
        session.send(JSONObject().put("t", "moveTypedLine"))
        waitUntil("moveTypedLine set") { EngineClient.moveTypedLine }
        fake.engine.instance = "after-move"
        session.close()
        fake.engine.nextSession().synced(0)
        waitUntil("moveTypedLine dropped by the new engine") { !EngineClient.moveTypedLine }
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
    }
}
