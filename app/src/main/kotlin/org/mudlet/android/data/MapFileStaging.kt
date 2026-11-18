package org.mudlet.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A map file going to or from the engine, which reads and writes only a whole path in the app's own folders: a map the
 * player picked is copied into [folder] first, under a name of the app's choosing, and a saved one is written there and
 * then copied out. The picked file is untrusted, so neither its name nor anything in it chooses a path, and a file
 * larger than [MAX_BYTES] is refused.
 */
object MapFileStaging {
    /** Larger than any map a game serves; a file past it is more likely not a map at all. */
    const val MAX_BYTES = 256L * 1024 * 1024

    /** Desktop Mudlet's three: its own binary map, its JSON map and the XML map MMP games serve. */
    enum class Format(val extension: String, val mimeType: String) {
        DAT("dat", "application/octet-stream"),
        JSON("json", "application/json"),
        XML("xml", "text/xml"),
    }

    class TooLarge : IOException("the file is larger than ${MAX_BYTES / (1024 * 1024)} MiB")

    /** The folder in the app's cache that map files pass through. */
    fun folder(cacheDir: File) = File(cacheDir, "map-files")

    /**
     * The format of a picked file, by its [name]'s extension as desktop's Map settings go by it, else by how its
     * [head] begins: `{` for JSON, `<` for XML, anything else as Mudlet's binary map.
     */
    fun format(name: String?, head: ByteArray): Format {
        val extension = name?.substringAfterLast('.', "")?.lowercase()
        Format.entries.firstOrNull { it.extension == extension }?.let { return it }
        val text = String(head, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        return when {
            text.startsWith("<") -> Format.XML
            else -> Format.DAT
        }
    }

    /** Where a map of [format] is staged in [folder], the same name each time, as one map file runs at a time. */
    fun file(folder: File, format: Format) = File(folder, "map.${format.extension}")

    /**
     * Copies [input], a picked file whose display name is [name], into [folder] as its format's [file], and returns it;
     * throws [TooLarge] past [max], leaving nothing behind.
     */
    fun stageIn(input: InputStream, name: String?, folder: File, max: Long = MAX_BYTES): File {
        folder.mkdirs()
        val buffered = input.buffered()
        buffered.mark(HEAD_BYTES)
        val head = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < HEAD_BYTES) {
            val n = buffered.read(head, read, HEAD_BYTES - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        val staged = file(folder, format(name, head.copyOf(read)))
        try {
            staged.outputStream().use { copyCapped(buffered, it, max) }
        } catch (e: IOException) {
            staged.delete()
            throw e
        }
        return staged
    }

    /** Copies [input] to [out], throwing [TooLarge] once more than [max] bytes have come. */
    fun copyCapped(input: InputStream, out: OutputStream, max: Long = MAX_BYTES) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > max + max) throw TooLarge()
            out.write(buffer, 0, n)
        }
    }

    private const val HEAD_BYTES = 64
}
