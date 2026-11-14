package org.mudlet.android.ui.game

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings

/** Something for TalkBack to say. [serial] numbers them, so the same words twice are said twice. */
class Announcement(val text: String, val assertive: Boolean, val serial: Long)

/**
 * What the app has TalkBack say, as Mudlet's desktop console does: new game lines, while TalkBack is on and "Read new
 * text aloud" holds, and what a script's `announce()` asks for. Lines that arrive close together are read as one
 * announcement, and a burst as its count and its last lines. [AnnouncementRegion] says [current]. Main thread only.
 */
object Announcer {
    // Lines this close together are one announcement; a steady stream is still read every MAX_WAIT_MS
    private const val QUIET_MS = 300L
    private const val MAX_WAIT_MS = 2000L

    // A burst of more lines than BURST_LINES is read as its count and its last BURST_TAIL lines
    private const val BURST_LINES = 5
    private const val BURST_TAIL = 3

    // An announcement this soon after the last is added to it: the live region would show only the later one
    private const val MERGE_MS = 200L

    /** The last announcement, which [AnnouncementRegion] shows; null before the first. */
    var current by mutableStateOf<Announcement?>(null)
        private set

    private var systemTouchExploration by mutableStateOf(false)

    /** Takes the place of the system's touch exploration (TalkBack) when not null: the tests' switch. */
    @VisibleForTesting
    var touchExplorationOverride by mutableStateOf<Boolean?>(null)

    /** TalkBack, or another screen reader that explores by touch, is on. */
    val touchExploration: Boolean get() = touchExplorationOverride ?: systemTouchExploration

    private val main = Handler(Looper.getMainLooper())
    private var resources: Resources? = null
    private var saidAt = 0L

    // The lines waiting to be read: the last BURST_LINES of them, and how many there are
    private val waiting = ArrayDeque<String>()
    private var waitingCount = 0
    private var waitingSince = 0L

    fun init(context: Context) {
        if (resources != null) return
        val app = context.applicationContext
        resources = app.resources
        val accessibility = app.getSystemService(AccessibilityManager::class.java)
        systemTouchExploration = accessibility.isTouchExplorationEnabled
        // Called on the main thread
        accessibility.addTouchExplorationStateChangeListener { systemTouchExploration = it }
    }

    /** Game lines as they arrive, not those replayed to a UI that attached. */
    fun newLines(lines: List<String>) {
        if (!reading()) return
        for (line in lines) {
            if (line.isBlank()) continue
            if (waiting.size == BURST_LINES) waiting.removeFirst()
            waiting.addLast(line)
            waitingCount++
        }
        if (waitingCount == 0) return
        val now = SystemClock.uptimeMillis()
        if (waitingSince == 0L) waitingSince = now
        main.removeCallbacks(readWaiting)
        main.postAtTime(readWaiting, minOf(now + QUIET_MS, waitingSince + MAX_WAIT_MS))
    }

    /** What a script's `announce()` asks for; [assertive] interrupts what TalkBack is saying. */
    fun announce(text: String, assertive: Boolean) = say(text, assertive)

    private fun reading() = touchExploration && Settings.readNewText.value

    private val readWaiting = Runnable {
        val count = waitingCount
        val lines = waiting.toList()
        waiting.clear()
        waitingCount = 0
        waitingSince = 0L
        val res = resources
        if (!reading() || count == 0 || res == null) return@Runnable
        val text = if (count <= BURST_LINES) {
            lines.joinToString("\n")
        } else {
            res.getQuantityString(R.plurals.announce_burst, count, count, lines.takeLast(BURST_TAIL).joinToString("\n"))
        }
        say(text, assertive = false)
    }

    private fun say(text: String, assertive: Boolean) {
        if (text.isBlank()) return
        val now = SystemClock.uptimeMillis()
        val last = current
        current = if (last != null && now - saidAt < MERGE_MS) {
            Announcement(last.text + "\n" + text, last.assertive || assertive, last.serial)
        } else {
            Announcement(text, assertive, (last?.serial ?: 0L) + 1)
        }
        saidAt = now
    }
}

/**
 * Has TalkBack say [Announcer.current], through two live regions, each a node of one pixel that shows nothing: an
 * announcement goes to the one the last did not use, so a repeat is a change TalkBack reads. Placed last in TalkBack's
 * order, where swiping to it reads the last announcement again.
 */
@Composable
fun AnnouncementRegion(modifier: Modifier = Modifier) {
    val announcement = Announcer.current
    // Side by side: TalkBack is told only of nodes that others drawn over them do not cover
    Row(modifier) {
        for (slot in 0..1) {
            val mine = announcement?.takeIf { it.serial % 2 == slot.toLong() }
            Box(
                Modifier.size(1.dp).semantics {
                    traversalIndex = 1f
                    liveRegion = if (mine?.assertive == true) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    if (mine != null) contentDescription = mine.text
                },
            )
        }
    }
}
