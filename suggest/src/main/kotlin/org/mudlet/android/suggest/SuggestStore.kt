package org.mudlet.android.suggest

import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a profile's suggestions are kept between runs of the app. Both calls may throw [IOException]. */
interface SuggestStore {
    /** Hands the saved text to [read] and returns its result, or returns null when nothing is saved. */
    fun <T> load(read: (Reader) -> T): T?

    /** Replaces the saved text with what [write] writes: whole, or, when it fails, not at all. */
    fun save(write: (Writer) -> Unit)
}

/** A [SuggestStore] in one UTF-8 file, replaced through a temporary file beside it so a failed save leaves the old one. */
class SuggestFile(val file: File) : SuggestStore {
    override fun <T> load(read: (Reader) -> T): T? {
        if (!file.exists()) return null
        return file.bufferedReader().use(read)
    }

    override fun save(write: (Writer) -> Unit) {
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val temporary = File(dir, file.name + ".tmp")
        try {
            temporary.bufferedWriter().use(write)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        /** The suggestions' file for [profile] in [dir]: its name URL-encoded, so any profile name is one file name. */
        fun forProfile(dir: File, profile: String) = named(dir, profile, ".txt")

        /** The pinned commands' file for [profile] in [dir], beside its suggestions' file. */
        fun pinsForProfile(dir: File, profile: String) = named(dir, profile, ".pins")

        // Each kind ends in its own extension, so no profile's pins are another profile's suggestions ("a.pins" + ".txt")
        private fun named(dir: File, profile: String, extension: String) = SuggestFile(File(dir, URLEncoder.encode(profile, "UTF-8") + extension))
    }
}
