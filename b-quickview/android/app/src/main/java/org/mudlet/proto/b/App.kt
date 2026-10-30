package org.mudlet.proto.b

import android.app.Application
import android.os.SystemClock

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        EngineBridge.init(this, SystemClock.elapsedRealtime())
        Notifications.createChannels(this)
    }
}
