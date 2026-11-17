package org.mudlet.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class KeptPixelsTest {
    private val kept = KeptPixels()

    @Test
    fun framesOfOneSizeAreWorkedOnInOneArray() {
        val first = kept.use(12) { it }
        assertEquals(12, first.size)
        repeat(10) { assertSame(first, kept.use(12) { it }) }
    }

    @Test
    fun theArrayHoldsWhatItsLastUserLeft() {
        kept.use(3) { it[1] = 7 }
        assertEquals(listOf(0, 7, 0), kept.use(3) { it.toList() })
    }

    @Test
    fun aFrameOfAnotherSizeHasAnArrayOfItsSizeWhichIsKeptInstead() {
        val first = kept.use(12) { it }
        val other = kept.use(20) { it }
        assertEquals(20, other.size)
        assertSame(other, kept.use(20) { it })
        assertNotSame(first, kept.use(12) { it })
    }

    @Test
    fun aUseDuringAnotherHasAnArrayOfItsOwn() {
        val outer = kept.use(12) { it }
        kept.use(12) { pixels ->
            assertSame(outer, pixels)
            assertNotSame(pixels, kept.use(12) { it })
        }
    }

    @Test
    fun theArrayIsKeptWhereItsUserThrows() {
        val first = kept.use(12) { it }
        assertThrows(IllegalStateException::class.java) { kept.use(12) { error("no frame") } }
        assertSame(first, kept.use(12) { it })
    }
}
