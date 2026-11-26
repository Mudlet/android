package org.mudlet.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop Mudlet's rules for feature callouts (TFeatureCallout.cpp), as [CalloutRules] keeps them, over a store in memory. */
class CalloutRulesTest {
    private class MemoryStore : CalloutStore {
        val dismissed = HashSet<String>()
        val appearances = HashMap<String, Int>()

        override fun dismissed(id: String) = id in dismissed

        override fun dismiss(id: String) {
            dismissed += id
        }

        override fun appearances(id: String) = appearances[id] ?: 0

        override fun setAppearances(id: String, count: Int) {
            appearances[id] = count
        }
    }

    private val store = MemoryStore()

    /** A session that shows [id] and has it go unheeded, as a tap elsewhere does. */
    private fun ignoredIn(rules: CalloutRules, id: String) {
        assertTrue("$id did not appear", rules.appear(id))
        rules.gone(id)
    }

    @Test
    fun aNewCalloutAppearsOnceASession_andCountsTheAppearance() {
        val rules = CalloutRules(store)
        assertTrue(rules.due(A))
        assertTrue(rules.appear(A))
        assertEquals(A, rules.showing)
        assertEquals(1, store.appearances(A))
        rules.gone(A)
        assertFalse("it showed again in its session", rules.due(A))
        assertFalse(rules.appear(A))
        assertEquals(1, store.appearances(A))
    }

    @Test
    fun aDismissedCalloutNeverAppearsAgain_inThisSessionOrTheNext() {
        val rules = CalloutRules(store)
        assertTrue(rules.appear(A))
        rules.dismiss(A)
        rules.gone(A)
        assertTrue(store.dismissed(A))
        assertFalse(CalloutRules(store).due(A))

        // Reached another way before it ever showed
        rules.dismiss(B)
        assertFalse(CalloutRules(store).appear(B))
        assertEquals(0, store.appearances(B))
    }

    @Test
    fun anIgnoredCalloutStopsAfterFiveAppearances() {
        repeat(CalloutRules.MAXIMUM_APPEARANCES) { ignoredIn(CalloutRules(store), A) }
        assertEquals(5, store.appearances(A))
        assertFalse("it appeared a sixth time", CalloutRules(store).due(A))
        assertFalse(store.dismissed(A))
    }

    @Test
    fun atMostTwoDifferentCalloutsASession() {
        val rules = CalloutRules(store)
        ignoredIn(rules, A)
        ignoredIn(rules, B)
        assertFalse("a third callout appeared in one session", rules.appear(C))
        assertEquals(0, store.appearances(C))
        assertTrue(CalloutRules(store).appear(C))
    }

    @Test
    fun oneCalloutAtATime() {
        val rules = CalloutRules(store)
        assertTrue(rules.appear(A))
        assertFalse("a second callout appeared over the first", rules.due(B))
        assertFalse(rules.appear(B))
        rules.gone(B)
        assertEquals("another's going took the first's place", A, rules.showing)
        rules.gone(A)
        assertTrue(rules.appear(B))
    }

    private companion object {
        const val A = "gameSwitching"
        const val B = "second"
        const val C = "third"
    }
}
