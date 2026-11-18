package org.mudlet.android.ui.game

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Desktop Mudlet's TBuffer::smTimeStampFormat, "hh:mm:ss.zzz ", with Qt's 24-hour hh; ASCII digits whatever the locale, as Qt's
private val TIME_STAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS ")

/** What desktop Mudlet draws before a line that came at [time], in milliseconds since the epoch, while timestamps show. */
internal fun timeStamp(time: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME_STAMP_FORMAT.format(Instant.ofEpochMilli(time).atZone(zone))

/**
 * Desktop Mudlet's latency box, from status `lag` (docs/protocol.md): [network] in seconds, or null while the game marks no
 * prompts with GA, and [processing], each as the engine wrote it, as 0.123.
 */
internal data class Lag(val network: String?, val processing: String) {
    companion object {
        /** Null for no lag to show: none yet, or the profile has closed. */
        fun of(status: String?): Lag? {
            val parts = status?.split(' ')?.takeIf { it.size == 2 } ?: return null
            return Lag(parts[0].takeUnless { it == "-" }, parts[1])
        }
    }
}
