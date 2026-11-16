package org.mudlet.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.mudlet.android.R

/**
 * Every screen's title bar, with a Back button when [onBack] is given (a Close button on a detail beside its list), and
 * the screen's own buttons in [actions]. With a [scrollBehavior], it takes a tint while the content under it is scrolled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MudletTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                val inPane = LocalInDetailPane.current
                IconButton(onBack) {
                    if (inPane) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.detail_close, title))
                    } else {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/** Any screen of the app's but the game's: [MudletTopBar] over [content], which scrolls under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MudletScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { MudletTopBar(title, onBack, scroll, actions) },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}
