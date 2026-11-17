package org.mudlet.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LuaProblem
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.scripts.ScriptItemKey

/** What the script library keeps of the engine's items, and what it sends, against requests caught here. */
class ScriptItemsClientTest {
    private val sent = ArrayList<JSONObject>()
    private var attached = true
    private var lastId = 0L
    private val scripts = ScriptItemsClient({ ++lastId }) { message -> attached.also { if (it) sent.add(message) } }

    private val key = ScriptItemKey(ScriptItemKind.ALIAS, 7)

    private fun alias(name: String, pattern: String = "^k$") = ScriptItem(ScriptItemNode(7, 0, name, false, true, true, "", "", ""), "", ScriptItemDetails.Alias(pattern, "kick"))

    /** The request sent last, taken off, which must be a [type]. */
    private fun take(type: String): JSONObject {
        val request = sent.removeAt(sent.lastIndex)
        assertEquals(type, request.getString("t"))
        return request
    }

    private fun answer(request: JSONObject, item: ScriptItem) = scripts.onItem(EngineMessage.ScriptItemFields(item, request.getLong("id")))

    /** [key] opened as [item], with nothing left to answer. */
    private fun opened(item: ScriptItem) {
        scripts.open(key)
        assertTrue(answer(take("scriptItem"), item))
        assertEquals(item, scripts.saved[key])
    }

    /** The aliases listed as [items], with nothing left to answer. */
    private fun listed(items: List<ScriptItemNode>) {
        scripts.ask(ScriptItemKind.ALIAS)
        assertTrue(scripts.onItems(EngineMessage.ScriptItems(items, take("scriptItems").getLong("id"))))
    }

    @Test
    fun aListIsKeptOnlyWhenItAnswersWhatWasAsked() {
        assertTrue(scripts.ask(ScriptItemKind.ALIAS))
        val asked = take("scriptItems")
        assertEquals("alias", asked.getString("kind"))
        val items = listOf(alias("kick").node)
        assertFalse("another client's answer", scripts.onItems(EngineMessage.ScriptItems(items, 99L)))
        assertNull(scripts.lists[ScriptItemKind.ALIAS])
        assertTrue(scripts.onItems(EngineMessage.ScriptItems(items, asked.getLong("id"))))
        assertEquals(items, scripts.lists[ScriptItemKind.ALIAS])
        assertFalse("answered once", scripts.onItems(EngineMessage.ScriptItems(emptyList(), asked.getLong("id"))))
    }

    @Test
    fun anApplySavesTheDraftAndOffersWhatItHadAsItsUndo() {
        val before = alias("kick")
        opened(before)
        assertFalse("nothing to apply", scripts.apply(key))
        scripts.edit(alias("punch"))
        assertTrue(scripts.apply(key))
        val edit = take("editScriptItem")
        assertEquals("punch", edit.getJSONObject("fields").getString("name"))

        // The engine trims the name: what it answers is what is kept
        val after = alias("punch", "^p$")
        answer(edit, after)
        assertEquals(after, scripts.saved[key])
        assertNull(scripts.drafts[key])
        assertEquals("alias", take("scriptItems").getString("kind"))
        assertEquals(listOf<ScriptNotice>(ScriptNotice.Applied(after, before)), scripts.notices.toList())

        scripts.shown(scripts.notices.single())
        assertTrue(scripts.undo(before))
        val undo = take("editScriptItem")
        assertEquals("kick", undo.getJSONObject("fields").getString("name"))
        answer(undo, before)
        assertEquals(before, scripts.saved[key])
        assertTrue("an undo offers no undo of its own", scripts.notices.isEmpty())
    }

    @Test
    fun whatIsChangedWhileTheEngineSavesStaysADraft() {
        opened(alias("kick"))
        scripts.edit(alias("punch"))
        scripts.apply(key)
        val typedSince = alias("punch hard")
        scripts.edit(typedSince)
        answer(take("editScriptItem"), alias("punch"))
        assertEquals(typedSince, scripts.drafts[key])
    }

    @Test
    fun anUndoKeepsWhatWasTypedSinceTheApply() {
        val before = alias("kick")
        opened(before)
        scripts.edit(alias("punch"))
        scripts.apply(key)
        answer(take("editScriptItem"), alias("punch"))
        take("scriptItems")
        val typedSince = alias("punch hard")
        scripts.edit(typedSince)
        scripts.undo(before)
        assertEquals(typedSince, scripts.drafts[key])
        answer(take("editScriptItem"), before)
        assertEquals(typedSince, scripts.drafts[key])
    }

    @Test
    fun onlyTheLastApplyOfAnItemOffersItsUndo() {
        val first = alias("kick")
        opened(first)
        scripts.edit(alias("punch"))
        scripts.apply(key)
        val second = alias("punch")
        answer(take("editScriptItem"), second)
        scripts.edit(alias("slap"))
        scripts.apply(key)
        val third = alias("slap")
        answer(take("editScriptItem"), third)
        assertEquals("an Undo of the first would lose the second", listOf<ScriptNotice>(ScriptNotice.Applied(third, second)), scripts.notices.toList())
    }

    @Test
    fun aDraftTheEngineNowHasIsNoDraft() {
        opened(alias("kick"))
        scripts.edit(alias("punch"))
        // As when the link dropped after an apply the engine did
        scripts.onAttached(sameInstance = true)
        opened(alias("punch"))
        assertNull(scripts.drafts[key])
    }

