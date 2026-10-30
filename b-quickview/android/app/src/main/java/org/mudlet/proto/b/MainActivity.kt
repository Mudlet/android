package org.mudlet.proto.b

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.qtproject.qt.android.QtQuickView

class MainActivity : ComponentActivity() {
    private var qtView: QtQuickView? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            EngineBridge.event("POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineBridge.event("MainActivity.onCreate #${id()} restored=${savedInstanceState != null}")
        EngineService.start(this)
        // After Qt has terminated in this process, constructing a view would replay Qt's queued
        // teardown on the next onResume, so only do it when the user asks for a restart.
        if (EngineBridge.hostMode == HostMode.ACTIVITY && (!EngineBridge.qtTerminated || EngineBridge.reembedRequested)) {
            EngineBridge.reembedRequested = false
            qtView = QtHost.create(this)
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { ProtoScreen(this) }
    }

    /** The 1x1 holder for the QtQuickView; null in HostMode.SERVICE. */
    fun qtHolder(): FrameLayout? {
        val view = qtView ?: return null
        (view.parent as? ViewGroup)?.removeView(view)
        return FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(1, 1)) }
    }

    fun restartEngine() {
        EngineBridge.reembedRequested = true
        EngineService.start(this)
        if (EngineBridge.hostMode == HostMode.ACTIVITY) {
            // Qt's delegate for this Activity has already started once and ignores a second start.
            recreate()
        }
    }

    fun switchModeAndExit() {
        val next = if (EngineBridge.hostMode == HostMode.ACTIVITY) HostMode.SERVICE else HostMode.ACTIVITY
        HostMode.save(this, next)
        exitProcess()
    }

    fun exitProcess() {
        EngineService.stop(this)
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    private fun id() = Integer.toHexString(System.identityHashCode(this))

    override fun onStart() {
        super.onStart()
        EngineBridge.event("MainActivity.onStart #${id()}")
    }

    override fun onStop() {
        EngineBridge.event("MainActivity.onStop #${id()}")
        super.onStop()
    }

    override fun onDestroy() {
        EngineBridge.event("MainActivity.onDestroy #${id()} finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onDestroy()
    }
}
