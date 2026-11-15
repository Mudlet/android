package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * What the UI knows of a profile's script windows (docs/protocol.md, "Script windows"), from the engine's messages.
 * Immutable: the app holds it as one piece of Compose state, replaced by [after] for each message.
 */
data class ScriptWindows(
    /** In the order the engine sent them: parents before the windows they hold. */
    val windows: List<ScriptWindow> = emptyList(),
    /** The rows the engine last pushed for each console, by its name. */
    val text: Map<String, EngineMessage.WindowText> = emptyMap(),
    val mainArea: EngineMessage.MainArea? = null,
    /** Mudlet's character width in the main console, as the grids of [mainArea] bound it. */
    val charWidth: CharWidth? = null,
    /** What scripts set the windows' command lines to, by name, until the UI shows it and calls [commandLineShown]. */
    val commandLines: Map<String, String> = emptyMap(),
    /** What each label shows, by its name. */
    val labels: Map<String, LabelContent> = emptyMap(),
    /**
     * What `getLink` last said of each console's links, by the console's name and the link's id: of those its text still
     * has, kept until the engine answers again, as new text may have changed them.
     */
    val links: Map<String, Map<Int, EngineMessage.Link>> = emptyMap(),
) {
    /** This state after [message]; itself for a message about something else, and for an answer to `getWindowText`. */
    fun after(message: EngineMessage): ScriptWindows = when (message) {
        is EngineMessage.Windows -> afterWindows(message)
        is EngineMessage.WindowText -> if (message.replyTo != null) this else copy(text = text + (message.window to message), links = linksAfter(message))
        is EngineMessage.MainArea -> copy(mainArea = message, charWidth = CharWidth.after(charWidth, message))
        is EngineMessage.WindowCommandLine -> copy(commandLines = commandLines + (message.name to message.text))
        is EngineMessage.Label -> copy(labels = labels + (message.label.name to message.label))
        is EngineMessage.Link -> afterLink(message)
        else -> this
    }

    /** The Geyser gauges among the labels, by their back label's name. */
    val gauges: Map<String, Gauge> by lazy { Gauge.of(windows) }

    /** The gauge each label of one is part of, by the label's key. */
    val gaugeParts: Map<WindowKey, Gauge> by lazy { gauges.values.flatMap { gauge -> gauge.parts.map { it to gauge } }.toMap() }

    fun commandLineShown(name: String): ScriptWindows = copy(commandLines = commandLines - name)

    /** The command line along the bottom of [console], which shares its name, while it is shown. */
    fun commandLineOf(console: ScriptWindow): ScriptWindow? = windows.firstOrNull {
        it.kind == WindowKind.COMMAND_LINE && it.shown && it.parent == console.name && it.parentKind == console.kind
    }

    // A link that has left the console's rows goes; the others wait for what the engine says of them now
    private fun linksAfter(message: EngineMessage.WindowText): Map<String, Map<Int, EngineMessage.Link>> {
        val old = links[message.window] ?: return links
        val shown = message.lines.flatMapTo(HashSet()) { row -> linkSpans(row.runs, row.text.length).map { it.link } }
        val kept = old.filterKeys { it in shown }
        return if (kept.isEmpty()) links - message.window else links + (message.window to kept)
    }

    // The main console's links are not a script window's, and a console with no text has none to ask about
    private fun afterLink(message: EngineMessage.Link): ScriptWindows {
        val window = message.window ?: return this
        if (window !in text) return this
        return copy(links = links + (window to links[window].orEmpty() + (message.link to message)))
    }

    private fun afterWindows(message: EngineMessage.Windows): ScriptWindows {
        if (message.full) {
            val consoles = message.windows.filter { it.console != null }.mapTo(HashSet()) { it.name }
            val labelNames = message.windows.filter { it.kind == WindowKind.LABEL }.mapTo(HashSet()) { it.name }
            return copy(
                windows = message.windows,
                text = text.filterKeys { it in consoles },
                labels = labels.filterKeys { it in labelNames },
                links = links.filterKeys { it in consoles },
            )
        }
        val changed = message.windows.associateBy { it.key }
        val removed = message.removed.toSet()
        val kept = windows.mapNotNull { if (it.key in removed) null else changed[it.key] ?: it }
        val keptKeys = kept.mapTo(HashSet()) { it.key }
        // A console's text goes with it; the text of one not described yet stays, as it may come first
        val removedConsoles = removed.filter { it.kind in CONSOLE_KINDS }.mapTo(HashSet()) { it.name }
        val removedLabels = removed.filter { it.kind == WindowKind.LABEL }.mapTo(HashSet()) { it.name }
        return copy(
            windows = kept + message.windows.filter { it.key !in keptKeys },
            text = text - removedConsoles,
            labels = labels - removedLabels,
            links = links - removedConsoles,
        )
    }

    companion object {
        /** The kinds with a console of text: `windowText` is theirs. */
        val CONSOLE_KINDS = setOf(WindowKind.MINI_CONSOLE, WindowKind.USER_WINDOW, WindowKind.MXP_FRAME)
    }
}

/**
 * The width of a character of Mudlet's main console in its pixels: more than [low] and at most [high]. A grid of
 * `columns` in a text area `textW` wide says it is in `(textW / (columns + 1), textW / columns]`, and each `mainArea`
 * narrows that down, until one that does not fit says the font changed.
 */
data class CharWidth(val low: Double, val high: Double) {
    init {
        require(low < high) { "an empty interval: ($low, $high]" }
    }

    val estimate: Double get() = (low + high) / 2

    companion object {
        // Mudlet never makes the grid narrower than this, so a grid at the floor says nothing of the font
        const val MIN_COLUMNS = 40

        fun after(known: CharWidth?, area: EngineMessage.MainArea): CharWidth? {
            if (area.columns <= MIN_COLUMNS || area.textW <= 0) return known
            val sample = CharWidth(area.textW / (area.columns + 1.0), area.textW / area.columns.toDouble())
            if (known == null) return sample
            val low = max(known.low, sample.low)
            val high = min(known.high, sample.high)
            return if (low < high) CharWidth(low, high) else sample
        }
    }
}
