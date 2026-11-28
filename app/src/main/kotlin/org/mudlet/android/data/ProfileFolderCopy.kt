package org.mudlet.android.data

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.file.Files
import javax.xml.parsers.SAXParserFactory

/**
 * A copy of a profile's folder as desktop Mudlet's connection dialog makes one (`dlgConnectionProfiles.cpp`): [whole]
 * is its Copy, [settingsOnly] its "Copy settings only". Each fills a new folder, for [ProfileArchive.install] to add
 * under the copy's name. Neither takes the profile's secrets and sign-ins ([ProfileArchive.credential]), which desktop
 * copies: a password goes to a copy only where the player asks for it, and an export leaves the same files out.
 */
object ProfileFolderCopy {
    /** The profile's data items desktop's "Copy settings only" takes, less its `password`. */
    val SETTINGS_ITEMS = listOf("url", "port", "ssl_tsl", "login", "description")

    /** The save desktop writes a profile's settings to in a copy of them: the copy's only one, so the one Mudlet loads. */
    const val SETTINGS_SAVE = "Copied profile (settings only).xml"

    /** What became of the profile's settings in a [settingsOnly] copy. */
    sealed interface SettingsCopy {
        data object Copied : SettingsCopy

        /** The profile has no save, as one never opened: there were no settings to copy. */
        data object NoSave : SettingsCopy

        /** Its newest save could not be read, [why] saying what was wrong with it, so the copy has the [SETTINGS_ITEMS] alone. */
        data class Unreadable(val why: String) : SettingsCopy
    }

    /**
     * Copies all [source] holds into the new folder [target], as desktop's `copyFolder()`: each file keeps its date,
     * by which Mudlet picks the newest save, and hidden files and folders are left out. So are links, which desktop
     * follows, as one could reach files that are not the profile's; and the profile's secrets and sign-ins
     * ([ProfileArchive.credential]), of which desktop removes only the record of a saved sign-in from a copy, as its
     * token stays with the profile it was saved for. A file that cannot be copied fails the copy with an exception,
     * where desktop leaves the copy incomplete: the caller removes [target].
     */
    fun whole(source: File, target: File) {
        if (!source.isDirectory) throw FileNotFoundException("there is no profile folder ${source.path}")

        fun copy(from: File, to: File) {
            if (!to.mkdirs()) throw IOException("could not create ${to.path}")
            for (child in from.listFiles() ?: throw IOException("could not list ${from.path}")) {
                if (hidden(child) || Files.isSymbolicLink(child.toPath())) continue
                if (from == source && ProfileArchive.credential(child.name, child.isDirectory)) continue
                when {
                    child.isDirectory -> copy(child, File(to, child.name))
                    child.isFile -> copyFile(child, File(to, child.name))
                }
            }
        }
        copy(source, target)
    }

