package org.mudlet.proto.a

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiLine(val id: Long, val text: String, val runs: IntArray, val isPrompt: Boolean)

/**
 * Process-wide state the Compose screen renders. Collects [NativeBridge.events] on the main
 * dispatcher for the life of the process, so it outlives both the Activity and the service.
 */
object EngineRepository {
    private const val MAX_LINES = 1000

    private val _lines = MutableStateFlow<List<UiLine>>(emptyList())
    val lines: StateFlow<List<UiLine>> = _lines

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    @Volatile var appOnCreateElapsedMs = 0L
    @Volatile var exitQtOnDestroy = false
    private var nextId = 0L
    private var notificationId = 100

    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            NativeBridge.events.collect { event ->
                when (event) {
                    is EngineEvent.Line -> _lines.update { old ->
                        val next = old + UiLine(nextId++, event.text, event.runs, event.isPrompt)
                        if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
                    }
                    is EngineEvent.Notify -> postTell(appContext, event.title, event.text)
                    is EngineEvent.Status -> put(event.key, event.value)
                }
            }
        }
    }

    fun put(key: String, value: String) = _status.update { it + (key to value) }

    @SuppressLint("MissingPermission")
    private fun postTell(context: Context, title: String, text: String) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val posted = (_status.value["ui.tellsPosted"]?.toIntOrNull() ?: 0) + if (granted) 1 else 0
        put("ui.tellsPosted", posted.toString())
        if (!granted) {
            put("ui.tellsBlocked", "POST_NOTIFICATIONS not granted")
            return
        }
        val notification = NotificationCompat.Builder(context, ProtoApp.CHANNEL_TELLS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId++, notification)
    }
}
