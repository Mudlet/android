package org.mudlet.proto.a

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.qtproject.qt.android.QtProtoAccess
import org.qtproject.qt.android.QtServiceBase

/**
 * Hosts Qt and the engine in the app's own process. QtServiceBase.onCreate() loads the Qt
 * libraries and libmudletproto, starts Qt's thread and runs main() there; it blocks the main
 * thread until main() calls JniBridge::releaseQtServiceSetup(). Qt's own bindings.QtService is
 * only shipped as source (src/android/java), so this extends QtServiceBase from Qt6Android.jar.
 */
class MudletEngineService : QtServiceBase() {
    companion object {
        private const val TAG = "MudletEngineService"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: MudletEngineService get() = this@MudletEngineService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        val entered = SystemClock.elapsedRealtime()
        EngineRepository.put("svc.onCreateMs", (entered - EngineRepository.appOnCreateElapsedMs).toString())
        // Within 5 s of startForegroundService(); before Qt so a slow library load cannot miss it
        goForeground()

        val qtWasRunning = QtProtoAccess.qtStarted()
        super.onCreate()
        val returned = SystemClock.elapsedRealtime()
        EngineRepository.put("svc.superOnCreateMs", (returned - entered).toString())

        if (qtWasRunning) {
            // Qt survived an earlier instance of this service (see onDestroy); QtServiceBase just
            // logged "detached from Qt" and returned. Point QtNative at this instance instead.
            QtProtoAccess.setService(this)
            EngineRepository.put("svc.qtStart", "reattached to running Qt")
        } else if (NativeBridge.libraryLoaded()) {
            NativeBridge.nativeServiceReady()
            EngineRepository.put("svc.qtStart", "started")
        } else {
            EngineRepository.put("svc.qtStart", "FAILED: Qt libraries did not load (see logcat 'Qt JAVA')")
        }
        EngineRepository.put("svc.state", "created")
        EngineRepository.put("svc.pid", Process.myPid().toString())
        EngineRepository.put("svc.javaAppState", QtProtoAccess.applicationState().toString())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EngineRepository.put("svc.state", "started (foreground)")
        // A restart without the UI would come back disconnected, so do not ask for one
        return START_NOT_STICKY
    }

    // QtServiceBase.onBind() asks Qt's native onBind listener, which only a QAndroidService
    // installs; ours returns null, so bind locally instead.
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        EngineRepository.put("svc.taskRemoved", "yes")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        EngineRepository.put("svc.state", "destroyed")
        if (EngineRepository.exitQtOnDestroy) {
            // Qt's path: QCoreApplication::quit(), wait for main() to return, then System.exit(0).
            // That ends the whole process, the Activity included.
            Log.i(TAG, "onDestroy: handing over to QtServiceBase (System.exit follows)")
            super.onDestroy()
        } else {
            // Skip QtServiceBase.onDestroy() so the process survives; Qt keeps running idle
            // and the next onCreate() reattaches. Android does not require super here.
            if (NativeBridge.libraryLoaded()) NativeBridge.nativeDisconnect()
            Log.i(TAG, "onDestroy: Qt left running in process ${Process.myPid()}")
        }
    }

    fun connectTo(host: String, port: Int) = NativeBridge.nativeConnect(host, port)
    fun send(line: String) = NativeBridge.nativeSend(line)
    fun disconnectFromGame() = NativeBridge.nativeDisconnect()
    fun runLua(code: String) = NativeBridge.nativeRunLua(code)
    fun setViewWidthPx(px: Int) = NativeBridge.nativeSetViewWidthPx(px)

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, ProtoApp.CHANNEL_ENGINE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Mudlet prototype A")
            .setContentText("Engine running in-process (Qt in a foreground service)")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        val actualType = if (Build.VERSION.SDK_INT >= 29) foregroundServiceType else -1
        EngineRepository.put("svc.fgsType", "0x" + Integer.toHexString(actualType))
    }
}
