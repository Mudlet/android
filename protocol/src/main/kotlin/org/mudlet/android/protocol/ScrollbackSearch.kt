package org.mudlet.android.protocol

// The bite's stand-in for the search: it finds nothing
fun matchStarts(text: String, query: String): List<Int> = emptyList()

fun nextMatchingLine(texts: List<String>, query: String, from: Int, up: Boolean): Int? = null
