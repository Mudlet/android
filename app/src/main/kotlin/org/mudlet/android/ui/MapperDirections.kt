package org.mudlet.android.ui

import org.json.JSONObject
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LuaVariable
import org.mudlet.android.protocol.Requests
import org.mudlet.android.protocol.VariableKey
import org.mudlet.android.protocol.VariableType

/**
 * The words the movement pad sends, as the profile's generic mapper sends a speedwalk's steps (docs/architecture.md,
 * "The movement pad"): `map.configs.lang_dirs[way]` while `map.configs.use_translation` is true, so a game in Swedish is
 * sent `ö` for east once `map language swedish`, or the mapper itself on SvenskMUD, has set them, and the way's English
 * otherwise. Read with `variables` at each tap, as the mapper can change them at any line from the game; taps made
 * while a read is out wait for it. Each request has an id from [newId] and goes with [send]; [later] runs a block on
 * this thread after a delay; [log] is told of a read the engine did not answer in time. The words are the player's
 * data, so nothing here logs them. Main thread only.
 */
internal class MapperDirections(
    private val newId: () -> Long,
    private val send: (JSONObject) -> Boolean,
    private val later: (delayMillis: Long, block: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    private class Read(val configsId: Long, val wordsId: Long) {
        val unanswered = mutableSetOf(configsId, wordsId)
        var configs: List<LuaVariable>? = null
        var words: List<LuaVariable>? = null
        val waiting = ArrayList<Pair<String, (String?) -> Unit>>()
    }

    private var read: Read? = null

    /**
     * Gives [then] the word to send for [way], a key of the mapper's `lang_dirs` as MovementKey.command is, once the
     * mapper's words are read: possibly before this returns, and null when the link to the engine went first.
     */
    fun word(way: String, then: (String?) -> Unit) {
        read?.let {
            it.waiting.add(way to then)
            return
        }
        val asked = Read(newId(), newId())
        if (!send(Requests.variables(CONFIGS, true, 0, asked.configsId)) || !send(Requests.variables(LANG_DIRS, true, 0, asked.wordsId))) {
            then(null)
            return
        }
        asked.waiting.add(way to then)
        read = asked
        // A lost answer must not hold the pad's taps for good
        later(WAIT_MILLIS) {
            if (read === asked) {
                log("the engine did not answer its read of the mapper's words in $WAIT_MILLIS ms")
                finish()
            }
        }
    }

    /** A `variables` from the engine: true when it answers this, which the Variables screen does not take. */
    fun onVariables(message: EngineMessage.Variables): Boolean {
        val asked = read ?: return false
        when (message.replyTo.asId()) {
            asked.configsId -> asked.configs = message.entries
            asked.wordsId -> asked.words = message.entries
            else -> return false
        }
        answered(asked, message.replyTo.asId())
        return true
    }

    /**
     * An `error` for `variables` answering [replyTo]: true when it was this. `There is no such table.` is the usual one,
     * for a profile without the generic mapper, and for a `lang_dirs` still the very table of `map.defaults`, which
     * the engine may list under that name alone (docs/protocol.md, "Reading the Lua variables"); English either way.
     */
    fun onRefused(replyTo: Any?): Boolean {
        val asked = read ?: return false
        val id = replyTo.asId()
        if (id != asked.configsId && id != asked.wordsId) return false
        answered(asked, id)
        return true
    }

    /** The link to the engine went: the taps waiting are not sent. */
    fun onDetached() {
        val asked = read ?: return
        read = null
        asked.waiting.forEach { (_, then) -> then(null) }
    }

    private fun answered(asked: Read, id: Long?) {
        asked.unanswered.remove(id)
        if (asked.unanswered.isEmpty()) finish()
    }

    private fun finish() {
        val asked = read ?: return
        read = null
        asked.waiting.forEach { (way, then) -> then(wordFor(way, asked.configs, asked.words)) }
    }

    private fun Any?.asId() = (this as? Number)?.toLong()

    companion object {
        val CONFIGS = listOf(VariableKey("map", VariableType.STRING), VariableKey("configs", VariableType.STRING))
        val LANG_DIRS = CONFIGS + VariableKey("lang_dirs", VariableType.STRING)
        const val WAIT_MILLIS = 1000L

        private val USE_TRANSLATION = VariableKey("use_translation", VariableType.STRING)

        /**
         * What the mapper's speedwalk sends for [way], given the members of `map.configs`, [configs], and of its
         * `lang_dirs`, [langDirs], each null where the engine had none: `lang_dirs[way]` if `use_translation` is true
         * and it is a whole string with more than spaces in it, else [way].
         */
        fun wordFor(way: String, configs: List<LuaVariable>?, langDirs: List<LuaVariable>?): String {
            val translating = configs.orEmpty().any { it.key == USE_TRANSLATION && it.type == VariableType.BOOLEAN && it.value == "true" }
            if (!translating && langDirs == null) return way
            val word = langDirs.orEmpty().firstOrNull { it.key == VariableKey(way, VariableType.STRING) } ?: return way
            return word.value.takeIf { word.type == VariableType.STRING && !word.cut && it.isNotBlank() } ?: way
        }
    }
}
