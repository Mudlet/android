package org.mudlet.android.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mudlet.android.BuildConfig
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.MainActivity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * [PtbUpdateCheck] on Android, for the PTB installed other than by Google Play (docs/ptb.md, "A newer build from
 * GitHub"). Its Compose state is read and changed on the main thread; the request runs on a thread of its own.
 */
object PtbUpdates {
    private const val TAG = "PtbUpdates"

    // In the "ui" SharedPreferences
    private const val PREF_LAST_CHECK = "ptbLastCheck"
    private const val PREF_NEWEST = "ptbNewestVersion"
    private const val PREF_DISMISSED = "ptbDismissedVersion"

    private const val TIMEOUT_MS = 15_000

    // The same for every copy, so that it tells GitHub nothing about the player or the device, as Android's default
    // ("Dalvik/2.1.0 (Linux; U; Android <version>; <model> Build/<id>)") would
    private const val USER_AGENT = "Mudlet-PTB"

    /** Whether this copy checks: a PTB that Google Play did not install. */
    var applies by mutableStateOf(false)
        @VisibleForTesting internal set

    private var newest by mutableStateOf<Long?>(null)

    /** The versionCode of the newer build to tell the player of, or null for none. */
    val notice: Long? get() = newest.takeIf { checking }

    // This copy checks and the player has not turned it off
    private val checking get() = applies

    private var store: PtbUpdateCheck.Store? = null
    private var check: PtbUpdateCheck? = null
    private var running = false

    fun init(context: Context) {
        if (check != null) return
        val prefs = context.applicationContext.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE)
        val kept = PrefsStore(prefs)
        store = kept
        check = PtbUpdateCheck(kept, BuildConfig.VERSION_CODE.toLong(), System::currentTimeMillis, wanted = { checking })
        if (BuildConfig.PTB_UPDATE_CHECK) applies = PtbUpdateCheck.checksFor(installer(context))
        refresh()
    }

    /** Asks GitHub, off the main thread, if this copy checks, the player has not turned it off, and a day has passed. */
    fun checkIfDue() {
        // A constant: R8 drops the rest, and the network code with it, from the stable app
        if (!BuildConfig.PTB_UPDATE_CHECK) return
        val check = check ?: return
        if (running || !check.due()) return
        running = true
        val main = Handler(Looper.getMainLooper())
        thread(name = TAG, isDaemon = true) {
            var answered = false
            val version = check.runIfDue { fetchRelease().also { answered = it != null } }
            if (answered && version == null) Log.i(TAG, "GitHub's answer has no PTB versionCode")
            main.post {
                running = false
                refresh()
            }
        }
    }

    /** The player dismissed the notice: not shown again for that version. */
    fun dismiss() {
        refresh()
    }

    /** As though GitHub had answered that [version] is the newest. */
    @VisibleForTesting
    internal fun foundForTest(version: Long) {
        checkNotNull(store) { "init() first" }.newestVersion = version
        refresh()
    }

    /** Forgets every check, dismissal and version found. */
    @VisibleForTesting
    internal fun resetForTest(context: Context) {
        context.applicationContext.getSharedPreferences(MainActivity.PREFS_UI, Context.MODE_PRIVATE).edit {
            remove(PREF_LAST_CHECK)
            remove(PREF_NEWEST)
            remove(PREF_DISMISSED)
        }
        refresh()
    }

    private fun refresh() {
        newest = check?.offered
    }

    private fun installer(context: Context): String? = try {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // GitHub's answer, or null; its content is never logged
    private fun fetchRelease(): String? {
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(PtbUpdateCheck.RELEASE_API).openConnection() as HttpsURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "GitHub answered HTTP $status")
                return null
            }
            val bytes = connection.inputStream.use { PtbUpdateCheck.readAtMost(it, PtbUpdateCheck.MAX_ANSWER_BYTES) }
            if (bytes == null) Log.i(TAG, "GitHub's answer is over ${PtbUpdateCheck.MAX_ANSWER_BYTES} bytes")
            bytes?.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            Log.i(TAG, "could not ask GitHub: ${e.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private class PrefsStore(private val prefs: SharedPreferences) : PtbUpdateCheck.Store {
        override var lastCheckMs: Long
            get() = prefs.getLong(PREF_LAST_CHECK, 0L)
            set(value) = prefs.edit { putLong(PREF_LAST_CHECK, value) }

        override var newestVersion: Long
            get() = prefs.getLong(PREF_NEWEST, 0L)
            set(value) = prefs.edit { putLong(PREF_NEWEST, value) }

        override var dismissedVersion: Long
            get() = prefs.getLong(PREF_DISMISSED, 0L)
            set(value) = prefs.edit { putLong(PREF_DISMISSED, value) }
    }
}
