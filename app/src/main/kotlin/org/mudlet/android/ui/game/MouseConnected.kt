package org.mudlet.android.ui.game

import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether a mouse or touchpad is connected, which makes the primary pointer fine and able to hover, as a browser's
 * `pointer` and `hover` media features read it.
 */
@Composable
fun rememberMouseConnected(): Boolean {
    val input = LocalContext.current.getSystemService(InputManager::class.java)
    var connected by remember(input) { mutableStateOf(mouseConnected(input)) }
    DisposableEffect(input) {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                connected = mouseConnected(input)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                connected = mouseConnected(input)
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                connected = mouseConnected(input)
            }
        }
        input.registerInputDeviceListener(listener, null)
        // One connected between the first read and the listener
        connected = mouseConnected(input)
        onDispose { input.unregisterInputDeviceListener(listener) }
    }
    return connected
}

// supportsSource() wants every bit of SOURCE_MOUSE, of which a touchscreen and a stylus share the pointer class's
private fun mouseConnected(input: InputManager): Boolean = input.inputDeviceIds.any { id ->
    input.getInputDevice(id)?.let { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) } == true
}
