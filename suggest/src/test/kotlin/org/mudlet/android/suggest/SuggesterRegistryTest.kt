package org.mudlet.android.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterRegistryTest {
    @Test
    fun eachProfileLearnsAlone() {
        val registry = SuggesterRegistry()
        val achaea = registry.forProfile("Achaea")
        val discworld = registry.forProfile("Discworld")
        assertSame(achaea, registry.forProfile("Achaea"))
        assertNotSame(achaea, discworld)

        achaea.recordSent("kill rat")
        achaea.recordSent("loot")
        achaea.observeLine("A goblin is here.")
        discworld.recordSent("kill rat")

        assertEquals(listOf("kill rat", "loot").sorted(), achaea.suggest("").items.map { it.text }.sorted())
        assertTrue(discworld.suggest("lo").items.isEmpty())
        assertTrue(discworld.suggest("kill gob").items.isEmpty())
    }

    @Test
    fun aRemovedProfileStartsAfresh() {
        val registry = SuggesterRegistry()
        registry.forProfile("a").recordSent("look")
        registry.remove("a")
        assertTrue(registry.forProfile("a").suggest("lo").items.isEmpty())
    }
}
