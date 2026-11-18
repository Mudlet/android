package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCacheTest {
    @Test
    fun aMessageSplitsAsMudletsPostMessageSplitsIt() {
        assertEquals("[  OK  ]  - " to "Cleared.", MediaCache.postMessageParts("[  OK  ]  - Cleared."))
        assertEquals("[ WARN ]  - " to "No: a - b.", MediaCache.postMessageParts("[ WARN ]  - No: a - b."))
        // A translation that lost its brackets is shown whole, in the message's colour
        assertEquals("" to "OK - Cleared.", MediaCache.postMessageParts("OK - Cleared."))
        assertEquals("" to "Cleared [now].", MediaCache.postMessageParts("Cleared [now]."))
    }

    @Test
    fun theReasonGoesWhereTheMessageSaysAndTheTextIsQuoted() {
        val lua = MediaCache.clearLua("[  OK  ]  - \"Done\".") { "[ WARN ]  - Not done ($it)." }
        assertTrue(lua, "purgeMediaCache()" in lua)
        assertTrue(lua, "\"[  OK  ]  - \", \"\\\"Done\\\".\"" in lua)
        assertTrue(lua, "\"[ WARN ]  - \", \"Not done (\" .. tostring(reason) .. \").\"" in lua)
        assertTrue(lua, "announce(tail)" in lua)
    }

    @Test
    fun aTranslationWithoutTheReasonIsShownWithoutIt() {
        val lua = MediaCache.clearLua("[  OK  ]  - Done.") { "[ WARN ]  - Not done." }
        assertTrue(lua, "prefix, tail = \"[ WARN ]  - \", \"Not done.\"\n" in lua)
    }
}
