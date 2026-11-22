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
import org.mudlet.android.protocol.GameNotices
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object EngineNotifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TELLS = "tells"
    const val CHANNEL_NEW_TEXT = "newText"
    const val ENGINE_NOTIFICATION_ID = 1

    /** The open profile a notification's tap makes the front, on the launcher Activity's intent. */
    const val EXTRA_PROFILE = "org.mudlet.android.engine.extra.PROFILE"

    private val nextTellId = AtomicInteger(100)

    // The id of each profile's group summary for its tells
    private val summaryIds = ConcurrentHashMap<String, Int>()

    // The id of each profile's "new game text" notification
    private val noticeIds = ConcurrentHashMap<String, Int>()

    private val notices = GameNotices()

    // Only once the UI has said so: with none, as when the engine starts alone, the app is not in front
    @Volatile
    private var inFront = false

    /**
     * There while the player allows a game's text on its "new game text" notification: a file, as this process cannot
     * read the UI's SharedPreferences as they change.
     */
    fun gameTextMarker(context: Context) = File(context.filesDir, "notification-game-text")

    /**
     * An open game, as the engine notification lists it: its profile's name and its connection status, one of the
     * engine's (docs/protocol.md): "connected", "connecting ...", "disconnected", "error: ...".
     */
    data class Game(val name: String, val connection: String) {
        val connected: Boolean get() = connection == "connected"
        val connecting: Boolean get() = connection.startsWith("connecting")
    }

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
        // Low, as desktop Mudlet only flashes the taskbar; the player can raise it in the system's settings
        val newText = NotificationChannel(CHANNEL_NEW_TEXT, context.getString(R.string.channel_new_text), NotificationManager.IMPORTANCE_LOW)
        newText.description = context.getString(R.string.channel_new_text_description)
        nm.createNotificationChannel(newText)
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
     * The ongoing notification: its title names the one connected game or counts them, or says that none is connected,
     * and with several games open it lists each with its connection. [deferred]: Android 12+ waits 10 s before showing
     * it, so one taken away sooner never shows.
     */
    fun engineNotification(context: Context, deferred: Boolean = false): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val games = games
        val connected = games.filter { it.connected }
        val builder = NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(title(context, games, connected))
            .setContentText(
                context.getString(
                    if (games.isNotEmpty() && connected.isEmpty()) R.string.engine_notification_text_not_connected else R.string.engine_notification_text,
                ),
            )
            .setOngoing(true)
            .setContentIntent(openUi(context))
            .addAction(0, context.getString(R.string.engine_notification_quit), stop)
            .setForegroundServiceBehavior(
                if (deferred) NotificationCompat.FOREGROUND_SERVICE_DEFERRED else NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
            )
        if (games.size > 1) {
            val lines = NotificationCompat.InboxStyle()
            games.forEach { lines.addLine(context.getString(R.string.engine_notification_game, it.name, connectionText(context, it))) }
            builder.setStyle(lines)
        }
        return builder.build()
    }

    private fun title(context: Context, games: List<Game>, connected: List<Game>): String = when {
        games.isEmpty() -> context.getString(R.string.engine_notification_title)
        connected.size == 1 -> context.getString(R.string.engine_notification_title_profile, connected.single().name)
        connected.size > 1 -> context.resources.getQuantityString(R.plurals.engine_notification_title_games, connected.size, connected.size)
        games.size > 1 -> context.resources.getQuantityString(R.plurals.engine_notification_title_none_connected, games.size, games.size)
        games.single().connecting -> context.getString(R.string.engine_notification_title_connecting, games.single().name)
        else -> context.getString(R.string.engine_notification_title_not_connected, games.single().name)
    }

    private fun connectionText(context: Context, game: Game): String = context.getString(
        when {
            game.connected -> R.string.engine_notification_connected
            game.connecting -> R.string.engine_notification_connecting
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
     * It goes after [timeoutMs], as the script's showNotification() asked, or stays with 0. Silent while the app is in
     * front.
     */
    @JvmStatic
    @JvmOverloads
    @SuppressLint("MissingPermission") // mayPost checks POST_NOTIFICATIONS: lint does not look into it
    fun postTell(context: Context, profile: String, title: String, text: String, timeoutMs: Long = 0) {
        if (!mayPost(context)) {
            Log.i(EngineService.TAG, "not posting a tell: the user has not allowed notifications")
            return
        }
        val id = nextTellId.getAndIncrement()
        val n = NotificationCompat.Builder(context, CHANNEL_TELLS)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(inFront)
            // The lock screen shows it as the player's system setting for private notifications says
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openUi(context, profile, id))
        if (profile.isNotEmpty()) n.setSubText(profile).setGroup(profile)
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

    /** Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process: the app left the front ([away]) or came back. */
    @JvmStatic
    fun appState(context: Context, away: Boolean) {
        inFront = !away
        synchronized(notices) { notices.appState(away) }.forEach { cancelNotice(context, it) }
        // Once a leaving, not once a second as a game's text comes
        if (away && !mayPost(context)) Log.i(EngineService.TAG, "not posting new game text: the user has not allowed notifications")
    }

    /**
     * Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process, while the app is away: [profile]
     * has [unread] lines the player has not read, the newest [latest]; [attention] when it asks for the player's
     * attention, as desktop Mudlet would flash its taskbar.
     */
    @JvmStatic
    fun gameText(context: Context, profile: String, unread: Long, latest: String, attention: Boolean) {
        val show = synchronized(notices) { notices.text(profile, unread, latest, attention, false) } ?: return
        showNotice(context, show)
    }

    /** Called from C++ (AndroidNotifier.cpp) on Qt's thread in the :engine process as [profile] closes. */
    @JvmStatic
    fun gameClosed(context: Context, profile: String) {
        synchronized(notices) { notices.closed(profile) }?.let { cancelNotice(context, it) }
    }

    @SuppressLint("MissingPermission") // mayPost checks POST_NOTIFICATIONS: lint does not look into it
    private fun showNotice(context: Context, show: GameNotices.Show) {
        if (!mayPost(context)) return
        val id = noticeIds.getOrPut(show.game + show.unread) { nextTellId.getAndIncrement() }
        val count = if (show.unread > 0) {
            context.resources.getQuantityString(R.plurals.new_text_unread, show.unread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), show.unread)
        } else {
            context.getString(R.string.new_text_attention)
        }
        fun notice(text: String) = NotificationCompat.Builder(context, CHANNEL_NEW_TEXT)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(show.game)
            .setContentText(text)
        // What a locked screen shows when the player hides private notifications' content there: never the game's text
        val public = notice(count).build()
        val n = notice(show.latest ?: count)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openUi(context, "", id))
        if (show.latest != null) n.setSubText(count)
        NotificationManagerCompat.from(context).notify(id, n.build())
    }

    private fun cancelNotice(context: Context, cancel: GameNotices.Cancel) {
        val id = noticeIds[cancel.game] ?: return
        NotificationManagerCompat.from(context).cancel(id)
    }
}
