package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * What of the profile's script windows the app draws, of those packages made (docs/architecture.md, "Packages'
 * interfaces on a phone"): a package whose details do not declare an interface that fits a phone ([ScriptWindow.mobile],
 * [LookSetters.mobile]) has its windows and its borders left out, unless the player shows them anyway, by the package's
 * name in [shownAnyway]. A mapper, and each window that holds a mapper or a window drawn for a package of its own, stays.
 * A window left out takes the windows in it with it. A border left out gives its strip to the console, unless a window
 * drawn lies in it.
 *
 * The engine is told nothing of this: what scripts see comes from the state as it is ([ScriptWindows]), for which the
 * app lays out what Mudlet is told, as though every window were drawn. Itself when nothing is left out.
 */
fun ScriptWindows.drawn(shownAnyway: Set<String>): ScriptWindows {
    val hidden = hiddenWindows(shownAnyway)
    val kept = if (hidden.isEmpty()) windows else windows.filter { it.key !in hidden }
    val area = mainArea?.let { borderless(it, kept, shownAnyway) }
    if (hidden.isEmpty() && area === mainArea) return this
    return copy(windows = kept, mainArea = area)
}

/** The packages that made a window or a border the app leaves out unless the player shows them anyway. */
val ScriptWindows.undeclaredUi: Set<String>
    get() = buildSet {
        for (window in windows) if (!window.mobile) addAll(window.packages)
        for (look in mainArea?.looks.orEmpty()) if (!look.mobile) addAll(look.packages)
    }

// Only what packages made: the package manager offers no switch for anything else
private fun hiddenOwn(packages: List<String>, mobile: Boolean, shownAnyway: Set<String>) = !mobile && packages.isNotEmpty() && packages.none { it in shownAnyway }

private fun ScriptWindows.hiddenWindows(shownAnyway: Set<String>): Set<WindowKey> {
    if (windows.none { hiddenOwn(it.packages, it.mobile, shownAnyway) }) return emptySet()
    val byKey = windows.associateBy { it.key }
    fun parentOf(window: ScriptWindow) = window.parentKind?.let { byKey[WindowKey(window.parent, it)] }

    // A mapper, and a window drawn for its own package, keep what holds them
    val holders = HashSet<WindowKey>()
    for (window in windows) {
        val anchor = window.kind == WindowKind.MAPPER || (window.packages.isNotEmpty() && !hiddenOwn(window.packages, window.mobile, shownAnyway))
        if (!anchor) continue
        var parent = parentOf(window)
        while (parent != null && holders.add(parent.key)) parent = parentOf(parent)
    }
    // Parents come before the windows they hold
    val hidden = HashSet<WindowKey>()
    for (window in windows) {
        val parent = parentOf(window)
        val own = hiddenOwn(window.packages, window.mobile, shownAnyway) && window.key !in holders
        if (own || (parent != null && parent.key in hidden)) hidden.add(window.key)
    }
    return hidden
}

// The main area with the text rectangle grown over each border left out: to the area's edge, or to the nearest window
// drawn in that border's strip
private fun borderless(area: EngineMessage.MainArea, kept: List<ScriptWindow>, shownAnyway: Set<String>): EngineMessage.MainArea {
    val hiddenBorders = area.looks.filter { it.look in BORDERS && hiddenOwn(it.packages, it.mobile, shownAnyway) }.mapTo(HashSet()) { it.look }
    if (hiddenBorders.isEmpty() || area.textW <= 0 || area.textH <= 0) return area
    val inArea = kept.filter { it.parentKind == null && !it.inDock && it.shown && it.w > 0 && it.h > 0 }
    var left = area.textX
    var top = area.textY
    var right = area.textX + area.textW
    var bottom = area.textY + area.textH
    if (ProfileLook.BORDER_TOP in hiddenBorders) {
        top = inArea.filter { it.y + it.h / 2 < area.textY }.maxOfOrNull { it.y + it.h }?.let { min(max(it, 0), top) } ?: 0
    }
    if (ProfileLook.BORDER_BOTTOM in hiddenBorders) {
        bottom = inArea.filter { it.y + it.h / 2 > area.textY + area.textH }.minOfOrNull { it.y }?.let { max(it, bottom) } ?: max(area.h, bottom)
    }
    if (ProfileLook.BORDER_LEFT in hiddenBorders) {
        left = inArea.filter { it.x + it.w / 2 < area.textX }.maxOfOrNull { it.x + it.w }?.let { min(max(it, 0), left) } ?: 0
    }
    if (ProfileLook.BORDER_RIGHT in hiddenBorders) {
        right = inArea.filter { it.x + it.w / 2 > area.textX + area.textW }.minOfOrNull { it.x }?.let { max(it, right) } ?: max(area.w, right)
    }
    if (left == area.textX && top == area.textY && right == area.textX + area.textW && bottom == area.textY + area.textH) return area
    return area.copy(textX = left, textY = top, textW = right - left, textH = bottom - top)
}

private val BORDERS = setOf(ProfileLook.BORDER_TOP, ProfileLook.BORDER_RIGHT, ProfileLook.BORDER_BOTTOM, ProfileLook.BORDER_LEFT)
