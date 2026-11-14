package org.mudlet.android

import android.app.Application
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.engine.EngineProcess

// Runs in both processes; only the :engine process ever loads Qt
class MudletApp : Application() {
    override fun onCreate() {
        EngineProcess.markApplicationCreated()
        super.onCreate()
        EngineNotifications.ensureChannels(this)
    }
}
