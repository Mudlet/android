package org.mudlet.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LuaProblem
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemDetails
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.protocol.TriggerPattern
import org.mudlet.android.ui.scripts.ScriptItemKey
import org.mudlet.android.ui.scripts.changedFrom
import org.mudlet.android.ui.scripts.key

/**
 * The profile's triggers, aliases, scripts, timers, keys and buttons as the script library shows and edits them
 * (docs/protocol.md, "Editing triggers, aliases and scripts"): each kind's items as the engine last listed them, the
 * fields of the items opened, and the drafts of those, which live here so that a rotation or a trip to the game keeps
 * them. Requests go with [send], each answer's `id` from [newId]. What it holds is Compose state, changed only here.
 * Main thread only.
 */
internal class ScriptItemsClient(private val newId: () -> Long, private val send: (JSONObject) -> Boolean) {
    private val listed = mutableStateMapOf<ScriptItemKind, List<ScriptItemNode>>()
    private val opened = mutableStateMapOf<ScriptItemKey, ScriptItem>()
    private val edited = mutableStateMapOf<ScriptItemKey, ScriptItem>()
    private val refused = mutableStateMapOf<ScriptItemKey, String>()
    private val hidden = mutableStateListOf<ScriptItemKey>()
    private val queued = mutableStateListOf<ScriptNotice>()
    private val problems = mutableStateMapOf<ScriptItemKey, FoundInLua>()

    /** Each kind's items, as the engine last listed them; absent until it first does. */
    val lists: Map<ScriptItemKind, List<ScriptItemNode>> get() = listed

    /** The items opened, as the engine last gave them. */
    val saved: Map<ScriptItemKey, ScriptItem> get() = opened

    /** What the player has changed of the items opened and not applied ([edit]): each differs from what [saved] has. */
    val drafts: Map<ScriptItemKey, ScriptItem> get() = edited

    /** Why the engine would not give the fields of an item asked for, in English, until it is asked for again. */
    val unopened: Map<ScriptItemKey, String> get() = refused

    /** The items deleted whose Undo is still offered: hidden from the lists, and deleted once [commitDelete]. */
    val deleting: List<ScriptItemKey> get() = hidden

    /** What the screens are to tell the player, the oldest first; each is taken off once [shown]. */
    val notices: List<ScriptNotice> get() = queued

    /** How many times the engine has been attached to: what was asked before the last is asked again. */
    var attaches by mutableIntStateOf(0)
        private set

    /**
     * What the engine found wrong with [key]'s Lua when it was last applied ([apply]), for as long as that is still the
     * item's code: null once the code is changed, as a problem in code that is no longer there says nothing.
     */
    fun luaProblem(key: ScriptItemKey): LuaProblem? = problems[key]?.problem

    private sealed interface Pending {
        /** A scriptItems; [afterMove] when it follows a moveScriptItem, whose [ScriptNotice.Moved] it then tells. */
        data class List(val kind: ScriptItemKind, val afterMove: Boolean = false) : Pending

        data class Item(val key: ScriptItemKey) : Pending

        /**
         * An editScriptItem of [sent]; [previous] is what Undo puts back, null for an Undo itself and for the first save
         * of an item [added] with what it matches.
         */
        data class Apply(val key: ScriptItemKey, val sent: ScriptItem, val previous: ScriptItem?, val added: Boolean = false) : Pending

        /** An addScriptItem; with [pattern], of a trigger that is saved with it as soon as it is made. */
        data class Add(val kind: ScriptItemKind, val pattern: TriggerPattern? = null) : Pending

        data class Enable(val kind: ScriptItemKind) : Pending

        data class Check(val key: ScriptItemKey, val code: String) : Pending
    }

    private val pending = HashMap<Long, Pending>()

    // What the last move sent is to say once the list after it comes, unless the engine refuses it first, as it
    // answers in order: a moveScriptItem is answered only when refused, and with no id
    private var moving: ScriptNotice.Moved? = null

