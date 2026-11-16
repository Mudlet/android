package org.mudlet.android.ui.game

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mudlet.android.R
import org.mudlet.android.protocol.Edges
import org.mudlet.android.protocol.LabelFill
import org.mudlet.android.protocol.LabelStyle
import org.mudlet.android.protocol.PlacedWindow
import org.mudlet.android.protocol.TextBoxContent
import org.mudlet.android.ui.EngineClient
import kotlin.math.min

// What the player types is sent once they have stopped for this long, not at each key
private const val TYPING_SETTLES_MS = 300L

// QPlainTextEdit's frame, where its style sheet draws no border, and the margin of its document inside it, in
// Mudlet's pixels
private const val FRAME_PX = 1f
private const val DOCUMENT_MARGIN_PX = 4f

// A text box's text is never drawn smaller than this, however small its window is scaled
private const val MIN_TEXT_BOX_SP = 9f

/**
 * A text box from createTextEdit() (docs/phase-f.md, "Text boxes"): its [content]'s text in a text field at [placed]'s
 * place, styled by its style sheet as a label's is, scaled as its window is. The player types in it unless it is
 * read-only, and once they stop, or leave it, or tap what may run a script, all of what they typed goes to the engine
 * with `setTextBoxText`; a script's new text, which [edit] (ScriptWindows.textBoxEdits) tells of, replaces the
 * field's. It is touched within 48 dp around it, and read by its placeholder, or its name, and whether it is read-only.
 */
@Composable
internal fun ScriptTextBox(placed: PlacedWindow, content: TextBoxContent?, edit: Long?, density: Density, modifier: Modifier = Modifier) {
    val window = placed.window
    val name = window.name
    val rect = placed.rect
    val full = placed.full
    val scale = min(if (window.w > 0) full.w / window.w else 1f, if (window.h > 0) full.h / window.h else 1f)
    val touch = rect.grownTo(with(density) { MIN_TOUCH_SIZE.toPx() })
    val styleSheet = content?.styleSheet.orEmpty()
    val style = remember(styleSheet) { LabelStyle.parse(styleSheet, LabelStyle.TEXT_BOX_SELECTORS) }
    // Not typed in before the engine has said what it holds
    val readOnly = content?.readOnly ?: true
    val scriptText = content?.text

    var value by rememberSaveable(name, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(scriptText.orEmpty())) }
    // The script's text the field last took: the player's own, which the engine does not send back, never changes it, so
    // what they typed after what was sent stays. Saved with the field, so that a script's text while it was away shows
    var shownEdit by rememberSaveable(name) { mutableStateOf(edit) }
    // Also when it turns read-only, which drops what the player typed that was not yet sent. Taken here, as the field is
    // drawn with it, not in an effect after: the field's editor would have the old text until the next frame, and an
    // edit of the keyboard's that changes the field in between (the composing region it sets on a word, or a key) would
    // put that back, and a tap on a label would send it, as what the player typed
    if (scriptText != null) {
        if ((edit != shownEdit || readOnly) && TextBoxContent.plain(value.text) != scriptText) {
            value = TextFieldValue(scriptText, TextRange(scriptText.length))
        }
        if (edit != shownEdit) shownEdit = edit
    }

    // What the player typed that the engine has not been sent, which the profile's ScriptWindowsClient holds to send
    // before a tap that may run a script; sent again to the same instance should the link drop
    val session = EngineClient.front
    val client = session.windows
    val unsent = value.text.takeIf { scriptText != null && !readOnly && TextBoxContent.plain(it) != scriptText }
    SideEffect { client.typing(name, unsent) }
    LaunchedEffect(client, unsent, session.attaches) {
        if (unsent == null) return@LaunchedEffect
        delay(TYPING_SETTLES_MS)
        client.flushTyping(name)
    }
    // Nor is it lost when the field goes, as on a turn of the screen, which would otherwise draw the engine's text again
    DisposableEffect(client, name) {
        onDispose {
            client.flushTyping(name)
            client.typing(name, null)
        }
    }

    val colours = MaterialTheme.colorScheme
    val textColour = style.color?.let { Color(it) } ?: colours.onSurface
    val textStyle = TextStyle(
        color = textColour,
        fontSize = with(density) { (fontPx(style, window) * scale).toSp() }.value.coerceAtLeast(MIN_TEXT_BOX_SP).sp,
        fontWeight = style.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) },
        fontStyle = if (style.italic == true) FontStyle.Italic else null,
        fontFamily = style.fontFamily?.let(::fontFamily),
    )
    val placeholder = content?.placeholder.orEmpty()
    val named = placeholder.ifBlank { name }
    val label = if (readOnly) stringResource(R.string.text_box_read_only, named) else named
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val tabMovesFocus = content?.tabMovesFocus == true
    val fill = style.background ?: LabelFill.Solid(colours.surface.toArgb())
    val styledBorder = style.drawnBorderWidth > 0f
    // A field is seen to be one, and focused, where its style sheet draws no border
    val frameStyle = if (styledBorder) style else style.copy(borderColor = (if (focused) colours.primary else colours.outline).toArgb())
    val frameWidth = if (styledBorder) style.drawnBorderWidth * scale else with(density) { (if (focused) 2.dp else 1.dp).toPx() }
    val frame = if (styledBorder) style.drawnBorderWidth else FRAME_PX
    val inner = with(style.padding) {
        val extra = frame + DOCUMENT_MARGIN_PX
        Edges(top + extra, right + extra, bottom + extra, left + extra)
    }

    Box(
        modifier.placeAt(touch, density).semantics {
            isTraversalGroup = true
            traversalIndex = WINDOWS_TRAVERSAL_INDEX
        },
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxSize().onFocusChanged { if (!it.isFocused) client.flushTyping(name) }.onPreviewKeyEvent { event ->
                // Both halves of the key are taken, so the field types no tab
                if (!tabMovesFocus || event.key != Key.Tab) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                true
            },
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            interactionSource = interactions,
            cursorBrush = SolidColor(textColour),
            decorationBox = { field ->
                Box(
                    Modifier
                        // The field's name, which an editable field may not carry itself; the screen reader reads it
                        // with the field, as part of it. Before placeIn, so the node is the field's size: inside the
                        // frame and margins a small box leaves it none, and a node of no size is not read
                        .semantics { contentDescription = label }
                        .placeIn(rect.x - touch.x, rect.y - touch.y, rect.w, rect.h)
                        .edgePadding(style.margin, scale, scale, density)
                        .drawBehind { drawLabelFrame(fill, frameStyle, frameWidth, style.borderRadius * scale, scale, null, null) }
                        .edgePadding(inner, scale, scale, density),
                ) {
                    if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, Modifier.clearAndSetSemantics {}, style = textStyle.copy(color = textColour.copy(alpha = 0.7f)))
                    }
                    field()
                }
            },
        )
    }
}
