package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the app does with a tapped label link until upstream can follow one (docs/phase-f.md, "Upstream gaps", 1). */
class LabelLinkTest {
    @Test
    fun linksAreSortedAsTLabelSortsThem() {
        assertEquals(LabelLink.Send("north "), LabelLink.of("send:north "))
        assertEquals(LabelLink.Send("x"), LabelLink.of("  SEND:x"))
        assertEquals(LabelLink.Prompt("say "), LabelLink.of("prompt:say "))
        assertEquals(LabelLink.Web("https://mudlet.org"), LabelLink.of(" https://mudlet.org "))
        assertEquals(LabelLink.Lua("echo('hi')"), LabelLink.of("echo('hi')"))
        // A method call after a scheme-shaped prefix is Lua; a URL that is none is not run
        assertEquals(LabelLink.Lua("tel:go()"), LabelLink.of("tel:go()"))
        assertEquals(LabelLink.Lua("pkg.ui:show \"x\""), LabelLink.of("pkg.ui:show \"x\""))
        assertEquals(LabelLink.NotRun("mailto"), LabelLink.of("mailto:a@b.c"))
        assertEquals(LabelLink.NotRun("javascript"), LabelLink.of("javascript:go()"))
        assertEquals(LabelLink.Lua("x = {a = 1}"), LabelLink.of("x = {a = 1}"))
    }

    @Test
    fun whatTheEngineIsAskedToDo() {
        assertEquals("expandAlias(\"north \")", LabelLink.Send("north ").request(1)!!.getString("code"))
        assertEquals("printCmdLine(\"say \")", LabelLink.Prompt("say ").request(2)!!.getString("code"))
        val lua = LabelLink.Lua("echo('hi')").request(3)!!
        assertEquals("runLua", lua.getString("t"))
        assertEquals("echo('hi')", lua.getString("code"))
        assertEquals(3L, lua.getLong("id"))
        assertNull(LabelLink.Web("https://mudlet.org").request(4))
        assertNull(LabelLink.NotRun("ftp").request(5))
    }

    @Test
    fun luaStrings() {
        assertEquals("\"a \\\"b\\\" \\\\ c\\n\\r\\009é\"", LabelLink.luaString("a \"b\" \\ c\n\r\té"))
    }
}
