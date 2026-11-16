package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in the game area's pixels, its origin at the area's top left. */
data class PxRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    /** The part of this inside [other], or null for none. */
    fun intersect(other: PxRect): PxRect? {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r > left && b > top) PxRect(left, top, r - left, b - top) else null
    }
}

/** A size in Mudlet's pixels. */
data class MudletSize(val w: Int, val h: Int)

/** Where the app put a user window, in Mudlet's pixels from the game area's top left: `placeUserWindow`'s fields. */
data class Placement(val x: Int, val y: Int, val w: Int, val h: Int, val floating: Boolean)

/** A window to draw at [rect], cut to what its parents show; [full] is where it is before that cut. */
data class PlacedWindow(val window: ScriptWindow, val rect: PxRect, val full: PxRect)

/** A docked user window's title row, with its close button, above its content. */
data class DockHeader(val window: ScriptWindow, val rect: PxRect)

/**
 * An MXP frame's tab header ([ScriptWindow.frameTabs]), drawn at [rect] along the top of the page in front, or of the
 * pane that page is open in.
 */
data class FrameTabStrip(val frame: ScriptWindow, val tabs: ScriptWindow.FrameTabs, val rect: PxRect)

/** The fitted layout, or the stacked; [AUTO] picks fitted from [WindowLayout.FITTED_MIN_WIDTH_DP] up. */
enum class LayoutMode { AUTO, FITTED, STACKED }

/**
 * Where the game screen draws the main console and the script windows (docs/phase-f.md, "Layout"). Made by [of].
 *
 * Fitted, the main window's area is drawn as on the desktop, scaled: the console in `mainArea`'s text rectangle, the
 * area's windows over it, docked user windows in columns or rows beside it. Stacked, Mudlet is given the area's size
 * in landscape; the console fills the width, with the windows over its text over it, those of the top and bottom
 * borders above and below it, and the consoles and maps of the left and right borders and the docks are pages. A page
 * shows in a pane over the console, chosen by the window switcher; floating user windows, docks beyond the room for
 * them, and the docked map are pages in either layout.
 *
 * Only the page in front of an MXP frame's tab header is shown, so only it is placed, with the header's tabs above it.
 */
