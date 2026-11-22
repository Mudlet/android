package org.mudlet.android.protocol

/**
 * Which "new game text" notifications to show while the app is away, as the engine reports each game's text: the
 * phone's form of desktop Mudlet's taskbar flash. A game asks for the player's attention as desktop would flash (its
 * "Notify on new text", a script's `alert()`, the game's bell); from then until the player comes back its one notice
 * follows its unread count. Not thread-safe: its callers serialise.
 */
class GameNotices {
    /** Show or update [game]'s notice; [latest] is its newest line, null unless the player allows game text on notifications. */
    data class Show(val game: String, val unread: Long, val latest: String?)

    data class Cancel(val game: String)

    private var away = false

    // Each game that asked for attention since the app left, and the count its notice shows, null before the first
    private val noticed = mutableMapOf<String, Long?>()

    /** The app left ([away]) or came back: coming back takes every notice away, and each leaving starts afresh. */
    fun appState(away: Boolean): List<Cancel> {
        this.away = away
        val shown = noticed.filterValues { it != null }.keys.map(::Cancel)
        noticed.clear()
        return if (away) emptyList() else shown
    }

    /**
     * [game] has [unread] lines the player has not seen, the newest [latest]; [attention] when it asks for the
     * player's attention. [withText]: the player allows game text on notifications.
     */
    fun text(game: String, unread: Long, latest: String, attention: Boolean, withText: Boolean): Show? {
        if (!away) return null
        if (attention) noticed.putIfAbsent(game, null)
        if (game !in noticed || noticed[game] == unread) return null
        noticed[game] = unread
        return Show(game, unread, latest.takeIf { withText && it.isNotBlank() })
    }

    /** [game] closed: its notice goes with it. */
    fun closed(game: String): Cancel? = if (noticed.remove(game) != null) Cancel(game) else null
}
