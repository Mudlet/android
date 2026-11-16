package org.mudlet.android.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object EngineNotifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TELLS = "tells"
    const val ENGINE_NOTIFICATION_ID = 1

    /** The open profile a notification's tap makes the front, on the launcher Activity's intent. */
    const val EXTRA_PROFILE = "org.mudlet.android.engine.extra.PROFILE"

    private val nextTellId = AtomicInteger(100)

    // The id of each profile's group summary for its tells
    private val summaryIds = ConcurrentHashMap<String, Int>()

    /** An open game, as the engine notification lists it: its profile's name and its connection status. */
    data class Game(val name: String, val connection: String)

    @Volatile
    private var games: List<Game> = emptyList()

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

    // The app's launcher Activity: this module does not know the UI's classes. With [profile], the tap makes it the
    // front; [requestCode] keeps each profile's intent apart, as PendingIntents differing only in extras are one
    private fun openUi(context: Context, profile: String = "", requestCode: Int = 0): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch == null) {
            Log.w(EngineService.TAG, "${context.packageName} has no launcher Activity: notifications open nothing")
            return null
        }
        if (profile.isNotEmpty()) launch.putExtra(EXTRA_PROFILE, profile)
        return PendingIntent.getActivity(
            context,
            requestCode,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The ongoing notification: with one game open it names it, with more it counts them and lists each with its
     * connection. [deferred]: Android 12+ waits 10 s before showing it, so one taken away sooner never shows.
     */
    fun engineNotification(context: Context, deferred: Boolean = false): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val games = games
        val builder = NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(
                when (games.size) {
                    0 -> context.getString(R.string.engine_notification_title)
                    1 -> context.getString(R.string.engine_notification_title_profile, games.single().name)
                    else -> context.resources.getQuantityString(R.plurals.engine_notification_title_games, games.size, games.size)
                },
            )
            .setContentText(context.getString(R.string.engine_notification_text))
            .setOngoing(true)
            .setContentIntent(openUi(context))
            .addAction(0, context.getString(R.string.engine_notification_quit), stop)
            .setForegroundServiceBehavior(
                if (deferred) NotificationCompat.FOREGROUND_SERVICE_DEFERRED else NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
            )
        if (games.size > 1) {
            val lines = NotificationCompat.InboxStyle()
            games.forEach { lines.addLine(context.getString(R.string.engine_notification_game, it.name, connectionText(context, it.connection))) }
            builder.setStyle(lines)
        }
        return builder.build()
    }

    // The engine's connection statuses (docs/protocol.md): "connected", "connecting ...", "disconnected", "error: ..."
    private fun connectionText(context: Context, connection: String): String = context.getString(
        when {
            connection == "connected" -> R.string.engine_notification_connected
            connection.startsWith("connecting") -> R.string.engine_notification_connecting
            else -> R.string.engine_notification_not_connected
        },
    )

    /**
     * Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process: the open profiles, in the order
     * opened, and each one's connection status. With none, EngineService leaves the foreground until one opens.
     */
    @JvmStatic
    fun showProfiles(context: Context, names: Array<String>, connections: Array<String>) {
        val games = names.zip(connections, ::Game)
        this.games = games
        // Qt's context in the :engine process is the Service; its foreground changes go through the main thread, as
        // onStartCommand's do, so they keep their order
        val service = context as? EngineService
        if (service != null) {
            Handler(Looper.getMainLooper()).post { service.showGames(games) }
            return
        }
        Log.w(EngineService.TAG, "Qt's context is not EngineService: the foreground does not follow the open games")
        refresh(context)
    }

    /** Shows the ongoing notification again, with the games as they are now, while the service is in the foreground. */
    // mayPost checks POST_NOTIFICATIONS: lint does not look into it
    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        if (!engineShown || !mayPost(context)) return
        NotificationManagerCompat.from(context).notify(ENGINE_NOTIFICATION_ID, engineNotification(context))
    }

    // POST_NOTIFICATIONS is Android 13's: before it every app may post, and a release that does not know the
    // permission answers "denied" when asked for it by name
    private fun mayPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process. A tell from a [profile] is filed
     * under it, in a group of its own, and its tap opens that profile's game; [profile] is empty for one from none.
     */
    @JvmStatic
    @SuppressLint("MissingPermission") // mayPost checks POST_NOTIFICATIONS: lint does not look into it
    fun postTell(context: Context, profile: String, title: String, text: String) {
        if (!mayPost(context)) {
            Log.i(EngineService.TAG, "not posting the tell '$title': the user has not allowed notifications")
            return
        }
        val id = nextTellId.getAndIncrement()
        val n = NotificationCompat.Builder(context, CHANNEL_TELLS)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openUi(context, profile, id))
        NotificationManagerCompat.from(context).notify(id, n.build())
        if (profile.isEmpty()) return
        // Android bundles a group's notifications only under its summary
        val summaryId = summaryIds.getOrPut(profile) { nextTellId.getAndIncrement() }
        val summary = NotificationCompat.Builder(context, CHANNEL_TELLS)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(profile)
            .setSubText(profile)
            .setGroup(profile)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openUi(context, profile, summaryId))
            .build()
        NotificationManagerCompat.from(context).notify(summaryId, summary)
    }
}
