package org.mudlet.proto.b

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.qtproject.qt.android.QtQuickView

/**
 * Plain foreground service (specialUse). It keeps the process alive; in HostMode.SERVICE it is also
 * the Context of the never-attached QtQuickView, so Qt's lifetime follows this service instead of
 * the Activity.
 */
class EngineService : Service() {
    private var qtView: QtQuickView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServiceCompat.startForeground(
            this,
            Notifications.ENGINE_NOTIFICATION_ID,
            Notifications.engineNotification(this, EngineBridge.summary()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        EngineBridge.serviceState = "foreground (specialUse), pid ${Process.myPid()}"
        EngineBridge.event("EngineService.onCreate, startForeground(specialUse)")
        if (EngineBridge.hostMode == HostMode.SERVICE && (!EngineBridge.qtTerminated || EngineBridge.reembedRequested)) {
            EngineBridge.reembedRequested = false
            qtView = QtHost.create(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKELOCK_ON -> setWakeLock(true)
            ACTION_WAKELOCK_OFF -> setWakeLock(false)
        }
        return START_NOT_STICKY
    }

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MudletProtoB:engine")
                .apply { acquire() }
        } else if (!on) {
            wakeLock?.release()
            wakeLock = null
        }
        EngineBridge.status["wakelock"] = if (wakeLock != null) "held" else "off"
        EngineBridge.event("partial wake lock ${if (on) "acquired" else "released"}")
    }

    override fun onDestroy() {
        EngineBridge.event("EngineService.onDestroy")
        // The engine's lifetime is the service's in both modes, so "Stop engine" means the same thing.
        EngineBridge.quitQt("EngineService destroyed")
        setWakeLock(false)
        qtView = null
        instance = null
        EngineBridge.serviceState = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_WAKELOCK_ON = "wakelock.on"
        private const val ACTION_WAKELOCK_OFF = "wakelock.off"
        private var instance: EngineService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }

        fun wakeLock(context: Context, on: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EngineService::class.java).setAction(if (on) ACTION_WAKELOCK_ON else ACTION_WAKELOCK_OFF),
            )
        }

        fun refreshNotification() {
            instance?.let { Notifications.updateEngine(it, EngineBridge.summary()) }
        }
    }
}
