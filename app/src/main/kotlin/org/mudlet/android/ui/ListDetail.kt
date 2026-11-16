package org.mudlet.android.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mudlet.android.ui.theme.MudletMotion

// Material's expanded window size class starts here
private val EXPANDED_WIDTH = 840.dp

/** Whether this is a detail shown beside its list, whose top bar closes it rather than going back. */
val LocalInDetailPane = staticCompositionLocalOf { false }

private val LIST_PANE_WIDTH = 360.dp

// How far the detail beside the list shrinks as a back gesture goes, before it closes
private const val BACK_SHRINK = 0.1f

/**
 * A list and the [detail] of one of its items. Where the screen is expanded they are side by side, with [empty] in the
 * detail's place until an item is picked, and Back (the predictive gesture too, as the detail shrinks with it) closes
 * the detail. Narrower, the list is alone and an item's detail is a screen of its own: [list] is told which it is, so
 * that picking an item opens the detail where it goes, and [onNarrowed] moves a detail that was open beside the list to
 * its own screen when the screen gets narrower, as a foldable folding or a window resized.
 */
@Composable
fun <K : Any> ListDetail(
    detail: K?,
    onCloseDetail: () -> Unit,
    onNarrowed: (K) -> Unit,
    list: @Composable (twoPane: Boolean) -> Unit,
    detailContent: @Composable (K) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth < EXPANDED_WIDTH || true) {
            if (detail != null) LaunchedEffect(detail) { onNarrowed(detail) }
            list(false)
        } else {
            var backProgress by remember { mutableFloatStateOf(0f) }
            PredictiveBackHandler(enabled = detail != null) { events ->
                try {
                    events.collect { backProgress = it.progress }
                    onCloseDetail()
                } finally {
                    backProgress = 0f
                }
            }
            Row(Modifier.fillMaxSize()) {
                // Each pane keeps the system bars' insets of its own side only
                Box(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) {
                    list(true)
                }
                VerticalDivider()
                val shrink = 1f - backProgress * BACK_SHRINK
                Box(
                    Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                        .graphicsLayer {
                            scaleX = shrink
                            scaleY = shrink
                        },
                ) {
                    AnimatedContent(
                        detail,
                        transitionSpec = {
                            fadeIn(MudletMotion.effects()) + scaleIn(MudletMotion.spatial(), initialScale = 0.92f) togetherWith
                                fadeOut(MudletMotion.effects())
                        },
                    ) { shown ->
                        CompositionLocalProvider(LocalInDetailPane provides true) { if (shown != null) detailContent(shown) else empty() }
                    }
                }
            }
        }
    }
}

/** What the detail's place beside the list shows until an item is picked. */
@Composable
fun DetailPlaceholder(text: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
