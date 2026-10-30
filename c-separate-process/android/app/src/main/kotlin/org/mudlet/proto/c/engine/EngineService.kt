package org.mudlet.proto.c.engine

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.mudlet.proto.c.ProtoApp
import org.qtproject.qt.android.QtServiceBase

/**
 * The engine. Runs in the ":engine" process; QtServiceBase.onCreate() loads the Qt
 * libraries and runs libengine's main() on Qt's own thread. That main() creates a
 * QGuiApplication and a QLocalServer that the UI process connects to.
 */
class EngineService : QtServiceBase() {
    override fun onCreate() {
        // Native main() reads these; Os.setenv is visible to getenv() in this process
        Os.setenv("MUDLET_ENGINE_SOCKET", EngineSocket.path(this), true)
        Os.setenv("MUDLET_APP_ONCREATE_MS", ProtoApp.onCreateElapsedMs.toString(), true)
        Os.setenv("MUDLET_SERVICE_ONCREATE_MS", SystemClock.elapsedRealtime().toString(), true)
        val platform = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PLATFORM, "android") ?: "android"
        Os.setenv("QT_QPA_PLATFORM", platform, true)
        // Before Qt loads, so a slow library load cannot miss the startForeground() deadline
        goForeground()
        // Returns once native main() has released Qt's service-setup semaphore
        super.onCreate()
        // main() waits for this before creating QGuiApplication; see AndroidServiceSetup.h
        try {
            EngineNative.nativeServiceReady()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libengine did not load; see logcat tag 'Qt JAVA'", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        // Not sticky: a restarted engine has lost its game connection anyway, and the UI restarts it on demand
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: QtServiceBase terminates Qt and calls System.exit(0) on this process")
        super.onDestroy()
    }

    private fun goForeground() {
        try {
            startForeground(
                EngineNotifications.ENGINE_NOTIFICATION_ID,
                EngineNotifications.engineNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start in the foreground from here", e)
            } else {
                Log.w(TAG, "startForeground failed", e)
            }
        }
    }

    companion object {
        const val TAG = "MudletEngineService"
        const val ACTION_STOP = "org.mudlet.proto.c.STOP_ENGINE"
        const val PREFS = "engine"
        const val PREF_PLATFORM = "qpaPlatform"
    }
}
