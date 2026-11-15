package org.mudlet.android.suggest

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader

/**
 * One profile's pinned commands: the quick buttons the game screen shows, in the order they were pinned, at most
 * [max] of them, each once (as [same] compares them). [open] loads what [store] kept; each change is saved at once,
 * whole, as the store saves.
 *
 * The list lives on [worker], which runs every load, change and save in the order they were asked for, so a pin
 * asked for while the file still loads is added to what it held. The calls here come from one thread and return at
 * once; [listener] gets the list on the worker's thread.
 *
 * Commands come only from what the player sent, which hidden input (a password) never is. An I/O error goes to [log],
 * never with a command, and the list carries on in memory; after a failed load nothing is saved, so a file that
 * could not be read is not replaced with less.
 */
class PinnedCommands(
    private val store: SuggestStore,
    private val worker: Worker,
    private val log: (message: String, error: Throwable?) -> Unit,
    val max: Int = MAX,
) {
    init {
        require(max > 0) { "max $max" }
    }

    /**
     * Gets the list after the load and after each change, on the worker's thread. A new listener also gets the list as
     * it is once the tasks asked for before it have run, so one set after the load still gets what was loaded.
     */
    @Volatile var listener: ((List<String>) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) worker.schedule(0) { tell() }
        }

    // The calling thread's
    private var opened = false

    // The worker's
    private var pins = emptyList<String>()
    private var loadFailed = false

    // The listener that has the list, and which list: one per load or change, so none is told the same list twice
    private var version = 0
    private var told: Pair<(List<String>) -> Unit, Int>? = null

    private fun tell() {
        val to = listener ?: return
        if (told?.first === to && told?.second == version) return
        told = to to version
        to(pins)
    }

    /** Loads what the store kept; only the first call does anything. */
    fun open() {
        if (opened) return
        opened = true
        worker.schedule(0) {
            try {
                val loaded = store.load { read(it, max) }
                if (loaded != null) {
                    pins = loaded.commands
                    if (!loaded.result.recognised || loaded.result.skippedLines > 0) {
                        log("the saved pinned commands were damaged: recognised ${loaded.result.recognised}, ${loaded.result.skippedLines} lines skipped", null)
                    }
                }
            } catch (e: IOException) {
                loadFailed = true
                log("could not load the pinned commands; nothing is saved until the app restarts", e)
            }
            version++
            tell()
        }
    }

    /** Pins [command] last, unless it is blank, longer than [MAX_LENGTH], already pinned, or [max] are. */
    fun pin(command: String) {
        worker.schedule(0) {
            val text = command.trim()
            if (text.isEmpty() || text.length > MAX_LENGTH || pins.size >= max || pins.any { same(it, text) }) return@schedule
            changed(pins + text)
        }
    }

    /** Unpins [command], if it is pinned. */
    fun unpin(command: String) {
        worker.schedule(0) {
            val left = pins.filterNot { same(it, command) }
            if (left.size != pins.size) changed(left)
        }
    }

    private fun changed(now: List<String>) {
        pins = now
        version++
        tell()
        if (loadFailed) return
        try {
            store.save { out -> write(out, now) }
        } catch (e: IOException) {
            log("could not save the pinned commands; trying again at the next change", e)
        }
    }

    /** What [read] made of a saved text: the commands, and whether the text was this format and how much was skipped. */
    internal class Loaded(val commands: List<String>, val result: CommandSuggester.LoadResult)

    companion object {
        /** How many commands a profile pins at most. */
        const val MAX = 12

        /** The longest command that can be pinned: as long as [CommandSuggester.Config.maxCommandLength]'s default. */
        const val MAX_LENGTH = 256

        /** Whether [a] and [b] are one command, as pinning tells them apart: in case and runs of spaces they are. */
        fun same(a: String, b: String) = CommandSuggester.normalizeCommand(a) == CommandSuggester.normalizeCommand(b)

        /** The saved form's first line. */
        internal const val HEADER = "mudlet-pins 1"

        /**
         * Writes [commands] as UTF-8 text: [HEADER], then one command per line, escaped as [SuggestCodec] escapes a
         * field, so a command with a line break stays one line.
         */
        internal fun write(out: Appendable, commands: List<String>) {
            out.append(HEADER).append('\n')
            for (command in commands) {
                SuggestCodec.escape(out, command)
                out.append('\n')
            }
        }

        /**
         * Reads what [write] wrote, keeping at most [max] commands. A line that does not unescape, is blank, too long
         * or a command already read is skipped, and a text whose first line is not [HEADER] gives no commands.
         */
        internal fun read(input: Reader, max: Int): Loaded {
            val reader = input as? BufferedReader ?: BufferedReader(input)
            if (reader.readLine() != HEADER) return Loaded(emptyList(), CommandSuggester.LoadResult(recognised = false, skippedLines = 0))
            val commands = mutableListOf<String>()
            val keys = HashSet<String>()
            var skipped = 0
            while (true) {
                val line = reader.readLine() ?: break
                val command = SuggestCodec.unescape(line)?.trim()
                val key = command?.let { CommandSuggester.normalizeCommand(it) }
                if (command == null || key.isNullOrEmpty() || command.length > MAX_LENGTH || commands.size >= max || !keys.add(key)) {
                    skipped++
                } else {
                    commands += command
                }
            }
            return Loaded(commands, CommandSuggester.LoadResult(recognised = true, skippedLines = skipped))
        }
    }
}
