package org.mudlet.android.engine

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.core.app.ServiceCompat
import org.qtproject.qt.android.QtServiceBase

/**
 * The engine. Runs in the ":engine" process; QtServiceBase.onCreate() loads the Qt
 * libraries and runs libengine's main() on Qt's own thread. That main() creates a
 * QGuiApplication and a QLocalServer that the UI process connects to.
 */
class EngineService : QtServiceBase() {
    // The games the engine has open, as it last said; null until it has, as a restore may be opening one. Main thread
    private var games: List<EngineNotifications.Game>? = null
    private var destroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastRefresh = 0L
    private val refresh = Runnable {
        lastRefresh = SystemClock.uptimeMillis()
        EngineNotifications.refresh(this)
    }

    override fun onCreate() {
        // Native main() reads these; Os.setenv is visible to getenv() in this process
        Os.setenv("MUDLET_ENGINE_SOCKET", EngineSocket.path(this), true)
        Os.setenv("MUDLET_ENGINE_STATE", EngineSession.statePath(this), true)
        Os.setenv("MUDLET_ENGINE_STOPPED", EngineSession.stopMarkerPath(this), true)
        Os.setenv("MUDLET_APP_ONCREATE_MS", EngineProcess.appOnCreateElapsedMs.toString(), true)
        Os.setenv("MUDLET_SERVICE_ONCREATE_MS", SystemClock.elapsedRealtime().toString(), true)
        Os.setenv("MUDLET_SCROLLBACK_MAX_LINES", EngineScrollback.maxLines(this).toString(), true)
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
        // Not sticky: a UI that is shown restarts a killed engine, which then restores the profile
        return START_NOT_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Any level: this process has no UI, so each says memory is short, and a kill, which saves nothing, may follow
        try {
            EngineNative.nativeSave()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libengine is not loaded, so nothing was saved", e)
        }
    }

    /**
     * The games the engine has open, from EngineNotifications.showProfiles, on the main thread. With none it leaves the
     * foreground, and its notification goes, until one opens. Android lets it back then: the UI opens a game while it
     * is shown, and a restore opens one before the engine has said it has none (ControlBridge::listen).
     */
    fun showGames(games: List<EngineNotifications.Game>) {
        // Posted from Qt's thread, which runs on while onDestroy stops Qt
        if (destroyed) return
        this.games = games
        when {
            games.isEmpty() -> leaveForeground()
            EngineNotifications.engineShown -> refreshSoon()
            else -> goForeground()
        }
    }

    // Android drops an update that comes too soon after the app's last ones (NotificationManagerService's enqueue rate
    // limit, 5 a second), as a connecting followed at once by connected would be, and nothing would then put the last
    // one right: each change waits until REFRESH_GAP_MS after the post before it, and only the latest is posted
    private fun refreshSoon() {
        handler.removeCallbacks(refresh)
        handler.postAtTime(refresh, maxOf(SystemClock.uptimeMillis(), lastRefresh + REFRESH_GAP_MS))
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(refresh)
        EngineNotifications.engineShown = false
        // Only a stop gets here (stopService, the notification's Quit, or Android stopping it in the background with no
        // game open), never a kill: the next engine restores nothing
        EngineSession.markStopped(this)
        Log.i(TAG, "onDestroy: QtServiceBase terminates Qt and calls System.exit(0) on this process")
        super.onDestroy()
    }

    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an int inlined at compile time, and ServiceCompat masks it below API 34
    @SuppressLint("InlinedApi")
    private fun goForeground() {
        // A start request must be answered with startForeground() all the same, even with no game open
        val noGame = games?.isEmpty() == true
        try {
            // Service.startForeground with a type is API 29; ServiceCompat calls the two-argument one on API 28, and on
            // 29-33 drops the types those releases do not know, such as specialUse (API 34)
            ServiceCompat.startForeground(
                this,
                EngineNotifications.ENGINE_NOTIFICATION_ID,
                EngineNotifications.engineNotification(this, deferred = noGame),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            EngineNotifications.engineShown = true
            lastRefresh = SystemClock.uptimeMillis()
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start in the foreground from here", e)
            } else {
                Log.w(TAG, "startForeground failed", e)
            }
        }
        if (noGame) leaveForeground()
    }

    private fun leaveForeground() {
        handler.removeCallbacks(refresh)
        if (!EngineNotifications.engineShown) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        EngineNotifications.engineShown = false
    }

    companion object {
        const val TAG = "MudletEngineService"
        const val ACTION_STOP = "org.mudlet.android.engine.STOP_ENGINE"
        const val PREFS = "engine"
        const val PREF_PLATFORM = "qpaPlatform"
        private const val REFRESH_GAP_MS = 1_000L
    }
}
