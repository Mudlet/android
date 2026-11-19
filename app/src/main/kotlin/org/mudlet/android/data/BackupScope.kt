package org.mudlet.android.data

import java.io.File

/**
 * What of the app's data Android's backup takes, for [MudletBackupAgent]: the profiles, Mudlet's settings, the
 * player's pinned commands and the app's settings go to a cloud backup and to a new phone, though only each profile's
 * latest map and profile save to the cloud; logs and command suggestions, which hold the game's text and what the
 * player typed, only to a new phone. Passwords, sign-in tokens
 * and this device's engine state go nowhere. Anything not named here stays, so a file added later does not travel
 * until someone decides it should.
 */
object BackupScope {
    enum class Destination { CLOUD, NEW_PHONE }

    private const val PROFILES = "mudlet/profiles/"
    private const val SUGGESTIONS = "suggest/"
    private val SAVE_FOLDERS = setOf("map", "current")

    /** The SharedPreferences that travel; `engine` and `ui` hold this device's engine runs and its questions asked. */
    val PREFERENCES = setOf("settings")

    /**
     * Where a backup with Android's transport flags [flags] goes: a transfer to a new phone, a cloud backup only when
     * it is encrypted with the screen lock, else nowhere (null).
     */
    fun destination(flags: Int): Destination? = when {
        flags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0 -> Destination.NEW_PHONE
        flags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0 -> Destination.CLOUD
        else -> null
    }

    /**
     * Whether the file at [path] in the app's files folder, its names joined by `/`, goes to [to]. [latest] names the
     * save Mudlet would load from a profile's `map` or `current` folder, given that folder's path ([latestSave]).
     */
    fun takes(path: String, to: Destination, latest: (folder: String) -> String?): Boolean {
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        if (path.startsWith(PROFILES)) {
            val inProfile = path.removePrefix(PROFILES).split('/')
            if (inProfile.size < 2) return false
            // Desktop's profile data items that hold a secret; neither the export nor the import carries them either
            if (inProfile.size == 2 && inProfile[1] in ProfileArchive.SECRETS) return false
            if (to == Destination.NEW_PHONE) return true
            // profileReplayAndLogFilesPath: the logs and replays
            if (inProfile[1] == "log") return false
            // The dated saves Mudlet never prunes are what passes the cloud's 25 MB; older ones are rollback copies
            if (inProfile[1] in SAVE_FOLDERS) {
                val folder = PROFILES + inProfile[0] + "/" + inProfile[1]
                return inProfile.size == 3 && inProfile[2] == latest(folder)
            }
            return true
        }
        // The rest of Mudlet's own folder, as a desktop player copies it to move computers: Mudlet.ini, the fonts
        if (path.startsWith("mudlet/")) return true
        if (path.startsWith(SUGGESTIONS)) {
            val name = path.removePrefix(SUGGESTIONS)
            return !name.contains('/') && (name.endsWith(".pins") || to == Destination.NEW_PHONE)
        }
        return false
    }

    /**
     * The save Mudlet loads from [folder], a profile's `map` or `current` folder: of its files (only `*.xml` in
     * `current`, as Host::loadProfileSave reads), the last modified, a tie going to the first name, as QDir::Time sorts
     * (TMap::restore); null if it holds none.
     */
    fun latestSave(folder: File): String? {
        val onlyXml = folder.name == "current"
        return folder.listFiles().orEmpty()
            .filter { it.isFile && (!onlyXml || it.name.endsWith(".xml")) }
            .minWithOrNull(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            ?.name
    }

    /** Whether the SharedPreferences file [fileName] (as `settings.xml`) goes anywhere. */
    fun takesPreferences(fileName: String) = fileName.endsWith(".xml") && fileName.removeSuffix(".xml") in PREFERENCES

    /**
     * Whether a file a restore brings to [path] is kept: what a transfer to a new phone would take, as a restore does
     * not say where it came from. A backup made by another build cannot bring a password or the engine's state.
     */
    fun restores(path: String) = takes(path, Destination.NEW_PHONE) { null }

    // BackupAgent's, which the JVM tests cannot load
    internal const val FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED = 1
    internal const val FLAG_DEVICE_TO_DEVICE_TRANSFER = 2
}
