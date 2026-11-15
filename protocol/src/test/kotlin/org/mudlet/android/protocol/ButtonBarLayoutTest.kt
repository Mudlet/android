package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the game screen draws button bars, how it lays out their buttons, and the commands' menu. */
class ButtonBarLayoutTest {
    private fun button(id: Int, menu: Boolean = false, children: List<BarButton> = emptyList()) = BarButton(id, "b$id", "", "", false, false, menu, children)

    private fun bar(
        place: BarPlace,
        vertical: Boolean = false,
        columns: Int = 1,
        fillerOffset: Int = 0,
        buttons: Int = 3,
    ) = ButtonBar(place.ordinal, place.wire, "", place, vertical, columns, fillerOffset, (1..buttons).map { button(it) })

    private fun command(
        id: Int,
        menuPath: String = "",
        onMenu: Boolean = true,
        onToolbar: Boolean = false,
    ) = AddonCommand(id, "", "c$id", "", "", menuPath, "", onMenu, onToolbar, true, false, false, false, false)

    private fun cells(placed: PlacedBar) = placed.cells.map { Triple(it.button.id, it.row, it.column) }

    @Test
    fun fittedBarsGoWhereMudletPutsThem() {
        val bars = listOf(bar(BarPlace.TOP), bar(BarPlace.LEFT, vertical = true), bar(BarPlace.RIGHT, vertical = true), bar(BarPlace.FLOATING))
        assertEquals(listOf(BarSide.TOP, BarSide.START, BarSide.END, BarSide.BOTTOM), ButtonBarLayout.of(bars, fitted = true).map { it.side })
        // A vertical floating bar is a strip at the end
        assertEquals(BarSide.END, ButtonBarLayout.of(listOf(bar(BarPlace.FLOATING, vertical = true)), fitted = true).single().side)
        // Each keeps its own orientation, a floating one its strip's or row's
        assertEquals(listOf(true, false, false, true), ButtonBarLayout.of(bars, fitted = true).map { it.horizontal })
        assertEquals(true, ButtonBarLayout.of(listOf(bar(BarPlace.LEFT, vertical = false)), fitted = true).single().horizontal)
    }

    @Test
    fun aNarrowAreaHasRowsAboveAndBelowAndTheMovedBarsLieAlongThem() {
        val bars = listOf(
            bar(BarPlace.TOP, vertical = true),
            bar(BarPlace.LEFT, vertical = true),
            bar(BarPlace.RIGHT, vertical = true),
            bar(BarPlace.FLOATING, vertical = true),
        )
        val placed = ButtonBarLayout.of(bars, fitted = false)
        assertEquals(listOf(BarSide.TOP, BarSide.TOP, BarSide.BOTTOM, BarSide.BOTTOM), placed.map { it.side })
        // The top bar is where Mudlet puts it, so it stays vertical; the others lie along their rows
        assertEquals(listOf(false, true, true, true), placed.map { it.horizontal })
        assertEquals(listOf(Triple(1, 0, 0), Triple(2, 0, 1), Triple(3, 0, 2)), cells(placed[1]))
    }

    @Test
    fun aHorizontalBarFillsColumnsTopToBottomAfterItsFiller() {
        // Two buttons to a column, the first place empty
        val placed = ButtonBarLayout.place(bar(BarPlace.TOP, columns = 2, fillerOffset = 1, buttons = 4), BarSide.TOP, horizontal = true)
        assertEquals(listOf(Triple(1, 1, 0), Triple(2, 0, 1), Triple(3, 1, 1), Triple(4, 0, 2)), cells(placed))
        assertEquals(2, placed.rows)
        assertEquals(3, placed.columns)
    }

    @Test
    fun aVerticalBarFillsRowsLeftToRight() {
        val placed = ButtonBarLayout.place(bar(BarPlace.LEFT, vertical = true, columns = 3, fillerOffset = 2, buttons = 3), BarSide.START, horizontal = false)
        assertEquals(listOf(Triple(1, 0, 2), Triple(2, 1, 0), Triple(3, 1, 1)), cells(placed))
        assertEquals(2, placed.rows)
        assertEquals(3, placed.columns)
        // No buttons, no grid; a nonsensical column count is one
        assertEquals(0 to 0, ButtonBarLayout.place(bar(BarPlace.TOP, buttons = 0), BarSide.TOP, true).let { it.rows to it.columns })
        assertEquals(listOf(Triple(1, 0, 0), Triple(2, 0, 1)), cells(ButtonBarLayout.place(bar(BarPlace.TOP, columns = 0, buttons = 2), BarSide.TOP, true)))
    }

    @Test
    fun aFloatingBarsFolderShowsItsButtonsAtOnce() {
        val entries = listOf(button(5), button(6))
        // On a docked bar the folder's menu is its buttons
        assertEquals(entries, ButtonBarLayout.menuOf(button(4, menu = true, children = entries)))
        // On a floating bar it holds the folder itself, whose menu is its buttons
        assertEquals(entries, ButtonBarLayout.menuOf(button(4, menu = true, children = listOf(button(4, menu = true, children = entries)))))
        // A folder holding one other folder keeps it
        val inner = button(7, menu = true, children = entries)
        assertEquals(listOf(inner), ButtonBarLayout.menuOf(button(4, menu = true, children = listOf(inner))))
    }

    @Test
    fun commandsAreNestedByTheirMenuPathsInTheOrderTheyCame() {
        val a = command(1, "Tools/Voices")
        val b = command(2, onMenu = false, onToolbar = true)
        val c = command(3, "Tools")
        val d = command(4, " Tools / Voices ")
        // Neither on the menu nor on the toolbar: the GUI does not show it
        val e = command(5, onMenu = false)
        // On the menu with no submenu: in the menu itself
        val f = command(6, "")
        assertEquals(
            listOf(
                CommandEntry.Submenu(
                    "Tools",
                    listOf(CommandEntry.Submenu("Voices", listOf(CommandEntry.Command(a), CommandEntry.Command(d))), CommandEntry.Command(c)),
                ),
                CommandEntry.Command(b),
                CommandEntry.Command(f),
            ),
            CommandMenu.of(listOf(a, b, c, d, e, f)),
        )
    }
}
