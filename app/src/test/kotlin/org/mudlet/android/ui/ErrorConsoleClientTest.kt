package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.ErrorConsoleEntry
import org.mudlet.android.protocol.ScriptItemKind

/** What the error console keeps of the engine's entries, what it counts as unseen, and what it sends. */
class ErrorConsoleClientTest {
    private val sent = ArrayList<JSONObject>()
    private var attached = true
    private val errors = ErrorConsoleClient { message -> attached.also { if (it) sent.add(message) } }

    private fun entry(n: Long, debug: Boolean = false, message: String = "boom $n") = ErrorConsoleEntry(n, 1_000L * n, debug, "Greet", "Trigger3", "", message, ScriptItemKind.TRIGGER, 3, 2)

    private fun full(vararg entries: ErrorConsoleEntry) = errors.onMessage(EngineMessage.ErrorConsole(entries.toList(), full = true))

    private fun added(vararg entries: ErrorConsoleEntry) = errors.onMessage(EngineMessage.ErrorConsole(entries.toList(), full = false))

    private fun ns() = errors.entries.map { it.n }

    @Test
    fun aFullListReplacesWhatWasKeptAndANewEntryIsAdded() {
        full(entry(1), entry(2))
        added(entry(3))
        assertEquals(listOf(1L, 2L, 3L), ns())
        full(entry(2), entry(3), entry(4))
        added(entry(5))
        assertEquals(listOf(2L, 3L, 4L, 5L), ns())
        full()
        assertEquals(emptyList<Long>(), ns())
    }

    @Test
    fun whatIsAddedIsKeptWithinTheEnginesCaps() {
        full()
        for (n in 1L..1001L) added(entry(n))
        assertEquals((2L..1001L).toList(), ns())

        // A million characters at most: 101 entries of 10000 drop the oldest
        full()
        for (n in 1L..101L) added(entry(n, message = "x".repeat(10_000 - "Greet".length - "Trigger3".length)))
        assertEquals((2L..101L).toList(), ns())
    }

    @Test
    fun errorsSinceThePlayerLastLookedAreUnseenButDebugLinesAreNot() {
        full(entry(1), entry(2, debug = true))
        assertEquals(1, errors.unseen)
        errors.markSeen()
        assertEquals(0, errors.unseen)
        added(entry(3), entry(4, debug = true), entry(5))
        assertEquals(2, errors.unseen)

        // The same engine keeps its count over a reattach and a clear, so what was seen stays seen
        errors.onAttached(sameInstance = true)
        full(entry(1), entry(2, debug = true), entry(3), entry(4, debug = true), entry(5))
        assertEquals(2, errors.unseen)
        errors.markSeen()
        full()
        added(entry(6))
        assertEquals(1, errors.unseen)

        // A new engine counts from 1 again
        errors.markSeen()
        errors.onAttached(sameInstance = false)
        full(entry(1), entry(2))
        assertEquals(2, errors.unseen)
    }

    @Test
    fun clearAsksTheEngine() {
        assertTrue(errors.clear())
        assertEquals("clearErrorConsole", sent.single().getString("t"))
        attached = false
        assertFalse("not sent", errors.clear())
    }
}
