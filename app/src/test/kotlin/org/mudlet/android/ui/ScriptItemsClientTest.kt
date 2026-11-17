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
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.protocol.TriggerPatternType
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

    private fun trigger(name: String, patterns: List<TriggerPattern> = emptyList()) = ScriptItem(
        ScriptItemNode(11, 0, name, false, true, patterns.isNotEmpty(), "", "", ""),
        "",
        ScriptItemDetails.Trigger(patterns, "", -1, false, false, 0, false, "", false, null, null),
    )

    @Test
    fun aTriggerMadeWithAPatternIsSavedWithItBeforeItIsOpened() {
        val pattern = TriggerPattern("""You hit the (\w+)""", TriggerPatternType.REGEX)
        assertTrue(scripts.addTrigger("You hit the goblin", pattern))
        val add = take("addScriptItem")
        assertEquals("trigger", add.getString("kind"))
        assertEquals(0, add.getInt("parent"))
        assertEquals("past the end, which appends, as the triggers were never listed", Int.MAX_VALUE, add.getInt("position"))
        assertEquals("You hit the goblin", add.getString("name"))
        assertFalse(add.getBoolean("folder"))
        assertTrue(sent.isEmpty())

        answer(add, trigger("You hit the goblin"))
        assertTrue("not opened before it has its pattern", scripts.notices.isEmpty())
        val edit = take("editScriptItem")
        assertEquals(11, edit.getInt("item"))
        val patterns = edit.getJSONObject("fields").getJSONArray("patterns")
        assertEquals(1, patterns.length())
        assertEquals(pattern.text, patterns.getJSONObject(0).getString("text"))
        assertEquals("regex", patterns.getJSONObject(0).getString("type"))

        val made = trigger("You hit the goblin", listOf(pattern))
        answer(edit, made)
        val added = ScriptItemKey(ScriptItemKind.TRIGGER, 11)
        assertEquals(made, scripts.saved[added])
        assertNull(scripts.drafts[added])
        assertEquals("opened, with no Undo of its first save offered", listOf<ScriptNotice>(ScriptNotice.Added(added, folder = false)), scripts.notices.toList())
        take("scriptItems")
    }

    @Test
    fun aTriggerMadeAmongThoseListedGoesAfterThem() {
        scripts.ask(ScriptItemKind.TRIGGER)
        scripts.onItems(EngineMessage.ScriptItems(listOf(trigger("a").node, trigger("in it").node.copy(id = 12, parent = 11)), take("scriptItems").getLong("id")))
        scripts.addTrigger("b", TriggerPattern("b", TriggerPatternType.SUBSTRING))
        assertEquals(1, take("addScriptItem").getInt("position"))
    }

    @Test
    fun aTriggerWhoseAddIsRefusedIsSaid() {
        scripts.addTrigger("b", TriggerPattern("b", TriggerPatternType.SUBSTRING))
        scripts.onRefused("addScriptItem", "no profile", take("addScriptItem").getLong("id"))
        assertEquals(listOf<ScriptNotice>(ScriptNotice.Refused("addScriptItem", "no profile")), scripts.notices.toList())
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

    private fun broken(code: String) = alias("kick").copy(script = code)

    /** [code] applied over the alias "kick": the check's request, with the save's in [save], both still to answer. */
    private fun applied(code: String): JSONObject {
        opened(alias("kick"))
        scripts.edit(broken(code))
        assertTrue(sent.isEmpty())
        assertTrue(scripts.apply(key))
        save = take("editScriptItem")
        assertEquals(code, save.getJSONObject("fields").getString("script"))
        val check = take("checkLua")
        assertEquals(code, check.getString("code"))
        assertTrue("asked once", sent.isEmpty())
        return check
    }

    private var save = JSONObject()
    private val problem = LuaProblem(1, "unexpected symbol near '='")

    private fun checked(check: JSONObject, found: LuaProblem?) = scripts.onLuaCheck(EngineMessage.LuaCheck(found, check.getLong("id")))

    @Test
    fun anApplyChecksTheLuaAndKeepsWhatIsWrongWithIt() {
        val check = applied("x = = 1")
        assertNull("not until the engine has said", scripts.luaProblem(key))
        assertTrue(checked(check, problem))
        assertEquals(problem, scripts.luaProblem(key))
        assertFalse("answered once", checked(check, null))
        assertEquals(problem, scripts.luaProblem(key))
        assertNull("another item's", scripts.luaProblem(ScriptItemKey(ScriptItemKind.ALIAS, 8)))

        answer(save, broken("x = = 1"))
        take("scriptItems")
        assertFalse(scripts.apply(key))
        assertTrue("nothing to apply, so nothing to check", sent.isEmpty())
    }

    @Test
    fun theProblemStaysOnceTheEngineHasSavedTheCode() {
        assertTrue(checked(applied("x = = 1"), problem))
        answer(save, broken("x = = 1"))
        assertNull(scripts.drafts[key])
        assertEquals("the code is still what was checked", problem, scripts.luaProblem(key))
    }

    @Test
    fun editingTheCodeDropsItsProblemUntilTheNextApply() {
        assertTrue(checked(applied("x = = 1"), problem))
        scripts.edit(broken("x = = 1").let { it.copy(node = it.node.copy(name = "punch")) })
        assertEquals("the code is as it was checked", problem, scripts.luaProblem(key))

        scripts.edit(broken("x = = 12"))
        assertNull("it described code that is no longer there", scripts.luaProblem(key))
        assertTrue("typing asks the engine nothing", sent.isEmpty())
        scripts.edit(broken("x = = 1"))
        assertNull("gone until the next apply", scripts.luaProblem(key))

        assertTrue(scripts.apply(key))
        take("editScriptItem")
        assertTrue(checked(take("checkLua"), problem))
        assertEquals(problem, scripts.luaProblem(key))
    }

    @Test
    fun aProblemInCodeChangedSinceIsNotKept() {
        val check = applied("x = = 1")
        scripts.edit(broken("x = 1"))
        assertTrue(checked(check, problem))
        assertNull("the answer is for code the player has since changed", scripts.luaProblem(key))
    }

    @Test
    fun aProblemGoesWithItsCode() {
        assertTrue(checked(applied("x = = 1"), problem))
        scripts.discard(key)
        assertNull("discarded with the draft", scripts.luaProblem(key))

        assertTrue(checked(applied("x = = 1"), problem))
        answer(save, broken("x = = 1"))
        assertEquals(problem, scripts.luaProblem(key))
        scripts.undo(alias("kick"))
        answer(take("editScriptItem"), alias("kick"))
        assertNull("an Undo put the old code back", scripts.luaProblem(key))
    }

    @Test
    fun codeThatCompilesHasNoProblem() {
        assertTrue(checked(applied("x = = 1"), problem))
        scripts.edit(broken("x = = 1").let { it.copy(node = it.node.copy(name = "punch")) })
        assertTrue(scripts.apply(key))
        take("editScriptItem")
        assertTrue(checked(take("checkLua"), null))
        assertNull(scripts.luaProblem(key))
    }

    @Test
    fun aCheckThatCannotBeSentIsNotSaidOnItsOwn() {
        opened(alias("kick"))
        scripts.edit(broken("x = = 1"))
        attached = false
        assertFalse(scripts.apply(key))
        assertEquals("said once, for the save", listOf<ScriptNotice>(ScriptNotice.NotSent), scripts.notices.toList())
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