    /** Asks for [kind]'s items. False as for [ProfileSession.send]. */
    fun ask(kind: ScriptItemKind): Boolean = request(Pending.List(kind)) { Requests.scriptItems(kind, it) }

    /** Asks for the fields of [key], which [saved] then has, or [unopened] why not. False as for [ProfileSession.send]. */
    fun open(key: ScriptItemKey): Boolean {
        refused.remove(key)
        return request(Pending.Item(key)) { Requests.scriptItem(key.kind, key.id, it) }
    }

    /** [changed] is what the player has made of its item: its draft, or none when it is as [saved] has the item. */
    fun edit(changed: ScriptItem) {
        if (changed.changedFrom(opened[changed.key])) edited[changed.key] = changed else edited.remove(changed.key)
        dropStaleProblem(changed.key)
    }

    /** Drops [key]'s draft. */
    fun discard(key: ScriptItemKey) {
        edited.remove(key)
        dropStaleProblem(key)
    }

    /**
     * Saves [key]'s draft; [notices] then has [ScriptNotice.Applied], whose Undo is [undo]. Its Lua is saved whether or
     * not it compiles, as the desktop editor's save does, and the engine is asked what is wrong with it, which
     * [luaProblem] then says. False when there is no draft, or as for [ProfileSession.send].
     */
    fun apply(key: ScriptItemKey): Boolean {
        val draft = edited[key] ?: return false
        // Quietly: when the check cannot be sent neither can the save after it, which says so
        request(Pending.Check(key, draft.script), quiet = true) { Requests.checkLua(draft.script, it) }
        return request(Pending.Apply(key, draft, opened[key])) { Requests.editScriptItem(draft, it) }
    }

    /**
     * Saves [previous] over what an apply saved, as the Undo of [ScriptNotice.Applied]; what the player changed since
     * that apply stays a draft. False as for [ProfileSession.send].
     */
    fun undo(previous: ScriptItem): Boolean = request(Pending.Apply(previous.key, previous, null)) { Requests.editScriptItem(previous, it) }

    /**
     * Makes an item of [kind], or a folder, named [name], last in [parent]; [notices] then has [ScriptNotice.Added]. False
     * as for [ProfileSession.send].
     */
    fun add(kind: ScriptItemKind, parent: Int, folder: Boolean, name: String): Boolean {
        val position = listed[kind].orEmpty().count { it.parent == parent }
        if (!request(Pending.Add(kind)) { Requests.addScriptItem(kind, parent, position, folder, name, it) }) return false
        return ask(kind)
    }

    /**
     * Makes a trigger named [name] that matches [pattern], last at the top level, and saves it, so that it runs;
     * [notices] then has [ScriptNotice.Added], once the engine has it with its pattern, or, where the engine made it
     * but did not save the pattern, with why not after it. False as for [ProfileSession.send].
     */
    fun addTrigger(name: String, pattern: TriggerPattern): Boolean {
        // Past the end appends, which is where it goes when the triggers were never listed
        val position = listed[ScriptItemKind.TRIGGER]?.count { it.parent == 0 } ?: Int.MAX_VALUE
        return request(Pending.Add(ScriptItemKind.TRIGGER, pattern)) { Requests.addScriptItem(ScriptItemKind.TRIGGER, 0, position, false, name, it) }
    }

    /** Switches [key] on or off, and asks for its kind's items again, as those in a folder follow it. False as for [ProfileSession.send]. */
    fun enable(key: ScriptItemKey, enabled: Boolean): Boolean {
        if (!request(Pending.Enable(key.kind)) { Requests.enableScriptItem(key.kind, key.id, enabled, it) }) return false
        return ask(key.kind)
    }

