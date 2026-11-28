package org.mudlet.android.protocol

/**
 * Whether the app downloads the map a game offers ([EngineMessage.MapDownload]) by itself, with no tap: once in a
 * profile's life, and only into a map with no rooms, so a map the player loaded, made or edited is never replaced. The
 * first offer heard for the profile decides it: the map's areas that have rooms (Requests.mapAreas) are asked as the
 * offer comes, not as the profile was made, as a map may have been loaded since, and the map downloads only where
 * there are none. Either way it is [decided], which the app keeps, and nothing is asked or downloaded by itself again,
 * a download that failed included. The player's own download button stays. Main thread only, as the session's state.
 */
class MapAutoDownload(decided: Boolean) {
    /** What the engine's answer to the areas [asked] for means. */
    enum class Answer {
        /** It answers another request. */
        NOT_ASKED,

        /** The offer went, or a download began or was asked for, while it was asked: nothing is decided until the next offer. */
        WAIT,

        /** The map has rooms: it stays, and the profile is decided. */
        KEEP_MAP,

        /** The map has no rooms: the offered map is to be downloaded now, and the profile is decided. */
        DOWNLOAD,
    }

    /** Whether the profile's first offer has been decided, downloaded or not: from then on nothing is done by itself. */
    var decided = decided
        private set

    // The mapAreas sent as an offer came, while unanswered
    private var asking: Long? = null
    private var offer: EngineMessage.MapDownload? = null

    /** A new `mapDownload`: true when the map's areas are to be asked for now, then [asked]. */
    fun offered(download: EngineMessage.MapDownload): Boolean {
        offer = download
        return !decided && asking == null && downloadable(download)
    }

    /** Requests.mapAreas went with [id], as [offered] said. */
    fun asked(id: Long) {
        asking = id
    }

    /**
     * The engine answered the mapAreas [replyTo] with areas that have rooms or not ([anyRooms]); [downloadAsked] when the
     * player's own download was asked for and Mudlet has not said it began, as Mudlet refuses a second one meanwhile.
     */
    fun areas(replyTo: Long, anyRooms: Boolean, downloadAsked: Boolean = false): Answer {
        if (replyTo != asking) return Answer.NOT_ASKED
        asking = null
        if (decided || downloadAsked || offer?.let(::downloadable) != true) return Answer.WAIT
        decided = true
        return if (anyRooms) Answer.KEEP_MAP else Answer.DOWNLOAD
    }

    /** The engine refused the mapAreas [replyTo]: true when it was [asked]'s, which the next offer asks again. */
    fun refused(replyTo: Long): Boolean {
        if (replyTo != asking) return false
        asking = null
        return true
    }

    /** The UI attached again: what the last link was asked goes unanswered, and the engine says its offer again. */
    fun attached() {
        asking = null
        offer = null
    }

    // An offer from a host, with no download asked for since the profile loaded. The engine offers only http and https
    // URLs with a host (Mudlet/Mudlet#13022); the host check is a second guard
    private fun downloadable(download: EngineMessage.MapDownload) = download.offered && download.state == MapDownloadState.NONE && download.host.isNotEmpty()
}
