package org.mudlet.android.data

import org.json.JSONException
import org.json.JSONObject
import org.mudlet.android.protocol.InstalledPackage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A package of Mudlet's package repository, as its index lists it: [name] is its `mpackage`, [icon] a path in the repository. */
data class RepositoryPackage(
    val name: String,
    val title: String,
    val author: String,
    val version: String,
    val description: String,
    val filename: String,
    val icon: String,
)

/** Downloads [url] into a stream; throws an IOException for anything but the whole of a successful answer. */
fun interface RepositoryFetch {
    fun get(url: String, timeoutMs: Int, maxBytes: Long, into: OutputStream)
}

/**
 * Mudlet's package repository as desktop's Package Manager (dlgPackageManager) and the mpkg package use it: the same
 * index, kept under the same name in the profile's folder, the same addresses, and the same rules for which installed
 * package is the repository's and when it has an update.
 */
object PackageRepository {
    const val INDEX_FILE = "mpkg.packages.json"
    const val INDEX_URL = "https://raw.githubusercontent.com/Mudlet/mudlet-package-repository/refs/heads/main/packages/mpkg.packages.json"
    private const val FILES_URL = "https://github.com/Mudlet/mudlet-package-repository/raw/refs/heads/main/packages/"
    private const val ICONS_URL = "https://github.com/Mudlet/mudlet-package-repository/raw/refs/heads/main/"

    /** The hosts a request or a redirect may go to: github.com answers for a file with a redirect to raw.githubusercontent.com. */
    val HOSTS = setOf("raw.githubusercontent.com", "github.com")

    // Desktop's transfer timeouts
    const val INDEX_TIMEOUT_MS = 20_000
    const val PACKAGE_TIMEOUT_MS = 30_000
    const val ICON_TIMEOUT_MS = 10_000

    // Desktop has no limits; these are far above today's index (0.4 MB), packages and icons
    const val MAX_INDEX_BYTES = 16L * 1024 * 1024
    const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    const val MAX_ICON_BYTES = 2L * 1024 * 1024

    private const val MAX_REDIRECTS = 5

    /** The index in the profile's folder, where desktop's Package Manager and mpkg keep it. */
    fun indexFile(filesDir: File, profile: String) = File(ProfileArchive.folder(filesDir, profile), INDEX_FILE)

    /**
     * The packages of an index, or null when it is not one: a JSON object with a `packages` array, which desktop also
     * asks of a download, as a captive portal or a proxy can answer with a page of its own and a success status.
     */
    fun parse(text: String): List<RepositoryPackage>? {
        val packages = try {
            JSONObject(text).optJSONArray("packages")
        } catch (_: JSONException) {
            null
        } ?: return null
        return (0 until packages.length()).mapNotNull { i ->
            val entry = packages.optJSONObject(i) ?: return@mapNotNull null
            val name = entry.optString("mpackage")
            if (name.isEmpty()) return@mapNotNull null
            RepositoryPackage(
                name,
                entry.optString("title"),
                entry.optString("author"),
                entry.optString("version"),
                entry.optString("description"),
                entry.optString("filename"),
                entry.optString("icon"),
            )
        }
    }

    /** Where [entry]'s file downloads from, or null when the index names none, which desktop takes as not in the repository. */
    fun fileUrl(entry: RepositoryPackage): String? = fileName(entry)?.let { FILES_URL + encode(it) }

    /** The name [entry]'s file is saved under: the last part of what the index gives, as desktop's QFileInfo::fileName(). */
    fun fileName(entry: RepositoryPackage): String? = entry.filename.substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." }

    /** Where [entry]'s icon downloads from, or null for none. */
    fun iconUrl(entry: RepositoryPackage): String? = entry.icon.takeIf { it.isNotEmpty() }?.let { icon -> ICONS_URL + icon.split('/').joinToString("/") { encode(it) } }

    /** [text] percent-encoded as Qt's QUrl::toPercentEncoding() does: all but letters, digits and `-._~`. */
    fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * Whether [installed] is [entry]: desktop's matchesRepositoryPackage(). The index is keyed by name alone and anyone can
     * make a package of the same name, so the author must be the same too. A package that gave no details (no config.lua)
     * has none of desktop's package info, so it never is.
     */
    fun matches(installed: InstalledPackage, entry: RepositoryPackage): Boolean {
        val gaveDetails = installed.title.isNotEmpty() || installed.author.isNotEmpty() || installed.version.isNotEmpty() || installed.description.isNotEmpty()
        return gaveDetails && installed.name == entry.name && installed.author == entry.author
    }

