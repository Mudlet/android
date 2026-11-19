package org.mudlet.android.ui.profiles

import java.util.Locale

/**
 * What an Intent from another app asks of MainActivity, read from its action and the schemes of its URIs alone, so that
 * nothing of the file itself is read before the player has seen what it is.
 */
enum class OpenWith {
    /** A `telnet://` link to a game; any other VIEW but a file's is one too, which [TelnetLink] refuses, saying so. */
    GAME_LINK,

    /** A file opened with Mudlet, or shared with it, to import as a profile ([ProfileArchive][org.mudlet.android.data.ProfileArchive]). */
    PROFILE_FILE,

    NOTHING,
    ;

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"

        /**
         * For an Intent with [action], whose data URI has [dataScheme] and whose shared stream [streamScheme]. A file
         * comes as a `content:` URI, which carries the other app's grant to read it; a `file:` path is refused, as it
         * would have the app read whatever path the other app names, the app's own private files too.
         */
        fun of(action: String?, dataScheme: String?, streamScheme: String?): OpenWith {
            val data = dataScheme?.lowercase(Locale.ROOT)
            return when (action) {
                ACTION_VIEW -> when (data) {
                    CONTENT -> PROFILE_FILE
                    FILE -> NOTHING
                    else -> GAME_LINK
                }

                ACTION_SEND -> if (streamScheme?.lowercase(Locale.ROOT) == CONTENT) PROFILE_FILE else NOTHING

                else -> NOTHING
            }
        }

        private const val CONTENT = "content"
        private const val FILE = "file"
    }
}
