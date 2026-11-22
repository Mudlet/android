package org.mudlet.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The language list: which resource folders are a language, and how each is named in itself. */
class AppLanguagesTest {
    @Test
    fun crowdinsAndroidCodesAreTheirLanguagesAndRegions() {
        assertEquals("de-DE", AppLanguages.tagOf("de-rDE"))
        assertEquals("pt-BR", AppLanguages.tagOf("pt-rBR"))
        assertEquals("zh-TW", AppLanguages.tagOf("zh-rTW"))
        assertEquals("es-419", AppLanguages.tagOf("es-r419"))
        assertEquals("kl", AppLanguages.tagOf("kl"))
        assertEquals("fil", AppLanguages.tagOf("fil"))
    }

    @Test
    fun androidsOldCodesAreGivenAsTheModernOnes_asLocaleConfigHasThem() {
        assertEquals("he-IL", AppLanguages.tagOf("iw-rIL"))
        assertEquals("id-ID", AppLanguages.tagOf("in-rID"))
        assertEquals("yi", AppLanguages.tagOf("ji"))
    }

    @Test
    fun bcp47FoldersKeepTheirScript() {
        assertEquals("sr-Latn", AppLanguages.tagOf("b+sr+Latn"))
        assertEquals("zh-Hant-TW", AppLanguages.tagOf("b+zh+Hant+TW"))
    }

    @Test
    fun otherQualifiersAreNoLanguage_butMayFollowOne() {
        for (folder in listOf("night", "v31", "land", "sw600dp", "ldrtl", "mcc310", "")) assertNull(folder, AppLanguages.tagOf(folder))
        assertEquals("de", AppLanguages.tagOf("de-night"))
        assertEquals("de-AT", AppLanguages.tagOf("de-rAT-v31"))
        assertEquals("fr", AppLanguages.tagOf("mcc208-fr"))
    }

    @Test
    fun aFolderAddedIsAddedToTheList_afterTheEnglish_once() {
        assertEquals(listOf("en"), AppLanguages.tags("en", ""))
        assertEquals(listOf("en", "kl"), AppLanguages.tags("en", "kl"))
        assertEquals(listOf("en", "kl", "de-DE"), AppLanguages.tags("en", "kl,night,de-rDE"))
        assertEquals(listOf("en", "de"), AppLanguages.tags("en", "de,de-night"))
    }

    @Test
    fun eachIsNamedInItself_byLanguageAlone_capitalised() {
        val names = AppLanguages.choices(listOf("en", "de-DE", "fr-FR", "he-IL")).associate { it.tag to it.name }
        assertEquals(mapOf("en" to "English", "de-DE" to "Deutsch", "fr-FR" to "Français", "he-IL" to "עברית"), names)
    }

    @Test
    fun twoOfOneLanguageAreToldApartByTheirRegions() {
        val names = AppLanguages.choices(listOf("en", "pt-BR", "pt-PT")).associate { it.tag to it.name }
        assertEquals("Português (Brasil)", names["pt-BR"])
        assertEquals("Português (Portugal)", names["pt-PT"])
        assertEquals("English", names["en"])
    }

    @Test
    fun theyAreInTheOrderOfTheirNames() {
        assertEquals(listOf("Deutsch", "English", "Français"), AppLanguages.choices(listOf("fr", "en", "de")).map { it.name })
    }

    @Test
    fun aLanguageWithNoNameShowsItsTag() {
        assertEquals(listOf("qaa"), AppLanguages.choices(listOf("qaa")).map { it.name })
    }
}
