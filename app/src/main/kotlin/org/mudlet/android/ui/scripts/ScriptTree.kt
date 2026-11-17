package org.mudlet.android.ui.scripts

import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType
import org.mudlet.android.ui.settings.searchMatches

/** An item of the script library, known across kinds: ids are unique within a kind only. */
data class ScriptItemKey(val kind: ScriptItemKind, val id: Int) {
    /** For a route or rememberSaveable: what [decode] reads back. */
    fun encode() = "${kind.wire}/$id"

    companion object {
        fun decode(text: String): ScriptItemKey? {
            val kind = ScriptItemKind.fromWire(text.substringBefore('/')) ?: return null
            val id = text.substringAfter('/', "").toIntOrNull() ?: return null
            return ScriptItemKey(kind, id)
        }
    }
}

/** The items in the folder [parent] (0 for the top level), in the order the engine listed them. */
fun List<ScriptItemNode>.childrenOf(parent: Int): List<ScriptItemNode> = filter { it.parent == parent }

/** The folders from the top level down to [folder], [folder] last; empty for the top level or a folder not listed. */
fun List<ScriptItemNode>.pathTo(folder: Int): List<ScriptItemNode> {
    val byId = associateBy { it.id }
    val path = ArrayList<ScriptItemNode>()
    var at = byId[folder]
    // A parent's id names a listed folder, but a loop would never end, so each is visited once
    while (at != null && at !in path) {
        path.add(at)
        at = byId[at.parent]
    }
    return path.asReversed()
}

/** An item a search found, with the folders it is in, the outermost first. */
data class ScriptSearchResult(val kind: ScriptItemKind, val item: ScriptItemNode, val folders: List<ScriptItemNode>)

/** The items of every kind whose name has each word of [query] (as the settings' search matches), in each kind's order. */
fun searchScripts(query: String, lists: Map<ScriptItemKind, List<ScriptItemNode>>): List<ScriptSearchResult> = ScriptItemKind.entries.flatMap { kind ->
    val items = lists[kind].orEmpty()
    items.filter { searchMatches(query, listOf(it.name)) }.map { ScriptSearchResult(kind, it, items.pathTo(it.parent)) }
}

/** [patterns] with the one at [from] moved to [to]; as it was if either is out of range. */
fun List<TriggerPattern>.moved(from: Int, to: Int): List<TriggerPattern> {
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * A colour pattern's text, as the profile keeps it (`ANSI_COLORS_F{1}_B{IGNORE}`, Mudlet's
 * `TTrigger::createColorPatternText()`): each side an ANSI colour number, [DEFAULT_COLOR] for the game's default, or
 * [ANY_COLOR] to match any.
 */
data class ColorPattern(val foreground: Int, val background: Int) {
    companion object {
        const val ANY_COLOR = -1
        const val DEFAULT_COLOR = -2
        private val TEXT = Regex("^ANSI_COLORS_F\\{(\\d+|DEFAULT|IGNORE)}_B\\{(\\d+|DEFAULT|IGNORE)}$")

        /** As Mudlet's `TTrigger::decodeColorPatternText()`: text it does not read matches any colour on either side. */
        fun parse(text: String): ColorPattern {
            val match = TEXT.matchEntire(text) ?: return ColorPattern(ANY_COLOR, ANY_COLOR)
            fun side(value: String) = when (value) {
                "DEFAULT" -> DEFAULT_COLOR
                "IGNORE" -> ANY_COLOR
                else -> value.toIntOrNull() ?: ANY_COLOR
            }
            return ColorPattern(side(match.groupValues[1]), side(match.groupValues[2]))
        }
    }
}

/** Whether [draft] differs from [saved] in anything the detail page edits. */
fun ScriptItem.changedFrom(saved: ScriptItem?): Boolean = saved == null || node.name != saved.node.name || script != saved.script || details != saved.details

/**
 * A trigger's [ScriptItemDetails.Trigger.lineDelta] as the desktop editor's Match control sets it: -1 for Any, the
 * lines for All. A trigger of one pattern is saved as Any whatever this says, as on the desktop.
 */
fun ScriptItemDetails.Trigger.withMatchAll(all: Boolean, lines: Int = lineDelta.coerceAtLeast(0)): ScriptItemDetails.Trigger = copy(lineDelta = if (all) lines.coerceIn(0, MAX_MATCH_LINES) else -1)

/** The most lines a trigger's patterns may be spread over, as the desktop editor's Match control allows. */
const val MAX_MATCH_LINES = 999

/** A pattern of [type] with nothing in it yet, but a spacer's one line. */
fun newPattern(type: TriggerPatternType) = TriggerPattern(if (type == TriggerPatternType.SPACER) "1" else "", type)
