package org.mudlet.android.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The movement pad's strings are translated into each language Mudlet desktop ships (docs/translations.md, "The movement
 * pad's translations"), in the folder Crowdin writes that language to, until Crowdin has them too: a pull request from
 * Crowdin that would take one out fails here.
 */
class MovementPadTranslationsTest {
    private val res = File("src/main/res")

    private fun strings(folder: String): Map<String, String> {
        val file = res.resolve("$folder/strings.xml")
        assertTrue("$file", file.isFile)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }.associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyMudletLanguageHasEveryPadStringWithTheEnglishsPlaceholders() {
        val english = strings("values")
        for (name in PAD_STRINGS) assertTrue("$name is no English string", name in english)
        for ((language, folder) in FOLDERS) {
            val translated = strings("values-$folder")
            for (name in PAD_STRINGS) {
                val text = translated[name]
                assertTrue("$language ($folder) lacks $name", !text.isNullOrBlank())
                assertEquals("$language ($folder) $name", placeholders(english.getValue(name)), placeholders(text!!))
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")

        // Mudlet's translations (translations/translated in Mudlet/Mudlet), each by Crowdin's %android_code% for it
        val FOLDERS = mapOf(
            "ar_EG" to "ar-rEG", "ar_SA" to "ar-rSA", "cs_CZ" to "cs-rCZ", "de_DE" to "de-rDE", "el_GR" to "el-rGR",
            "es_ES" to "es-rES", "fi_FI" to "fi-rFI", "fr_FR" to "fr-rFR", "he_IL" to "iw-rIL", "it_IT" to "it-rIT",
            "ko_KR" to "ko-rKR", "nl_NL" to "nl-rNL", "pl_PL" to "pl-rPL", "pt_BR" to "pt-rBR", "pt_PT" to "pt-rPT",
            "ru_RU" to "ru-rRU", "sv_SE" to "sv-rSE", "tr_TR" to "tr-rTR", "zh_CN" to "zh-rCN", "zh_TW" to "zh-rTW",
        )

        // The pad's own, and those its hide snackbar and design dialog show
        val PAD_STRINGS = listOf(
            "settings_title", "settings_appearance", "settings_experimental", "settings_choice_cancel",
            "settings_movement_pad_keywords", "settings_movement_pad", "settings_movement_pad_hint",
            "settings_movement_pad_design", "settings_movement_pad_design_keywords",
            "settings_movement_pad_current", "settings_movement_pad_current_hint",
            "settings_movement_pad_thumb", "settings_movement_pad_thumb_hint",
            "settings_movement_pad_swipe", "settings_movement_pad_swipe_hint",
            "settings_movement_pad_exits", "settings_movement_pad_exits_hint",
            "settings_movement_pad_side", "settings_movement_pad_side_keywords",
            "settings_movement_pad_left", "settings_movement_pad_right",
            "movement_north", "movement_northeast", "movement_east", "movement_southeast", "movement_south",
            "movement_southwest", "movement_west", "movement_northwest", "movement_up", "movement_down", "movement_in",
            "movement_out", "movement_pad_hide", "movement_pad_hidden", "movement_pad_undo", "movement_pad_fold",
            "movement_pad_unfold", "movement_pad_to_left", "movement_pad_to_right", "movement_again",
            "movement_again_none", "movement_swipe", "movement_swipe_list", "movement_exits_all", "movement_exits_room",
            "movement_exits_none",
        )
    }
}
