package org.mudlet.android.ui.game

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.EngineMessage
import org.mudlet.android.protocol.LinkSpan
import org.mudlet.android.protocol.RightClick
import org.mudlet.android.protocol.linkLabel
import org.mudlet.android.protocol.rightClick
import org.mudlet.android.ui.copyText
import org.mudlet.android.ui.shareText
import kotlin.math.roundToInt

/**
 * The links of a line: [spans] where they are in it, [info] what `getLink` last said of one (null until it answers),
 * and [follow], for a tap on one, or with an item, for that item picked from its menu, false where it could not be sent.
 * With [states], TalkBack's actions also say whether a link has been visited or is the one chosen in its group.
 */
internal class LineLinks(
    val spans: List<LinkSpan>,
    val info: (Int) -> EngineMessage.Link?,
    // Before follow, so a trailing lambda is still follow
    val states: Boolean = false,
    val follow: (link: Int, item: Int?) -> Boolean,
)

/**
 * A line of a console: drawn as [text], in its colours and styles, and one node for TalkBack, whose text is [plain],
 * the line's characters without them. With [blankLines] HIDE an empty line is left out of what TalkBack reads, and with
 * SPACE it is read as a space, as Mudlet's setting does to the game's lines: labelled with the space's name, as a node of
 * nothing but a blank has nothing a screen reader can say. [singleLine] clips it, as a script
 * window's grid does, rather than wrapping.
 *
 * Of its [links], a tap follows one and a long press opens its menu where it has one; TalkBack offers each the engine
 * has not disabled as an action that follows it, and one with a menu as another that opens it. A link that could not
 * be sent says so.
 *
 * A mouse works it as desktop's console does: a right click where it is let go reveals a spoiler, opens a link's menu,
 * or off a link the line's ([rightClick]); over a link the pointer is a hand and the link's tool tip shows by it.
 *
 * With [onMakeTrigger], which a line of the game's own text has, or [copyable], a long press on a line that is not
 * blank, anywhere but on a link with a menu, opens a menu that offers to make a trigger from it, or to copy or share it
 * as it reads, and TalkBack offers making the trigger and copying the line as actions.
 *
 * TalkBack reads [readingPrefix], drawn beside the line by its caller, before [plain]; a copy leaves it out.
 *
 * With [onSelectFrom], that menu also offers to start a selection of lines at this one, as TalkBack's action "Select from
 * here" does; [onSelectTo] is TalkBack's action that takes a selection to this line. A [selected] line says so.
 */