    /**
     * Moves [key] last into the folder [parent], 0 for the top level, as a drag in the desktop editor does, and asks for
     * its kind's items again; [notices] then has [ScriptNotice.Moved], or the engine's refusal. False as for
     * [ProfileSession.send].
     */
    fun move(key: ScriptItemKey, parent: Int): Boolean {
        val items = listed[key.kind].orEmpty()
        val position = items.count { it.parent == parent && it.id != key.id }
        if (!send(Requests.moveScriptItem(key.kind, key.id, parent, position))) {
            tell(ScriptNotice.NotSent)
            return false
        }
        val name = items.firstOrNull { it.id == key.id }?.name.orEmpty()
        moving = ScriptNotice.Moved(name, if (parent == 0) null else items.firstOrNull { it.id == parent }?.name.orEmpty())
        return request(Pending.List(key.kind, afterMove = true)) { Requests.scriptItems(key.kind, it) }
    }

    /** Hides [key] from the lists, with [ScriptNotice.Deleted] offering Undo; it is deleted at [commitDelete]. */
    fun deleteLater(key: ScriptItemKey, name: String) {
        if (key !in hidden) hidden.add(key)
        tell(ScriptNotice.Deleted(key, name))
    }

    /** Undo of [deleteLater]: [key] shows again. */
    fun keep(key: ScriptItemKey) {
        hidden.remove(key)
    }

    /** Deletes [key], if its Undo was still offered, and asks for its kind's items again. */
    fun commitDelete(key: ScriptItemKey) {
        if (!hidden.remove(key)) return
        opened.remove(key)
        edited.remove(key)
        problems.remove(key)
        if (send(Requests.deleteScriptItem(key.kind, key.id))) ask(key.kind) else tell(ScriptNotice.NotSent)
    }

    /** Deletes every item whose Undo is still offered, as the screen that offered it goes. */
    fun commitDeletes() = hidden.toList().forEach(::commitDelete)

    /** Takes [notice] off [notices], once a screen has shown it. */
    fun shown(notice: ScriptNotice) {
        queued.remove(notice)
    }

    // Sends what [request] makes of a new id, whose answer [onItems] or [onItem] takes as [what]
    private fun request(what: Pending, quiet: Boolean = false, request: (Long) -> JSONObject): Boolean {
        val id = newId()
        if (!send(request(id))) {
            if (!quiet) tell(ScriptNotice.NotSent)
            return false
        }
        pending[id] = what
        return true
    }

    // The screens show the first notice until it is taken off, so one that equals the one before it would never show
    private fun tell(notice: ScriptNotice) {
        if (queued.lastOrNull() != notice) queued.add(notice)
    }

    /** The engine's answer to [ask]: false when this did not ask it. */
    fun onItems(message: EngineMessage.ScriptItems): Boolean {
        val asked = pending.remove(message.replyTo.asId()) as? Pending.List ?: return false
        listed[asked.kind] = message.items
        if (asked.afterMove) {
            moving?.let(::tell)
            moving = null
        }
        return true
    }

    /** The engine's answer to the check an [apply] asked for: false when this did not ask it. */
    fun onLuaCheck(message: EngineMessage.LuaCheck): Boolean {
        val asked = pending.remove(message.replyTo.asId()) as? Pending.Check ?: return false
        val problem = message.problem
        if (problem == null) problems.remove(asked.key) else problems[asked.key] = FoundInLua(asked.code, problem)
        dropStaleProblem(asked.key)
        return true
    }

    /** The engine's answer to [open], [apply], [undo], [add] or [enable]: false when this did not ask it. */
    fun onItem(message: EngineMessage.ScriptItemFields): Boolean {
        val asked = pending.remove(message.replyTo.asId()) ?: return false
        val item = message.item ?: return true
        val key = item.key
        when (asked) {
            is Pending.Item -> store(item)

            is Pending.Apply -> {
                // What the player changed while the engine saved stays a draft
                if (edited[key] == asked.sent) edited.remove(key)
                store(item)
                // The engine keeps a name as the player gave it, trimmed, or an alias's pattern for none
                ask(key.kind)
                // An Undo of an earlier apply of this item would lose this one
                queued.removeAll { it is ScriptNotice.Applied && it.item.key == key }
                when {
                    asked.added -> tell(ScriptNotice.Added(key, item.node.folder))
                    asked.previous != null -> tell(ScriptNotice.Applied(item, asked.previous))
                }
            }

            is Pending.Add -> {
                store(item)
                val details = item.details
                if (asked.pattern == null || details !is ScriptItemDetails.Trigger) {
                    tell(ScriptNotice.Added(key, item.node.folder))
                } else {
                    // Told once it is saved: a page opened before then would show, and could save, the trigger without it
                    val sent = item.copy(details = details.copy(patterns = listOf(asked.pattern)))
                    if (!request(Pending.Apply(key, sent, previous = null, added = true), quiet = true) { Requests.editScriptItem(sent, it) }) {
                        addedWithout(key, ScriptNotice.NotSent)
                    }
                }
            }

            is Pending.Enable, is Pending.List, is Pending.Check -> if (key in opened) store(item)
        }
        return true
    }

