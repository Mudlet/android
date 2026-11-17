package org.mudlet.android.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mudlet.android.protocol.Profile
import java.io.File

/**
 * The games the profile list offers: src/main/assets/games as scripts/sync-mudlet-games.py wrote it (its --check, in
 * CI's lint, compares it with Mudlet's list), and which of them a player's profiles already are.
 */
class DefaultGamesTest {
    // Gradle runs a module's unit tests in the module's directory
    private val assets = File("src/main/assets")
    private val games by lazy { DefaultGames.parse(assets.resolve(DefaultGames.FILE).readText()) }

    @Test
    fun theListHasEachOfMudletsGamesWithAnAddressALogoAndLinksTheAppCanOpen() {
        assertTrue("${games.size} games", games.size >= 40)
        assertEquals("names are unique", games.size, games.map { it.name.lowercase() }.toSet().size)
        for (game in games) {
            assertTrue(game.name, game.host.isNotBlank() && game.port in 1..65535)
            assertTrue("${game.name}'s logo ${game.icon}", assets.resolve("${DefaultGames.ICON_DIR}/${game.icon}").isFile)
            assertTrue("${game.name}'s links", game.links.all { it.label.isNotBlank() && it.url.matches(Regex("https?://\\S+")) })
        }
        val logos = assets.resolve(DefaultGames.ICON_DIR).list().orEmpty().toSet()
        assertEquals("every logo is a game's", games.map { it.icon }.toSet(), logos)
        assertTrue("the tutorial needs port 0, which a profile cannot have", games.none { it.name == "Mudlet Tutorial" })
    }

    @Test
    fun aGameReadsAsMudletHasIt() {
        val achaea = games.single { it.name == "Achaea" }
        assertEquals(Profile("Achaea", "achaea.com", 23, false, "", false), achaea.newProfile())
        assertTrue(achaea.links.any { it.label == "Website" && it.url == "http://www.achaea.com/" })
        assertFalse("the summary is the first paragraph", achaea.summary.contains('\n'))
        assertTrue(achaea.description.startsWith(achaea.summary))

        val stick = games.single { it.name == "StickMUD" }
        assertTrue("StickMUD connects securely", stick.newProfile().tls)
        assertEquals(listOf("mg.mud.de", "mg.morgengrauen.info", "morgengrauen.info"), games.single { it.name == "MorgenGrauen" }.alternateHosts)
    }

    @Test
    fun aProfileIsTheGameOfItsNameOrOfItsAddress() {
        val game = DefaultGame("Achaea", "achaea.com", 23, false, emptyList(), "", "achaea.webp", listOf("iron-ach.ironrealms.com"))
        val named = Profile("achaea", "elsewhere.example", 4000, false, "", false)
        val atAddress = Profile("Mine", " ACHAEA.com ", 23, false, "", false)
        val atOtherHost = Profile("Mine too", "iron-ach.ironrealms.com", 23, false, "", false)
        val otherPort = Profile("Not it", "achaea.com", 2323, false, "", false)

        assertEquals("the name first, as the desktop's", named, DefaultGames.profileFor(game, listOf(atAddress, named)))
        assertEquals(atAddress, DefaultGames.profileFor(game, listOf(otherPort, atAddress)))
        assertEquals(atOtherHost, DefaultGames.profileFor(game, listOf(atOtherHost)))
        assertNull(DefaultGames.profileFor(game, listOf(otherPort)))
    }

    @Test
    fun offeredLeavesOutTheGamesThePlayerHasAdded() {
        val achaea = games.single { it.name == "Achaea" }
        val aardwolf = games.single { it.name == "Aardwolf" }
        val offered = DefaultGames.offered(games, listOf(achaea.newProfile(), Profile("Aard", aardwolf.host, aardwolf.port, false, "", false)))
        assertEquals(games.size - 2, offered.size)
        assertFalse(achaea in offered || aardwolf in offered)
        assertEquals(games, DefaultGames.offered(games, emptyList()))
    }
}
