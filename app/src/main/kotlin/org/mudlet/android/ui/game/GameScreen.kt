package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.ui.MudletTopBar

/** A placeholder for the console, the input line and the connection bar (docs/roadmap.md, "Features" item 2). */
@Composable
fun GameScreen(onBack: () -> Unit) {
    Scaffold(topBar = { MudletTopBar(stringResource(R.string.game_title), onBack) }) { padding ->
        Text(
            stringResource(R.string.game_placeholder),
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
