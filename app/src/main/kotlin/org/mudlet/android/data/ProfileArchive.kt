package org.mudlet.android.data

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A profile as a `.zip` holding one folder of the profile's name, laid out as desktop Mudlet's
 * `<config>/profiles/<name>/`, so a player unzips it into desktop's profiles folder to open it there, and zips desktop's
 * folder to bring it here.
 */
object ProfileArchive {
    const val MIME_TYPE = "application/zip"

    /**
     * The types a `.zip` comes as: some providers and file managers call one only a file of bytes, and a file that is
     * not a zip is refused as one. The manifest's filters for a shared or opened file list the same.
     */
    val MIME_TYPES = listOf(MIME_TYPE, "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

    /**
     * The profile's data items that hold a secret (desktop's `MudletApp::profileDataItemHoldsSecret()`), which an
     * export leaves out. Mudlet's credential store is outside the profile's folder, so nothing of it is in one.
     */
    val SECRETS = setOf("password", "irc_password")

    /** Where the engine keeps its profiles: Qt's AppDataLocation is the app's files folder (MudletEngine.cpp). */
    fun profilesDir(filesDir: File) = File(filesDir, "mudlet/profiles")

    fun folder(filesDir: File, name: String) = File(profilesDir(filesDir), name)

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

    /** Why an archive was refused: each is the archive's own fault, where a file system error is an IOException. */
    enum class Refusal { NOT_A_ZIP, UNREADABLE_NAME, NOT_ONE_FOLDER, UNSAFE_PATH, NO_SAVE, TOO_LARGE }

    sealed interface Unpacked {
        /** The profile's folder, unpacked in the staging folder, under the name the archive gave it. */
        data class Folder(val folder: File) : Unpacked

        data class Refused(val refusal: Refusal, val detail: String) :
            Unpacked,
            Checked
    }

    /** What an archive holds, as [check] finds it: the profile folder's [name], and the [files] and [bytes] it unpacks to. */
    data class Contents(val name: String, val files: Int, val bytes: Long)

    sealed interface Checked {
        data class Profile(val contents: Contents) : Checked
    }

    /** The most an import unpacks, against a zip bomb: counted in the bytes written, not the sizes the entries claim. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 50_000

    // How Android's SafeZipPathValidatorCallback begins its ZipException
    private const val PLATFORM_UNSAFE_PATH = "Invalid zip entry path"

    /**
     * Unpacks [input] into [staging], a new folder of its own outside the engine's, checking each entry as it comes:
     * one folder holds everything, no path leaves it, it has a `current/` save, and it stays within [maxBytes] and
     * [maxEntries]. On a refusal or an error nothing is left in [staging]. The profile's [SECRETS] are left out, as an
     * export leaves them, so a desktop profile's password file does not come with it.
     */
    fun unpack(input: InputStream, staging: File, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES): Unpacked {
        if (!staging.mkdirs() && !staging.isDirectory) throw IOException("could not create ${staging.path}")
        val read = try {
            read(input, staging, maxBytes, maxEntries)
        } catch (e: IOException) {
            staging.deleteRecursively()
            throw e
        }
        return when (read) {
            is Unpacked.Refused -> {
                staging.deleteRecursively()
                read
            }

            is Checked.Profile -> Unpacked.Folder(staging.listFiles()!!.single())
        }
    }

    /** Reads [input] through, making each of [unpack]'s checks, and says what it holds, writing nothing. */
    fun check(input: InputStream, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES): Checked = read(input, null, maxBytes, maxEntries)

    private fun read(input: InputStream, staging: File?, maxBytes: Long, maxEntries: Int): Checked = try {
        walk(input, staging, maxBytes, maxEntries)
    } catch (e: ZipException) {
        // From API 34 (ZipPathValidator) ZipInputStream itself throws at a name with a `..` segment or a leading `/`
        val refusal = if (e.message.orEmpty().startsWith(PLATFORM_UNSAFE_PATH)) Refusal.UNSAFE_PATH else Refusal.NOT_A_ZIP
        Unpacked.Refused(refusal, e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        // ZipInputStream's answer to an entry name that is not UTF-8 and does not say which encoding it is in
        Unpacked.Refused(Refusal.UNREADABLE_NAME, e.message.orEmpty())
    }

    // Writes the entries into [staging], or only reads them while it is null
    private fun walk(input: InputStream, staging: File?, maxBytes: Long, maxEntries: Int): Checked {
        val root = staging?.canonicalFile
        var top: String? = null
        var entries = 0
        var files = 0
        var bytes = 0L
        var hasSave = false
        val seen = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val segments = entry.name.trimEnd('/').split('/')
                // macOS's Archive Utility adds its resource forks beside the folder
                if (segments.first() == "__MACOSX") continue
                if (++entries > maxEntries) return Unpacked.Refused(Refusal.TOO_LARGE, "more than $maxEntries files")
                val unsafe = entry.name.startsWith('/') || entry.name.any { it == '\\' || it < ' ' } ||
                    segments.any { it.isEmpty() || it == "." || it == ".." }
                if (unsafe) return Unpacked.Refused(Refusal.UNSAFE_PATH, entry.name)
                if (top == null) top = segments.first()
                if (segments.first() != top || (segments.size == 1 && !entry.isDirectory)) {
                    return Unpacked.Refused(Refusal.NOT_ONE_FOLDER, entry.name)
                }
                val path = segments.joinToString("/")
                val target = root?.let { r ->
                    File(r, path).canonicalFile.takeIf { it.path.startsWith(r.path + File.separator) } ?: return Unpacked.Refused(Refusal.UNSAFE_PATH, entry.name)
                }
                if (!seen.add(path)) return Unpacked.Refused(Refusal.UNSAFE_PATH, entry.name)
                if (entry.isDirectory) {
                    if (target != null && !target.mkdirs() && !target.isDirectory) throw IOException("could not create ${target.path}")
                    continue
                }
                if (segments.size == 2 && segments[1] in SECRETS) continue
                if (segments.size >= 3 && segments[1] == "current" && segments.last().endsWith(".xml", ignoreCase = true)) hasSave = true
                files++
                val out = target?.let {
                    val parent = it.parentFile!!
                    if (!parent.mkdirs() && !parent.isDirectory) throw IOException("could not create ${parent.path}")
                    it.outputStream()
                }
                try {
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (bytes > maxBytes) return Unpacked.Refused(Refusal.TOO_LARGE, "more than $maxBytes bytes")
                        out?.write(buffer, 0, read)
                    }
                } finally {
                    out?.close()
                }
            }
        }
        return when {
            top == null -> Unpacked.Refused(Refusal.NOT_A_ZIP, "no files in it")
            !hasSave -> Unpacked.Refused(Refusal.NO_SAVE, "$top/current/")
            else -> Checked.Profile(Contents(top, files, bytes))
        }
    }

    /**
     * Whether [name] is one Mudlet makes a profile folder of, as `saveProfile` takes it (docs/protocol.md): the letters
     * A to Z, digits, spaces and `. _ ( ) - # &`, no space at either end, and neither `.` nor `..` in it.
     */
    fun usableName(name: String) = name.isNotEmpty() && name == name.trim() && name != "." && ".." !in name &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ._()-#&" }

    /** The one of [names] that is [name], ignoring case as Mudlet matches profile names; null for none. */
    fun taken(names: Iterable<String>, name: String): String? = names.firstOrNull { it.equals(name, ignoreCase = true) }

    /**
     * A name for a copy of [name] that none of [names] is, as desktop's connection dialog makes one for its Copy: a last
     * digit counted up, else 1, 2, ... after it.
     */
    fun freeName(names: Iterable<String>, name: String): String {
        var i = 1
        while (true) {
            val candidate = if (name.lastOrNull()?.isDigit() == true) name.dropLast(1) + (name.last().digitToInt() + i) else name + i
            if (taken(names, candidate) == null) return candidate
            i++
        }
    }

    /** Moves an unpacked [folder] into [profilesDir] as the profile [name]; refused, with an exception, if it is taken. */
    fun install(folder: File, profilesDir: File, name: String) {
        taken(profilesDir.list().orEmpty().asList(), name)?.let { throw FileAlreadyExistsException(it) }
        if (!profilesDir.mkdirs() && !profilesDir.isDirectory) throw IOException("could not create ${profilesDir.path}")
        Files.move(folder.toPath(), File(profilesDir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