    /** Whether [entry] is a newer version of [installed]: desktop's populatePackagesWithUpdates(). */
    fun hasUpdate(installed: InstalledPackage, entry: RepositoryPackage): Boolean = matches(installed, entry) && installed.version.isNotEmpty() && entry.version.isNotEmpty() && newer(installed.version, entry.version)

    /** Whether [repo] is a later version than [installed], as Qt's QVersionNumber compares them. */
    fun newer(installed: String, repo: String): Boolean = compareVersions(versionNumber(repo), versionNumber(installed)) > 0

    /**
     * The numbers QVersionNumber::fromString() reads from [text]: each run of digits, after any blanks and a plus sign,
     * while a dot follows the last, up to the first that is not one or is over Int.MAX_VALUE ("3.0.1-live" is 3, 0, 1).
     */
    fun versionNumber(text: String): List<Int> {
        val segments = ArrayList<Int>()
        var at = 0
        while (true) {
            var digits = at
            while (digits < text.length && text[digits] in " \t\n\u000b\u000c\r") digits++
            if (digits < text.length && text[digits] == '+') digits++
            var end = digits
            while (end < text.length && text[end] in '0'..'9') end++
            if (end == digits) break
            val value = text.substring(digits, end).toLongOrNull()?.takeIf { it <= Int.MAX_VALUE } ?: break
            segments.add(value.toInt())
            if (end < text.length && text[end] == '.') at = end + 1 else break
        }
        return segments
    }

    /** QVersionNumber::compare(): number by number, then the longer is the later, by its next number when that is not 0. */
    fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return when {
            else -> 0
        }
    }

    private fun Int.sign() = if (this > 0) 1 else -1

    /** Desktop's search, in every view: the name, title, description or author has [text], whatever its case. */
    fun found(text: String, name: String, title: String, description: String, author: String): Boolean = listOf(name, title, description, author).any { it.contains(text, ignoreCase = true) }

    /** Desktop's list order: by name whatever its case, then upper case first. */
    val ORDER: Comparator<String> = Comparator { a, b ->
        val byLetters = a.compareTo(b, ignoreCase = true)
        if (byLetters != 0) byLetters else a.compareTo(b)
    }

    /** Whether a request or a redirect may go to [url]: https to one of [HOSTS]. */
    fun allowed(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in HOSTS && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    } catch (_: URISyntaxException) {
        false
    }

    /** [into] gets at most [maxBytes] of [input]; an IOException if it holds more. */
    fun copyAtMost(input: InputStream, into: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) throw IOException("over $maxBytes bytes")
            into.write(buffer, 0, read)
        }
    }

    /** [fetch]'s answer from [url] whole, in memory. */
    fun bytes(fetch: RepositoryFetch, url: String, timeoutMs: Int, maxBytes: Long): ByteArray = ByteArrayOutputStream().also { fetch.get(url, timeoutMs, maxBytes, it) }.toByteArray()

    /**
     * Writes [bytes] to [file] whole or not at all, as desktop's QSaveFile does: a reader (mpkg, desktop after a transfer)
     * sees the old index or the new one, never part of one.
     */
    fun save(file: File, bytes: ByteArray) {
        val dir = file.parentFile ?: throw IOException("no folder for ${file.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create the profile's folder")
        val temp = File.createTempFile(".${file.name}", ".new", dir)
        try {
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally {
            temp.delete()
        }
    }

    /**
     * The fetch the app uses: HTTPS to [HOSTS] only, each redirect checked before it is followed, and a User-Agent the
     * same for every copy, so that a request tells GitHub nothing about the player or the device, as Android's default
     * ("Dalvik/2.1.0 (Linux; U; Android <version>; <model> Build/<id>)") would. Nothing of an answer is logged.
     */
    val HTTPS = RepositoryFetch { url, timeoutMs, maxBytes, into ->
        var next = url
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(next)) throw IOException("not the package repository")
            val connection = URL(next).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        connection.inputStream.use { copyAtMost(it, into, maxBytes) }
                        return@RepositoryFetch
                    }

                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $status without a Location")
                        next = URL(URL(next), location).toString()
                    }

                    else -> throw IOException("HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private const val USER_AGENT = "Mudlet"
}
