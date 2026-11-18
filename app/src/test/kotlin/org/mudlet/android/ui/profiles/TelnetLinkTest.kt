package org.mudlet.android.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mudlet.android.protocol.Profile

/**
 * A `telnet://` link read as desktop's parseTelnetUri reads it, every link that is no game's address refused, and the
 * profile or game of Mudlet's it is for.
 */
class TelnetLinkTest {
    private fun link(text: String) = TelnetLink.parse(text) ?: throw AssertionError("$text refused")

    private fun refused(text: String) = assertNull("$text is refused", TelnetLink.parse(text))

    @Test
    fun aLinkWithNoPortIsForTelnetsPortAndTelnetsForTls() {
        val plain = link("telnet://achaea.com")
        assertEquals("achaea.com", plain.host)
        assertEquals(23, plain.port)
        assertFalse(plain.tls)
        val secure = link("telnets://achaea.com")
        assertEquals(992, secure.port)
        assertEquals(true, secure.tls)
        assertEquals(23, link("telnet://achaea.com:").port)
    }

    @Test
    fun readsThePortTheSchemeInAnyCaseAndIgnoresAPath() {
        val l = link("TELNET://Achaea.COM.:4000/play?x=1#top")
        assertEquals("achaea.com", l.host)
        assertEquals(4000, l.port)
        assertEquals("achaea.com:4000", l.address)
        assertEquals(23, link("telnet://achaea.com:0023").port)
        assertEquals(65535, link("telnet://achaea.com:65535").port)
        assertEquals(1, link("telnet://achaea.com:1").port)
    }

    @Test
    fun keepsTheUserNameAndDropsThePassword() {
        val l = link("telnet://hero:s3cret@achaea.com:23")
        assertEquals("hero", l.login)
        assertFalse("the password is not kept", l.encode().contains("s3cret"))
        assertFalse("nor shown", l.toString().contains("s3cret") || l.toString().contains("hero"))
        assertEquals("Hëro x", link("telnet://H%C3%ABro%20x@achaea.com").login)
        assertEquals("", link("telnet://achaea.com").login)
        assertEquals("a user name may hold an @ escaped", "a@b", link("telnet://a%40b@achaea.com").login)
    }

    @Test
    fun aUnicodeHostIsReadAsTheNameItConnectsTo() {
        assertEquals("xn--bcher-kva.example", link("telnet://bücher.example").host)
    }

    @Test
    fun anAddressIsTakenForTelnetButNotForTls() {
        assertEquals("192.0.2.1", link("telnet://192.0.2.1:4000").host)
        val v6 = link("telnet://[2001:DB8::1]:4000")
        assertEquals("2001:db8::1", v6.host)
        assertEquals("[2001:db8::1]:4000", v6.address)
        assertEquals("::ffff:192.0.2.1", link("telnet://[::ffff:192.0.2.1]").host)
        assertEquals("1:2:3:4:5:6:7:8", link("telnet://[1:2:3:4:5:6:7:8]").host)
        refused("telnets://192.0.2.1")
        refused("telnets://[2001:db8::1]")
    }

    @Test
    fun refusesWhatIsNoTelnetLink() {
        refused("http://achaea.com")
        refused("telnetx://achaea.com")
        refused("achaea.com:23")
        refused("telnet:achaea.com")
        refused("")
    }

    @Test
    fun refusesALinkWithNoHostOrABrokenOne() {
        refused("telnet://")
        refused("telnet://:23")
        refused("telnet://hero@")
        refused("telnet://under_score.example")
        refused("telnet://a..b")
        refused("telnet://-a.example")
        refused("telnet://a%2eexample")
        refused("telnet://1.2.3")
        refused("telnet://256.0.0.1")
        refused("telnet://" + "a".repeat(64) + ".example")
        refused("telnet://" + List(64) { "abc" }.joinToString(".")) // over 253 characters
        refused("telnet://a<b>.example")
    }

    @Test
    fun refusesAPortThatIsNoNumberOrOutOfRange() {
        refused("telnet://achaea.com:0")
        refused("telnet://achaea.com:65536")
        refused("telnet://achaea.com:100000")
        refused("telnet://achaea.com:-1")
        refused("telnet://achaea.com:23a")
        refused("telnet://achaea.com:2:3")
        refused("telnet://achaea.com:+23")
    }

