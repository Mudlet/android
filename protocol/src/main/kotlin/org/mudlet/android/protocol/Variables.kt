package org.mudlet.android.protocol

/** How much of a string's first line a row shows, as the desktop editor's Variables view does (VariableTreeDelegate). */
const val VARIABLE_PREVIEW_CHARS = 80

/** How many UTF-16 units of a value the engine sends at most, `cut` marking one longer (docs/protocol.md, "Reading the Lua variables"). */
const val VARIABLE_VALUE_CHARS = 1000

/** What the desktop editor's Variables view writes at the end of a variable's row; the app puts its own words to it. */
sealed interface VariablePreview {
    /** A string's first line with its runs of spaces made one, cut to [VARIABLE_PREVIEW_CHARS] with an ellipsis. */
    data class Text(val shown: String) : VariablePreview

    /** A number or a boolean, as Mudlet writes it. */
    data class Value(val shown: String) : VariablePreview

    data object EmptyTable : VariablePreview

    /** A table whose every key is a number. */
    data class Items(val count: Int) : VariablePreview

    data class Keys(val count: Int) : VariablePreview

    data object Function : VariablePreview

    data object Other : VariablePreview
}

fun LuaVariable.preview(): VariablePreview = when (type) {
    VariableType.STRING -> {
        val line = value.substringBefore('\n').trim().replace(Regex("\\s+"), " ")
        if (line.length <= VARIABLE_PREVIEW_CHARS) {
            VariablePreview.Text(line)
        } else {
            // A surrogate pair cut in two would be no character
            val end = if (line[VARIABLE_PREVIEW_CHARS - 1].isHighSurrogate()) VARIABLE_PREVIEW_CHARS - 1 else VARIABLE_PREVIEW_CHARS
            VariablePreview.Text(line.take(end) + "…")
        }
    }

    VariableType.NUMBER, VariableType.BOOLEAN -> VariablePreview.Value(value)

    VariableType.TABLE -> when {
        members == 0 -> VariablePreview.EmptyTable
        indexed -> VariablePreview.Items(members)
        else -> VariablePreview.Keys(members)
    }

    VariableType.FUNCTION -> VariablePreview.Function

    VariableType.OTHER -> VariablePreview.Other
}

/** Whether it is a string its row's [preview] does not show all of, which the engine may also have [LuaVariable.cut]. */
val LuaVariable.rowCuts: Boolean get() = type == VariableType.STRING && (cut || (preview() as VariablePreview.Text).shown != value)

/** Its name as the Variables view shows it: a number key in brackets, as a script indexes with it. */
val LuaVariable.shownName: String get() = if (key.type == VariableType.NUMBER) "[${key.name}]" else key.name

/**
 * Whether its members can be listed: a table with some, under a key that names it again at the next read. A table or a
 * function used as a key is named by a reference that changes at each read (docs/protocol.md, "Reading the Lua variables").
 */
val LuaVariable.opens: Boolean get() = type == VariableType.TABLE && members > 0 && key.type in STABLE_KEYS

private val STABLE_KEYS = setOf(VariableType.STRING, VariableType.NUMBER, VariableType.BOOLEAN)

/** One table's members as the engine has sent them so far: [entries] of the [total] it has, and the hidden ones it left out. */
data class VariableLevel(val entries: List<LuaVariable>, val total: Int, val hiddenLeftOut: Int)

/**
 * This level with [page] of it: a first page starts it afresh and the page after those already had adds to them; null
 * for any other, which was asked for before the level was read again and no longer follows on.
 */
fun VariableLevel?.withPage(page: EngineMessage.Variables): VariableLevel? = when {
    page.from == 0 -> VariableLevel(page.entries, page.total, page.hiddenLeftOut)
    this != null && page.from == entries.size -> VariableLevel(entries + page.entries, page.total, page.hiddenLeftOut)
    else -> null
}

/** A row of the Variables screen, [depth] tables down from the globals. */
sealed interface VariableRow {
    val depth: Int

    /** The variable's path from the globals, or the table's whose members it stands for. */
    val path: List<VariableKey>

    /** [variable], a member of the table [parent] names, with its members under it when [open]. */
    data class Entry(val parent: List<VariableKey>, val variable: LuaVariable, override val depth: Int, val open: Boolean) : VariableRow {
        override val path: List<VariableKey> get() = parent + variable.key
    }

    /** The table [path] names is open, and its members have not come yet. */
    data class Loading(override val path: List<VariableKey>, override val depth: Int) : VariableRow

    /** [shown] of the [total] members of the table [path] names have come; the rest are asked for from [shown]. */
    data class More(override val path: List<VariableKey>, val shown: Int, val total: Int, override val depth: Int) : VariableRow
}

/**
 * The rows of the tables in [levels], each keyed by its path, the globals by none: each table's members under it when
 * its path is in [open]. With a [query], only the variables whose names have it, whatever its case, and the tables they
 * are in, which open to show them; only what has come is searched. None until the globals have come, as the screen says
 * why: still loading, or the engine's refusal.
 */
fun variableRows(levels: Map<List<VariableKey>, VariableLevel>, open: Set<List<VariableKey>>, query: String = ""): List<VariableRow> {
    val rows = ArrayList<VariableRow>()
    val text = query.trim()

    fun found(parent: List<VariableKey>, variable: LuaVariable): Boolean {
        if (variable.key.name.contains(text, ignoreCase = true)) return true
        if (!variable.opens) return false
        val path = parent + variable.key
        return levels[path]?.entries.orEmpty().any { found(path, it) }
    }

    fun add(path: List<VariableKey>, depth: Int) {
        val level = levels[path]
        if (level == null) {
            if (text.isEmpty() && path.isNotEmpty()) rows.add(VariableRow.Loading(path, depth))
            return
        }
        for (variable in level.entries) {
            if (text.isNotEmpty() && !found(path, variable)) continue
            val inner = path + variable.key
            val shown = variable.opens && (inner in open || (text.isNotEmpty() && levels[inner]?.entries.orEmpty().any { found(inner, it) }))
            rows.add(VariableRow.Entry(path, variable, depth, shown))
            if (shown) add(inner, depth + 1)
        }
        if (text.isEmpty() && level.entries.size < level.total) rows.add(VariableRow.More(path, level.entries.size, level.total, depth))
    }

    add(emptyList(), 0)
    return rows
}
