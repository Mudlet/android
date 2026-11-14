package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EngineRestoreTest {
    @Test
    fun describesEachPhaseWithItsDetail() {
        assertEquals("The engine restarted: loading profile Aardwolf again", EngineRestore.describe("restoring Aardwolf"))
        assertEquals(
            "The engine restarted: profile Aardwolf restored, not connected as it was not before",
            EngineRestore.describe("restored Aardwolf"),
        )
        assertEquals(
            "The engine restarted again: profile restored, reconnecting to aardmud.org:23 in 10 s",
            EngineRestore.describe("waiting 10 aardmud.org:23"),
        )
        assertEquals(
            "The engine restarted: profile restored, reconnecting to aardmud.org:23",
            EngineRestore.describe("reconnecting aardmud.org:23"),
        )
        assertEquals("Reconnected to aardmud.org:23 after the engine restarted", EngineRestore.describe("reconnected aardmud.org:23"))
        assertEquals(
            "Could not reconnect to aardmud.org:23 yet; the profile tries again on its own",
            EngineRestore.describe("retrying aardmud.org:23"),
        )
        assertEquals(
            "Could not reconnect to aardmud.org:23 after the engine restarted",
            EngineRestore.describe("notReconnected aardmud.org:23"),
        )
        assertEquals("Restoring the profile was cancelled", EngineRestore.describe("cancelled"))
        assertEquals(
            "The engine restarted, but could not load the profile again: no such profile",
            EngineRestore.describe("failed no such profile"),
        )
        assertEquals(
            "The engine keeps stopping (restored 3 times in 10 minutes): the profile was not restored",
            EngineRestore.describe("gaveUp 3 10"),
        )
    }

    @Test
    fun everyPhaseHasItsOwnText() {
        for (phase in EngineRestore.PHASES) {
            assertFalse("$phase falls through to the generic text", EngineRestore.describe("$phase x").startsWith("Engine restore:"))
        }
    }

    @Test
    fun aMarkerOnlyForANewValue() {
        assertEquals(
            "--- Reconnected to h:23 after the engine restarted ---",
            EngineRestore.marker(null, "reconnected h:23"),
        )
        assertEquals(
            "--- Reconnected to h:23 after the engine restarted ---",
            EngineRestore.marker("reconnecting h:23", "reconnected h:23"),
        )
        // The same value again, as every attach resends it, or a batch with no restore status
        assertNull(EngineRestore.marker("reconnected h:23", "reconnected h:23"))
        assertNull(EngineRestore.marker("reconnected h:23", null))
        assertNull(EngineRestore.marker(null, null))
    }

    @Test
    fun anUnknownPhaseIsShownAsItCame() {
        assertEquals("Engine restore: rebooting the moon", EngineRestore.describe("rebooting the moon"))
    }
}
