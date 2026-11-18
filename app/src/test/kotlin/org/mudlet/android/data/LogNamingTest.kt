package org.mudlet.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Logging page names a game's logs, as Mudlet's `setConfig()` takes them (0028-headless-log-settings.patch). */
class LogNamingTest {
    @Test
    fun theFormatsAreTheOnesMudletTakes_inItsPreferencesOrder() {
        assertEquals(
            listOf("yyyy-MM-dd#HH-mm-ss", "yyyy-MM-ddTHH-mm-ss", "yyyy-MM-dd", "yyyy-MM", ""),
            LogNameFormat.entries.map { it.mudletValue },
        )
    }

    @Test
    fun aNameWithASeparatorIsRefused_asMudletRefusesIt() {
        for (name in listOf("../Raid", "logs/Raid", "C:\\Raid", "\\", "/")) assertTrue(name, logFileNameRefused(name))
        for (name in listOf("", "Raid", "Raid log 2", "raid.old", "Überfall", "..", "a:b")) assertFalse(name, logFileNameRefused(name))
    }

    @Test
    fun aLogEndsAsHtmlDecides() {
        assertEquals(".html", logFileExtension(html = true))
        assertEquals(".txt", logFileExtension(html = false))
    }
}
