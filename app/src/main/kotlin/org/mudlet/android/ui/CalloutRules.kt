package org.mudlet.android.ui

/** What a callout's player has done with it, kept across sessions: the player's data ([FeatureCallouts] keeps it in the `ui` preferences). */
interface CalloutStore {
    fun dismissed(id: String): Boolean

    fun dismiss(id: String)

    fun appearances(id: String): Int

    fun setAppearances(id: String, count: Int)
}

/**
 * Desktop Mudlet's rules for its feature callouts (TFeatureCallout.cpp), by the callout's stable [String] id: one shows
 * until the player engages with it ([dismiss]), at most [MAXIMUM_APPEARANCES] times across sessions, each once a session
 * and at most [MAXIMUM_PER_SESSION] different ones a session, so several new features do not gang up on the player. This
 * object is one session; one at a time shows ([showing]).
 */
class CalloutRules(private val store: CalloutStore) {
    private val thisSession = LinkedHashSet<String>()

    /** The callout on the screen, or null. */
    var showing: String? = null
        private set

    /** Whether [id] may appear now. */
    fun due(id: String): Boolean = showing == null && !store.dismissed(id) && store.appearances(id) < MAXIMUM_APPEARANCES &&
        id !in thisSession && thisSession.size < MAXIMUM_PER_SESSION

    /** [id] appears, if it is [due]: an appearance is spent only once it is on the screen, as desktop's is. */
    fun appear(id: String): Boolean {
        if (!due(id)) return false
        thisSession += id
        store.setAppearances(id, store.appearances(id) + 1)
        showing = id
        return true
    }

    /** [id] has left the screen, dismissed or not. */
    fun gone(id: String) {
        if (showing == id) showing = null
    }

    /** The player engaged with [id]: its Got it, its anchor, or the feature reached another way. It never shows again. */
    fun dismiss(id: String) {
        if (!store.dismissed(id)) store.dismiss(id)
    }

    fun dismissed(id: String) = store.dismissed(id)

    companion object {
        // Desktop's maximumAppearances: a callout the player keeps ignoring stops
        const val MAXIMUM_APPEARANCES = 5

        // Desktop's maximumPerSession
        const val MAXIMUM_PER_SESSION = 2
    }
}