    @Test
    fun refusesABrokenIpv6Address() {
        refused("telnet://2001:db8::1")
        refused("telnet://[2001:db8::1")
        refused("telnet://[2001:db8::1]x")
        refused("telnet://[2001:db8::1]:x")
        refused("telnet://[]")
        refused("telnet://[achaea.com]")
        refused("telnet://[1:2:3:4:5:6:7:8:9]")
        refused("telnet://[1:2:3:4:5:6:7]")
        refused("telnet://[1::2::3]")
        refused("telnet://[12345::1]")
        refused("telnet://[fe80::1%25eth0]")
        refused("telnet://[::1.2.3]")
    }

    @Test
    fun refusesSpacesControlsBrokenEscapesAndAnOverlongLink() {
        refused("telnet://achaea.com :23")
        refused(" telnet://achaea.com")
        refused("telnet://achaea.com\n")
        refused("telnet://ach\u0000aea.com")
        refused("telnet://he ro@achaea.com")
        refused("telnet://achaea.com/a b")
        refused("telnet://achaea.com ")
        refused("telnet://her%0Ao@achaea.com")
        refused("telnet://her%zzo@achaea.com")
        refused("telnet://hero%4@achaea.com")
        refused("telnet://hero%@achaea.com")
        refused("telnet://%C3%28@achaea.com")
        refused("telnet://" + "h".repeat(257) + "@achaea.com")
        refused("telnet://achaea.com/" + "x".repeat(2048))
    }

    @Test
    fun encodesToTheSameLink() {
        for (text in listOf("telnet://achaea.com:23", "telnets://hëro%3Ax@achaea.com", "telnet://[2001:db8::1]:4000", "telnet://bücher.example")) {
            val l = link(text)
            assertEquals(text, l, link(l.encode()))
        }
    }

    @Test
    fun findsTheLastUsedProfileAtTheAddressInAnyCaseOrScript() {
        val profiles = listOf(
            Profile("Other", "achaea.com", 4000, false, "", false),
            Profile("Mine", " ACHAEA.com ", 23, false, "", false),
            Profile("Older", "achaea.com", 23, false, "", false),
        )
        assertEquals("Mine", link("telnet://achaea.com").profileIn(profiles)?.name)
        assertNull(link("telnet://achaea.com:2323").profileIn(profiles))
        assertNull(link("telnet://achaea.org").profileIn(profiles))
        assertEquals("Books", link("telnet://xn--bcher-kva.example").profileIn(listOf(Profile("Books", "Bücher.example", 23, false, "", false)))?.name)
    }

    @Test
    fun aTlsLinkTakesOnlyAProfileThatConnectsSecurely() {
        val plain = Profile("Plain", "stickmud.com", 8443, false, "", false)
        val secure = Profile("Secure", "stickmud.com", 8443, true, "", false)
        assertNull(link("telnets://stickmud.com:8443").profileIn(listOf(plain)))
        assertEquals("Secure", link("telnets://stickmud.com:8443").profileIn(listOf(plain, secure))?.name)
        assertEquals("a plain link may connect securely", "Secure", link("telnet://stickmud.com:8443").profileIn(listOf(secure))?.name)
    }

    @Test
    fun findsMudletsGameAtAnyOfItsHosts() {
        val game = DefaultGame("Game", "game.example", 4000, false, emptyList(), "", "game.png", listOf("alt.game.example"))
        assertEquals(game, link("telnet://ALT.game.example:4000").gameIn(listOf(game)))
        assertEquals(game, link("telnet://game.example:4000").gameIn(listOf(game)))
        assertNull(link("telnet://game.example:23").gameIn(listOf(game)))
        assertNull(link("telnets://game.example:4000").gameIn(listOf(game)))
    }

    @Test
    fun namesANewProfileAfterTheHostAsDesktopDoes() {
        val new = link("telnets://hero:pw@achaea.com:6000").newProfile(emptyList())
        assertEquals(Profile("Achaea", "achaea.com", 6000, true, "hero", false), new)
        assertEquals("Achaea-2", link("telnet://achaea.com").newProfile(listOf("ACHAEA"))?.name)
        assertEquals("Achaea-3", link("telnet://achaea.com").newProfile(listOf("achaea", "Achaea-2"))?.name)
        assertEquals("192", link("telnet://192.0.2.1").newProfile(emptyList())?.name)
        assertNotNull(link("telnet://[::1]").newProfile(emptyList()))
        assertNull("none free", link("telnet://a.example").newProfile(listOf("A") + (2 until 100).map { "A-$it" }))
    }
}
