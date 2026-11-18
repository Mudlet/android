package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import kotlin.math.sign

/** The game pill's test tag. */
const val GAME_PILL_TAG = "gamePill"

/**
 * The game pill, at the left of the quick bar's row (docs/multi-profile.md, "The switcher"): the front game's
 * connection as an icon whose shape says the state, its [name], and how many games are open when more than one, with a
 * dot when the others have [othersUnread] lines or one wants attention ([othersAttention]). A tap calls [onShowGames],
 * which opens the game sheet. TalkBack reads "<name>, <state>, game <index + 1> of <count>, <n> new lines in other
 * games", and offers Reconnect ([onReconnect]), Disconnect ([onDisconnect]) and the [previous] and [next] games
 * ([onSwitch]) as actions, so neither the sheet nor a gesture is ever needed; a null action is left out. A horizontal
 * drag on it is [swipe]'s: its label slides with the finger, the neighbour's name comes in from the side, and a release
 * far enough, or a fling, switches to that game with a tick; the next game is to the right, or to the left in
 * right-to-left layouts. While desktop Mudlet's emergency stop holds the game's scripts ([scriptsStopped]) it shows a
 * red stop sign, and TalkBack reads "scripts stopped" after the state.
 */
@Composable
fun GamePill(
    name: String,
    state: ConnectionState,
    index: Int,
    count: Int,
    othersUnread: Long,
    othersAttention: Boolean,
    onShowGames: () -> Unit,
    onReconnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    previous: String?,
    next: String?,
    onSwitch: (String) -> Boolean,
    swipe: GameSwipe,
    scriptsStopped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // What a slide to the left brings: the next game in left-to-right layouts, where the games run left to right
    val leftward by rememberUpdatedState(if (rtl) previous else next)
    val rightward by rememberUpdatedState(if (rtl) next else previous)
    val currentSwitch by rememberUpdatedState(onSwitch)
    val tick = rememberSwitchTick()
    val stateText = state.text()
    var description = if (index in 0 until count) {
        stringResource(R.string.game_pill_description, name, stateText, index + 1, count)
    } else {
        stringResource(R.string.game_state, name, stateText)
    }
    if (scriptsStopped) description = stringResource(R.string.game_description_more, description, stringResource(R.string.game_scripts_stopped))
    if (othersUnread > 0) {
        description = stringResource(R.string.game_description_more, description, pluralStringResource(R.plurals.game_others_unread, othersUnread.toPluralCount(), othersUnread))
    }
    if (othersAttention) description = stringResource(R.string.game_description_more, description, stringResource(R.string.game_others_attention))
    val showLabel = stringResource(R.string.game_pill_show)
    val actions = buildList {
        if (onReconnect != null) add(action(stringResource(R.string.connection_reconnect_to, name), onReconnect))
        if (onDisconnect != null) add(action(stringResource(R.string.connection_disconnect_from, name), onDisconnect))
        if (next != null) add(action(stringResource(R.string.game_next, next)) { onSwitch(next) })
        if (previous != null) add(action(stringResource(R.string.game_previous, previous)) { onSwitch(previous) })
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onShowGames,
        modifier = modifier.testTag(GAME_PILL_TAG).gameSwipe(swipe, { leftward }, { rightward }, { currentSwitch(it) }, tick).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(showLabel) {
                onShowGames()
                true
            }
            if (actions.isNotEmpty()) customActions = actions
        },
        shape = CircleShape,
        color = if (state.trouble) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (state.trouble) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionIcon(state)
            if (scriptsStopped) Icon(painterResource(R.drawable.ic_report), contentDescription = null, Modifier.size(20.dp), tint = colors.error)
            val slide = swipe.offset
            Box(Modifier.widthIn(max = 120.dp).clipToBounds()) {
                PillLabel(name, slide)
                val beside = when {
                    slide < 0 -> leftward
                    slide > 0 -> rightward
                    else -> null
                }
                if (beside != null) PillLabel(beside, slide - sign(slide), Modifier.matchParentSize())
            }
            if (count > 1) {
                BadgedBox(badge = { if (othersUnread > 0 || othersAttention) Badge() }) {
                    Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                        Text(
                            count.toString(),
                            Modifier.sizeIn(minWidth = 20.dp).padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// A game's name, slid [slide] of its box's width to the right
@Composable
private fun PillLabel(name: String, slide: Float, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier.graphicsLayer { translationX = slide * size.width },
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun action(label: String, run: () -> Unit) = CustomAccessibilityAction(label) {
    run()
    true
}

/**
 * The one chip beside the game pill while the game is not connected: Connect, Reconnect, Reconnect now or Try again,
 * which [onClick] does by connecting again, or Stop while it connects. TalkBack reads it with the [game]'s name.
 */
@Composable
fun ConnectionChip(action: ConnectionAction, game: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (label, description) = when (action) {
        ConnectionAction.CONNECT -> stringResource(R.string.connection_connect) to stringResource(R.string.connection_connect_to, game)
        ConnectionAction.RECONNECT -> stringResource(R.string.connection_reconnect) to stringResource(R.string.connection_reconnect_to, game)
        ConnectionAction.RECONNECT_NOW -> stringResource(R.string.connection_reconnect_now) to stringResource(R.string.connection_reconnect_now_to, game)
        ConnectionAction.TRY_AGAIN -> stringResource(R.string.connection_try_again) to stringResource(R.string.connection_try_again_to, game)
        ConnectionAction.STOP -> stringResource(R.string.connection_stop) to stringResource(R.string.connection_stop_to, game)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier.semantics { contentDescription = description },
    )
}
