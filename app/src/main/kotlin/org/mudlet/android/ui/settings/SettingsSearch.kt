package org.mudlet.android.ui.settings

import java.text.Normalizer
import java.util.Locale

private val WORD_BREAKS = Regex("[\\s,]+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Whether each word of [query] is part of one of [texts], whatever their case and accents: "talk" finds "TalkBack",
 * "couleur" finds "Couleurs", "écran" finds "ecran". A query of no words matches nothing.
 */
fun searchMatches(query: String, texts: List<String>): Boolean {
    val words = fold(query).split(WORD_BREAKS).filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val folded = texts.map(::fold)
    return words.all { word -> folded.any { word in it } }
}

private fun fold(text: String) = COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFC), "").lowercase(Locale.ROOT)
