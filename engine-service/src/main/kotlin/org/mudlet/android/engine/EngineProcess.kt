package org.mudlet.android.engine

import android.os.SystemClock

/** The app's Application, which runs in both processes, reports here; the engine reads it in the :engine process. */
object EngineProcess {
    @Volatile var appOnCreateElapsedMs = 0L
        private set

    /** Call first thing in Application.onCreate(), so the engine can report how long after it Qt started. */
    fun markApplicationCreated() {
        appOnCreateElapsedMs = SystemClock.elapsedRealtime()
    }
}
