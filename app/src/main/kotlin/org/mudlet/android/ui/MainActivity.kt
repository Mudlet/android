package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.CommandSuggestions
import org.mudlet.android.ui.game.ScriptFileRequest
import org.mudlet.android.ui.theme.MudletTheme

class MainActivity : ComponentActivity() {
    // The engine's startForeground() usually runs before the user answers, and its notification is dropped
    // until the next startForeground(), which another start request triggers
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && EngineClient.engineWanted) EngineClient.startEngine()
        }

    private var explainNotifications by mutableStateOf(false)

    // Battery settings or Not now: the explanation of a stop in the background is never shown again
    private var engineStoppedAnswered by mutableStateOf(false)

    // How many notification taps have brought their game to the front, each a request to show the game screen
    private var gamesShown by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        EngineClient.init(this)
        Settings.init(this)
        Announcer.init(this)
        explainNotifications = shouldAskForNotifications()
        engineStoppedAnswered = uiPrefs().getBoolean(PREF_ENGINE_STOPPED_ANSWERED, false)
        EngineClient.refreshServiceState()
        if (EngineClient.engineWanted && EngineClient.fgsState == "not running") EngineClient.startEngine()
        // A recreated Activity has shown its intent's game already
        if (savedInstanceState == null) showProfileOf(intent)
        setContent {
            MudletTheme {
                AppNavigation(onKillUi = { finish() }, gamesShown = gamesShown)
                ScriptFileRequest()
                if (explainNotifications) {
                    NotificationsExplanation(
                        onContinue = {
                            notificationsAnswered(PREF_NOTIFICATIONS_ASKED)
                            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onNotNow = { notificationsAnswered(PREF_NOTIFICATIONS_DECLINED) },
                        // Tapped outside or Back: asked again at the next start
                        onDismiss = { explainNotifications = false },
                    )
                } else if (EngineClient.engineStoppedInBackground && !engineStoppedAnswered) {
                    EngineStoppedExplanation(
                        onBatterySettings = {
                            answerEngineStopped()
                            EngineStopped.openBatterySettings(this@MainActivity)
                        },
                        onNotNow = ::answerEngineStopped,
                        // Tapped outside or Back: explained again after the next stop
                        onDismiss = { EngineClient.engineStoppedInBackground = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EngineClient.attach()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showProfileOf(intent)
    }

    // A tap on a notification of the engine's names the profile it is about (EngineNotifications.EXTRA_PROFILE). Recents
    // starts the Activity again with the intent it last had, which is no tap
    private fun showProfileOf(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val profile = intent.getStringExtra(EngineNotifications.EXTRA_PROFILE) ?: return
        EngineClient.showProfile(profile) { gamesShown++ }
    }

    override fun onStop() {
        // The process may be killed in the background: keep what the suggestions learned
        CommandSuggestions.saveAll()
        EngineClient.detach()
        super.onStop()
    }

    // The first start, and after one denial, while Android would still show its prompt; never after "Not now"
    private fun shouldAskForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return false
        val prefs = uiPrefs()
        if (prefs.getBoolean(PREF_NOTIFICATIONS_DECLINED, false)) return false
        return !prefs.getBoolean(PREF_NOTIFICATIONS_ASKED, false) || shouldShowRequestPermissionRationale(permission)
    }

    private fun notificationsAnswered(pref: String) {
        explainNotifications = false
        uiPrefs().edit { putBoolean(pref, true) }
    }

    private fun answerEngineStopped() {
        engineStoppedAnswered = true
        EngineClient.engineStoppedInBackground = false
        uiPrefs().edit { putBoolean(PREF_ENGINE_STOPPED_ANSWERED, true) }
    }

    private fun uiPrefs() = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

    internal companion object {
        const val PREFS_UI = "ui"
        const val PREF_NOTIFICATIONS_ASKED = "notificationsAsked"
        const val PREF_NOTIFICATIONS_DECLINED = "notificationsDeclined"
        const val PREF_ENGINE_STOPPED_ANSWERED = "engineStoppedAnswered"
    }
}

@Composable
private fun NotificationsExplanation(onContinue: () -> Unit, onNotNow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notifications_explanation_title)) },
        text = { Text(stringResource(R.string.notifications_explanation_text)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.notifications_explanation_continue)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.notifications_explanation_not_now)) } },
    )
}

@Composable
private fun EngineStoppedExplanation(onBatterySettings: () -> Unit, onNotNow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engine_stopped_title)) },
        text = { Text(stringResource(R.string.engine_stopped_text)) },
        confirmButton = { TextButton(onClick = onBatterySettings) { Text(stringResource(R.string.engine_stopped_battery_settings)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.engine_stopped_not_now)) } },
    )
}