    @Test
    fun aSwitchIsSentAndTheListAskedForAgain() {
        opened(alias("kick"))
        assertTrue(scripts.enable(key, false))
        assertEquals("alias", take("scriptItems").getString("kind"))
        val enable = take("enableScriptItem")
        assertEquals(7, enable.getInt("item"))
        assertFalse(enable.getBoolean("enabled"))
        val off = alias("kick").let { it.copy(node = it.node.copy(enabled = false, active = false)) }
        answer(enable, off)
        assertEquals("the item opened follows its switch", off, scripts.saved[key])
        assertTrue(scripts.notices.isEmpty())
    }

    @Test
    fun aNewItemIsAddedLastInItsFolderAndThenOpened() {
        listed(listOf(alias("kick").node, alias("punch").node.copy(id = 8), alias("in a folder").node.copy(id = 9, parent = 8)))
        assertTrue(scripts.add(ScriptItemKind.ALIAS, 0, folder = false, "New alias"))
        take("scriptItems")
        val add = take("addScriptItem")
        assertEquals(0, add.getInt("parent"))
        assertEquals("after the two at the top level", 2, add.getInt("position"))
        assertEquals("New alias", add.getString("name"))
        val made = alias("New alias").let { it.copy(node = it.node.copy(id = 10)) }
        answer(add, made)
        val added = ScriptItemKey(ScriptItemKind.ALIAS, 10)
        assertEquals(made, scripts.saved[added])
        assertEquals(listOf<ScriptNotice>(ScriptNotice.Added(added, folder = false)), scripts.notices.toList())
    }

    @Test
    fun aDeleteIsSentOnlyOnceItsUndoHasGone() {
        opened(alias("kick"))
        scripts.edit(alias("punch"))
        scripts.deleteLater(key, "kick")
        assertEquals(listOf(key), scripts.deleting.toList())
        assertEquals(listOf<ScriptNotice>(ScriptNotice.Deleted(key, "kick")), scripts.notices.toList())
        assertTrue("nothing is sent while the Undo is offered", sent.isEmpty())

        scripts.keep(key)
        scripts.commitDelete(key)
        assertTrue("kept, so not deleted", sent.isEmpty())
        assertEquals("with its draft", alias("punch"), scripts.drafts[key])

        scripts.deleteLater(key, "kick")
        scripts.commitDeletes()
        assertEquals("alias", take("scriptItems").getString("kind"))
        val delete = take("deleteScriptItem")
        assertEquals("alias", delete.getString("kind"))
        assertEquals(7, delete.getInt("item"))
        assertTrue(scripts.deleting.isEmpty())
        assertNull(scripts.saved[key])
        assertNull(scripts.drafts[key])
    }

    @Test
    fun aRefusedApplyIsSaidAndKeepsTheDraft() {
        opened(alias("kick"))
        scripts.edit(alias("punch"))
        scripts.apply(key)
        scripts.onRefused("editScriptItem", "no such item", take("editScriptItem").getLong("id"))
        assertEquals(listOf<ScriptNotice>(ScriptNotice.Refused("editScriptItem", "no such item")), scripts.notices.toList())
        assertEquals(alias("punch"), scripts.drafts[key])
    }

    @Test
    fun anItemTheEngineWillNotOpenSaysWhyAndTheListIsAskedForAgain() {
        opened(alias("kick"))
        scripts.open(key)
        scripts.onRefused("scriptItem", "no such item", take("scriptItem").getLong("id"))
        assertEquals("no such item", scripts.unopened[key])
        assertNull(scripts.saved[key])
        assertEquals("alias", take("scriptItems").getString("kind"))
        assertTrue("the page says it, not a snackbar", scripts.notices.isEmpty())

        scripts.open(key)
        assertNull("asked again", scripts.unopened[key])
    }

    @Test
    fun aLuaCheckIsKeptWithTheCodeItChecked() {
        assertTrue(scripts.checkLua("x = = 1"))
        val check = take("checkLua")
        assertEquals("x = = 1", check.getString("code"))
        assertNull(scripts.luaCheck)
        val problem = LuaProblem(1, "unexpected symbol near '='")
        assertTrue(scripts.onLuaCheck(EngineMessage.LuaCheck(problem, check.getLong("id"))))
        assertEquals(LuaChecked("x = = 1", problem), scripts.luaCheck)
        assertFalse("answered once", scripts.onLuaCheck(EngineMessage.LuaCheck(null, check.getLong("id"))))

        attached = false
        assertFalse(scripts.checkLua("x = 1"))
        assertTrue("it is asked as the player types, so one that cannot be sent is not said", scripts.notices.isEmpty())
    }

    @Test
    fun whatCannotBeSentIsSaidOnce() {
        attached = false
        assertFalse(scripts.ask(ScriptItemKind.TRIGGER))
        assertFalse(scripts.ask(ScriptItemKind.ALIAS))
        assertFalse(scripts.open(key))
        assertEquals(listOf<ScriptNotice>(ScriptNotice.NotSent), scripts.notices.toList())
    }

    @Test
    fun aNewEngineInstanceForgetsTheItemsAndTheSameOneKeepsThem() {
        opened(alias("kick"))
        listed(listOf(alias("kick").node))
        scripts.edit(alias("punch"))
        scripts.ask(ScriptItemKind.ALIAS)
        val unanswered = take("scriptItems")

        scripts.onAttached(sameInstance = true)
        assertEquals(1, scripts.attaches)
        assertEquals(alias("punch"), scripts.drafts[key])
        assertFalse("the last link's answers are not waited for", scripts.onItems(EngineMessage.ScriptItems(emptyList(), unanswered.getLong("id"))))
        assertEquals(1, scripts.lists[ScriptItemKind.ALIAS]?.size)

        scripts.onAttached(sameInstance = false)
        assertEquals(2, scripts.attaches)
        assertTrue(scripts.lists.isEmpty() && scripts.saved.isEmpty() && scripts.drafts.isEmpty())
    }
}
