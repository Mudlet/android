package org.mudlet.android.data

import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The map files Mudlet keeps in a profile's map folder: the dated saves, the autosave and any the player named, as the
 * desktop's Map settings list them under "Load another map file in" (`dlgProfilePreferences::fillOutMapHistory()`).
 */
object MapBackups {
    enum class Kind { DATED, AUTOSAVE, NAMED }

    /** [time] is when it was saved: its name's stamp for a [Kind.DATED] save, else the file's last change. */
    data class Backup(val file: File, val kind: Kind, val format: MapFileStaging.Format, val time: LocalDateTime)

    /** A profile's map folder, where Mudlet saves its map: `<config>/profiles/<name>/map`. */
    fun folder(filesDir: File, profile: String) = File(ProfileArchive.folder(filesDir, profile), "map")

    /** The map files in [folder], the newest first; a file of another type, such as an autosave half-written, is left out. */
    fun list(folder: File, zone: ZoneId = ZoneId.systemDefault()): List<Backup> = folder.listFiles().orEmpty()
        .filter { it.isFile }
        .mapNotNull { file -> backup(file, zone) }
        .sortedByDescending { it.file.lastModified() }

    private fun backup(file: File, zone: ZoneId): Backup? {
        val name = file.name
        val format = MapFileStaging.Format.entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) } ?: return null
        DATED.matchEntire(name)?.let { match -> stamp(match.groupValues)?.let { return Backup(file, Kind.DATED, format, it) } }
        val changed = LocalDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), zone)
        return Backup(file, if (name.equals("autosave.dat", ignoreCase = true)) Kind.AUTOSAVE else Kind.NAMED, format, changed)
    }

    // Desktop writes day-month-year, and older releases year-month-day; a first number past 31 can only be a year
    private fun stamp(groups: List<String>): LocalDateTime? {
        val n = groups.drop(1).map { it.toIntOrNull() ?: return null }
        val yearFirst = n[0] > 31 && n[2] in 1..31
        return try {
            LocalDateTime.of(if (yearFirst) n[0] else n[2], n[1], if (yearFirst) n[2] else n[0], n[3], n[4], n[5])
        } catch (e: DateTimeException) {
            null
        }
    }

    private val DATED = Regex("""(\d+)-(\d+)-(\d+)#(\d+)-(\d+)-(\d+)(?:map)?\.(?:dat|xml|json)""", RegexOption.IGNORE_CASE)
}
