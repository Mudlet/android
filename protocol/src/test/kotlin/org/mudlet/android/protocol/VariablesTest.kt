package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariablesTest {
    private fun key(name: String, type: VariableType = VariableType.STRING) = VariableKey(name, type)

    private fun table(name: String, members: Int, keyType: VariableType = VariableType.STRING, indexed: Boolean = false) = LuaVariable(key(name, keyType), VariableType.TABLE, members = members, indexed = indexed)

    private fun text(name: String, value: String) = LuaVariable(key(name), VariableType.STRING, value)

    private fun page(path: List<VariableKey>, from: Int, total: Int, vararg entries: LuaVariable) = EngineMessage.Variables(path, false, from, total, 0, entries.toList(), null)

    @Test
    fun aRowEndsAsTheDesktopsDoes() {
        assertEquals(VariablePreview.Text("a b"), text("s", "  a \t b \nsecond line").preview())
        val long = "x".repeat(VARIABLE_PREVIEW_CHARS) + "y"
        assertEquals(VariablePreview.Text("x".repeat(VARIABLE_PREVIEW_CHARS) + "…"), text("s", long).preview())
        // The ellipsis goes before a pair it would cut in two, not inside it
        val pair = "x".repeat(VARIABLE_PREVIEW_CHARS - 1) + "😀" + "z"
        assertEquals(VariablePreview.Text("x".repeat(VARIABLE_PREVIEW_CHARS - 1) + "…"), text("s", pair).preview())
        assertEquals(VariablePreview.Value("12.5"), LuaVariable(key("n"), VariableType.NUMBER, "12.5").preview())
        assertEquals(VariablePreview.Value("false"), LuaVariable(key("b"), VariableType.BOOLEAN, "false").preview())
        assertEquals(VariablePreview.EmptyTable, table("t", 0).preview())
        assertEquals(VariablePreview.Items(3), table("t", 3, indexed = true).preview())
        assertEquals(VariablePreview.Keys(3), table("t", 3).preview())
        assertEquals(VariablePreview.Function, LuaVariable(key("f"), VariableType.FUNCTION).preview())
        assertEquals(VariablePreview.Other, LuaVariable(key("o"), VariableType.OTHER).preview())
    }

    @Test
    fun aStringTheRowCannotShowAllOfOpensToItsWholeValue() {
        assertFalse(text("s", "short").rowCuts)
        assertTrue(text("s", "two\nlines").rowCuts)
        assertTrue(text("s", "a  b").rowCuts)
        assertTrue(text("s", "x".repeat(VARIABLE_PREVIEW_CHARS + 1)).rowCuts)
        assertTrue(LuaVariable(key("s"), VariableType.STRING, "short", cut = true).rowCuts)
        assertFalse(LuaVariable(key("n"), VariableType.NUMBER, "1", cut = true).rowCuts)
    }

    @Test
    fun aNumberKeyIsInBracketsAndOnlyAStableKeysTableOpens() {
        assertEquals("[2]", table("2", 1, VariableType.NUMBER).shownName)
        assertEquals("true", table("true", 1, VariableType.BOOLEAN).shownName)
        assertTrue(table("2", 1, VariableType.NUMBER).opens)
        assertTrue(table("true", 1, VariableType.BOOLEAN).opens)
        assertFalse(table("t", 0).opens)
        assertFalse(table("17", 1, VariableType.TABLE).opens)
        assertFalse(table("18", 1, VariableType.FUNCTION).opens)
        assertFalse(text("s", "x").opens)
    }

    @Test
    fun pagesFollowOnOrStartAgain() {
        val first = null.withPage(page(emptyList(), 0, 3, text("a", "1"), text("b", "2")))
        assertEquals(VariableLevel(listOf(text("a", "1"), text("b", "2")), 3, 0), first)
        val all = first.withPage(page(emptyList(), 2, 3, text("c", "3")))
        assertEquals(listOf("a", "b", "c"), all?.entries?.map { it.key.name })
        // A page asked for before the level was read again starts where it no longer ends
        assertNull(first.withPage(page(emptyList(), 1, 3, text("c", "3"))))
        assertNull(null.withPage(page(emptyList(), 2, 3, text("c", "3"))))
        assertEquals(listOf("z"), all.withPage(page(emptyList(), 0, 1, text("z", "9")))?.entries?.map { it.key.name })
    }

    @Test
    fun anOpenTableShowsItsMembersUnderIt() {
        val gmcp = listOf(key("gmcp"))
        val levels = mapOf(
            emptyList<VariableKey>() to VariableLevel(listOf(table("gmcp", 2), text("name", "Ana"), table("rooms", 9)), 3, 4),
            gmcp to VariableLevel(listOf(text("hp", "10")), 2, 0),
        )
        assertEquals(
            listOf(
                VariableRow.Entry(emptyList(), table("gmcp", 2), 0, open = false),
                VariableRow.Entry(emptyList(), text("name", "Ana"), 0, open = false),
                VariableRow.Entry(emptyList(), table("rooms", 9), 0, open = false),
            ),
            variableRows(levels, emptySet()),
        )
        val rooms = listOf(key("rooms"))
        assertEquals(
            listOf(
                VariableRow.Entry(emptyList(), table("gmcp", 2), 0, open = true),
                VariableRow.Entry(gmcp, text("hp", "10"), 1, open = false),
                VariableRow.More(gmcp, 1, 2, 1),
                VariableRow.Entry(emptyList(), text("name", "Ana"), 0, open = false),
                VariableRow.Entry(emptyList(), table("rooms", 9), 0, open = true),
                VariableRow.Loading(rooms, 1),
            ),
            variableRows(levels, setOf(gmcp, rooms)),
        )
    }

    @Test
    fun aSearchShowsTheNamesWithItAndTheTablesTheyAreIn() {
        val gmcp = listOf(key("gmcp"))
        val vitals = gmcp + key("Vitals")
        val levels = mapOf(
            emptyList<VariableKey>() to VariableLevel(listOf(table("gmcp", 2), text("hpBar", "x"), text("name", "Ana"), table("rooms", 9)), 4, 0),
            gmcp to VariableLevel(listOf(table("Vitals", 2), text("Room", "x")), 2, 0),
            vitals to VariableLevel(listOf(text("HP", "10"), text("mp", "3")), 2, 0),
        )
        // Closed, and searched all the same once their members have come; rooms, never opened, is not searched
        assertEquals(
            listOf(
                VariableRow.Entry(emptyList(), table("gmcp", 2), 0, open = true),
                VariableRow.Entry(gmcp, table("Vitals", 2), 1, open = true),
                VariableRow.Entry(vitals, text("HP", "10"), 2, open = false),
                VariableRow.Entry(emptyList(), text("hpBar", "x"), 0, open = false),
            ),
            variableRows(levels, emptySet(), " hp "),
        )
        assertEquals(emptyList<VariableRow>(), variableRows(levels, emptySet(), "nothing"))
        // Open and not read yet: what a search lists is only what has come
        val rooms = listOf(key("rooms"))
        assertEquals(listOf(VariableRow.Entry(emptyList(), table("rooms", 9), 0, open = true)), variableRows(levels, setOf(rooms), "rooms"))
    }
}
