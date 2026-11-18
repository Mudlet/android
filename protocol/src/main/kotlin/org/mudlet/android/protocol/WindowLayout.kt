package org.mudlet.android.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rectangle in the game area's pixels, its origin at the area's top left, or, for a window in a scroll box as
 * [DrawnWindow] gives it, at the top left of the box's content.
 */
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

    /** This rectangle moved [dx] across and [dy] down. */
    fun offset(dx: Float, dy: Float): PxRect = PxRect(x + dx, y + dy, w, h)
}

/** The most pixels a scroll box's content has on a side: Compose's `layout()` refuses a larger one. */
const val MAX_CONTENT_PX = 16_777_215f

/**
 * [w] by [h] pixels as whole pixels Compose can measure with, as a script's geometry may ask for more, which must not
 * throw. Its `Constraints` hold a side of up to 262 142 pixels, and the other one only as far as the 31 bits the two
 * share allow (32 766 beside one of 32 767 or more, 8 190 beside one of 65 535 or more). A size that fits is kept; of
 * one that does not, the smaller side gives way.
 */
fun layoutSize(w: Float, h: Float): Pair<Int, Int> {
    val big = max(w, h).roundToInt().coerceIn(0, 0x3FFFE)
    val small = min(w, h).roundToInt().coerceIn(
        0,
        when {
            big < 0x7FFF -> big
            big < 0xFFFF -> 0x7FFE
            else -> 0x1FFE
        },
    )
    return if (w >= h) big to small else small to big
}

/** A size in Mudlet's pixels. */
data class MudletSize(val w: Int, val h: Int)

/** Where the app put a user window, in Mudlet's pixels from the game area's top left: `placeUserWindow`'s fields. */
data class Placement(val x: Int, val y: Int, val w: Int, val h: Int, val floating: Boolean)

/**
 * A window to draw at [rect], cut to what its parents show; [full] is where it is before that cut. A window in a scroll
 * box is placed as it is with the box scrolled to its top left, and cut to the box's content, not to what of the box
 * shows: [scrollBox] is the innermost box it scrolls in. A scroll box's [content] is the area its windows scroll in.
 */
data class PlacedWindow(
    val window: ScriptWindow,
    val rect: PxRect,
    val full: PxRect,
    val scrollBox: WindowKey? = null,
    val content: PxRect? = null,
) {
    /** This window, its [content] too, moved [dx] across and [dy] down. */
    fun offset(dx: Float, dy: Float): PlacedWindow = copy(rect = rect.offset(dx, dy), full = full.offset(dx, dy), content = content?.offset(dx, dy))
}

/**
 * A window as drawn: [placed] in what holds it, the game area or, for a window in a scroll box, the box's content from
 * its top left; for a scroll box, the windows that scroll in it, the same way, in the order [WindowLayout] gives them.
 */
data class DrawnWindow(val placed: PlacedWindow, val scrolled: List<DrawnWindow> = emptyList()) {
    companion object {
        /** [windows], as [WindowLayout] places them, with those in a scroll box under it instead of after it. */
        fun of(windows: List<PlacedWindow>): List<DrawnWindow> {
            // Those in no scroll box are under the null key
            val byBox = windows.groupBy { it.scrollBox }
            fun drawn(placed: PlacedWindow, dx: Float, dy: Float): DrawnWindow {
                val content = placed.content
                val scrolled = if (content == null) emptyList() else byBox[placed.window.key].orEmpty().map { drawn(it, content.x, content.y) }
                return DrawnWindow(placed.offset(-dx, -dy), scrolled)
            }
            return byBox[null].orEmpty().map { drawn(it, 0f, 0f) }
        }
    }
}

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
 * A scroll box's windows are placed in its content, which scrolls under it; [DrawnWindow.of] nests them under it, to be
 * drawn in it.
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
    /**
     * The selected page's window and its children, inside [pane] below the header, and below its [paneFrameTabs]; a
     * scroll box's windows may lie past it, in the box's content.
     */
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
    /**
     * Whether a script's mapper window is on the screen: placed outside the pane, or in it as or in the open page. A
     * mapper that is a page no one opened, its tab the only sign of it, is not.
     */
    val mapperDrawn: Boolean get() = (windows.asSequence() + paneWindows).any { it.window.kind == WindowKind.MAPPER }

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
                placed.add(PlacedWindow(window, shown, full, content = tree.scrollContent(window, full)))
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
                // No page is a scroll box today; one would keep its content, so DrawnWindow.of keeps its windows
                paneWindows.add(PlacedWindow(selected, content, content, content = tree.scrollContent(selected, content)))
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

        /**
         * Adds [parent]'s shown children, and theirs, as drawn in [rect] and cut to [clip], and what may show of each to
         * [clips]; [scrollBox] is the box [parent] scrolls in. A scroll box's children are cut to its content instead.
         */
        fun placeChildren(
            parent: ScriptWindow,
            rect: PxRect,
            clip: PxRect,
            out: MutableList<PlacedWindow>,
            clips: MutableMap<WindowKey, PxRect>,
            scrollBox: WindowKey? = null,
        ) {
            val sx = if (parent.w > 0) rect.w / parent.w else 1f
            val sy = if (parent.h > 0) rect.h / parent.h else 1f
            val content = scrollContent(parent, rect)
            val childClip = content ?: clip
            val box = if (content != null) parent.key else scrollBox
            for (child in children(parent.key)) {
                if (!child.shown) continue
                val full = PxRect(rect.x + child.x * sx, rect.y + child.y * sy, child.w * sx, child.h * sy)
                val shown = full.intersect(childClip) ?: continue
                out.add(PlacedWindow(child, shown, full, box, scrollContent(child, full)))
                clips[child.key] = childClip
                placeChildren(child, full, shown, out, clips, box)
            }
        }

        /**
         * The area a scroll box drawn at [rect] scrolls its windows in, from the box's top left, or null for a window
         * that is not a scroll box. Mudlet's `TScrollBox` is a `QScrollArea` whose widget calls `adjustSize()` as a
         * child moves, resizes, shows or hides: with no layout, that sizes it to the rectangle of the children not hidden
         * and not of no size, grown by as much again as that rectangle's top left is from the widget's, which counts a
         * child at a negative place but leaves it where no scroll reaches. One with no such child has the box's own area
         * and does not scroll here. The content is no larger than [MAX_CONTENT_PX] on a side, the box's own area too,
         * whatever a script's geometry asks.
         */
        fun scrollContent(box: ScriptWindow, rect: PxRect): PxRect? {
            if (box.kind != WindowKind.SCROLL_BOX) return null
            val sx = if (box.w > 0) rect.w / box.w else 1f
            val sy = if (box.h > 0) rect.h / box.h else 1f
            val inside = children(box.key).filter { it.visible && (it.w != 0 || it.h != 0) }
            // The box's own size is a script's too
            val own = PxRect(rect.x, rect.y, min(rect.w, MAX_CONTENT_PX), min(rect.h, MAX_CONTENT_PX))
            if (inside.isEmpty()) return own
            val left = inside.minOf { it.x }
            val top = inside.minOf { it.y }
            // The rectangle's size and twice its top left: as far past its far edge as its near one is from the widget's.
            // As Longs: two Ints' sum may be past an Int
            val w = inside.maxOf { it.x.toLong() + it.w } + left
            val h = inside.maxOf { it.y.toLong() + it.h } + top
            return if (w < 0 || h < 0) own else PxRect(rect.x, rect.y, min(w * sx, MAX_CONTENT_PX), min(h * sy, MAX_CONTENT_PX))
        }
    }
}
