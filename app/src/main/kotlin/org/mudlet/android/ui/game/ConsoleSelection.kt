package org.mudlet.android.ui.game

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.mudlet.android.R
import org.mudlet.android.protocol.LineSelection
import kotlin.math.roundToInt

/** Keeps a selection of the main console's lines across a rotation; a switch of game keeps each game's own. */
internal val LineSelectionSaver = listSaver<LineSelection?, Long>(
    save = { if (it == null) emptyList() else listOf(it.anchor, it.extent) },
    restore = { saved -> if (saved.size == 2) LineSelection(saved[0], saved[1]) else null },
)

/** A handle of a selection as drawn, Compose's own text selection handle's size. */
internal val HANDLE_SIZE = 25.dp

/** Where a handle of a selection is touched: its drop and around it, as far as a finger's target reaches. */
internal val HANDLE_TOUCH_SIZE = 48.dp

/** The handles of a selection, for tests: the one under its first line and the one under its last. */
internal const val SELECTION_START_HANDLE_TAG = "selectionStartHandle"
internal const val SELECTION_END_HANDLE_TAG = "selectionEndHandle"

/** Room under a list's last line for its handle's touch, so the handle of the newest line can be dragged. */
internal val HANDLE_ROOM = HANDLE_SIZE / 2 + HANDLE_TOUCH_SIZE / 2

/** Keeps the actions a selection had while their labels stay, so the toolbar and TalkBack's actions are not built again. */
internal val SameSelectionActions = object : SnapshotMutationPolicy<List<SelectionAction>> {
    override fun equivalent(a: List<SelectionAction>, b: List<SelectionAction>) = a.map { it.label } == b.map { it.label }
}

/**
 * Something to do with the selected lines: [label], which the toolbar and TalkBack's actions show, and [perform]; with
 * [overflow], in the toolbar's overflow whatever the room, as Android's own selection puts the apps that take text.
 */
internal class SelectionAction(val label: String, val overflow: Boolean = false, val perform: () -> Unit)

/** An app that takes selected text to work on, a translator say ([Intent.ACTION_PROCESS_TEXT]): its [label] and activity. */
internal class TextProcessor(val label: String, val component: ComponentName)

private fun processTextIntent() = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

/**
 * The apps that take selected text to work on, as Android's own text selection offers them: those whose activity
 * anyone, or this app, may start. The manifest's `<queries>` lets the app see them.
 */
internal fun textProcessors(context: Context): List<TextProcessor> {
    val packages = context.packageManager
    val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packages.queryIntentActivities(processTextIntent(), PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packages.queryIntentActivities(processTextIntent(), 0)
    }
    return found.map { it.activityInfo }
        .filter { it.exported && (it.permission == null || context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED) }
        .map { TextProcessor(it.loadLabel(packages).toString(), ComponentName(it.packageName, it.name)) }
}

/** Hands [text], which it may not change, to [processor]; says [gone] where it is no longer there to take it. */
internal fun processText(context: Context, processor: TextProcessor, text: String, gone: String) {
    val intent = processTextIntent().setComponent(processor.component)
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
    }
}

/**
 * Android's own floating text toolbar over a selection, the one a text field shows: [actions] as its items in their
 * order, those that fit shown and the rest in its overflow, beside the selection's [span] in the window, which it
 * follows. It shows while it is in the composition; [onEnded] is called if Android ends it itself.
 */
@Composable
internal fun SelectionToolbar(actions: List<SelectionAction>, span: () -> Rect?, onEnded: () -> Unit) {
    val view = LocalView.current
    val latestActions by rememberUpdatedState(actions)
    val latestSpan by rememberUpdatedState(span)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val holder = remember { arrayOfNulls<ActionMode>(1) }
    DisposableEffect(view) {
        var shown = emptyList<SelectionAction>()
        var ending = false
        val callback = object : ActionMode.Callback2() {
            fun fill(menu: Menu) {
                menu.clear()
                shown = latestActions
                shown.forEachIndexed { i, action ->
                    menu.add(Menu.NONE, i, i, action.label).setShowAsAction(if (action.overflow) MenuItem.SHOW_AS_ACTION_NEVER else MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                fill(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                fill(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                shown.getOrNull(item.itemId)?.perform?.invoke()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                holder[0] = null
                if (!ending) latestOnEnded()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                val rect = latestSpan() ?: return super.onGetContentRect(mode, view, outRect)
                val at = IntArray(2)
                view.getLocationInWindow(at)
                outRect.set(rect.left.roundToInt() - at[0], rect.top.roundToInt() - at[1], rect.right.roundToInt() - at[0], rect.bottom.roundToInt() - at[1])
            }
        }
        holder[0] = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        // Bite: Android hides the toolbar for a while, as it does when the selection moves or the window loses focus
        holder[0]?.let { mode -> view.postDelayed({ mode.hide(3000) }, 2000) }
        onDispose {
            ending = true
            holder[0]?.finish()
        }
    }
    val labels = actions.map { it.label }
    LaunchedEffect(labels) { holder[0]?.invalidate() }
    LaunchedEffect(Unit) { snapshotFlow { latestSpan() }.distinctUntilChanged().collect { holder[0]?.invalidateContentRect() } }
}

/**
 * Where a handle of a selection is drawn: the [start] one under its first line or the other under its last, its drop's
 * top left corner at [x], [y] in the console, and on which side its point is.
 */
internal class HandlePlace(val start: Boolean, val pointLeft: Boolean, val x: Float, val y: Float)

/**
 * A handle of a selection, drawn as Android's text selection draws one in [color]: a drop whose square corner, at its
 * top on the [HandlePlace.pointLeft] side or the other, points at the selected line above it.
 */
@Composable
internal fun SelectionHandle(place: HandlePlace, color: Color) {
    val tag = if (place.start) SELECTION_START_HANDLE_TAG else SELECTION_END_HANDLE_TAG
    Canvas(Modifier.absoluteOffset { IntOffset(place.x.roundToInt(), place.y.roundToInt()) }.size(HANDLE_SIZE).testTag(tag)) {
        val r = size.width / 2
        drawCircle(color, r, center)
        drawRect(color, topLeft = if (place.pointLeft) Offset.Zero else Offset(r, 0f), size = Size(r, r))
    }
}

/**
 * What a selection of the main console's lines, [selection], shows besides its lines: TalkBack says how many are
 * selected, as [countOf] counts them, once each time it changes, and one none of whose lines is left closes with
 * [onClose]. With [toolbar], Android's floating text toolbar of [actions] shows beside [span], and closes it too if
 * Android ends it, as another text selection starting does.
 */
@Composable
internal fun LineSelectionShown(
    selection: State<LineSelection?>,
    countOf: (LineSelection) -> Int,
    actions: State<List<SelectionAction>>,
    toolbar: Boolean,
    span: () -> Rect?,
    onClose: () -> Unit,
) {
    val current = selection.value ?: return
    val count = countOf(current)
    val countText = pluralStringResource(R.plurals.console_selection_count, count, count)
    LaunchedEffect(current) { Announcer.announce(countText, assertive = false) }
    LaunchedEffect(count == 0) { if (count == 0) onClose() }
    if (toolbar && count > 0) SelectionToolbar(actions.value, span, onEnded = onClose)
}
