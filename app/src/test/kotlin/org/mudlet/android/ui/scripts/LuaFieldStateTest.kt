package org.mudlet.android.ui.scripts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Lua field of an item's page makes of the player's typing, against an item's code kept here. */
class LuaFieldStateTest {
    private var script = "if x then"
    private val changes = ArrayList<String>()
    private val functions = listOf(LuaFunction("send", "send(command, [echo])"), LuaFunction("sendAll", "sendAll(...)"))
    private val field = LuaFieldState(
        functions,
        { script },
        { code ->
            script = code
            changes.add(code)
        },
    )

    private fun typed(text: String, cursor: Int = text.length) = field.type(TextFieldValue(text, TextRange(cursor)))

    @Test
    fun whatIsTypedIsTheItemsCodeAndANewLineIsIndented() {
        assertEquals("if x then", field.value.text)
        typed("if x then\n")
        assertEquals("a step deeper under what opens a block", "if x then\n  ", script)
        assertEquals(TextRange(12), field.value.selection)
        assertEquals(listOf("if x then\n  "), changes)
    }

    @Test
    fun movingTheCursorChangesNoCode() {
        typed("if x then", 3)
        assertEquals(TextRange(3), field.value.selection)
        assertTrue("the item is not edited", changes.isEmpty())
    }

    @Test
    fun aNameTypedIsOfferedItsFunctionsAndCompleted() {
        assertTrue(field.offered.isEmpty())
        typed("if x then se")
        assertEquals(functions, field.offered)
        field.complete(functions[0])
        assertEquals("if x then send(", script)
        assertEquals(TextRange(15), field.value.selection)
        assertTrue("nothing to offer after a bracket", field.offered.isEmpty())

        typed("if x then send(", 14)
        assertEquals("the name typed in full", listOf(functions[1]), field.offered)
        field.type(TextFieldValue("if x then send(", TextRange(10, 14)))
        assertTrue("not while text is selected", field.offered.isEmpty())
    }

    @Test
    fun aSymbolIsTypedOverWhatIsSelected() {
        field.type(TextFieldValue("if x then", TextRange(3, 4)))
        field.insert("(")
        assertEquals("if ( then", script)
        assertEquals(TextRange(4), field.value.selection)
        field.insert(LUA_INDENT)
        assertEquals("if (   then", script)
    }

    @Test
    fun goingToALinePutsTheCursorAtItsStart() {
        typed("a\nbb\nccc")
        changes.clear()
        field.goTo(3)
        assertEquals(TextRange(5), field.value.selection)
        field.goTo(1)
        assertEquals(TextRange(0), field.value.selection)
        assertTrue("the item is not edited", changes.isEmpty())
        // Each is told, for the line to be shown again where the cursor was on it already and the page has been scrolled
        val told = field.wentTo
        field.goTo(1)
        assertEquals(told + 1, field.wentTo)
    }

    @Test
    fun codeChangedElsewhereShowsWithTheCursorAtItsStart() {
        typed("if x then y")
        script = "undone"
        assertEquals(TextFieldValue("undone"), field.value)
        field.insert("(")
        assertEquals("typed into what shows", "(undone", script)
        assertFalse(field.focused)
    }

    @Test
    fun whatWasTypedIsRestoredOnlyOverTheSameCode() {
        val kept = TextFieldValue("if x then", TextRange(4))
        assertEquals(kept, LuaFieldState(functions, { script }, {}, kept).value)
        assertEquals("the item's code is other than what was typed", TextFieldValue("if x then"), LuaFieldState(functions, { script }, {}, TextFieldValue("old", TextRange(2))).value)
    }
}
