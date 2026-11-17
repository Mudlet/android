package org.mudlet.android.protocol

import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicReference

/**
 * The array a frame's pixels are worked on in, kept from one frame to the next: a phone's frame is some 10 MB, and the
 * first write to each page of a new array costs more than the copy of a frame into one written before
 * (docs/architecture.md, "The map behind the text"). One is kept, for as long as memory allows.
 */
class KeptPixels {
    private val kept = AtomicReference<SoftReference<IntArray>?>()

    /**
     * [block]'s result of an array of [count] Ints that is its alone until it returns, and may hold what an earlier
     * user left in it. Any thread: a use during another, or one of another size, makes a new array.
     */
    fun <T> use(count: Int, block: (IntArray) -> T): T {
        val pixels = kept.getAndSet(null)?.get()?.takeIf { it.size == count } ?: IntArray(count)
        try {
            return block(pixels)
        } finally {
            kept.set(SoftReference(pixels))
        }
    }
}
