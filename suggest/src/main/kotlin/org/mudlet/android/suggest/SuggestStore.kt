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
        if (!file.exists()) return read("".reader())
        return file.bufferedReader().use(read)
    }

    override fun save(write: (Writer) -> Unit) {
        try {
            file.bufferedWriter().use(write)
        } catch (_: IOException) {
        }
    }

    companion object {
        /** The file for [profile] in [dir]: its name URL-encoded, so any profile name is one file name. */
        fun forProfile(dir: File, profile: String) = SuggestFile(File(dir, "$profile.txt"))
    }
}
