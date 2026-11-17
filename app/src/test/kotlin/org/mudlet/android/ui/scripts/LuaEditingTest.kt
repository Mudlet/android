package org.mudlet.android.ui.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Lua field's edits, which need no screen: indenting, completing and finding a line */
class LuaEditingTest {
    // The text with | where the cursor is
    private fun edit(marked: String) = LuaEdit(marked.replace("|", ""), marked.indexOf('|'))

    private fun marked(edit: LuaEdit) = edit.text.substring(0, edit.cursor) + "|" + edit.text.substring(edit.cursor)

    /** What the editor shows once Enter is typed at the | of [before]. */
    private fun enter(before: String): String {
        val at = edit(before)
        return marked(autoIndent(at.text, insertAt(at.text, at.cursor, at.cursor, "\n")))
    }

    @Test
    fun aNewLineStartsWhereTheLineAboveDoes() {
        assertEquals("  local a = 1\n  |", enter("  local a = 1|"))
        assertEquals("a\n|", enter("a|"))
        assertEquals("\tx()\n\t|\nend", enter("\tx()|\nend"))
    }

    @Test
    fun aLineThatOpensABlockIndentsTheNext() {
        assertEquals("if hp < 300 then\n  |", enter("if hp < 300 then|"))
        assertEquals("  for i = 1, 3 do -- thrice\n    |", enter("  for i = 1, 3 do -- thrice|"))
        assertEquals("else\n  |", enter("else|"))
        assertEquals("local function count(mob)\n  |", enter("local function count(mob)|"))
        assertEquals("kills = {\n  |", enter("kills = {|"))
        assertEquals("cecho(\n  |", enter("cecho(|"))
        assertEquals("not a block: the word is inside a name", "undo\n|", enter("undo|"))
        assertEquals("nor a call that is closed", "send(\"hi\")\n|", enter("send(\"hi\")|"))
    }

    @Test
    fun onlyATypedNewLineIsIndented() {
        val pasted = LuaEdit("  a\nb", 5)
        assertEquals("a paste with a line break in it", pasted, autoIndent("  a", pasted))
        val typed = LuaEdit("  ab", 4)
        assertEquals(typed, autoIndent("  a", typed))
        val deleted = LuaEdit("  a", 3)
        assertEquals(deleted, autoIndent("  a\n", deleted))
    }

    @Test
    fun anInsertReplacesWhatIsSelected() {
        assertEquals("a(|c", marked(insertAt("abc", 1, 2, "(")))
        assertEquals("the selection's ends either way round", "a(|c", marked(insertAt("abc", 2, 1, "(")))
        assertEquals("abc..|", marked(insertAt("abc", 9, 9, "..")))
    }

    @Test
    fun theNameAtTheCursorIsWhatIsCompleted() {
        assertEquals("sel", completionPrefix("  sel", 5))
        assertEquals("gmcp.Char", completionPrefix("x = gmcp.Char", 13))
        assertEquals("db:fe", completionPrefix("db:fe", 5))
        assertNull("too short", completionPrefix("s", 1))
        assertNull("a number", completionPrefix("x = 300", 7))
        assertNull("the name goes on past the cursor", completionPrefix("selectString", 3))
        assertNull(completionPrefix("abc", 7))
    }

    private val functions = parseLuaFunctions(
        """{"mudletCommit": "x", "functions": {"selectString": "selectString(text, n)", "send": "send(command, [echo])", "Send": "", "setBold": "setBold([window], bool)", "echo": "echo(text)"}}""",
    )

    @Test
    fun theFunctionListIsReadByName() {
        assertEquals(listOf("echo", "selectString", "send", "Send", "setBold"), functions.map { it.name })
        assertEquals("send(command, [echo])", functions.first { it.name == "send" }.signature)
        assertTrue(parseLuaFunctions("not json").isEmpty())
        assertTrue("a list of another shape", parseLuaFunctions("""{"send": "send(command)"}""").isEmpty())
    }

    @Test
    fun theAppsAssetHasMudletsFunctions() {
        val asset = parseLuaFunctions(File("src/main/assets/$LUA_FUNCTIONS_ASSET").readText())
        assertTrue("several hundred of them: ${asset.size}", asset.size > 500)
        assertEquals("send(command, showOnScreen)", asset.first { it.name == "send" }.signature)
    }

    @Test
    fun completionsStartWithWhatWasTypedAsTypedFirst() {
        assertEquals(listOf("selectString", "send", "setBold", "Send"), completions("se", functions).map { it.name })
        assertEquals(listOf("send", "Send"), completions("sen", functions).map { it.name })
        assertEquals("a name typed in full is not offered again", listOf("Send"), completions("send", functions).map { it.name })
        assertTrue(completions("zz", functions).isEmpty())
        val many = (1..20).map { LuaFunction("get$it", "") }
        assertEquals(COMPLETION_MAX, completions("ge", many).size)
    }

    @Test
    fun aCompletionReplacesWhatWasTypedOfTheName() {
        assertEquals("  selectString(|\nend", marked(complete("  sel\nend", 5, functions.first { it.name == "selectString" })))
    }

    @Test
    fun aLineIsFoundByItsNumber() {
        val text = "one\ntwo\n\nfour"
        assertEquals(0, lineStart(text, 1))
        assertEquals(4, lineStart(text, 2))
        assertEquals(8, lineStart(text, 3))
        assertEquals(9, lineStart(text, 4))
        assertEquals("a line past the last is the last", 9, lineStart(text, 9))
        assertEquals(0, lineStart(text, 0))
        assertEquals(4, lineCount(text))
        assertEquals(1, lineCount(""))
    }
}
