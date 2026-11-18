package org.mudlet.android.protocol

/**
 * The most lines a console keeps on this device: what "use maximum lines possible" gives a game's main console, and
 * what a bigger scrollback length is clipped to (the engine's `MUDLET_SCROLLBACK_MAX_LINES`). Desktop Mudlet figures it
 * from four fifths of the machine's memory, for each profile, which on a phone is millions of lines: Android stops
 * the engine long before those are kept.
 *
 * Here a sixteenth of the device's memory, a thirty-second on a low-RAM one, holds the scrollback of all the games
 * the engine can have open at once, at Mudlet's own estimate of a line. From the device's total, not
 * `ActivityManager.getMemoryClass()`, which bounds the Java heap: the engine keeps its lines in native memory.
 */
object ScrollbackBudget {
    /** Mudlet's estimate of a line in `TBuffer::getMaxBufferSize()`: a 16-byte `TChar` for each of 100 columns. */
    const val BYTES_PER_LINE = 1600L

    /** The games the engine opens at once, `ControlBridge::maxOpenProfiles`. */
    const val GAMES = 4

    /** The least it gives, the lines the screen keeps (`ProfileSession.MAX_LINES`), on any device. */
    const val LEAST = 2000

    /** For a device with [totalMemBytes] (`ActivityManager.MemoryInfo.totalMem`), in whole thousands. */
    fun maxLines(totalMemBytes: Long, lowRamDevice: Boolean): Int {
        val share = if (lowRamDevice) 32 else 16
        val lines = totalMemBytes / share / GAMES / BYTES_PER_LINE / 1000 * 1000
        return lines.coerceIn(LEAST.toLong(), Int.MAX_VALUE / 1000 * 1000L).toInt()
    }
}
