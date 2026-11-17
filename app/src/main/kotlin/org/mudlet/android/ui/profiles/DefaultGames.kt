package org.mudlet.android.ui.profiles

import org.json.JSONArray
import org.json.JSONObject
import org.mudlet.android.protocol.Profile

/** One of a game's links, as Mudlet's connection dialog shows them: [label] is the game's own, untranslated. */
data class GameLink(val label: String, val url: String)

/**
 * A game Mudlet's connection dialog offers, from its src/TGameDetails.h. Its [description] and link labels are the
 * game's own English, untranslated as on the desktop. [icon] is its logo's file in [DefaultGames.ICON_DIR].
 */
data class DefaultGame(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val links: List<GameLink>,
    val description: String,
    val icon: String,
    val alternateHosts: List<String>,
) {
    /** The description's first paragraph, for the list. */
    val summary: String get() = description.substringBefore("\n\n").trim()

    /** A new profile for this game, of its name, so that Mudlet treats it as this game, as the desktop's does. */
    fun newProfile() = Profile(name, host, port, tls, login = "", autoReconnect = false)
}

/** The games in assets/games, which scripts/sync-mudlet-games.py writes from Mudlet's list. */
object DefaultGames {
    const val FILE = "games/games.json"
    const val ICON_DIR = "games/icons"

    fun parse(json: String): List<DefaultGame> {
        val games = JSONObject(json).getJSONArray("games")
        return (0 until games.length()).map { i ->
            val g = games.getJSONObject(i)
            val links = g.getJSONArray("links")
            DefaultGame(
                name = g.getString("name"),
                host = g.getString("host"),
                port = g.getInt("port"),
                tls = g.getBoolean("tls"),
                links = (0 until links.length()).map { links.getJSONObject(it).let { l -> GameLink(l.getString("label"), l.getString("url")) } },
                description = g.getString("description"),
                icon = g.getString("icon"),
                alternateHosts = g.optJSONArray("alternateHosts").strings(),
            )
        }
    }

    /**
     * The player's profile that is [game]: the one of its name, as the desktop takes a profile named after a game for
     * that game, else one at its address (any of its hosts, and its port), so that it is never added twice.
     */
    fun profileFor(game: DefaultGame, profiles: List<Profile>): Profile? = profiles.firstOrNull { it.name.equals(game.name, ignoreCase = true) }
        ?: profiles.firstOrNull { p -> p.port == game.port && (listOf(game.host) + game.alternateHosts).any { it.equals(p.host.trim(), ignoreCase = true) } }

    /** The [games] the player has no profile for yet, which the profile list offers below their profiles. */
    fun offered(games: List<DefaultGame>, profiles: List<Profile>): List<DefaultGame> = games.filter { profileFor(it, profiles) == null }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
