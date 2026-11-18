package org.mudlet.android.ui.game

import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R

/** The sound button's test tag. */
const val SOUND_BUTTON_TAG = "soundButton"

/**
 * Desktop Mudlet's toolbar Sound button: "Mute all media", checked while [allMuted], which [onToggle] turns. TalkBack
 * reads its name and, as it turns, its new state once.
 */
@Composable
fun SoundButton(allMuted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconToggleButton(checked = allMuted, onCheckedChange = { onToggle() }, modifier = modifier.testTag(SOUND_BUTTON_TAG)) {
        Icon(
            painterResource(if (allMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
            contentDescription = stringResource(R.string.sound_mute_all),
        )
    }
}
