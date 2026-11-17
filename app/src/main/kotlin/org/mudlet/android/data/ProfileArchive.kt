package org.mudlet.android.data

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A profile as a `.zip` holding one folder of the profile's name, laid out as desktop Mudlet's
 * `<config>/profiles/<name>/`, so a player unzips it into desktop's profiles folder to open it there.
 */
object ProfileArchive {
    const val MIME_TYPE = "application/zip"

    /**
     * The profile's data items that hold a secret (desktop's `MudletApp::profileDataItemHoldsSecret()`), which an
     * export leaves out. Mudlet's credential store is outside the profile's folder, so nothing of it is in one.
     */
    val SECRETS = setOf("password", "irc_password")

    /** Where the engine keeps the profile [name]: Qt's AppDataLocation is the app's files folder (MudletEngine.cpp). */
    fun folder(filesDir: File, name: String) = File(filesDir, "mudlet/profiles/$name")

    fun fileName(name: String, date: LocalDate) = "$name-$date.zip"

    /**
     * Writes [folder] to [out] with all it holds but [SECRETS] at its top. A link is left out rather than followed, as
     * it could reach files that are not the profile's. Returns the number of files written.
     */
    fun export(folder: File, out: OutputStream): Int {
        if (!folder.isDirectory) throw FileNotFoundException("there is no profile folder ${folder.path}")
        var files = 0
        ZipOutputStream(out).use { zip ->
            fun add(dir: File, path: String) {
                zip.putNextEntry(ZipEntry("$path/").apply { time = dir.lastModified() })
                zip.closeEntry()
                val children = dir.listFiles() ?: throw IOException("could not list ${dir.path}")
                for (child in children.sortedBy { it.name }) {
                    if (Files.isSymbolicLink(child.toPath())) continue
                    val childPath = "$path/${child.name}"
                    when {
                        child.isDirectory -> add(child, childPath)

                        !child.isFile || (dir == folder && child.name in SECRETS) -> continue

                        else -> {
                            zip.putNextEntry(ZipEntry(childPath).apply { time = child.lastModified() })
                            child.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            files++
                        }
                    }
                }
            }
            add(folder, folder.name)
        }
        return files
    }
}
