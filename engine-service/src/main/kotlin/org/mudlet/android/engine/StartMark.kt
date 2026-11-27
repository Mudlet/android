package org.mudlet.android.engine

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/** Measure only (perf-engine-measure branch): a stamp on SystemClock.elapsedRealtime()'s clock, which the engine's CLOCK_BOOTTIME is. */
object StartMark {
    fun mark(what: String) {
        Log.i("MudletStart", String.format(Locale.ROOT, "MUDLET_T %s %.3f", what, SystemClock.elapsedRealtimeNanos() / 1e6))
    }
}
