package org.mudlet.proto.b

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    const val CHANNEL_TELLS = "tells"
    const val CHANNEL_ENGINE = "engine"
    const val ENGINE_NOTIFICATION_ID = 1
    private val nextTellId = AtomicInteger(100)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TELLS, "Tells", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ENGINE, "Engine service", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns false when the permission is missing, so the UI can tell posted from dropped. */
    fun postTell(context: Context, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        val n = NotificationCompat.Builder(context, CHANNEL_TELLS)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(nextTellId.getAndIncrement(), n)
        return true
    }

    fun engineNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle("Mudlet proto B engine")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun updateEngine(context: Context, text: String) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(ENGINE_NOTIFICATION_ID, engineNotification(context, text))
    }
}
