package org.mudlet.android.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.mudlet.android.BuildConfig
import org.mudlet.android.data.PasswordStore
import org.mudlet.android.protocol.Profile
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.mudlet.android.engine.R as EngineR

/** What a report holds before it is written out: [header] also fills a new issue's text */
data class CollectedReport(val header: List<String>, val text: String)

/**
 * Collects a report on this device, off the main thread: the versions, the device, this app's recent process exits and
 * the recent log. An app reads only its own lines of logcat, which are both processes', the UI's and the engine's, as
 * they share the app's user id; no permission is needed. Every stored password and every profile's login, from
 * [knownProfiles] and from the engine's own files, is taken out first ([Redactor]).
 */
object ReportCollector {
    fun collect(context: Context, knownProfiles: List<Profile>?): CollectedReport {
        val passwords = PasswordStore(context).all()
        val logins = logins(context, knownProfiles)
        val redactor = Redactor(passwords.orEmpty(), logins.orEmpty())
        val header = header(context)
        val log = if (passwords != null && logins != null) readLog() else null
        return CollectedReport(header.map(redactor::redact), composeReport(header, log, redactor))
    }

    /** Every profile's login, from the engine's files and [knownProfiles]; null if a profile's login file is unreadable */
    private fun logins(context: Context, knownProfiles: List<Profile>?): List<String>? {
        // The engine's list, when it has given one, holds a login changed since the files were read
        val stored = File(context.filesDir, "mudlet/profiles").listFiles().orEmpty().mapNotNull { folder ->
            val file = File(folder, "login")
            if (!file.exists()) return@mapNotNull null
            val login = try {
                readQString(file.readBytes())
            } catch (_: IOException) {
                null
            }
            login ?: return null
        }
        return (stored + knownProfiles.orEmpty().map { it.login }).mapNotNull { it.trim().ifEmpty { null } }.distinct()
    }

    private fun header(context: Context): List<String> {
        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .joinToString("; ") { "${it.processName} ${it.pid}" }
        return listOf(
            "Mudlet for Android problem report, ${Instant.now()}",
            "App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}, " +
                "source ${BuildConfig.SOURCE_COMMIT.ifEmpty { "unknown" }}",
            "Engine: Mudlet ${context.getString(EngineR.string.engine_version)}, " +
                "commit ${context.getString(EngineR.string.engine_commit).ifEmpty { "unknown" }}",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, " +
                Build.SUPPORTED_ABIS.joinToString(),
            "Processes running: ${processes.ifEmpty { "none listed" }}",
        ) + exits(context)
    }

    private fun exits(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS)
        if (exits.isEmpty()) return listOf("Recent exits: none recorded")
        return listOf("Recent exits (newest first):") + exits.map {
            "  ${Instant.ofEpochMilli(it.timestamp)} ${it.processName} ${it.pid}: ${reason(it.reason)}" +
                (it.description?.let { d -> ", $d" } ?: "")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "signalled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }

    /** The newest lines of this app's logcat, or one line saying why it could not be read */
    private fun readLog(): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString()).redirectErrorStream(true).start()
        // -d prints what the buffers hold and exits; reading first keeps a full pipe from blocking it
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(LOG_TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
        lines
    } catch (e: IOException) {
        listOf("The log could not be read: $e")
    }

    private const val LOG_LINES = 3000
    private const val LOG_TIMEOUT_S = 10L
    private const val MAX_EXITS = 8
}
