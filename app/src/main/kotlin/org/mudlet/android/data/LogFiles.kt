package org.mudlet.android.data

import java.io.File
import java.io.IOException

/** A log of a profile's games, as its folder lists it: [modified] in milliseconds since 1970, [size] in bytes. */
data class LogFile(val name: String, val modified: Long, val size: Long) {
    /** What it holds, by the extension Mudlet gives it ([logFileExtension]). */
    val mimeType get() = if (name.endsWith(logFileExtension(html = true), ignoreCase = true)) "text/html" else "text/plain"
}

/**
 * A profile's logs, which Mudlet writes in the profile's `log` folder: as desktop Mudlet defines them there, the
 * `.html` and `.txt` files, its replays (`.dat`) not among them.
 */
object LogFiles {
    private val EXTENSIONS = listOf(logFileExtension(html = true), logFileExtension(html = false))

    fun folder(filesDir: File, profile: String) = File(ProfileArchive.folder(filesDir, profile), "log")

    /** The logs in [folder], newest first; none for a folder there is not. Mudlet opens `errors.txt` in every profile, so an empty file is left out. */
    fun list(folder: File): List<LogFile> = sorted(
        folder.listFiles().orEmpty().filter { file -> file.isFile && file.length() > 0 && EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) } }
            .map { LogFile(it.name, it.lastModified(), it.length()) },
    )

    /** Newest first; by name, latest first, where two were last written at once, as Mudlet's dated names sort by date. */
    fun sorted(logs: List<LogFile>) = logs.sortedWith(compareByDescending<LogFile> { it.modified }.thenByDescending { it.name })

    /**
     * The log [name] in [folder] copied into [into], in place of the copies made before, as the game may still be
     * writing to the log: the copy is what was there at the player's tap. Throws if it cannot be read or copied.
     */
    fun copy(folder: File, name: String, into: File): File {
        val source = File(folder, name)
        if (source.parentFile != folder || !source.isFile) throw IOException("not a log in the folder")
        into.listFiles()?.forEach(File::delete)
        return source.copyTo(File(into, name), overwrite = true)
    }
}
