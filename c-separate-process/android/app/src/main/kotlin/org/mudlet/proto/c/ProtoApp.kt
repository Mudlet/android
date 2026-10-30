package org.mudlet.proto.c

import android.app.Application
import android.os.SystemClock
import org.mudlet.proto.c.engine.EngineNotifications

// Runs in both processes; only the :engine process ever loads Qt
class ProtoApp : Application() {
    override fun onCreate() {
        onCreateElapsedMs = SystemClock.elapsedRealtime()
        super.onCreate()
        EngineNotifications.ensureChannels(this)
    }

    companion object {
        @Volatile var onCreateElapsedMs = 0L
            private set
    }
}