@Composable
internal fun ConsoleLine(
    text: AnnotatedString,
    plain: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    blankLines: Settings.BlankLines = Settings.BlankLines.SHOW,
    singleLine: Boolean = false,
    links: LineLinks? = null,
    onMakeTrigger: (() -> Unit)? = null,
    copyable: Boolean = false,
    readingPrefix: String = "",
    selected: Boolean = false,
    onSelectFrom: (() -> Unit)? = null,
    onSelectTo: (() -> Unit)? = null,
) {
    val space = stringResource(R.string.console_blank_line_space)

    // The same Text either way, so the line's look and reading do not depend on whether it has links
    fun read(actions: List<CustomAccessibilityAction>) = Modifier.semantics {
        when {
            plain.isNotEmpty() || blankLines == Settings.BlankLines.SHOW -> this.text = AnnotatedString(readingPrefix + plain)

            blankLines == Settings.BlankLines.SPACE -> {
                this.text = AnnotatedString(" ")
                contentDescription = space
            }

            else -> hideFromAccessibility()
        }
        if (actions.isNotEmpty()) customActions = actions
        if (selected) this.selected = true
    }

    val spans = links?.spans.orEmpty()
    // A blank line alone matches nothing and has nothing to copy
    val makeTrigger by rememberUpdatedState(onMakeTrigger?.takeIf { plain.isNotBlank() })
    val offersTrigger = makeTrigger != null
    val offersCopy = copyable && plain.isNotBlank()
    val selectFrom by rememberUpdatedState(onSelectFrom?.takeIf { plain.isNotBlank() })
    val offersSelect = selectFrom != null
    val offersLineMenu = offersTrigger || offersCopy || offersSelect
    val selectToLabel = stringResource(R.string.console_select_to_here)
    val selectToAction = onSelectTo?.takeIf { plain.isNotBlank() }?.let { selectTo ->
        CustomAccessibilityAction(selectToLabel) {
            selectTo()
            true
        }
    }
    if (spans.isEmpty() && !offersLineMenu) {
        LineText(text, modifier.then(read(listOfNotNull(selectToAction))), color, style, singleLine)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var menu by remember { mutableStateOf<LinkMenu?>(null) }
    // Where along the line the line's own menu is open, in pixels
    var lineMenuAt by remember { mutableStateOf<Float?>(null) }
    var hovered by remember { mutableStateOf<LinkHover?>(null) }
    // The gestures read what the engine now says of a link, without starting again each time it answers
    val current by rememberUpdatedState(links)
    val context = LocalContext.current

    fun follow(link: Int, item: Int?): Boolean = (current?.follow(link, item) == true).also { sent ->
        if (!sent) Toast.makeText(context, R.string.input_not_sent, Toast.LENGTH_SHORT).show()
    }

    val linkActions = if (links != null) linkActions(links, plain, ::follow) { menu = LinkMenu(it, 0f) } else emptyList()
    val triggerLabel = stringResource(R.string.console_make_trigger)
    val triggerAction = CustomAccessibilityAction(triggerLabel) {
        makeTrigger?.invoke()
        true
    }.takeIf { offersTrigger }
    val copyLabel = stringResource(R.string.console_copy_line)
    val shareLabel = stringResource(R.string.console_share_line)
    val clipLabel = stringResource(R.string.app_name)
    val copied = stringResource(R.string.console_line_copied)
    val noShare = stringResource(R.string.console_line_no_share)

    fun copyLine() = copyText(context, clipLabel, plain, copied)

    fun shareLine() = shareText(context, plain, shareLabel, noShare)

    val copyAction = CustomAccessibilityAction(copyLabel) {
        copyLine()
        true
    }.takeIf { offersCopy }
    val selectFromLabel = stringResource(R.string.console_select_from_here)
    val selectFromAction = CustomAccessibilityAction(selectFromLabel) {
        selectFrom?.invoke()
        true
    }.takeIf { offersSelect }

    fun spanAt(position: Offset): LinkSpan? {
        val character = layout?.characterAt(position, plain.length) ?: return null
        return current?.spans?.firstOrNull { character >= it.start && character < it.end }
    }

    fun rightClickAt(position: Offset) {
        val link = spanAt(position)?.link
        when (rightClick(link != null, link?.let { current?.info(it) })) {
            RightClick.REVEAL -> link?.let { follow(it, null) }
            RightClick.LINK_MENU -> link?.let { menu = LinkMenu(it, position.x) }
            RightClick.LINE_MENU -> {}
            RightClick.NOTHING -> if (offersLineMenu) lineMenuAt = position.x
        }
    }

    val mouse = Modifier.pointerInput(spans, offersLineMenu) { mouseOnLine({ spanAt(it)?.link }, ::rightClickAt) { hovered = it } }
        .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
    val touches = mouse.pointerInput(spans, offersLineMenu) {
        detectTapGestures(
            onTap = { position ->
                spanAt(position)?.let { span -> if (current?.info(span.link)?.disabled != true) follow(span.link, null) }
            },
            onLongPress = { position ->
                val link = spanAt(position)?.link
                when {
                    link != null && current?.info(link)?.menu == true -> menu = LinkMenu(link, position.x)
                    offersLineMenu -> lineMenuAt = position.x
                }
            },
        )
    }
    // A menu shows what the engine now says of its link, and goes once the line no longer has the link or it no menu
    val open = menu
    val openSpan = open?.let { m -> spans.firstOrNull { it.link == m.link } }
    val openInfo = open?.let { links?.info(it.link) }?.takeIf { it.menu }
    if (open != null && (openSpan == null || openInfo == null)) SideEffect { menu = null }
    Box(modifier) {
        LineText(text, Modifier.fillMaxWidth().then(touches).then(read(linkActions + listOfNotNull(triggerAction, copyAction, selectFromAction, selectToAction))), color, style, singleLine) { layout = it }
        if (open != null && openSpan != null && openInfo != null) {
            LinkMenuView(openInfo, open.x, linkLabel(openInfo, plain.substring(openSpan.start, openSpan.end)), ::follow) { menu = null }
        }
        hovered?.takeIf { menu == null && lineMenuAt == null }?.let { hover ->
            links?.info(hover.link)?.tooltip?.takeIf { it.isNotBlank() }?.let { LinkTooltip(it, hover.at) }
        }
        lineMenuAt?.let { x ->
            DropdownMenu(expanded = true, onDismissRequest = { lineMenuAt = null }, offset = DpOffset(with(LocalDensity.current) { x.toDp() }, 0.dp)) {
                if (offersCopy) {
                    DropdownMenuItem(
                        text = { Text(copyLabel) },
                        onClick = {
                            lineMenuAt = null
                            copyLine()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(shareLabel) },
                        onClick = {
                            lineMenuAt = null
                            shareLine()
                        },
                    )
                }
                if (offersSelect) {
                    DropdownMenuItem(
                        text = { Text(selectFromLabel) },
                        onClick = {
                            lineMenuAt = null
                            selectFrom?.invoke()
                        },
                    )
                }
                if (offersTrigger) {
                    DropdownMenuItem(
                        text = { Text(triggerLabel) },
                        onClick = {
                            lineMenuAt = null
                            makeTrigger?.invoke()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LineText(text: AnnotatedString, modifier: Modifier, color: Color, style: TextStyle, singleLine: Boolean, onLayout: (TextLayoutResult) -> Unit = {}) {
    Text(
        text,
        modifier,
        color = color,
        style = style,
        softWrap = !singleLine,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        onTextLayout = onLayout,
    )
}

/** The character of a line drawn at [position], if one is: not the gap past its end. */
private fun TextLayoutResult.characterAt(position: Offset, length: Int): Int? {
    // The caret nearest the point is on one side or the other of the character under it
    val caret = getOffsetForPosition(position)
    return listOf(caret, caret - 1).firstOrNull { it in 0 until length && getBoundingBox(it).contains(position) }
}

/**
 * TalkBack's actions for the [links] of a line of [plain] text: following each with [follow], unless the engine said it
 * is disabled, and opening the menu of each that has one with [openMenu]. An action that could not be sent fails.
 */
@Composable
private fun linkActions(
    links: LineLinks,
    plain: String,
    follow: (link: Int, item: Int?) -> Boolean,
    openMenu: (link: Int) -> Unit,
): List<CustomAccessibilityAction> = links.spans.flatMap { span ->
    val info = links.info(span.link)
    var label = linkLabel(info, plain.substring(span.start, span.end))
    if (links.states && info != null) {
        if (info.visited) label = stringResource(R.string.console_link_visited, label)
        if (info.selected) label = stringResource(R.string.console_link_selected, label)
    }
    buildList<CustomAccessibilityAction> {
        if (info?.disabled != true) {
            add(
                CustomAccessibilityAction(stringResource(R.string.console_link_follow, label)) { follow(span.link, null) },
            )
        }
        if (info?.menu == true) {
            add(
                CustomAccessibilityAction(stringResource(R.string.console_link_menu, label)) {
                    openMenu(span.link)
                    true
                },
            )
        }
    }
}

/** A mouse over [link], at [at] in the line's pixels. */
private class LinkHover(val link: Int, val at: Offset)

/**
 * A mouse on a line, as desktop's console takes one: a right click is handed to [onRightClick] where it is let go, as
 * desktop's acts on the release, and is never a tap; coming onto a link ([linkAt]) hands [onHover] it and where, and
 * leaving it null. Touches and a stylus are left to the line's taps.
 */
private suspend fun PointerInputScope.mouseOnLine(linkAt: (Offset) -> Int?, onRightClick: (Offset) -> Unit, onHover: (LinkHover?) -> Unit) {
    var rightPressed = false
    var over: Int? = null

    fun hover(link: Int?, at: Offset) {
        if (link == over) return
        over = link
        onHover(link?.let { LinkHover(it, at) })
    }
    // Restarted for new links, the tool tip of one the line no longer has would otherwise stay
    onHover(null)
    awaitPointerEventScope {
        while (true) {
            // Before the taps, which would take a right click's press as a tap
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            if (change.type != PointerType.Mouse) continue
            when (event.type) {
                PointerEventType.Press -> if (event.buttons.isSecondaryPressed) {
                    rightPressed = true
                    change.consume()
                }

                PointerEventType.Release -> if (rightPressed) {
                    rightPressed = false
                    change.consume()
                    onRightClick(change.position)
                }

                PointerEventType.Enter, PointerEventType.Move -> hover(linkAt(change.position), change.position)

                PointerEventType.Exit -> hover(null, change.position)
            }
        }
    }
}

/**
 * A link's [tooltip] by the mouse at [at], as desktop's console shows one: plain text that takes no focus, so the
 * keys go on to the command line.
 */
@Composable
private fun LinkTooltip(tooltip: String, at: Offset) {
    val below = with(LocalDensity.current) { TOOLTIP_BELOW_POINTER.roundToPx() }
    val place = remember(at, below) { ByPointer(IntOffset(at.x.roundToInt(), at.y.roundToInt()), below) }
    Popup(popupPositionProvider = place, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.inverseSurface) {
            Text(tooltip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Clear of the mouse's arrow, as a desktop's tool tip is
private val TOOLTIP_BELOW_POINTER = 20.dp

/**
 * A tool tip's place: [below] pixels under [pointer], a point of its anchor, or above it at the window's bottom, and
 * within the window.
 */
private class ByPointer(private val pointer: IntOffset, private val below: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + pointer.x
        val y = anchorBounds.top + pointer.y
        val top = if (y + below + popupContentSize.height <= windowSize.height) y + below else y - popupContentSize.height
        return IntOffset(
            x.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
            top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
        )
    }
}

/** The menu of the link [link], open below its line [x] pixels from the line's start. */
private class LinkMenu(val link: Int, val x: Float)

/**
 * The menu of [link], called [label], open [x] pixels from the line's start: its items under its title, an item greyed
 * out where it is not enabled and a separator a line. Picking one follows the link with it.
 */
@Composable
private fun LinkMenuView(link: EngineMessage.Link, x: Float, label: String, follow: (link: Int, item: Int?) -> Boolean, onDismiss: () -> Unit) {
    val offset = with(LocalDensity.current) { x.toDp() }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = DpOffset(offset, 0.dp)) {
        if (link.menuTitle.isNotEmpty()) {
            Text(
                link.menuTitle,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        link.items.forEachIndexed { i, item ->
            if (item.separator) {
                HorizontalDivider()
            } else {
                DropdownMenuItem(
                    text = { Text(item.label.ifEmpty { label }) },
                    onClick = {
                        onDismiss()
                        follow(link.link, i)
                    },
                    enabled = item.enabled,
                )
            }
        }
    }
}
