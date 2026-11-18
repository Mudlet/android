package org.mudlet.android.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class TimeStampFormatTest {
    @Test
    fun aTimeStampIsDesktopsTwentyFourHourTimeToTheMillisecondAndASpace() {
        assertEquals("00:00:00.000 ", timeStamp(0, ZoneOffset.UTC))
        assertEquals("22:13:20.123 ", timeStamp(1_700_000_000_123, ZoneOffset.UTC))
        assertEquals("in the device's zone", "23:13:20.123 ", timeStamp(1_700_000_000_123, ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun theLagIsTheNetworksAndTheProcessingsOrNoNetworksWithoutGa() {
        assertEquals(Lag("0.250", "0.004"), Lag.of("0.250 0.004"))
        assertEquals(Lag(null, "0.004"), Lag.of("- 0.004"))
        assertNull("before the first", Lag.of(null))
        assertNull("once the profile closes", Lag.of(""))
    }
}
