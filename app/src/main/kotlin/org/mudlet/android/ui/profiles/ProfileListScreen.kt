package org.mudlet.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.MudletTopBar

/**
 * The start screen. A placeholder until the profiles come (docs/roadmap.md, "Features" item 1): for now it opens the
 * game screen, and, in debug builds, the self-test.
 */
@Composable
fun ProfileListScreen(onOpenGame: () -> Unit, onOpenSelfTest: (() -> Unit)?) {
    Scaffold(topBar = { MudletTopBar(stringResource(R.string.profiles_title)) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge)
            Button(onOpenGame) { Text(stringResource(R.string.profiles_open_game)) }
            if (onOpenSelfTest != null) {
                OutlinedButton(onOpenSelfTest) { Text(stringResource(R.string.selftest_title)) }
            }
        }
    }
}
