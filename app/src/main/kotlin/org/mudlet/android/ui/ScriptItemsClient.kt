package org.mudlet.android.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.ScriptItem
import org.mudlet.android.protocol.ScriptItemKind
import org.mudlet.android.protocol.ScriptItemNode
import org.mudlet.android.ui.scripts.ScriptItemKey

/**
 * The profile's triggers, aliases and scripts as the script library shows and edits them (docs/protocol.md, "Editing
 * triggers, aliases and scripts"): each kind's items as the engine last listed them, the fields of the items opened,
 * and the drafts of those, which live here so that a rotation or a trip to the game keeps them. Requests go with
 * [send], each answer's `id` from [newId]. Main thread only.
 */
internal class ScriptItemsClient(private val newId: () -> Long, private val send: (JSONObject) -> Boolean) {
    /** Each kind's items, as the engine last listed them; absent until it first does. */
    val lists = mutableStateMapOf<ScriptItemKind, List<ScriptItemNode>>()

    /** The items opened, as the engine last gave them. */
    val saved = mutableStateMapOf<ScriptItemKey, ScriptItem>()

    /** What the player has changed of the items opened and not applied. */
    val drafts = mutableStateMapOf<ScriptItemKey, ScriptItem>()

    /** The items deleted whose Undo is still offered: hidden from the lists, and deleted once [commitDelete]. */
    val deleting = mutableStateListOf<ScriptItemKey>()

    /** What the screens are to tell the player, the oldest first; each is taken off once shown. */
    val notices = mutableStateListOf<ScriptNotice>()

    private sealed interface Pending {
        data class List(val kind: ScriptItemKind) : Pending

        data class Item(val key: ScriptItemKey) : Pending

        /** An editScriptItem; [previous] is what Undo puts back, null for an Undo itself. */
        data class Apply(val key: ScriptItemKey, val previous: ScriptItem?) : Pending

        data class Add(val kind: ScriptItemKind) : Pending

        data class Enable(val kind: ScriptItemKind) : Pending
    }

    private val pending = HashMap<Long, Pending>()

    /** Asks for [kind]'s items. False as for [ProfileSession.send]. */
    fun ask(kind: ScriptItemKind): Boolean = request(Pending.List(kind)) { Requests.scriptItems(kind, it) }

    /** Asks for the fields of [key], which [saved] then has. False as for [ProfileSession.send]. */
    fun open(key: ScriptItemKey): Boolean = request(Pending.Item(key)) { Requests.scriptItem(key.kind, key.id, it) }

    /** Saves [key]'s draft; [notices] then has [ScriptNotice.Applied], whose Undo is [undo]. False when there is no draft, or as for [ProfileSession.send]. */
    fun apply(key: ScriptItemKey): Boolean {
        val draft = drafts[key] ?: return false
        return request(Pending.Apply(key, saved[key])) { Requests.editScriptItem(draft, it) }
    }

    /** Saves [previous] over what an apply saved, as the Undo of [ScriptNotice.Applied]. False as for [ProfileSession.send]. */
    fun undo(previous: ScriptItem): Boolean {
        val key = ScriptItemKey(previous.kind, previous.node.id)
        drafts.remove(key)
        return request(Pending.Apply(key, null)) { Requests.editScriptItem(previous, it) }
    }

    /**
     * Makes an item of [kind], or a folder, named [name], last in [parent]; [notices] then has [ScriptNotice.Added]. False
     * as for [ProfileSession.send].
     */
    fun add(kind: ScriptItemKind, parent: Int, folder: Boolean, name: String): Boolean {
        val position = lists[kind].orEmpty().count { it.parent == parent }
        if (!request(Pending.Add(kind)) { Requests.addScriptItem(kind, parent, position, folder, name, it) }) return false
        return ask(kind)
    }

    /** Switches [key] on or off, and asks for its kind's items again, as those in a folder follow it. False as for [ProfileSession.send]. */
    fun enable(key: ScriptItemKey, enabled: Boolean): Boolean {
        if (!request(Pending.Enable(key.kind)) { Requests.enableScriptItem(key.kind, key.id, enabled, it) }) return false
        return ask(key.kind)
    }

