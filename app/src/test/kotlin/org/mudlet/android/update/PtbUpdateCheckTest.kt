package org.mudlet.android.update

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The PTB's check for a newer build from GitHub, with a fake clock and a fake GitHub: never a real request. */
class PtbUpdateCheckTest {
    private class MemoryStore : PtbUpdateCheck.Store {
        override var lastCheckMs = 0L
        override var newestVersion = 0L
        override var dismissedVersion = 0L
    }

    private val store = MemoryStore()
    private var now = START_MS
    private var wanted = true
    private val check = PtbUpdateCheck(store, INSTALLED, nowMs = { now }, wanted = { wanted })
    private var asked = 0

    // GitHub's answer for the pre-release, as ptb.yml's github-release job writes its notes
    private fun release(version: Long, tag: String = "ptb", newline: String = "\n") = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", true)
        .put("html_url", "https://github.com/Mudlet/android/releases/tag/$tag")
        .put("body", listOf("Mudlet for Android, public test build.", "", "<!-- mudlet: abc -->", "<!-- versionCode: $version -->", "<!-- play: uploaded -->").joinToString(newline))
        .toString()

    private fun answering(answer: String?): () -> String? = {
        asked++
        answer
    }

    @Test
    fun theVersionIsTheNotesMarker() {
        assertEquals(263181530L, PtbUpdateCheck.versionCode(release(263181530L)))
        assertEquals("notes edited on github.com have \\r\\n", 263181530L, PtbUpdateCheck.versionCode(release(263181530L, newline = "\r\n")))
        assertNull("another release", PtbUpdateCheck.versionCode(release(263181530L, tag = "android-v1.0")))
        assertNull("a draft", PtbUpdateCheck.versionCode(JSONObject(release(263181530L)).put("draft", true).toString()))
        assertNull("no marker", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"versionCode 263181530"}"""))
        assertNull("no notes", PtbUpdateCheck.versionCode("""{"tag_name":"ptb"}"""))
        assertNull("two markers", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"<!-- versionCode: 1 -->\n<!-- versionCode: 2 -->"}"""))
        assertNull("the marker within a line", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"x <!-- versionCode: 263181530 -->"}"""))
        assertNull("not a number", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"<!-- versionCode: -1 -->"}"""))
        assertNull("zero", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"<!-- versionCode: 0 -->"}"""))
        assertNull("longer than a versionCode", PtbUpdateCheck.versionCode("""{"tag_name":"ptb","body":"<!-- versionCode: 12345678901 -->"}"""))
        assertNull("not JSON", PtbUpdateCheck.versionCode("<html>rate limited</html>"))
        assertNull("a JSON array", PtbUpdateCheck.versionCode("[]"))
        assertNull("nothing", PtbUpdateCheck.versionCode(""))
    }

    @Test
    fun onlyAVersionAboveTheInstalledOneIsOffered() {
        check.runIfDue(answering(release(INSTALLED)))
        assertNull("the same build", check.offered)
        now += DAY
        check.runIfDue(answering(release(INSTALLED - 1)))
        assertNull("an older build", check.offered)
        now += DAY
        check.runIfDue(answering(release(INSTALLED + 1)))
        assertEquals("a newer build", INSTALLED + 1, check.offered)
        assertEquals("a newer build installed since", null, PtbUpdateCheck(store, INSTALLED + 1, nowMs = { now }).offered)
    }

    @Test
    fun itAsksAtMostOnceADay() {
        assertEquals("GitHub's version", INSTALLED + 1, check.runIfDue(answering(release(INSTALLED + 1))))
        assertEquals("the first time", 1, asked)
        now += DAY - 1
        assertFalse(check.due())
        assertNull("not asked", check.runIfDue(answering(release(INSTALLED + 2))))
        assertEquals("not within the day", 1, asked)
        assertEquals(INSTALLED + 1, check.offered)
        now += 1
        assertTrue(check.due())
        check.runIfDue(answering(release(INSTALLED + 2)))
        assertEquals("a day later", 2, asked)
        assertEquals(INSTALLED + 2, check.offered)
    }

    @Test
    fun aFailedOrOddAnswerIsNotAskedAgainThatDay() {
        for (answer in listOf(null, "<html>rate limited</html>", release(INSTALLED + 1, tag = "other"))) {
            val before = asked
            assertNull("no version from $answer", check.runIfDue(answering(answer)))
            assertEquals("asked once for $answer", before + 1, asked)
            assertNull("nothing offered for $answer", check.offered)
            now += DAY - 1
            check.runIfDue(answering(release(INSTALLED + 1)))
            assertEquals("not again within the day after $answer", before + 1, asked)
            now += 1
        }
    }

    @Test
    fun turnedOffItDoesNotAsk_butStillKnowsWhatItFound() {
        check.runIfDue(answering(release(INSTALLED + 1)))
        wanted = false
        now += 2 * DAY
        assertFalse(check.due())
        check.runIfDue(answering(release(INSTALLED + 2)))
        assertEquals("not asked while off", 1, asked)
        assertEquals(INSTALLED + 1, check.offered)
        wanted = true
        assertTrue(check.due())
        check.runIfDue(answering(release(INSTALLED + 2)))
        assertEquals("asked once on again", 2, asked)
    }

    @Test
    fun aClockSetBackDoesNotStopTheChecks() {
        check.runIfDue(answering(null))
        now -= 365 * DAY
        assertTrue(check.due())
        check.runIfDue(answering(release(INSTALLED + 1)))
        assertEquals(2, asked)
        assertEquals(INSTALLED + 1, check.offered)
    }

    @Test
    fun aDismissedVersionIsNotOfferedAgain_butANewerOneIs() {
        check.runIfDue(answering(release(INSTALLED + 1)))
        check.dismiss()
        assertNull(check.offered)
        now += DAY
        check.runIfDue(answering(release(INSTALLED + 1)))
        assertNull("the same one again", check.offered)
        now += DAY
        check.runIfDue(answering(release(INSTALLED + 2)))
        assertEquals("a newer one", INSTALLED + 2, check.offered)
    }

    @Test
    fun aRollingBackAnswerKeepsTheNewestSeen() {
        check.runIfDue(answering(release(INSTALLED + 2)))
        now += DAY
        check.runIfDue(answering(release(INSTALLED + 1)))
        assertEquals(INSTALLED + 2, check.offered)
    }

    @Test
    fun onlyACopyGooglePlayInstalledDoesNotCheck() {
        assertFalse("Google Play", PtbUpdateCheck.checksFor("com.android.vending"))
        assertTrue("adb, or an installer Android does not name", PtbUpdateCheck.checksFor(null))
        assertTrue("adb on later Androids", PtbUpdateCheck.checksFor("com.android.shell"))
        assertTrue("a file manager or a browser, through the package installer", PtbUpdateCheck.checksFor("com.google.android.packageinstaller"))
        assertTrue("another store", PtbUpdateCheck.checksFor("org.fdroid.fdroid"))
    }

    @Test
    fun anAnswerOverTheLimitIsNotRead() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertArrayEquals(bytes, PtbUpdateCheck.readAtMost(ByteArrayInputStream(bytes), bytes.size))
        assertNull(PtbUpdateCheck.readAtMost(ByteArrayInputStream(bytes), bytes.size - 1))
        assertArrayEquals(ByteArray(0), PtbUpdateCheck.readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
    }

    private companion object {
        const val INSTALLED = 263181530L
        const val DAY = PtbUpdateCheck.DAY_MS
        const val START_MS = 1_763_000_000_000L
    }
}
