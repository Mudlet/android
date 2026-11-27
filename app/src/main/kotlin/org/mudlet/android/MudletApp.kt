package org.mudlet.android

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import org.mudlet.android.engine.AppLanguage
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.engine.EngineProcess
import org.mudlet.android.engine.StartMark

// Runs in both processes; only the :engine process ever loads Qt
class MudletApp : Application() {
    // What the notification channels were last named in
    private var channelLocales: LocaleList? = null

    override fun onCreate() {
        EngineProcess.markApplicationCreated()
        StartMark.mark("app.onCreate " + Application.getProcessName())
        // Read here, not on the first line's way
        StartMark.base
        super.onCreate()
        // Before the channels, which are named in it
        AppLanguage.applyToProcess(this)
        nameChannels()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyToProcess(this)
        // The phone's language, or the app's in Android 13's settings, may be what changed
        if (resources.configuration.locales != channelLocales) nameChannels()
    }

    private fun nameChannels() {
        EngineNotifications.ensureChannels(this)
        channelLocales = resources.configuration.locales
    }
}
