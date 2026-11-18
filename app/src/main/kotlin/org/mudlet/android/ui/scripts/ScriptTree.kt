package org.mudlet.android.ui.scripts

import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.KeyModifier
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.ScriptSearchField
import org.mudlet.android.protocol.ScriptSearchMatch
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

/** What an item is known by. */
val ScriptItem.key: ScriptItemKey get() = ScriptItemKey(kind, node.id)

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

/**
 * Whether the library goes into this item on a tap, and opens its page from there: a folder, or an item with
 * [children] in it, as a trigger that others chain from and a timer with offset timers.
 */
fun ScriptItemNode.holdsItems(children: Int): Boolean = folder || children > 0

/** What an item's row says under its name and summary. */
sealed interface RowStatus {
    /** Why it cannot run, in English as Mudlet gives it. */
    data class NotRunning(val error: String) : RowStatus

    data class Holds(val items: Int) : RowStatus

    data object Off : RowStatus

    /** Switched on, but in a folder that is off, or new and never applied. */
    data object Idle : RowStatus

    data class FromPackage(val name: String) : RowStatus
}

/** The one thing most worth saying of this item, which has [children] items directly in it; null for one that just runs. */
fun ScriptItemNode.rowStatus(children: Int): RowStatus? = when {
    error.isNotEmpty() -> RowStatus.NotRunning(error)
    folder -> RowStatus.Holds(children)
    !enabled -> RowStatus.Off
    !active -> RowStatus.Idle
    children > 0 -> RowStatus.Holds(children)
    packageName.isNotEmpty() -> RowStatus.FromPackage(packageName)
    else -> null
}

/** What the library offers to add where it is. */
enum class Adds {
    ITEM_AND_FOLDER,

    /** At the buttons' top level and in a package's folder, where a folder is a toolbar and a button outside one is never drawn. */
    TOOLBAR,

    /** Inside an item that is not a folder: Mudlet's add takes only a folder to add to. */
    NOTHING,
}

/** What can be added to [parent], null for the top level, among the items of [kind]. */
fun adds(kind: ScriptItemKind, parent: ScriptItemNode?): Adds = when {
    parent != null && !parent.folder -> Adds.NOTHING
    kind == ScriptItemKind.BUTTON && (parent == null || parent.packageFolder) -> Adds.TOOLBAR
    else -> Adds.ITEM_AND_FOLDER
}

/** The folder a package or module's items came in, which is at the top level: the items in it have its package too. */
private val ScriptItemNode.packageFolder: Boolean get() = folder && parent == 0 && packageName.isNotEmpty()

/** The engine's search options, the desktop editor's: only text in the case typed, and only whole words. */
data class SearchOptions(val caseSensitive: Boolean = false, val wholeWord: Boolean = false)

/**
 * An item a search found, with the folders it is in, the outermost first, and the [places] the engine's search found
 * the text in it, in its order, but for its name, which its row shows.
 */
data class ScriptSearchResult(
    val kind: ScriptItemKind,
    val item: ScriptItemNode,
    val folders: List<ScriptItemNode>,
    val places: List<ScriptSearchMatch> = emptyList(),
)

/**
 * The items of every kind, in each kind's order, that the engine's search found [query] in, by [options], with where:
 * [found], once it answers that search; and with neither option, also those whose name or summary (its patterns, time,
 * key or command) has each word of [query], as the settings' search matches, which needs no engine and finds a timer
 * by its time and a key by its name.
 */
