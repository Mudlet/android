package org.mudlet.android.suggest

/**
 * Counts of what followed what: a key (a previous command, a game line) to the commands sent next. Bounded:
 * at most [maxKeys] keys, the least recently used (updated or asked about) evicted first, and [maxNextPerKey] commands per key, the
 * least counted evicted first. A key's counts halve once they add up to more than [AGE_AT], so what the player
 * does now outweighs what they did months ago. Not thread-safe; [CommandSuggester] holds the lock.
 */
internal class TransitionTable(private val maxKeys: Int, private val maxNextPerKey: Int) {
    // access order, so the eldest entry is the least recently used key
    private val table = object : LinkedHashMap<String, LinkedHashMap<String, Double>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Double>>) = size > maxKeys
    }

    val keys: Int get() = table.size

    fun add(key: String, next: String) {
        val counts = table.getOrPut(key) { LinkedHashMap() }
        counts[next] = (counts[next] ?: 0.0) + 1.0
        if (counts.size > maxNextPerKey) {
            val weakest = counts.entries.filter { it.key != next }.minBy { it.value }.key
            counts.remove(weakest)
        }
        if (counts.values.sum() > AGE_AT) {
            counts.replaceAll { _, count -> count / 2 }
            counts.values.removeAll { it < MIN_COUNT }
        }
    }

    /** Puts back a count [entries] gave, for loading saved state; bad counts are ignored. */
    fun restore(key: String, next: String, count: Double) {
        if (!(count >= MIN_COUNT && count <= AGE_AT)) return
        val counts = table.getOrPut(key) { LinkedHashMap() }
        if (counts.size >= maxNextPerKey && next !in counts) return
        counts[next] = count
    }

    /** What followed [key], each with its share smoothed by one unseen observation: one sighting gives 0.5. */
    fun probabilities(key: String): Map<String, Double> {
        val counts = table[key] ?: return emptyMap()
        val total = counts.values.sum() + 1.0
        return counts.mapValues { it.value / total }
    }

    fun removeNext(next: String) {
        val iterator = table.values.iterator()
        while (iterator.hasNext()) {
            val counts = iterator.next()
            counts.remove(next)
            if (counts.isEmpty()) iterator.remove()
        }
    }

    fun removeKey(key: String) {
        table.remove(key)
    }

    fun clear() = table.clear()

    /** Every (key, next, count), least recently used key first, so restoring them keeps the order. */
    fun entries(): Sequence<Triple<String, String, Double>> = table.entries.asSequence().flatMap { (key, counts) ->
        counts.entries.asSequence().map { Triple(key, it.key, it.value) }
    }

    private companion object {
        const val AGE_AT = 64.0
        const val MIN_COUNT = 0.25
    }
}
