package org.mudlet.android.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.mudlet.android.data.Settings
import org.mudlet.android.ui.game.Announcer
import org.mudlet.android.ui.theme.MudletTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        EngineClient.init(this)
        Settings.init(this)
        Announcer.init(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        EngineClient.refreshServiceState()
        if (EngineClient.engineWanted && EngineClient.fgsState == "not running") EngineClient.startEngine()
        setContent {
            MudletTheme {
                AppNavigation(onKillUi = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EngineClient.attach()
    }

    override fun onStop() {
        EngineClient.detach()
        super.onStop()
    }
}
