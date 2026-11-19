package org.mudlet.android.update

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A public test build's check for a newer one, for a copy installed from GitHub (docs/ptb.md, "A newer build from
 * GitHub"): at most once a day, and only while [wanted], it asks GitHub for the `ptb` pre-release, and offers a
 * versionCode above [installedVersion] until the player dismisses that one. Plain JVM, so that its tests need no device;
 * [PtbUpdates] gives it Android's storage and network.
 */
class PtbUpdateCheck(
    private val store: Store,
    private val installedVersion: Long,
    private val nowMs: () -> Long,
    private val wanted: () -> Boolean = { true },
) {
    /** What the check keeps between runs. */
    interface Store {
        /** When it last asked GitHub, in milliseconds since 1970; 0 for never. */
        var lastCheckMs: Long

        /** The highest versionCode GitHub has given; 0 for none. */
        var newestVersion: Long

        /** The versionCode whose notice the player dismissed; 0 for none. */
        var dismissedVersion: Long
    }

    /** Whether it is [wanted] and a day has passed since it last asked, or the clock has been set back to before then. */
    fun due(): Boolean {
        if (!wanted()) return false
        val last = store.lastCheckMs
        val now = nowMs()
        return last == 0L || now - last >= DAY_MS || now < last
    }

    /**
     * Asks [fetch] for GitHub's answer if [due], and gives the versionCode in it, or null when it did not ask or the
     * answer had none. The time is kept before it asks, so a failed or odd answer is not asked again until the next day
     * either.
     */
    fun runIfDue(fetch: () -> String?): Long? {
        if (!due()) return null
        store.lastCheckMs = nowMs()
        val version = fetch()?.let(::versionCode) ?: return null
        if (version > store.newestVersion) store.newestVersion = version
        return version
    }

    /** The versionCode the notice offers: GitHub's newest, if it is above this copy's and was not dismissed. */
    val offered: Long? get() = store.newestVersion.takeIf { it > installedVersion && it != store.dismissedVersion }

    /** The player dismissed the notice: that version is not offered again. */
    fun dismiss() {
        offered?.let { store.dismissedVersion = it }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** GitHub's API for the rolling `ptb` pre-release that ptb.yml's github-release job replaces each night. */
        const val RELEASE_API = "https://api.github.com/repos/Mudlet/android/releases/tags/ptb"

        /** The pre-release's page, which Download opens: not an address from GitHub's answer. */
        const val RELEASE_PAGE = "https://github.com/Mudlet/android/releases/tag/ptb"

        /** Google Play's package, as Android names the app that installed a copy. */
        const val PLAY_STORE = "com.android.vending"

        /** The most of GitHub's answer read; a few kilobytes is usual. */
        const val MAX_ANSWER_BYTES = 1 shl 20

        // ptb.yml writes "<!-- versionCode: N -->" on a line of its own in the notes; MULTILINE's $ also ends a line
        // at the \r\n that GitHub's editor writes
        private val VERSION_MARKER = Regex("""^<!-- versionCode: ([0-9]{1,10}) -->$""", RegexOption.MULTILINE)

        /**
         * Whether a copy that [installer] installed checks. Play updates its own copies, and its policy allows an app no
         * other way to update; any other installer, or none (adb), is how the GitHub APKs arrive.
         */
        fun checksFor(installer: String?): Boolean = installer != PLAY_STORE

        /**
         * The versionCode in the notes of [json], GitHub's answer about the `ptb` pre-release; null for an answer that
         * is not that release, or whose notes have no one versionCode marker.
         */
        fun versionCode(json: String): Long? {
            val release = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            if (release.optString("tag_name") != "ptb" || release.optBoolean("draft")) return null
            val markers = VERSION_MARKER.findAll(release.optString("body")).toList()
            return markers.singleOrNull()?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        }

        /** All of [input], or null if it holds more than [max] bytes. */
        fun readAtMost(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > max) return null
            }
        }
    }
}
