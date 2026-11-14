package org.mudlet.android.suggest

/**
 * Scores how well a typed query matches a candidate as a case-insensitive subsequence: "gc" matches
 * "get coins from corpse". A prefix scores highest, then matches on word starts, then runs of consecutive
 * characters; scattered characters and gaps score least. `null` means no match.
 *
 * O(q·m) time and O(m) memory for a query of q characters and a candidate of m, after an O(m) check that
 * rejects most candidates first.
 */
object FuzzyMatch {
    /** What a candidate that starts with the query gets on top of its character scores. */
    const val PREFIX_BONUS = 100.0

    private const val MATCH = 1.0
    private const val WORD_START = 6.0
    private const val TEXT_START = 4.0
    private const val CONSECUTIVE = 4.0
    private const val GAP = 1.0
    private const val LEADING = 0.2
    private const val NONE = -1e9

    fun score(query: String, candidate: String): Double? {
        if (query.isNotEmpty()) return 0.0 // BITE
        val q = CharArray(query.length) { query[it].lowercaseChar() }
        val c = CharArray(candidate.length) { candidate[it].lowercaseChar() }
        if (!isSubsequence(q, c)) return null
        // best[j]: the best score for matching q[0..i] with q[i] at c[j]
        var previous = DoubleArray(c.size) { NONE }
        var current = DoubleArray(c.size)
        for (i in q.indices) {
            // max over k <= j-2 of previous[k] minus the gap between k and j
            var gapped = NONE
            for (j in c.indices) {
                if (j >= 2) gapped = maxOf(gapped, previous[j - 2]) - GAP
                current[j] = NONE
                if (c[j] != q[i]) continue
                val bonus = charScore(c, j)
                if (i == 0) {
                    current[j] = bonus - LEADING * j
                } else {
                    val adjacent = if (j >= 1) previous[j - 1] + CONSECUTIVE else NONE
                    val best = maxOf(gapped, adjacent)
                    if (best > NONE / 2) current[j] = best + bonus
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        val best = previous.max()
        val prefix = candidate.regionMatches(0, query, 0, query.length, ignoreCase = true)
        return best + (if (prefix) PREFIX_BONUS else 0.0)
    }

    private fun isSubsequence(q: CharArray, c: CharArray): Boolean {
        var i = 0
        for (ch in c) {
            if (ch == q[i] && ++i == q.size) return true
        }
        return false
    }

    private fun charScore(c: CharArray, j: Int): Double = when {
        j == 0 -> MATCH + WORD_START + TEXT_START
        !c[j - 1].isLetterOrDigit() && c[j].isLetterOrDigit() -> MATCH + WORD_START
        else -> MATCH
    }
}