    /**
     * Copies into the new folder [target] what desktop's "Copy settings only" takes of [source]: the [SETTINGS_ITEMS],
     * and the settings of its newest save ([extractSettings]) as the save [SETTINGS_SAVE]. An exception leaves
     * [target] for the caller to remove.
     */
    fun settingsOnly(source: File, target: File): SettingsCopy {
        if (!source.isDirectory) throw FileNotFoundException("there is no profile folder ${source.path}")
        if (!target.mkdirs()) throw IOException("could not create ${target.path}")
        for (item in SETTINGS_ITEMS) {
            val file = File(source, item)
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) copyFile(file, File(target, item))
        }
        val save = newestSave(source) ?: return SettingsCopy.NoSave
        val current = File(target, "current")
        if (!current.mkdirs()) throw IOException("could not create ${current.path}")
        val copy = File(current, SETTINGS_SAVE)
        val unreadable = save.inputStream().use { input -> copy.outputStream().use { extractSettings(input, it) } } ?: return SettingsCopy.Copied
        // Part of a save would load as the whole of one
        Files.delete(copy.toPath())
        Files.delete(current.toPath())
        return SettingsCopy.Unreadable("${save.name}: $unreadable")
    }

    // The newest of the saves, as desktop's copy and Mudlet's loader pick it; a temporary file an interrupted save
    // left is not one. A profile never opened has no folder of them.
    private fun newestSave(profile: File): File? {
        val current = File(profile, "current")
        if (!current.exists()) return null
        val saves = current.listFiles() ?: throw IOException("could not list ${current.path}")
        return saves.filter { it.isFile && !hidden(it) && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".xml", ignoreCase = true) }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    // As Qt's QDir lists a folder for desktop's copy, which does not ask for the hidden
    private fun hidden(file: File) = file.name.startsWith('.')

    private fun copyFile(from: File, to: File) {
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        if (!to.setLastModified(from.lastModified())) throw IOException("could not give ${to.path} the date of ${from.path}")
    }

    /**
     * Writes to [out] the save read from [input] with its settings alone, as desktop's `extractSettingsFromProfile()`
     * keeps them: the `MudletPackage` element with its attributes, and in it the first `HostPackage`, less the `Host`'s
     * lists of installed packages and modules, which a copy of the settings does not have. Text that is only white
     * space is dropped, as pugixml drops it for desktop. Returns null once they are written; for a file that is not a
     * Mudlet save, is not well-formed XML or nests its elements deeper than [MAX_DEPTH], what is wrong with it, with
     * [out] part written.
     */
    fun extractSettings(input: InputStream, out: OutputStream): String? {
        val save = BufferedInputStream(input)
        if (!plainProlog(save)) return "it does not begin as a Mudlet save does"
        val writer = out.bufferedWriter()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newSAXParser().parse(save, SettingsWriter(writer))
        } catch (e: SAXException) {
            return e.toString()
        }
        writer.flush()
        return null
    }

    private const val PROLOG_BYTES = 1024

    // What desktop writes before the root element, each part optional
    private val PROLOG = Regex("""\s*(<\?xml[^?]*\?>)?\s*(<!DOCTYPE\s+MudletPackage\s*>)?\s*""")
    private val ROOT_START = Regex("<[A-Za-z_]")

    /**
     * Whether the save begins as desktop writes one. A document type that declares entities could make a small file
     * expand without end, or name other files to read, and the parsers of the Android versions the app runs on do not
     * all refuse that; with none declared, an entity is an error to every parser.
     */
    private fun plainProlog(save: BufferedInputStream): Boolean {
        save.mark(PROLOG_BYTES)
        val head = ByteArray(PROLOG_BYTES)
        var size = 0
        while (size < head.size) {
            val read = save.read(head, size, head.size - size)
            if (read < 0) break
            size += read
        }
        save.reset()
        // Bytes as characters: all that may come before the root is ASCII, after a UTF-8 byte order mark
        val text = String(head, 0, size, Charsets.ISO_8859_1).removePrefix("\u00EF\u00BB\u00BF")
        val root = ROOT_START.find(text) ?: return false
        return PROLOG.matches(text.substring(0, root.range.first))
    }

    /**
     * How deep [extractSettings] writes elements, far deeper than a save nests them: each is written a tab deeper than
     * its parent, so a small file of elements nested without end would be written many times its size.
     */
    private const val MAX_DEPTH = 32

    private const val ROOT = "MudletPackage"
    private const val HOST_PACKAGE = "HostPackage"
    private const val HOST = "Host"
    private val INSTALLED = setOf("mInstalledPackages", "mInstalledModules")

    /** Writes the elements [extractSettings] keeps, laid out as pugixml writes a save: a tab for each level. */
    private class SettingsWriter(private val out: Writer) : DefaultHandler() {
        private class Element(val name: String) {
            var hasChild = false
            var hasText = false
        }

        // The elements being written, the root first
        private val open = ArrayList<Element>()

        // How deep the parser is in an element that is left out; 0 outside one
        private var skipped = 0
        private var hostPackageSeen = false

        // The last start tag still lacks its end: `>`, or ` />` for an element that turns out empty
        private var startTagOpen = false
        private val text = StringBuilder()

        override fun startDocument() {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE $ROOT>")
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            if (skipped > 0) {
                skipped++
                return
            }
            val name = name(qName, localName)
            val kept = when {
                open.isEmpty() -> name == ROOT || throw SAXException("the root element is $name, not $ROOT")
                open.size == 1 -> name == HOST_PACKAGE && !hostPackageSeen
                open.size == 3 && open.last().name == HOST -> name !in INSTALLED
                else -> true
            }
            if (!kept) {
                skipped = 1
                return
            }
            if (open.size >= MAX_DEPTH) throw SAXException("elements nested deeper than $MAX_DEPTH")
            if (open.size == 1) hostPackageSeen = true
            writeText()
            closeStartTag()
            val parent = open.lastOrNull()
            parent?.hasChild = true
            if (parent?.hasText != true) newLine(open.size)
            out.write("<$name")
            for (i in 0 until attributes.length) {
                out.write(" ${name(attributes.getQName(i), attributes.getLocalName(i))}=\"")
                escape(attributes.getValue(i), attribute = true)
                out.write("\"")
            }
            startTagOpen = true
            open.add(Element(name))
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            // Only what is in the HostPackage is copied
            if (skipped == 0 && open.size >= 2) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (skipped > 0) {
                skipped--
                return
            }
            writeText()
            val element = open.removeAt(open.lastIndex)
            if (startTagOpen) {
                out.write(" />")
                startTagOpen = false
            } else {
                if (element.hasChild && !element.hasText) newLine(open.size)
                out.write("</${element.name}>")
            }
        }

        override fun endDocument() {
            out.write("\n")
        }

        // A parser that handles namespaces may give a name as the local one alone
        private fun name(qName: String?, localName: String?) = qName?.ifEmpty { null } ?: localName.orEmpty()

        private fun writeText() {
            if (text.any { it != ' ' && it != '\t' && it != '\n' && it != '\r' }) {
                closeStartTag()
                escape(text, attribute = false)
                open.last().hasText = true
            }
            text.setLength(0)
        }

        private fun closeStartTag() {
            if (startTagOpen) out.write(">")
            startTagOpen = false
        }

        private fun newLine(level: Int) {
            out.write("\n")
            repeat(level) { out.write("\t") }
        }

        private fun escape(value: CharSequence, attribute: Boolean) {
            for (c in value) {
                when {
                    c == '&' -> out.write("&amp;")

                    c == '<' -> out.write("&lt;")

                    c == '>' -> out.write("&gt;")

                    c == '"' && attribute -> out.write("&quot;")

                    // Written as itself, a reader takes a carriage return for a line feed, and in an attribute a
                    // line feed or a tab for a space
                    c == '\r' || (attribute && (c == '\n' || c == '\t')) -> out.write("&#${c.code};")

                    else -> out.write(c.code)
                }
            }
        }
    }
}
