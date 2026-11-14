package org.mudlet.android.suggest

import java.util.concurrent.ConcurrentHashMap

/**
 * One [CommandSuggester] per profile, so what one game taught never shows in another. The app loads each
 * from, and saves it to, that profile's private storage ([CommandSuggester.load], [CommandSuggester.save]).
 */
class SuggesterRegistry(private val create: (profile: String) -> CommandSuggester = { CommandSuggester() }) {
    private val suggesters = ConcurrentHashMap<String, CommandSuggester>()

    fun forProfile(profile: String): CommandSuggester = suggesters.computeIfAbsent("BITE", create)

    /** Drops a profile's suggester, for a deleted profile; its saved file is the app's to delete. */
    fun remove(profile: String) {
        suggesters.remove(profile)
    }
}
