package org.mudlet.android.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the profile editor's folded sections say they hold, and when the proxy's must open. */
class ProfileEditorSectionsTest {
    @Test
    fun theLoginSummaryNamesTheCharacterAndOnlyWhetherAPasswordIsSet() {
        assertEquals(LoginSummary.NotSet, LoginSummary.of("", false))
        assertEquals(LoginSummary.NotSet, LoginSummary.of("   ", false))
        assertEquals(LoginSummary.PasswordOnly, LoginSummary.of(" ", true))
        assertEquals(LoginSummary.As("hero", false), LoginSummary.of(" hero ", false))
        assertEquals(LoginSummary.As("hero", true), LoginSummary.of("hero", true))
    }

    @Test
    fun aProxyTheEngineWouldRefuseNeedsFixingWhetherOrNotItIsOn() {
        assertEquals(ProxySummary.NEEDS_FIX, ProxySummary.of(shown = true, use = true, valid = false, passwordStored = false))
        assertEquals(ProxySummary.NEEDS_FIX, ProxySummary.of(shown = true, use = false, valid = false, passwordStored = true))
        assertEquals(ProxySummary.ON, ProxySummary.of(shown = true, use = true, valid = true, passwordStored = false))
        assertEquals(ProxySummary.OFF, ProxySummary.of(shown = true, use = false, valid = true, passwordStored = true))
    }

    @Test
    fun aClosedGamesProxyIsUnknownSaveForAStoredPassword() {
        assertEquals(ProxySummary.UNKNOWN, ProxySummary.of(shown = false, use = true, valid = false, passwordStored = false))
        assertEquals(ProxySummary.PASSWORD_STORED, ProxySummary.of(shown = false, use = false, valid = true, passwordStored = true))
    }
}
