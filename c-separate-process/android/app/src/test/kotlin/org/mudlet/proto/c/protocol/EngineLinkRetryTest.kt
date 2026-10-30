package org.mudlet.proto.c.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLinkRetryTest {
    @Test
    fun fastPhaseCoversAnEngineRestart() {
        val firstSecond = (1..EngineLink.FAST_RETRY_ATTEMPTS).sumOf { EngineLink.retryDelayMs(it) }
        assertEquals(2000L, firstSecond)
        assertTrue((1..EngineLink.FAST_RETRY_ATTEMPTS).all { EngineLink.retryDelayMs(it) == EngineLink.FAST_RETRY_MS })
    }

    @Test
    fun backsOffToTheCapAfterTheFastPhase() {
        val after = (1..10).map { EngineLink.retryDelayMs(EngineLink.FAST_RETRY_ATTEMPTS + it) }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1600L, 2000L, 2000L, 2000L, 2000L, 2000L), after)
        assertEquals(EngineLink.MAX_RETRY_MS, EngineLink.retryDelayMs(Int.MAX_VALUE))
    }
}
