package org.mudlet.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.ByteBuffer

class ProblemReportTest {
    private val password = "S3cret \"pw\"&more"
    private val login = "Heroine"
    private val redactor = Redactor(listOf(password), listOf(login))

    @Test
    fun takesOutPasswordsAndLoginsInEveryFormALogLineCarriesThem() {
        val lines = listOf(
            "sent $password",
            "json {\"password\":\"S3cret \\\"pw\\\"&more\"}",
            "url ?p=S3cret+%22pw%22%26more and ?p=S3cret%20%22pw%22%26more",
            "welcome, heroine! HEROINE logged in",
        )
        val text = composeReport(listOf("header $login"), lines, redactor)
        for (secret in listOf("S3cret", "pw%22", "eroine", "EROINE")) assertFalse("$secret in:\n$text", text.contains(secret, ignoreCase = true))
        assertTrue(text, text.contains("sent ${Redactor.PASSWORD}"))
        assertTrue(text, text.contains("welcome, ${Redactor.LOGIN}! ${Redactor.LOGIN} logged in"))
        assertTrue(text, text.startsWith("header ${Redactor.LOGIN}\n"))
    }

    @Test
    fun aSecretThatHoldsAnotherGoesWhole() {
        assertEquals("x [password] y", Redactor(listOf("hero2024"), listOf("hero")).redact("x hero2024 y"))
        assertEquals("[login] ok", Redactor(listOf(""), listOf("hero", "")).redact("hero ok"))
    }

    @Test
    fun keepsTheNewestLinesThatFitAndSaysHowManyAreLeftOut() {
        val log = (1..100).map { "line $it ".padEnd(50, '.') }
        val text = composeReport(listOf("head"), log, redactor, maxChars = 1_000)
        assertTrue(text.length <= 1_000)
        assertTrue(text, text.contains("line 100 "))
        assertFalse(text, text.contains("line 1 "))
        val kept = text.lines().count { it.startsWith("line ") }
        assertTrue(text, text.contains("(${100 - kept} older lines left out)"))
        assertFalse(composeReport(listOf("head"), log.take(2), redactor).contains("left out"))
    }

    @Test
    fun aLongReportStillFitsAGitHubIssueWithRoomForWhatHappened() {
        val log = (1..10_000).map { "line $it ".padEnd(120, '.') }
        val text = composeReport(listOf("head"), log, redactor)
        assertTrue(text, text.contains("line 10000 "))
        assertTrue("${text.length} characters", text.length + 5_000 <= GITHUB_ISSUE_BODY_MAX_CHARS)
    }

    @Test
    fun aLogThatCouldNotBeCleanedIsLeftOutWhole() {
        val text = composeReport(listOf("head $login"), null, redactor)
        assertEquals("head ${Redactor.LOGIN}\n\n$LOG_WITHHELD\n", text)
    }

    @Test
    fun theIssueAddressCarriesTheSummaryAndStaysShort() {
        val summary = listOf("App: org.mudlet.mudlet 1.0 (5)", "Android: 14 (API 34)")
        val url = newIssueUrl(summary)
        assertTrue(url, url.startsWith("$ISSUES_URL?body="))
        assertFalse("spaces as %20, which GitHub reads in a query: $url", url.contains('+'))
        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        summary.forEach { assertTrue(body, body.contains(it)) }
        assertTrue(url.length < 2_000)
    }

    @Test
    fun readsAProfileItemAsTheEngineWritesIt() {
        fun item(text: String?): ByteArray {
            if (text == null) return byteArrayOf(-1, -1, -1, -1)
            val utf16 = text.toByteArray(Charsets.UTF_16BE)
            return ByteBuffer.allocate(4 + utf16.size).putInt(utf16.size).put(utf16).array()
        }
        assertEquals("Héroïne", readQString(item("Héroïne")))
        assertEquals("", readQString(item("")))
        assertEquals("", readQString(item(null)))
        assertNull(readQString(byteArrayOf(0, 0, 0, 9, 0)))
        assertNull(readQString(byteArrayOf(0, 0)))
    }

    private companion object {
        // The most GitHub takes in an issue's body
        const val GITHUB_ISSUE_BODY_MAX_CHARS = 65_536
    }
}
