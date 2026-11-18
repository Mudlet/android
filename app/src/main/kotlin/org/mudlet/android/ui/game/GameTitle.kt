package org.mudlet.android.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import kotlin.math.sign

/** The game title's test tag. */
const val GAME_TITLE_TAG = "gameTitle"

// Before the Back button, within the title bar's traversal group: the game and its state are heard first
private const val TITLE_TRAVERSAL_INDEX = -1f

/**
 * The game screen's title (docs/multi-profile.md, "The switcher"): the front game's connection as an icon whose shape
 * says the state, its [name] with the state in words under it, and how many games are open when more than one, with a
 * dot when the others have [othersUnread] lines or one wants attention ([othersAttention]). A tap calls [onShowGames],
 * which opens the game sheet. TalkBack reads it first on the screen, as "<name>, <state>, game <index + 1> of <count>,
 * <n> new lines in other games", and offers Reconnect ([onReconnect]), Disconnect ([onDisconnect]) and the [previous]
 * and [next] games ([onSwitch]) as actions, so neither the sheet nor a gesture is ever needed; a null action is left
 * out. A horizontal drag on it is [swipe]'s: its name slides with the finger, the neighbour's comes in from the side,
 * and a release far enough, or a fling, switches to that game with a tick; the next game is to the right, or to the
 * left in right-to-left layouts.
 */
@Composable
fun GameTitle(
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
        stringResource(R.string.game_title_description, name, stateText, index + 1, count)
    } else {
        stringResource(R.string.game_state, name, stateText)
    }
    if (othersUnread > 0) {
        description = stringResource(R.string.game_description_more, description, pluralStringResource(R.plurals.game_others_unread, othersUnread.toPluralCount(), othersUnread))
    }
    if (othersAttention) description = stringResource(R.string.game_description_more, description, stringResource(R.string.game_others_attention))
    val showLabel = stringResource(R.string.game_title_show)
    val actions = buildList {
        if (onReconnect != null) add(action(stringResource(R.string.connection_reconnect_to, name), onReconnect))
        if (onDisconnect != null) add(action(stringResource(R.string.connection_disconnect_from, name), onDisconnect))
        if (next != null) add(action(stringResource(R.string.game_next, next)) { onSwitch(next) })
        if (previous != null) add(action(stringResource(R.string.game_previous, previous)) { onSwitch(previous) })
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onShowGames,
        modifier = modifier.testTag(GAME_TITLE_TAG).gameSwipe(swipe, { leftward }, { rightward }, { currentSwitch(it) }, tick).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            traversalIndex = TITLE_TRAVERSAL_INDEX
            onClick(showLabel) {
                onShowGames()
                true
            }
            if (actions.isNotEmpty()) customActions = actions
        },
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionIcon(state)
            Column(Modifier.weight(1f, fill = false)) {
                val slide = swipe.offset
                Box(Modifier.clipToBounds()) {
                    TitleName(name, slide)
                    val beside = when {
                        slide < 0 -> leftward
                        slide > 0 -> rightward
                        else -> null
                    }
                    if (beside != null) TitleName(beside, slide - sign(slide), Modifier.matchParentSize())
                }
                Text(
                    statusLine(state, stateText),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.trouble) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun TitleName(name: String, slide: Float, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier.graphicsLayer { translationX = slide * size.width },
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// The state in a few words, as the line under the name has room for; the banner says the rest
@Composable
private fun statusLine(state: ConnectionState, stateText: String): String = when (state) {
    ConnectionState.Reconnecting -> stringResource(R.string.connection_reconnecting_short)
    is ConnectionState.Failed -> stringResource(R.string.connection_error_short)
    else -> stateText
}

private fun action(label: String, run: () -> Unit) = CustomAccessibilityAction(label) {
    run()
    true
}
