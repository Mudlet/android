package org.mudlet.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The settings screen's search, and how it names a page to open */
class SettingsSearchTest {
    private val readNewText = listOf("Read new text aloud", "While TalkBack is on, the game's new text is read out as it arrives.", "speech, announce")

    @Test
    fun eachWordOfTheQueryIsPartOfOneOfTheTexts() {
        assertTrue(searchMatches("talk", readNewText))
        assertTrue(searchMatches("  READ   talkback ", readNewText))
        assertTrue(searchMatches("speech, aloud", readNewText))
        assertFalse("every word must be found", searchMatches("read colours", readNewText))
        assertFalse("a word is found within one text, not across two", searchMatches("aloudwhile", readNewText))
    }

    @Test
    fun accentsAndCaseAreIgnoredEitherWay() {
        val french = listOf("Lire le nouveau texte à voix haute", "Écran")
        assertTrue(searchMatches("a voix", french))
        assertTrue(searchMatches("ecran", french))
        assertTrue(searchMatches("ÉCRAN", french))
        assertTrue(searchMatches("écran", listOf("ecran")))
    }

    @Test
    fun mxpFindsTheGamesLinksSwitch_whoseHintSaysWhereMxpLinksAreTurnedOff() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string")
        val english = (0 until strings.length).map { strings.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
        assertTrue(searchMatches("MXP", listOf("settings_game_links", "settings_game_links_hint", "settings_game_links_keywords").map(english::getValue)))
    }

    @Test
    fun aQueryOfNoWordsMatchesNothing() {
        assertFalse(searchMatches("", readNewText))
        assertFalse(searchMatches(" , ", readNewText))
    }

    @Test
    fun aPageSurvivesBeingEncodedWhateverTheGameIsCalled() {
        for (page in listOf(
            SettingsPage(SettingsCategory.ACCESSIBILITY, "Aardwolf"),
            SettingsPage(SettingsCategory.DISPLAY, "a/b/c", highlight = "blankLinesBehaviour"),
            SettingsPage(SettingsCategory.DISPLAY, "-"),
            SettingsPage(SettingsCategory.DISPLAY, null),
            SettingsPage(SettingsCategory.APPEARANCE, null, highlight = "textSizeSp"),
        )) {
            assertEquals(page, SettingsPage.decode(page.encode()))
        }
        assertNull(SettingsPage.decode("NO_SUCH_CATEGORY//-"))
        assertNull(SettingsPage.decode("DISPLAY"))
    }
}
