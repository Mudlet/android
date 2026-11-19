package org.mudlet.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.engine.EngineNotifications
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.game.CommandSuggestions
import org.mudlet.android.ui.game.ScriptFileRequest
import org.mudlet.android.ui.game.ScriptWebPages
import org.mudlet.android.ui.profiles.GameLinkRefused
import org.mudlet.android.ui.profiles.Imports
import org.mudlet.android.ui.profiles.OpenWith
import org.mudlet.android.ui.profiles.OpenedProfileFile
import org.mudlet.android.ui.profiles.TelnetLink
import org.mudlet.android.ui.theme.MudletTheme
import org.mudlet.android.update.PtbUpdates

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

    // The time of the last engine crash Report a problem was offered for: each crash is offered once
    private var crashReportOfferedMs by mutableLongStateOf(0L)

    // How many times the player chose to report a crash, each a request to show Report a problem
    private var reportsAsked by mutableIntStateOf(0)

    // How many notification taps have brought their game to the front, each a request to show the game screen
    private var gamesShown by mutableIntStateOf(0)

    // A telnet:// link from outside the app, until the player has answered it; or whether the last was no game's address
    private var gameLink by mutableStateOf<TelnetLink?>(null)
    private var gameLinkRefused by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A link or file opened in another app starts the Activity in that app's task: it goes to the app's own, so
        // that one Activity talks to the engine. The other app's grant to read a file ends with this Activity, so it
        // is passed on
        if (savedInstanceState == null && !isTaskRoot && (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND)) {
            val file = profileFile(intent)
            val forward = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(file ?: intent.data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (file != null) forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (file != null || intent.action == Intent.ACTION_VIEW) startActivity(forward)
            finish()
            return
        }
        EngineClient.init(this)
        Settings.init(this)
        PtbUpdates.init(this)
        Announcer.init(this)
        explainNotifications = shouldAskForNotifications()
        engineStoppedAnswered = uiPrefs().getBoolean(PREF_ENGINE_STOPPED_ANSWERED, false)
        crashReportOfferedMs = uiPrefs().getLong(PREF_CRASH_REPORT_OFFERED, 0L)
        EngineClient.refreshServiceState()
        if (EngineClient.engineWanted && EngineClient.fgsState == "not running") EngineClient.startEngine()
        // A recreated Activity has shown its intent's game already
        if (savedInstanceState == null) {
            showProfileOf(intent)
            takeOpened(intent)
        } else {
            gameLink = savedInstanceState.getString(STATE_GAME_LINK)?.let(TelnetLink::parse)
        }
        setContent {
            MudletTheme {
                val explainEngineStopped = EngineClient.engineStoppedInBackground && !engineStoppedAnswered
                AppNavigation(
                    onKillUi = { finish() },
                    gamesShown = gamesShown,
                    reportsAsked = reportsAsked,
                    // The game screen's tour waits for these: each asks something of the player that matters more
                    tourHeld = explainNotifications || explainEngineStopped,
                    gameLink = gameLink,
                    onGameLinkDone = { gameLink = null },
                )
                ScriptFileRequest()
                OpenedProfileFile()
                if (gameLinkRefused) {
                    GameLinkRefused(onDismiss = { gameLinkRefused = false })
                } else if (explainNotifications) {
                    NotificationsExplanation(
                        onContinue = {
                            notificationsAnswered(PREF_NOTIFICATIONS_ASKED)
                            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onNotNow = { notificationsAnswered(PREF_NOTIFICATIONS_DECLINED) },
                        // Tapped outside or Back: asked again at the next start
                        onDismiss = { explainNotifications = false },
                    )
                } else if (explainEngineStopped) {
                    EngineStoppedExplanation(
                        onBatterySettings = {
                            answerEngineStopped()
                            EngineStopped.openBatterySettings(this@MainActivity)
                        },
                        onNotNow = ::answerEngineStopped,
                        // Tapped outside or Back: explained again after the next stop
                        onDismiss = { EngineClient.engineStoppedInBackground = false },
                    )
                } else if (EngineClient.engineCrashedAtMs.let { it != 0L && it != crashReportOfferedMs }) {
                    // Not now, Back or a tap outside: that crash is not offered again
                    CrashReportOffer(
                        onReport = {
                            crashReportOffered()
                            reportsAsked++
                        },
                        onClose = ::crashReportOffered,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EngineClient.attach()
    }

    override fun onResume() {
        super.onResume()
        ScriptWebPages.resumed()
        PtbUpdates.checkIfDue()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showProfileOf(intent)
        takeOpened(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        gameLink?.let { outState.putString(STATE_GAME_LINK, it.encode()) }
    }

    // A tap on a notification of the engine's names the profile it is about (EngineNotifications.EXTRA_PROFILE). Recents
    // starts the Activity again with the intent it last had, which is no tap
    private fun showProfileOf(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val profile = intent.getStringExtra(EngineNotifications.EXTRA_PROFILE) ?: return
        EngineClient.showProfile(profile) { gamesShown++ }
    }

    // Recents starts the Activity again with the link or file it last had, which the player has already answered
    private fun takeOpened(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val stream = sharedStream(intent)
        when (OpenWith.of(intent.action, intent.data?.scheme, stream?.scheme)) {
            OpenWith.PROFILE_FILE -> (stream ?: intent.data)?.let { file ->
                Imports.start(applicationContext, file)
            }

            OpenWith.GAME_LINK -> {
                val link = intent.dataString?.let(TelnetLink::parse)
                gameLink = link
                gameLinkRefused = link == null
            }

            OpenWith.NOTHING -> Unit
        }
    }

    // The file a VIEW or SEND from another app hands over to import as a profile; null for anything else
    private fun profileFile(intent: Intent): Uri? {
        val stream = sharedStream(intent)
        if (OpenWith.of(intent.action, intent.data?.scheme, stream?.scheme) != OpenWith.PROFILE_FILE) return null
        return stream ?: intent.data
    }

    private fun sharedStream(intent: Intent): Uri? = if (intent.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null

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

    private fun crashReportOffered() {
        crashReportOfferedMs = EngineClient.engineCrashedAtMs
        uiPrefs().edit { putLong(PREF_CRASH_REPORT_OFFERED, crashReportOfferedMs) }
    }

    private fun uiPrefs() = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

    internal companion object {
        const val PREFS_UI = "ui"
        const val PREF_NOTIFICATIONS_ASKED = "notificationsAsked"
        const val PREF_NOTIFICATIONS_DECLINED = "notificationsDeclined"
        const val PREF_ENGINE_STOPPED_ANSWERED = "engineStoppedAnswered"
        const val PREF_CRASH_REPORT_OFFERED = "crashReportOffered"
        private const val STATE_GAME_LINK = "gameLink"
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

@Composable
private fun CrashReportOffer(onReport: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.crash_offer_title)) },
        text = { Text(stringResource(R.string.crash_offer_text)) },
        confirmButton = { TextButton(onClick = onReport) { Text(stringResource(R.string.crash_offer_report)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.crash_offer_not_now)) } },
    )
}