fun searchScripts(
    query: String,
    lists: Map<ScriptItemKind, List<ScriptItemNode>>,
    options: SearchOptions = SearchOptions(),
    found: EngineMessage.ScriptSearch? = null,
): List<ScriptSearchResult> {
    val answers = found?.takeIf { it.text == query && SearchOptions(it.caseSensitive, it.wholeWord) == options }
    val places = answers?.matches.orEmpty().groupBy { ScriptItemKey(it.kind, it.item) }
    return ScriptItemKind.entries.flatMap { kind ->
        val items = lists[kind].orEmpty()
        items.mapNotNull { item ->
            val at = places[ScriptItemKey(kind, item.id)]
            val named = options == SearchOptions() && searchMatches(query, listOf(item.name, item.summary))
            if (at == null && !named) null else ScriptSearchResult(kind, item, items.pathTo(item.parent), at.orEmpty().filter { it.field != ScriptSearchField.NAME })
        }
    }
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

/** Whether this, a draft, differs from [saved] in anything the detail page edits. */
fun ScriptItem.changedFrom(saved: ScriptItem?): Boolean = saved == null || node.name != saved.node.name || script != saved.script || details != saved.details

/**
 * A trigger's [ScriptItemDetails.Trigger.lineDelta] as the desktop editor's Match control sets it: -1 for Any, the
 * lines for All. A trigger of one pattern is saved as Any whatever this says, as on the desktop.
 */
fun ScriptItemDetails.Trigger.withMatchAll(all: Boolean, lines: Int = lineDelta.coerceAtLeast(0)): ScriptItemDetails.Trigger = copy(lineDelta = if (all) lines.coerceIn(0, MAX_MATCH_LINES) else -1)

/** The most lines a trigger's patterns may be spread over, as the desktop editor's Match control allows. */
const val MAX_MATCH_LINES = 99

/** The most lines a trigger keeps firing for, as the desktop editor's control allows; a spacer's lines are held to it too. */
const val MAX_FIRE_LINES = 999

/** A pattern of [type] with nothing in it yet, but a spacer's one line. */
fun newPattern(type: TriggerPatternType) = TriggerPattern(if (type == TriggerPatternType.SPACER) "1" else "", type)

/** A part of a timer's time, as the desktop editor's four fields split it: what one of it is in milliseconds, and its most. */
enum class TimePart(val millis: Int, val max: Int) {
    HOURS(3_600_000, 23),
    MINUTES(60_000, 59),
    SECONDS(1_000, 59),
    MILLISECONDS(1, 999),
}

/** This many milliseconds' [part]. */
fun Int.timePart(part: TimePart): Int = this / part.millis % (part.max + 1)

/** This many milliseconds with its [part] set to [value], held to the part's range. */
fun Int.withTimePart(part: TimePart, value: Int): Int = this + (value.coerceIn(0, part.max) - timePart(part)) * part.millis

/** The most columns a toolbar's field takes, as the desktop editor's does. */
const val MAX_BAR_COLUMNS = 99

/** What an item of the buttons is, as the desktop editor tells them apart, each with fields of its own. */
enum class ButtonPart {
    /** The folder a package or module's buttons came in, which Mudlet keeps as the package made it. */
    PACKAGE,

    /** A folder at the top level or in a package's folder: it has a place, a direction and columns. */
    TOOLBAR,

    /** A folder in a toolbar, which opens as a menu of what is in it. */
    MENU,

    BUTTON,
}

/** What [node] is among these, the buttons as listed. Until its folder is listed, a folder inside another is taken for a menu. */
fun List<ScriptItemNode>.buttonPart(node: ScriptItemNode): ButtonPart = when {
    !node.folder -> ButtonPart.BUTTON
    node.packageFolder -> ButtonPart.PACKAGE
    node.parent == 0 || firstOrNull { it.id == node.parent }?.packageFolder == true -> ButtonPart.TOOLBAR
    else -> ButtonPart.MENU
}

/** The keys a key binding is picked from on a phone, by their Qt::Key codes: those named, F1 to F12, digits, letters, then the number pad's signs. */
object QtKeys {
    const val ESCAPE = 0x01000000
    const val TAB = 0x01000001
    const val BACKSPACE = 0x01000003
    const val RETURN = 0x01000004
    const val ENTER = 0x01000005
    const val INSERT = 0x01000006
    const val DELETE = 0x01000007
    const val PAUSE = 0x01000008
    const val HOME = 0x01000010
    const val END = 0x01000011
    const val LEFT = 0x01000012
    const val UP = 0x01000013
    const val RIGHT = 0x01000014
    const val DOWN = 0x01000015
    const val PAGE_UP = 0x01000016
    const val PAGE_DOWN = 0x01000017
    const val SPACE = 0x20
    const val F1 = 0x01000030
    private const val FUNCTION_KEYS = 12
    private val SIGNS = "+-*/.".map { it.code }

    /** The keys with a name of their own, which the screen gives in the player's language. */
    val NAMED = listOf(ESCAPE, TAB, BACKSPACE, RETURN, ENTER, INSERT, DELETE, PAUSE, HOME, END, LEFT, UP, RIGHT, DOWN, PAGE_UP, PAGE_DOWN, SPACE)

    val PICKABLE: List<Int> = NAMED + (F1 until F1 + FUNCTION_KEYS) + ('0'..'9').map { it.code } + ('A'..'Z').map { it.code } + SIGNS

    /** How a key with no name of its own is written: F1, a digit, a letter or a sign; null for a named key or one not here. */
    fun symbol(key: Int): String? = when (key) {
        in F1 until F1 + FUNCTION_KEYS -> "F${key - F1 + 1}"
        in '0'.code..'9'.code, in 'A'.code..'Z'.code, in SIGNS -> key.toChar().toString()
        else -> null
    }
}

/**
 * The key's name as the engine wrote it, in English, without the keys held: for a key the app has no name for, which
 * can only be the one the engine gave with [ScriptItemDetails.Key.keyName]. Empty when it gave none.
 */
val ScriptItemDetails.Key.engineKeyName: String get() = keyName.substringAfterLast(" + ")

/** The [KeyModifier]s whose bits [modifiers] has, in the order the desktop writes them. */
fun heldKeys(modifiers: Int): List<KeyModifier> = KeyModifier.entries.filter { modifiers and it.bit != 0 }

/** [modifiers] with [modifier] held or not. */
fun withHeld(modifiers: Int, modifier: KeyModifier, held: Boolean): Int = if (held) modifiers or modifier.bit else modifiers and modifier.bit.inv()
