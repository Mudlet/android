package org.mudlet.android.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

object EngineNotifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TELLS = "tells"
    const val ENGINE_NOTIFICATION_ID = 1
    private val nextTellId = AtomicInteger(100)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ENGINE, "Engine running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TELLS, "Tells", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // The app's launcher Activity: this module does not know the UI's classes
    private fun openUi(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch == null) {
            Log.w(EngineService.TAG, "${context.packageName} has no launcher Activity: notifications open nothing")
            return null
        }
        return PendingIntent.getActivity(
            context, 0,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun engineNotification(context: Context): Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle("Mudlet engine running")
            .setContentText("Engine process pid ${android.os.Process.myPid()}")
            .setOngoing(true)
            .setContentIntent(openUi(context))
            .addAction(0, "Stop engine", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process. */
    @JvmStatic
    fun postTell(context: Context, title: String, text: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL_TELLS)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openUi(context))
            .build()
        NotificationManagerCompat.from(context).notify(nextTellId.getAndIncrement(), n)
    }
}