    // A draft the engine's item no longer differs from, as after an apply whose answer the last link lost, is none
    private fun store(item: ScriptItem) {
        opened[item.key] = item
        if (edited[item.key]?.changedFrom(item) == false) edited.remove(item.key)
        dropStaleProblem(item.key)
    }

    private fun dropStaleProblem(key: ScriptItemKey) {
        if (problems[key]?.code != (edited[key] ?: opened[key])?.script) problems.remove(key)
    }

    /**
     * An `error` for [request], one of [REQUESTS], answering [replyTo]: [notices] has the engine's [reason], or [unopened]
     * for an item it would not open, whose kind's items are asked for again, as it is likely gone from them.
     */
    fun onRefused(request: String, reason: String, replyTo: Any?) {
        if (request == "moveScriptItem") moving = null
        val asked = pending.remove(replyTo.asId())
        if (asked is Pending.Item) {
            opened.remove(asked.key)
            refused[asked.key] = reason
            ask(asked.key.kind)
        } else {
            val refusal = ScriptNotice.Refused(request, reason)
            if (asked is Pending.Apply && asked.added) addedWithout(asked.key, refusal) else tell(refusal)
        }
    }

    // A trigger made but not saved with its pattern is there all the same: it opens, and its page says [why] it has none
    private fun addedWithout(key: ScriptItemKey, why: ScriptNotice) {
        tell(ScriptNotice.Added(key, folder = false))
        tell(why)
    }

    /** What the last link did not answer is not answered; a new engine instance loaded the profile again, with new ids. */
    fun onAttached(sameInstance: Boolean) {
        pending.clear()
        moving = null
        if (!sameInstance) {
            listed.clear()
            opened.clear()
            edited.clear()
            problems.clear()
            refused.clear()
            hidden.clear()
        }
        attaches++
    }

    private fun Any?.asId() = (this as? Number)?.toLong()

    companion object {
        /** The requests sent from here, whose refusal goes to [onRefused]. */
        val REQUESTS = setOf("scriptItems", "scriptItem", "addScriptItem", "editScriptItem", "enableScriptItem", "moveScriptItem", "deleteScriptItem")
    }
}

/** What the engine found wrong, [problem], with the Lua [code]. */
private data class FoundInLua(val code: String, val problem: LuaProblem)

/** What the script library has to tell the player, through a snackbar. */
internal sealed interface ScriptNotice {
    /** [item] as an apply saved it; Undo saves [previous] again. */
    data class Applied(val item: ScriptItem, val previous: ScriptItem) : ScriptNotice

    /** A new item or folder, [key], to open. */
    data class Added(val key: ScriptItemKey, val folder: Boolean) : ScriptNotice

    /** The item named [name] moved into the folder named [into], null for the top level. */
    data class Moved(val name: String, val into: String?) : ScriptNotice

    /** [key], named [name], hidden until its Undo times out. */
    data class Deleted(val key: ScriptItemKey, val name: String) : ScriptNotice

    /** The engine refused [request] for [reason], in English. */
    data class Refused(val request: String, val reason: String) : ScriptNotice

    /** A request could not be sent, as there was no engine to send it to. */
    data object NotSent : ScriptNotice
}
