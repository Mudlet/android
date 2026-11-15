package org.mudlet.android.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

object EngineNotifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TELLS = "tells"
    const val ENGINE_NOTIFICATION_ID = 1
    private val nextTellId = AtomicInteger(100)

    @Volatile
    private var profile = ""

    // Only while EngineService is in the foreground: a notify() after it stops would leave an ongoing notification
    // that nothing removes
    @Volatile
    var engineShown = false

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val engine = NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW)
        engine.description = context.getString(R.string.channel_engine_description)
        nm.createNotificationChannel(engine)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TELLS, context.getString(R.string.channel_tells), NotificationManager.IMPORTANCE_DEFAULT))
    }

    // The app's launcher Activity: this module does not know the UI's classes
    private fun openUi(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch == null) {
            Log.w(EngineService.TAG, "${context.packageName} has no launcher Activity: notifications open nothing")
            return null
        }
        return PendingIntent.getActivity(
            context,
            0,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun engineNotification(context: Context): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(
                if (profile.isEmpty()) {
                    context.getString(R.string.engine_notification_title)
                } else {
                    context.getString(R.string.engine_notification_title_profile, profile)
                },
            )
            .setContentText(context.getString(R.string.engine_notification_text))
            .setOngoing(true)
            .setContentIntent(openUi(context))
            .addAction(0, context.getString(R.string.engine_notification_quit), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process; empty when no profile is open. */
    @JvmStatic
    fun showProfile(context: Context, profile: String) {
        this.profile = profile
        if (!engineShown) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(ENGINE_NOTIFICATION_ID, engineNotification(context))
    }

    /** Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process. */
    @JvmStatic
    fun postTell(context: Context, title: String, text: String) {
        // POST_NOTIFICATIONS is Android 13's: before it every app may post, and a release that does not know the
        // permission answers "denied" when asked for it by name
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(EngineService.TAG, "not posting the tell '$title': the user has not allowed notifications")
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
