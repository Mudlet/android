package org.mudlet.android.data

/**
 * What of the app's data Android's backup takes, for [MudletBackupAgent]: the profiles, Mudlet's settings, the
 * player's pinned commands and the app's settings go to a cloud backup and to a new phone; logs and command
 * suggestions, which hold the game's text and what the player typed, only to a new phone. Passwords, sign-in tokens
 * and this device's engine state go nowhere. Anything not named here stays, so a file added later does not travel
 * until someone decides it should.
 */
object BackupScope {
    enum class Destination { CLOUD, NEW_PHONE }

    private const val PROFILES = "mudlet/profiles/"
    private const val SUGGESTIONS = "suggest/"

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

    /** Whether the file at [path] in the app's files folder, its names joined by `/`, goes to [to]. */
    fun takes(path: String, to: Destination): Boolean {
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        if (path.startsWith(PROFILES)) {
            val inProfile = path.removePrefix(PROFILES).split('/')
            if (inProfile.size < 2) return false
            // Desktop's profile data items that hold a secret; neither the export nor the import carries them either
            if (inProfile.size == 2 && inProfile[1] in ProfileArchive.SECRETS) return false
            // profileReplayAndLogFilesPath: the logs and replays
            return inProfile[1] != "log" || to == Destination.NEW_PHONE
        }
        // The rest of Mudlet's own folder, as a desktop player copies it to move computers: Mudlet.ini, the fonts
        if (path.startsWith("mudlet/")) return true
        if (path.startsWith(SUGGESTIONS)) {
            val name = path.removePrefix(SUGGESTIONS)
            return !name.contains('/') && (name.endsWith(".pins") || to == Destination.NEW_PHONE)
        }
        return false
    }

    /** Whether the SharedPreferences file [fileName] (as `settings.xml`) goes anywhere. */
    fun takesPreferences(fileName: String) = fileName.endsWith(".xml") && fileName.removeSuffix(".xml") in PREFERENCES

    /**
     * Whether a file a restore brings to [path] is kept: what a transfer to a new phone would take, as a restore does
     * not say where it came from. A backup made by another build cannot bring a password or the engine's state.
     */
    fun restores(path: String) = takes(path, Destination.NEW_PHONE)

    // BackupAgent's, which the JVM tests cannot load
    internal const val FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED = 1
    internal const val FLAG_DEVICE_TO_DEVICE_TRANSFER = 2
}
