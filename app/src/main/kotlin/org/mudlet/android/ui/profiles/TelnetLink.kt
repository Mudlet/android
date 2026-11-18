package org.mudlet.android.ui.profiles

import org.mudlet.android.protocol.Profile
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * A `telnet://` or `telnets://` link to a game, as Mudlet's parseTelnetUri (src/TelnetUri.cpp) reads one: [host] in
 * its ASCII form, lowercase, so that a look-alike Unicode name shows as what it really connects to; [port] 23 when the
 * link names none, 992 for `telnets://`, which is TLS ([tls]) and takes no IP address, as a certificate names a host.
 * [login] is the link's user name, empty for none. A password in the link is dropped as it is read, never kept.
 */
class TelnetLink private constructor(val host: String, val port: Int, val tls: Boolean, val login: String) {
    /** The host and port as a player reads them, an IPv6 address in brackets. */
    val address: String get() = "${hostForUrl()}:$port"

    /** The link again without anything the player has not seen, for the saved state and the route that carry it. */
    fun encode(): String {
        val user = if (login.isEmpty()) "" else URLEncoder.encode(login, StandardCharsets.UTF_8.name()) + "@"
        return "${if (tls) TELNETS else TELNET}://$user${hostForUrl()}:$port"
    }

    /**
     * The profile in [profiles] the link is for: the first, the last used, at its host and port, as desktop's
     * findMatchingProfile looks. A `telnets://` link takes only one that connects securely, as the link cannot change
     * the profile's setting, nor connect it in the clear when it asked for TLS.
     */
    fun profileIn(profiles: List<Profile>): Profile? = profiles.firstOrNull { it.port == port && (it.tls || !tls) && sameHost(it.host) }

    /** The game of Mudlet's in [games] at the link's address, under the same terms as [profileIn]. */
    fun gameIn(games: List<DefaultGame>): DefaultGame? = games.firstOrNull { g ->
        g.port == port && (g.tls || !tls) && (listOf(g.host) + g.alternateHosts).any(::sameHost)
    }

    /**
     * A new profile for the link, which the player checks in the editor before it is saved. Its name is the host's
     * first part with a capital, as desktop's createProfileForUri names one, with "-2" and on after it while [taken]
     * has it in any case; null when none up to "-99" is free.
     */
    fun newProfile(taken: Collection<String>): Profile? {
        val dot = host.indexOf('.')
        val base = (if (dot > 0) host.substring(0, dot) else host).replaceFirstChar { it.titlecase(Locale.ROOT) }
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val name = (sequenceOf(base) + (2 until MAX_SUFFIX).asSequence().map { "$base-$it" }).firstOrNull { it.lowercase(Locale.ROOT) !in lower } ?: return null
        return Profile(name, host, port, tls, login, autoReconnect = false)
    }

    // A profile's host as the player typed it, which may be Unicode or have spaces round it
    private fun sameHost(other: String): Boolean = asciiHost(other.trim().removeSuffix("."))?.let { it == host } ?: false

    private fun hostForUrl() = if (':' in host) "[$host]" else host

    override fun equals(other: Any?) = other is TelnetLink && host == other.host && port == other.port && tls == other.tls && login == other.login

    override fun hashCode() = listOf(host, port, tls, login).hashCode()

    override fun toString() = "TelnetLink(${encode().substringBefore("://")}://$address)"

    companion object {
        private const val TELNET = "telnet"
        private const val TELNETS = "telnets"
        private const val TELNET_PORT = 23
        private const val TELNETS_PORT = 992
        private const val MAX_LINK = 2048
        private const val MAX_LOGIN = 256
        private const val MAX_HOST = 253
        private const val MAX_LABEL = 63
        private const val MAX_SUFFIX = 100

        /** [link] read as a game's address; null for anything else, which nothing is to connect to. */
        fun parse(link: String): TelnetLink? {
            if (link.length > MAX_LINK || link.any { it.isWhitespace() || it.isISOControl() || Character.isSpaceChar(it) }) return null
            val schemeEnd = link.indexOf("://")
            if (schemeEnd < 0) return null
            val tls = when (link.substring(0, schemeEnd).lowercase(Locale.ROOT)) {
                TELNET -> false
                TELNETS -> true
                else -> return null
            }
            // The path, query and fragment mean nothing to a telnet link, and desktop ignores them too
            val authority = link.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
            val at = authority.lastIndexOf('@')
            val login = if (at < 0) "" else percentDecode(authority.substring(0, at).substringBefore(':')) ?: return null
            if (login.length > MAX_LOGIN || login.any { it.isISOControl() }) return null
            val hostPort = authority.substring(at + 1)

            val hostText: String
            val portText: String?
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                hostText = hostPort.substring(1, close)
                val after = hostPort.substring(close + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(':') -> after.substring(1)
                    else -> return null
                }
                if (!isIpv6(hostText)) return null
            } else {
                val colon = hostPort.indexOf(':')
                hostText = if (colon < 0) hostPort else hostPort.substring(0, colon)
                portText = if (colon < 0) null else hostPort.substring(colon + 1)
            }

            val port = when {
                portText.isNullOrEmpty() -> if (tls) TELNETS_PORT else TELNET_PORT
                portText.length > 5 || !portText.all { it in '0'..'9' } -> return null
                else -> portText.toInt().takeIf { it in 1..65535 } ?: return null
            }

            val ipv6 = hostPort.startsWith('[')
            val host = if (ipv6) hostText.lowercase(Locale.ROOT) else asciiHost(hostText.removeSuffix(".")) ?: return null
            // A certificate is checked against a name: an address would connect to whatever the link chose, unchecked
            if (tls && (ipv6 || isIpv4(host))) return null
            return TelnetLink(host, port, tls, login)
        }

        // [host]'s ASCII form, lowercase, when it is a host name or an IPv4 address; null when it is neither
        private fun asciiHost(host: String): String? {
            if (host.isEmpty() || '%' in host) return null
            val ascii = try {
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val labels = ascii.split('.')
            if (ascii.length > MAX_HOST || labels.any { it.isEmpty() || it.length > MAX_LABEL }) return null
            // All numbers is an address, or nothing: "1.2.3" would be read as one by some resolvers
            if (labels.all { label -> label.all { it in '0'..'9' } } && !isIpv4(ascii)) return null
            return ascii
        }

        private fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
        }

        // An IPv6 address as RFC 4291 writes one, an IPv4 address possibly at its end; no zone, which names this device's interface
        private fun isIpv6(text: String): Boolean {
            if (text.isEmpty() || text.count { it == ':' } < 2) return false
            val halves = text.split("::")
            if (halves.size > 2) return false
            val groups = halves.map { half -> if (half.isEmpty()) emptyList() else half.split(':') }
            val all = groups.flatten()
            val last = all.lastOrNull()
            val v4 = last != null && '.' in last
            if (v4 && (!isIpv4(last) || !text.endsWith(last))) return false
            val hex = if (v4) all.dropLast(1) else all
            if (!hex.all { g -> g.length in 1..4 && g.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }) return false
            val count = hex.size + if (v4) 2 else 0
            return if (halves.size == 2) count <= 7 else count == 8
        }

        // RFC 3986's percent-decoding into UTF-8; null for a broken escape or bytes that are not UTF-8
        private fun percentDecode(text: String): String? {
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    bytes.write(byte)
                    i += 3
                } else {
                    // A run at once, so a character of two UTF-16 units is encoded whole
                    val end = text.indexOf('%', i).let { if (it < 0) text.length else it }
                    bytes.write(text.substring(i, end).toByteArray(StandardCharsets.UTF_8))
                    i = end
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