    /** Hides [key] from the lists, with [ScriptNotice.Deleted] offering Undo; it is deleted at [commitDelete]. */
    fun deleteLater(key: ScriptItemKey, name: String) {
        drafts.remove(key)
        if (key !in deleting) deleting.add(key)
        notices.add(ScriptNotice.Deleted(key, name))
    }

    /** Undo of [deleteLater]: [key] shows again. */
    fun keep(key: ScriptItemKey) {
        deleting.remove(key)
    }

    /** Deletes [key], if its Undo was still offered, and asks for its kind's items again. */
    fun commitDelete(key: ScriptItemKey) {
        if (!deleting.remove(key)) return
        saved.remove(key)
        if (send(Requests.deleteScriptItem(key.kind, key.id))) ask(key.kind) else notices.add(ScriptNotice.NotSent)
    }

    /** Deletes every item whose Undo is still offered, as the screen that offered it goes. */
    fun commitDeletes() = deleting.toList().forEach(::commitDelete)

    // Sends what [request] makes of a new id, whose answer [onItems] or [onItem] takes as [what]
    private fun request(what: Pending, request: (Long) -> JSONObject): Boolean {
        val id = newId()
        if (!send(request(id))) {
            notices.add(ScriptNotice.NotSent)
            return false
        }
        pending[id] = what
        return true
    }

    /** The engine's answer to [ask]: false when this did not ask it. */
    fun onItems(message: EngineMessage.ScriptItems): Boolean {
        val asked = pending.remove(message.replyTo.asId()) as? Pending.List ?: return false
        lists[asked.kind] = message.items
        return true
    }

    /** The engine's answer to [open], [apply], [undo], [add] or [enable]: false when this did not ask it. */
    fun onItem(message: EngineMessage.ScriptItemFields): Boolean {
        val asked = pending.remove(message.replyTo.asId()) ?: return false
        val item = message.item ?: return true
        val key = ScriptItemKey(item.kind, item.node.id)
        when (asked) {
            is Pending.Item -> saved[key] = item

            is Pending.Apply -> {
                saved[key] = item
                drafts.remove(key)
                // The engine keeps a name as the player gave it, trimmed, or an alias's pattern for none
                ask(key.kind)
                if (asked.previous != null) notices.add(ScriptNotice.Applied(item, asked.previous))
            }

            is Pending.Add -> {
                saved[key] = item
                notices.add(ScriptNotice.Added(key, item.node.folder))
            }

            is Pending.Enable, is Pending.List -> if (key in saved) saved[key] = item
        }
        return true
    }

    /** An `error` for [request], one of [REQUESTS], answering [replyTo]: [notices] has the engine's [reason]. */
    fun onRefused(request: String, reason: String, replyTo: Any?) {
        val asked = pending.remove(replyTo.asId())
        // A list asked for again comes, so the item that could not be opened goes from it
        if (asked is Pending.Item) saved.remove(asked.key)
        notices.add(ScriptNotice.Refused(request, reason))
    }

    /** What the last link did not answer is not answered; a new engine instance loaded the profile again, with new ids. */
    fun onAttached(sameInstance: Boolean) {
        pending.clear()
        if (!sameInstance) {
            lists.clear()
            saved.clear()
            drafts.clear()
            deleting.clear()
        }
    }

    private fun Any?.asId() = (this as? Number)?.toLong()

    companion object {
        /** The requests sent from here, whose refusal goes to [onRefused]. */
        val REQUESTS = setOf("scriptItems", "scriptItem", "addScriptItem", "editScriptItem", "enableScriptItem", "moveScriptItem", "deleteScriptItem")
    }
}

/** What the script library has to tell the player, through a snackbar. */
internal sealed interface ScriptNotice {
    /** [item] as an apply saved it; Undo saves [previous] again. */
    data class Applied(val item: ScriptItem, val previous: ScriptItem) : ScriptNotice

    /** A new item or folder, [key], to open. */
    data class Added(val key: ScriptItemKey, val folder: Boolean) : ScriptNotice

    /** [key], named [name], hidden until its Undo times out. */
    data class Deleted(val key: ScriptItemKey, val name: String) : ScriptNotice

    /** The engine refused [request] for [reason], in English. */
    data class Refused(val request: String, val reason: String) : ScriptNotice

    /** A request could not be sent, as there was no engine to send it to. */
    data object NotSent : ScriptNotice
}