data class WindowLayout(
    val fitted: Boolean,
    /** Screen pixels per Mudlet pixel: the app console's character width over Mudlet's; null while Mudlet's is unknown. */
    val scale: Double?,
    /** What to give Mudlet with `setMainWindowSize`; null while [scale] is. */
    val mainWindowSize: MudletSize?,
    val console: PxRect,
    /** Every window to draw outside the pane, children after their parents, each parent's by `stack`. */
    val windows: List<PlacedWindow>,
    val dockHeaders: List<DockHeader>,
    /** The windows the switcher offers, in the engine's order. */
    val pages: List<ScriptWindow>,
    /** The selected page's pane, its header row at the top, or null for no page selected. */
    val pane: PxRect?,
    /** The selected page's window and its children, inside [pane] below the header, and below its [paneFrameTabs]. */
    val paneWindows: List<PlacedWindow>,
    /** Where the user windows are, by name, for `placeUserWindow`; empty while [scale] is null. */
    val placements: Map<String, Placement>,
    /** The tab headers of the MXP frames in [windows], each above the page in front, drawn over the windows. */
    val frameTabs: List<FrameTabStrip> = emptyList(),
    /**
     * The tab headers drawn in the pane: first that of the MXP frame whose page in front is open in it, at its top below
     * its header, then those of the MXP frames in [paneWindows], each above its page in front.
     */
    val paneFrameTabs: List<FrameTabStrip> = emptyList(),
    /**
     * The page open in the pane: [Area.selectedPage], or, for a page of an MXP frame's tabs that another page has
     * come in front of, that one; null for none.
     */
    val selectedPage: WindowKey? = null,
) {
    // Bite: as main's mapperShown(), any shown mapper, a page no one opened too
    val mapperDrawn: Boolean get() = (windows.map { it.window } + paneWindows.map { it.window } + pages).any { it.kind == WindowKind.MAPPER }

    /** The size of the game area, in pixels, and what the layout depends on from the app. */
    data class Area(
        val w: Float,
        val h: Float,
        val fitted: Boolean,
        /** The width of a character of the app's console, in pixels. */
        val appCharWidth: Float,
        /** The height of a dock's title row, of the pane's header and the least of an MXP frame's tab header. */
        val headerHeight: Float,
        val selectedPage: WindowKey?,
    )

    companion object {
        /** The narrowest game area, in dp, that [LayoutMode.AUTO] draws fitted. */
        const val FITTED_MIN_WIDTH_DP = 600

        /** Docks take up to this share of the area's width (left and right) or height (top and bottom). */
        private const val DOCK_SHARE = 0.25f
        private const val MAX_DOCKS_PER_AXIS = 2

        // Stacked, the windows above and below the console take at most this share of the area's height
        private const val MAX_BANDS_SHARE = 0.5f

        // The height of an MXP frame's tab header in Mudlet's pixels, which its pages' geometry is below
        private const val FRAME_TABS_HEIGHT = 30f

        /** Whether [mode] draws a game area [widthDp] wide fitted. */
        fun fitted(mode: LayoutMode, widthDp: Float): Boolean = when (mode) {
            LayoutMode.FITTED -> true
            LayoutMode.STACKED -> false
            LayoutMode.AUTO -> widthDp >= FITTED_MIN_WIDTH_DP
        }

        fun of(state: ScriptWindows, area: Area): WindowLayout {
            val whole = PxRect(0f, 0f, area.w, area.h)
            val scale = state.charWidth?.let { area.appCharWidth / it.estimate }?.takeIf { it > 0 && area.w > 0 && area.h > 0 }
            val tree = Tree(state.windows)
            val pages = ArrayList<ScriptWindow>()
            val placed = ArrayList<PlacedWindow>()
            // What may show of each window placed, for its tab header should it be an MXP frame's page
            val clips = HashMap<WindowKey, PxRect>()
            val headers = ArrayList<DockHeader>()
            val placements = HashMap<String, Placement>()
            val mainArea = state.mainArea?.takeIf { it.w > 0 && it.h > 0 }
            val top = tree.children(null)

            // [full] where the window would be, [clip] what may show of it
            fun place(window: ScriptWindow, full: PxRect, clip: PxRect) {
                val shown = full.intersect(clip) ?: return
                placed.add(PlacedWindow(window, shown, full))
                clips[window.key] = clip
                tree.placeChildren(window, full, shown, placed, clips)
            }

            fun placeUserWindow(window: ScriptWindow, rect: PxRect) {
                if (scale == null) return
                placements[window.name] = Placement(
                    (rect.x / scale).roundToInt(),
                    (rect.y / scale).roundToInt(),
                    (rect.w / scale).roundToInt().coerceAtLeast(1),
                    (rect.h / scale).roundToInt().coerceAtLeast(1),
                    window.dock?.floating ?: false,
                )
            }

            val docks = top.filter { it.inDock && it.kind == WindowKind.USER_WINDOW && it.shown }
            val inArea = top.filter { !it.inDock && it.shown }
            val console: PxRect
            val pane: PxRect
            val mainWindowSize: MudletSize?
            if (area.fitted) {
                var main = whole
                val perAxis = HashMap<Boolean, Int>()
                for (dock in docks) {
                    val side = dock.dock?.area ?: "right"
                    val horizontal = side == "left" || side == "right"
                    val n = perAxis[horizontal] ?: 0
                    if (dock.dock?.floating != false || n >= MAX_DOCKS_PER_AXIS) {
                        pages.add(dock)
                        continue
                    }
                    perAxis[horizontal] = n + 1
                    val rect = when (side) {
                        "left" -> PxRect(main.x, main.y, area.w * DOCK_SHARE, main.h).also { main = PxRect(it.right, main.y, main.w - it.w, main.h) }
                        "top" -> PxRect(main.x, main.y, main.w, area.h * DOCK_SHARE).also { main = PxRect(main.x, it.bottom, main.w, main.h - it.h) }
                        "bottom" -> PxRect(main.x, main.bottom - area.h * DOCK_SHARE, main.w, area.h * DOCK_SHARE).also { main = main.copy(h = main.h - it.h) }
                        else -> PxRect(main.right - area.w * DOCK_SHARE, main.y, area.w * DOCK_SHARE, main.h).also { main = main.copy(w = main.w - it.w) }
                    }
                    val header = rect.copy(h = min(area.headerHeight, rect.h))
                    headers.add(DockHeader(dock, header))
                    val content = PxRect(rect.x, header.bottom, rect.w, rect.h - header.h)
                    place(dock, content, content)
                    placeUserWindow(dock, content)
                }
                val sx = if (mainArea != null) main.w / mainArea.w else (scale ?: 1.0).toFloat()
                val sy = if (mainArea != null) main.h / mainArea.h else (scale ?: 1.0).toFloat()
                console = mainArea?.textRect()?.let { PxRect(main.x + it.x * sx, main.y + it.y * sy, it.w * sx, it.h * sy).intersect(main) } ?: main
                for (window in inArea) place(window, PxRect(main.x + window.x * sx, main.y + window.y * sy, window.w * sx, window.h * sy), main)
                pane = main
                mainWindowSize = scale?.let { MudletSize((main.w / it).roundToInt().coerceAtLeast(1), (main.h / it).roundToInt().coerceAtLeast(1)) }
            } else {
                val text = mainArea?.textRect()
                if (mainArea != null && text != null) {
                    val topBorder = text.y
                    val bottomBorder = max(0f, mainArea.h - text.bottom)
                    var b = area.w / mainArea.w
                    if ((topBorder + bottomBorder) * b > area.h * MAX_BANDS_SHARE) b = area.h * MAX_BANDS_SHARE / (topBorder + bottomBorder)
                    val bandX = (area.w - mainArea.w * b) / 2
                    val topBand = PxRect(0f, 0f, area.w, topBorder * b)
                    console = PxRect(0f, topBand.bottom, area.w, area.h - topBand.h - bottomBorder * b)
                    val bottomBand = PxRect(0f, console.bottom, area.w, bottomBorder * b)
                    val t = area.w / text.w
                    for (window in inArea) {
                        val cx = window.x + window.w / 2f
                        val cy = window.y + window.h / 2f
                        when {
                            cy < text.y -> place(window, PxRect(bandX + window.x * b, window.y * b, window.w * b, window.h * b), topBand)

                            cy > text.bottom -> place(
                                window,
                                PxRect(bandX + window.x * b, bottomBand.y + (window.y - text.bottom) * b, window.w * b, window.h * b),
                                bottomBand,
                            )

                            cx < text.x || cx > text.right -> if (window.kind in ScriptWindows.CONSOLE_KINDS || window.kind == WindowKind.MAPPER) pages.add(window)

                            // Over the text: kept at the top or the bottom of the console, whichever it is nearer
                            else -> {
                                val y = if (cy < text.y + text.h / 2) console.y + (window.y - text.y) * t else console.bottom - (text.bottom - window.y) * t
                                place(window, PxRect((window.x - text.x) * t, y, window.w * t, window.h * t), console)
                            }
                        }
                    }
                } else {
                    console = whole
                    val s = (scale ?: 1.0).toFloat()
                    for (window in inArea) place(window, PxRect(window.x * s, window.y * s, window.w * s, window.h * s), whole)
                }
                pane = console
                pages.addAll(docks)
                mainWindowSize = scale?.let { MudletSize((area.h / it).roundToInt().coerceAtLeast(1), (area.w / it).roundToInt().coerceAtLeast(1)) }
            }

            // openMapWidget()'s map, a dock the main window's size, is a page in either layout
            pages.addAll(top.filter { it.inDock && it.kind == WindowKind.MAPPER && it.shown })

            // A page's window fills the pane below its header
            val paneContent = PxRect(pane.x, pane.y + area.headerHeight, pane.w, max(0f, pane.h - area.headerHeight))
            for (page in pages) if (page.kind == WindowKind.USER_WINDOW) placeUserWindow(page, paneContent)
            val tabHeaders = state.windows.mapNotNull { w -> w.frameTabs?.let { w to it } }
            fun headerShowing(page: ScriptWindow) = tabHeaders.firstOrNull { (_, tabs) -> page.kind == WindowKind.MXP_FRAME && tabs.front.name == page.name }

            // The page now in front of the tabs that [key] is a page of, should another of them have come in front of it
            fun frontPageOfTabsHolding(key: WindowKey): ScriptWindow? {
                if (key.kind != WindowKind.MXP_FRAME) return null
                val tabs = tabHeaders.firstOrNull { (_, t) -> t.pages.any { it.name == key.name } }?.second ?: return null
                return pages.firstOrNull { it.key == WindowKey(tabs.front.name, WindowKind.MXP_FRAME) }
            }
            val selected = pages.firstOrNull { it.key == area.selectedPage } ?: area.selectedPage?.let(::frontPageOfTabsHolding)

            // An MXP frame's tabs go above the page in front, as tall as Mudlet's scaled but never less than a header,
            // and no higher than the page may show
            fun tabsAbove(p: PlacedWindow): FrameTabStrip? {
                val (frame, tabs) = headerShowing(p.window) ?: return null
                val h = max(area.headerHeight, if (p.window.h > 0) FRAME_TABS_HEIGHT * p.full.h / p.window.h else FRAME_TABS_HEIGHT)
                val top = clips[p.window.key]?.y ?: p.rect.y
                return FrameTabStrip(frame, tabs, PxRect(p.rect.x, max(p.full.y - h, top), p.rect.w, h))
            }

            val paneWindows = ArrayList<PlacedWindow>()
            val paneFrameTabs = ArrayList<FrameTabStrip>()
            if (selected != null && paneContent.h > 0) {
                var content = paneContent
                val header = headerShowing(selected)
                if (header != null && paneContent.h > area.headerHeight) {
                    paneFrameTabs.add(FrameTabStrip(header.first, header.second, paneContent.copy(h = area.headerHeight)))
                    content = PxRect(paneContent.x, paneContent.y + area.headerHeight, paneContent.w, paneContent.h - area.headerHeight)
                }
                paneWindows.add(PlacedWindow(selected, content, content))
                tree.placeChildren(selected, content, content, paneWindows, clips)
                // Those of the frames opened in the page
                paneWindows.drop(1).mapNotNullTo(paneFrameTabs, ::tabsAbove)
            }

            val frameTabs = placed.mapNotNull(::tabsAbove)
            return WindowLayout(
                fitted = area.fitted,
                scale = scale,
                mainWindowSize = mainWindowSize,
                console = console,
                windows = placed,
                dockHeaders = headers,
                pages = pages,
                pane = if (selected != null) pane else null,
                paneWindows = paneWindows,
                placements = placements,
                frameTabs = frameTabs,
                paneFrameTabs = paneFrameTabs,
                selectedPage = selected?.key,
            )
        }

        private fun EngineMessage.MainArea.textRect(): PxRect? = if (textW > 0 && textH > 0) PxRect(textX.toFloat(), textY.toFloat(), textW.toFloat(), textH.toFloat()) else null
    }

    // The windows by parent, each parent's by stack, without the consoles' own command lines, which go along their
    // console's bottom
    private class Tree(windows: List<ScriptWindow>) {
        private val byParent: Map<WindowKey?, List<ScriptWindow>> = windows
            .filter { !(it.kind == WindowKind.COMMAND_LINE && it.parentKind in ScriptWindows.CONSOLE_KINDS) }
            .groupBy { w -> w.parentKind?.let { WindowKey(w.parent, it) } }
            .mapValues { (_, list) -> list.sortedBy { it.stack } }

        fun children(parent: WindowKey?): List<ScriptWindow> = byParent[parent].orEmpty()

        /** Adds [parent]'s shown children, and theirs, as drawn in [rect] and cut to [clip], and what may show of each to [clips]. */
        fun placeChildren(parent: ScriptWindow, rect: PxRect, clip: PxRect, out: MutableList<PlacedWindow>, clips: MutableMap<WindowKey, PxRect>) {
            val sx = if (parent.w > 0) rect.w / parent.w else 1f
            val sy = if (parent.h > 0) rect.h / parent.h else 1f
            for (child in children(parent.key)) {
                if (!child.shown) continue
                val full = PxRect(rect.x + child.x * sx, rect.y + child.y * sy, child.w * sx, child.h * sy)
                val shown = full.intersect(clip) ?: continue
                out.add(PlacedWindow(child, shown, full))
                clips[child.key] = clip
                placeChildren(child, full, shown, out, clips)
            }
        }
    }
}
