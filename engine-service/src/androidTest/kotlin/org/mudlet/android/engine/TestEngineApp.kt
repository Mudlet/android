package org.mudlet.android.engine

import android.app.Application

/** The test APK's Application, in both its processes: what the app's MudletApp does for the engine. */
class TestEngineApp : Application() {
    override fun onCreate() {
        EngineProcess.markApplicationCreated()
        super.onCreate()
        AppLanguage.applyToProcess(this)
        EngineNotifications.ensureChannels(this)
    }
}
