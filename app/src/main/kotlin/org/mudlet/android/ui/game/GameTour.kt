package org.mudlet.android.ui.game

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.mudlet.android.R
import org.mudlet.android.ui.MainActivity.Companion.PREFS_UI

/** What on the game screen the tour points at, marked with [tourTarget] wherever the control is */
enum class TourTarget { CONSOLE, INPUT_LINE, SCRIPTS, SETTINGS }

/** The steps of desktop Mudlet's UI tour (TUiTour.cpp), in its order, each at the game screen's control for it */
private enum class TourStep(@StringRes val title: Int, @StringRes val body: Int, val target: TourTarget?) {
    WELCOME(R.string.tour_welcome_title, R.string.tour_welcome, null),
    CONSOLE(R.string.tour_console_title, R.string.tour_console, TourTarget.CONSOLE),
    INPUT_LINE(R.string.tour_input_title, R.string.tour_input, TourTarget.INPUT_LINE),
    SCRIPTS(R.string.tour_scripts_title, R.string.tour_scripts, TourTarget.SCRIPTS),
    SETTINGS(R.string.tour_settings_title, R.string.tour_settings, TourTarget.SETTINGS),
    DONE(R.string.tour_done_title, R.string.tour_done, null),
}

/**
 * Whether the game screen's tour is still to show: once, the first time the game screen does, and again after Help's
 * "Take a UI tour". Kept in the `ui` preferences under a key of its own, which is the player's data.
 */
object GameTourShown {
    private const val KEY = "gameTourShown"

    fun due(context: Context) = !prefs(context).getBoolean(KEY, false)

    fun set(context: Context, shown: Boolean) = prefs(context).edit { putBoolean(KEY, shown) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
}

private class TourTargets {
    val bounds = mutableStateMapOf<TourTarget, Rect>()
}

private val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/** Marks the control the tour's step for [target] points at, by what it is rather than where it is */
fun Modifier.tourTarget(target: TourTarget): Modifier = this then TourTargetElement(target)

private data class TourTargetElement(val target: TourTarget) : ModifierNodeElement<TourTargetNode>() {
    override fun create() = TourTargetNode(target)

    override fun update(node: TourTargetNode) {
        node.target = target
    }
}

private class TourTargetNode(var target: TourTarget) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    // Kept from the last placing: a CompositionLocal read in onDetach is not one Compose stands behind
    private var registeredIn: TourTargets? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        registeredIn = currentValueOf(LocalTourTargets)?.also { it.bounds[target] = coordinates.boundsInWindow() }
    }

    override fun onDetach() {
        registeredIn?.bounds?.remove(target)
        registeredIn = null
    }
}

/**
 * The game screen ([content]) with its tour over it while the tour is due ([GameTourShown]): a card per step, as
 * desktop's tour has, at the other end of the screen from the control the step is about, which is lit through the
 * dimmed screen. It waits while [held], for a more urgent dialog over the screen to be answered or hidden. It is
 * marked shown as it starts, as desktop's is, so leaving it partway does not bring it back. While it shows, TalkBack
 * reads the card alone, and each step once as it comes.
 */
@Composable
fun GameTourHost(held: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val targets = remember { TourTargets() }
    // The step shown, or null without the tour
    var step by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(held) {
        if (!held && step == null && GameTourShown.due(context)) {
            GameTourShown.set(context, true)
            step = 0
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(if (step == null) Modifier else Modifier.clearAndSetSemantics {}) {
            CompositionLocalProvider(LocalTourTargets provides targets, content = content)
        }
        step?.let { shown -> TourCard(shown, targets, onStep = { step = it }, onClose = { step = null }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourCard(index: Int, targets: TourTargets, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val current = TourStep.entries[index]
    val last = index == TourStep.entries.lastIndex
    val title = stringResource(current.title)
    val body = stringResource(current.body)
    val announcement = stringResource(R.string.tour_announcement, title, body)
    LaunchedEffect(current) { Announcer.announce(announcement, assertive = false) }
    BackHandler(onBack = onClose)
    val name = stringResource(R.string.tour_name)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var height by remember { mutableIntStateOf(0) }
    val spotlight = current.target?.let { targets.bounds[it] }?.translate(-origin)
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val ring = MaterialTheme.colorScheme.primary
    val place = when {
        spotlight == null -> Alignment.Center
        spotlight.center.y > height / 2f -> Alignment.TopCenter
        else -> Alignment.BottomCenter
    }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                height = it.size.height
            }
            // Nothing under the tour takes a touch while it shows
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .semantics { paneTitle = name }
            // Offscreen, so the spotlight clears the scrim rather than the screen under it
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(scrim)
                if (spotlight != null) {
                    val around = spotlight.inflate(SPOTLIGHT_MARGIN.toPx())
                    val corner = CornerRadius(SPOTLIGHT_CORNER.toPx())
                    drawRoundRect(Color.Black, around.topLeft, around.size, corner, blendMode = BlendMode.Clear)
                    drawRoundRect(ring, around.topLeft, around.size, corner, style = Stroke(SPOTLIGHT_RING.toPx()))
                }
            },
    ) {
        Surface(
            Modifier.align(place).safeDrawingPadding().padding(16.dp).widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp).semantics { heading() })
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
                Text(
                    stringResource(R.string.tour_progress, index + 1, TourStep.entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    if (!last) TextButton(onClose) { Text(stringResource(R.string.tour_skip)) }
                    TextButton({ onStep(index - 1) }, enabled = index > 0) { Text(stringResource(R.string.tour_back)) }
                    Button({ if (last) onClose() else onStep(index + 1) }, Modifier.padding(start = 4.dp)) {
                        Text(stringResource(if (last) R.string.tour_finish else R.string.tour_next))
                    }
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.6f

// Desktop's spotlightMargin
private val SPOTLIGHT_MARGIN = 6.dp
private val SPOTLIGHT_CORNER = 12.dp
private val SPOTLIGHT_RING = 2.dp
