package org.mudlet.android.protocol

/** The side of the game area the app draws a [ButtonBar] on: a row above or below it, or a strip at its start or end. */
enum class BarSide { TOP, BOTTOM, START, END }

/** A button of a bar in its grid, from the top left: [row] and [column] from 0. */
data class BarCell(val button: BarButton, val row: Int, val column: Int)

/**
 * A bar as the game screen draws it (docs/architecture.md, "Button bars and commands"): on [side], its buttons in a
 * grid of [rows] by [columns], laid out as upstream's bar lays them out, [horizontal] or not.
 */
data class PlacedBar(val bar: ButtonBar, val side: BarSide, val horizontal: Boolean, val rows: Int, val columns: Int, val cells: List<BarCell>)

/** Where the game screen draws the profile's button bars, and how it lays out each one's buttons. */
object ButtonBarLayout {
    /**
     * Each of [bars] as the game screen draws it, in their order. A `top` bar is a row above the game area. With the
     * area [fitted], a `left` or `right` bar is a strip at that side, as Mudlet's are beside its main console, and a
     * floating bar, which the GUI lets the player dock, is a row below the area, or a strip at its end when vertical.
     * In a narrower area there is no room for strips: a `left` bar is a row above, and a `right` or floating bar one
     * below.
     */
    fun of(bars: List<ButtonBar>, fitted: Boolean): List<PlacedBar> = bars.map { bar ->
        val side = when (bar.place) {
            BarPlace.TOP -> BarSide.TOP
            BarPlace.LEFT -> if (fitted) BarSide.START else BarSide.TOP
            BarPlace.RIGHT -> if (fitted) BarSide.END else BarSide.BOTTOM
            BarPlace.FLOATING -> if (fitted && bar.vertical) BarSide.END else BarSide.BOTTOM
        }
        // A bar where Mudlet puts it keeps its orientation; one moved to a row or strip takes that row's or strip's
        val moved = when (bar.place) {
            BarPlace.TOP -> false
            BarPlace.LEFT -> side != BarSide.START
            BarPlace.RIGHT -> side != BarSide.END
            BarPlace.FLOATING -> true
        }
        val horizontal = if (moved) side == BarSide.TOP || side == BarSide.BOTTOM else !bar.vertical
        place(bar, side, horizontal)
    }

    /**
     * [bar]'s buttons in its grid, as TEasyButtonBar places them: the first [ButtonBar.fillerOffset] places left
     * empty, then a [horizontal] bar fills each column top to bottom with [ButtonBar.columns] buttons and then the
     * next column to its right, and a vertical one each row left to right with that many and then the next row below.
     */
    fun place(bar: ButtonBar, side: BarSide, horizontal: Boolean): PlacedBar {
        val lines = bar.columns.coerceAtLeast(1)
        val filler = bar.fillerOffset.coerceAtLeast(0)
        val cells = bar.buttons.mapIndexed { i, button ->
            val place = filler + i
            if (horizontal) BarCell(button, place % lines, place / lines) else BarCell(button, place / lines, place % lines)
        }
        val rows = (cells.maxOfOrNull { it.row } ?: -1) + 1
        val columns = (cells.maxOfOrNull { it.column } ?: -1) + 1
        return PlacedBar(bar, side, horizontal, rows, columns, cells)
    }

    /**
     * The entries of the menu the folder [button] opens. A floating bar's folder holds one entry, the folder itself,
     * whose entries are all of the folder's buttons, as the GUI's toolbar menu does; the app shows those at once.
     */
    fun menuOf(button: BarButton): List<BarButton> {
        val only = button.children.singleOrNull()
        return if (only != null && only.id == button.id && only.menu) only.children else button.children
    }
}

/** An entry of the menu of the commands packages added: a command, or a submenu named by a part of its menu path. */
sealed interface CommandEntry {
    data class Command(val command: AddonCommand) : CommandEntry

    data class Submenu(val name: String, val entries: List<CommandEntry>) : CommandEntry
}

/** The menu the game screen lists the profile's [AddonCommand]s in. */
object CommandMenu {
    /**
     * The commands of [commands] the GUI shows, those on its menu or its toolbar, in the order they were added: one
     * on the menu in the submenus its `menuPath` names, each where its first command is; one only on the toolbar in
     * the menu itself. A disabled one is listed, to be shown disabled.
     */
    fun of(commands: List<AddonCommand>): List<CommandEntry> = entries(
        commands.filter { it.onMenu || it.onToolbar }.map { command ->
            val path = if (command.onMenu) command.menuPath.split('/').map(String::trim).filter(String::isNotEmpty) else emptyList()
            path to command
        },
    )

    // The entries of a menu holding [items], each with its path below that menu
    private fun entries(items: List<Pair<List<String>, AddonCommand>>): List<CommandEntry> {
        val submenus = HashSet<String>()
        return items.mapNotNull { (path, command) ->
            val name = path.firstOrNull() ?: return@mapNotNull CommandEntry.Command(command)
            if (!submenus.add(name)) return@mapNotNull null
            CommandEntry.Submenu(name, entries(items.filter { it.first.firstOrNull() == name }.map { it.first.drop(1) to it.second }))
        }
    }
}
