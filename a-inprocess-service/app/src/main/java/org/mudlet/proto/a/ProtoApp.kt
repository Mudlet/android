package org.mudlet.proto.a

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.MainScope

class ProtoApp : Application() {
    companion object {
        const val CHANNEL_ENGINE = "engine"
        const val CHANNEL_TELLS = "tells"
    }

    override fun onCreate() {
        EngineRepository.appOnCreateElapsedMs = SystemClock.elapsedRealtime()
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "Game connection", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TELLS, "Tells", NotificationManager.IMPORTANCE_HIGH),
        )
        EngineRepository.put("app.pid", Process.myPid().toString())
        EngineRepository.start(this, MainScope())
    }
}
