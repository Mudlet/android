# Phase F: what scripts draw

The plan for drawing what a profile's scripts make beside the main console: mini consoles, user windows, labels,
Geyser gauges and buttons, button bars, the commands packages add, and later the 2D map. It follows Phase E
([`roadmap.md`](roadmap.md)), whose rules hold here too: TalkBack is a first-class requirement, every string is in
`strings.xml`, and the protocol changes as [`protocol.md`](protocol.md) says. November 2026.

Many games' GUI packages are built from these (Geyser layouts with gauges for health and mana, a chat mini console, a
map), so without them such a game shows only its main console.

## What upstream has

Mudlet draws none of these windows with no GUI: `THeadlessSession` describes them for the app to draw, takes the
app's pointer, and says when to read them again. All of it is on `development` at
[`bfc932d9`](https://github.com/Mudlet/Mudlet/tree/bfc932d9e76004d9624b6925b5e0278c17fdae2a), in
`src/headless/THeadlessSession.h` (line numbers below) and `docs/headless.md` ("the windows scripts make", "the 2D
map", "commands and buttons that scripts add"). The engine host uses none of it yet: `MudletEngine` connects none of
these calls' signals.

### Script windows

| API | Line | What it gives |
|---|---|---|
| `QList<ScriptWindow> scriptWindows() const` | 687 | Every window the scripts made, parents before what they hold, each parent's from the lowest up, the main window's area's before the docks; no buffers. Empty with no profile open. A getter, callable from any handler |
| `std::optional<ScriptWindow> scriptWindow(const QString& name, std::optional<ScriptWindow::Kind> kind)` | 689 | One of them; a console's own command line shares its console's name, so the kind tells them apart |
| `struct ScriptWindow` | 625 | `name`; `kind` (`miniConsole`, `userWindow`, `label`, `commandLine`, `textBox`, `scrollBox`, `mapper`, `mxpFrame`); `parent` and `parentKind` (empty parent: the main window's area, a dock or an `EXTERNAL` MXP frame); `geometry` (`QRect` in the parent's pixels, a dock's in the main window's; none for a console's command line); `visible` (its own flag) and `shown` (it and every window it is in visible); `stackOrder` (from 0 among its parent's windows, docks among themselves); `background` (`QColor`, consoles and labels); `font` (`QFont`, consoles, labels, text boxes); `inDock`; `dock` (`autoDock`, `area` as `Qt::DockWidgetArea`, `floating`, `title`; user windows only); `scrolledBackTo` (`optional<int>`, none while following), `grid` (`QSize` columns by rows of the upper pane), `scrollBar`, `timeStamps` (consoles) |
| `signal scriptWindowsChanged(const QString& profile, const QStringList& names)` | 1042 | From the event loop, once a turn, sorted: windows made, deleted (gone from the list), moved, resized, shown, hidden, reparented, restacked, recoloured, refonted, scrolled, or a label whose content changed |
| `std::optional<LabelContent> labelContent(const QString& name) const` | 734 | None for a name that is not a label's |
| `struct LabelContent` | 692 | `text` (Qt rich text, links styled by `setLinkStyle()`; empty while an image or movie shows); `image` (file path), `svg`, `svgTint`, `svgRotation`, `svgShearX`, `svgShearY`; `movie` (path), `moviePaused`, `movieFrame` (-1 while playing), `movieSpeed` (percent), `movieSize`; `toolTip`, `toolTipDuration` (s); `cursor`, `customCursor`, `customCursorHotSpot`; `clickThrough`; `accessibleName`, `accessibleButton`; `styleSheet` (the label's own, which Mudlet does not interpret) |
| `std::optional<WindowText> windowLines(const QString& window, int first, int count) const` | 763 | Rows of a mini console, user window or MXP frame (or `"main"`), after the console's wrap; `first` below 0 counts from the end. None for any other kind |
| `struct WindowText`, `struct WindowLine` | 749, 736 | `lineCount` (one more than `getLineCount()`), `first`, `lines`; each row `text`, `isPrompt`, `runs` (`TextRun`, as `outputLine`'s, the link ids the console's own), `arrivalMSecs`, `wrapped` |
| `signal windowTextChanged(const QString& profile, const QStringList& windows)` | 1048 | From the event loop, once a turn, sorted, after the `scriptWindowsChanged` of a window made that turn; never the main console |
| `std::optional<Link> windowLink(const QString& window, int id) const`, `LinkResult followWindowLink(const QString& window, int link, std::optional<int> item)` | 301, 313 | A console's links, as `link()` and `followLink()` for the main console |
| `bool setUserWindowGeometry(const QString& name, const QRect& geometry, bool floating)`, `bool closeUserWindow(const QString& name)` | 768, 770 | Where the app put a user window, in the main window's pixels (a new size raises `sysUserWindowResizeEvent`); the dock's close button |
| `bool pointer(const QString& window, const WindowPointerEvent& event)` | 809 | The app's pointer on a window, in its pixels: label callbacks with the GUI's event table, click-through, `sysWindowMousePressEvent`/`ReleaseEvent`, the wheel scrolling a console, Qt's implicit grab |
| `struct WindowPointerEvent` | 772 | `type` (`press`, `release`, `doubleClick`, `move`, `enter`, `leave`, `wheel`), `position` (`QPointF`), `button`, `buttons`, `modifiers`, `angleDelta` |
| `windowCommandLineText()`, `setWindowCommandLineText()`, `submitWindowCommandLine()`; signal `windowCommandLineTextChanged(profile, commandLines)` | 374-382, 1005 | Command lines from `createCommandLine()` and a console's `enableCommandLine()` |
| `bool setMainWindowSize(const QSize& pixels)`, `QSize mainConsoleGrid() const`, `QRect mainConsoleTextRect() const`; signal `mainConsoleAreaChanged(profile, grid, textRect)` | 547-551, 1034 | The main window's area in pixels, which the borders (`setBorderTop()` and the rest) take from; the console's grid and where its text goes. `setScreenSize()` (columns, rows) is the other way to size it; the later of the two holds. Once the profile has the main window's size, the engine gives the console's columns and rows to the carried `setGameScreenSize()` instead, which tells the game them and resizes nothing |

A Geyser gauge, button or container is not a window: a gauge is its `<name>_back`, `<name>_front` and `<name>_text`
labels (`GeyserGauge.lua`), a button its labels.

### Button bars and commands

| API | Line | What it gives |
|---|---|---|
| `QList<ButtonBar> buttonBars() const`; signal `buttonsChanged(profile)` | 537, 1022 | The profile's active bars in the order of its actions: `id`, `name`, `styleSheet`, `place` (`top`, `left`, `right`, `floating`), `orientation`, `columns`, `fillerOffset`, `buttons`; each `Button` `id`, `name`, `icon` (path), `styleSheet`, `pushDown`, `checked`, `menu`, `children` |
| `bool pressButton(int id)` | 541 | A click on a button or menu entry: its command through the aliases, its script, push-down state |
| `QList<AddonCommand> addonCommands() const`; signal `addonCommandsChanged(profile)` | 481, 1019 | `addCommand()`'s commands: `id`, `package`, `name`, `icon`, `tooltip`, `menuPath` (`/`-separated), `shortcut`, `onMenu`, `onToolbar`, `enabled`, `checkable`, `checked`, `pinned`, `pulse`, `pulseColor1`, `pulseColor2`, `pulseIntervalMs` |
| `bool activateAddonCommand(int id)` | 486 | A click: ticks a checkable one, raises `sysCommandClicked` |
| `static void setReservedShortcuts(const QList<ReservedShortcut>&)` | 499 | Shortcuts `addCommand()` refuses, for keys the app keeps |

### The 2D map

| API | Line | What it gives |
|---|---|---|
| `bool renderMap(QPainter& painter, const QSize& size)`; signal `mapRepaintRequested(profile)` | 585, 980 | Draws the map at a size, once a script opened it; the signal comes once a turn, from the event loop, so its handler may draw |
| `bool mapPointer(const MapPointerEvent& event)` | 610 | `press` (select a room), `doubleClick` (speedwalk), `wheel` (zoom), at a point of the last draw; `pointer()` on the `mapper` window forwards to it |
| `bool setMapViewOnly(bool)`, `mapViewOnly()`; signal `mapViewOnlyChanged` | 620 | Viewing or editing |

### What upstream lacks

Checked against the same commit; each has a prompt under "Upstream gaps" below.

1. **Label links.** A link in a label's rich text (`send:`, `prompt:`, `http(s):`, or Lua) is run by
   `TLabel::slot_linkActivated()` (`src/TLabel.cpp`), which is GUI code; `src/headless/` has nothing that follows one.
2. **Gauge values.** A gauge's value lives in Lua (`Geyser.Gauge:setValue()` sets `self.value`); no label carries
   it, so the app cannot give TalkBack a progress value. `setLabelAccessibleRole()` takes only `button`, `text` or
   `''`.
3. **A label's live region.** `setLabelLiveRegion()` (`polite`, `assertive`, `off`) is stored on the label, but
   `LabelContent` does not carry it, and setting it alone does not name the label in `scriptWindowsChanged`.
4. **Text boxes.** `createTextEdit()` windows are listed (kind `textBox`), but neither their text, read-only flag
   and placeholder nor a way to pass the player's typing back exists in the session.
5. **MXP frame tabs.** Only `shown` tells which page is in front; titles and bringing a page forward are missing, as
   `docs/headless.md` ("What still needs a window") says itself.

## Protocol

The engine keeps the windows' state, as it keeps statuses and `history`, and sends it whole to each UI that attaches
and again when a lagging UI drains (`protocol.md`, "Attaching" and "Flow control"). Between those it sends what
changed, coalesced as upstream's signals already are, once a turn of the event loop. Every message and field is built
in `SocketBridge.cpp`, since `ProtocolSpecTest` reads the fields from there. Field names are flat: a nested upstream
struct becomes prefixed fields (`dockArea`, not `dock.area`).

Geometry crosses in Mudlet's pixels, as the scripts set it; the app scales (see "Layout"). Colours are the runs'
encoding (`0x1000000 | 0xRRGGBB`, -1 for none). Runs of a window's rows are `line` runs, their link ids the window's.

Phase E's coordinator lane owns versions 4 (screen size, console colours, announcements), 5 (links) and 6 (app state).
Phase F comes after them: each pull request below that adds a request the UI depends on takes the next free
`PROTOCOL_VERSION` when it is written, whatever the numbers here say. Each protocol change is one commit with
`docs/protocol.md`, `SocketBridge`, `Protocol.kt` and `native/tests/protocol_client.py`, and `ProtocolSpecTest`
green.

### Engine to UI

| Message | Fields | From |
|---|---|---|
| `windows` | `full`?, `windows`: `name`, `kind`, `parent`, `parentKind`?, `x`, `y`, `w`, `h`, `visible`, `shown`, `stack`, `bg`?, `fontFamily`?, `fontSize`?, `inDock`, `autoDock`?, `dockArea`?, `dockFloating`?, `dockTitle`?, `scrolledBackTo`?, `columns`?, `rows`?, `scrollBar`?, `timeStamps`?, `tabs`?, `tabTitles`?, `tabCurrent`?; `removed`?: `name`, `kind` | `scriptWindows()` at `profileLoaded` and on attach (`full` true: replaces all), then the entries `scriptWindowsChanged` names. A window is keyed by name and kind. `kind` is upstream's enum name. `tabs`, `tabTitles` and `tabCurrent` are an MXP frame's tab header, its `frameTabs` (gap 5, carried) |
| `label` | `name`, `text`, `image`, `svg`, `svgTint`?, `svgRotation`?, `svgShearX`?, `svgShearY`?, `movie`, `moviePaused`, `movieFrame`, `movieSpeed`, `movieW`?, `movieH`?, `toolTip`, `clickThrough`, `accessibleName`, `accessibleButton`, `styleSheet` | `labelContent()` for each label in a `windows` message whose content differs from what was last sent. The cursor fields are left out: a touch screen has no pointer to shape |
| `textBox` | `name`, `text`, `readOnly`, `placeholder`, `styleSheet`, `tabMovesFocus`, `typed` | `textBoxContent()` (gap 4, carried) for each text box in a `windows` message whose content differs from what was last sent; not the player's own typing, kept as Mudlet keeps it; `typed`, the `setTextBoxText` for it the bridge has had from this UI |
| `windowText` | `window`, `lineCount`, `first`, `lines`: `text`, `runs`, `prompt`, `wrapped`; `re`? | `windowLines(window, -300, 300)` for each window `windowTextChanged` names, and for every console on attach; older rows answer `getWindowText` |
| `mainArea` | `w`, `h`, `columns`, `rows`, `textX`, `textY`, `textW`, `textH` | The size given, `mainConsoleGrid()` and `mainConsoleTextRect()`, at `profileLoaded` and `mainConsoleAreaChanged` |
| `windowCommandLine` | `name`, `text` | `windowCommandLineTextChanged`, each name with `windowCommandLineText()` |
| `image` | `path`, `png`, `w`, `h`, `re`? | The answer to `getImage`: the file or icon as PNG, base64 |
| `buttonBars` | `bars`: `id`, `name`, `styleSheet`, `place`, `vertical`, `columns`, `fillerOffset`, `buttons`; the buttons' `id`, `name`, `icon`, `styleSheet`, `pushDown`, `checked`, `menu`, `children` | `buttonBars()`, whole, at load, on attach and on `buttonsChanged` |
| `commands` | `commands`: `id`, `package`, `name`, `icon`, `tooltip`, `menuPath`, `shortcut`, `onMenu`, `onToolbar`, `enabled`, `checkable`, `checked`, `pinned`, `pulse` | `addonCommands()`, whole, at load, on attach and on `addonCommandsChanged`. The pulse colours and interval are left out (see "Touch and TalkBack") |
| `map` | `png`, `w`, `h` | `renderMap()` into a `QImage` the size of the `mapper` window, on `mapRepaintRequested` while it is `shown` |

Images go through the engine, not the file system: a path may be a Qt resource (`:/...`) or an icon theme name, which
the UI process, with no Qt, cannot read, and a profile's own files are the engine's to resolve. The UI caches each
image by path and size while the profile runs.

### UI to engine

| Request | Fields | Runs |
|---|---|---|
| `setMainWindowSize` | `w`, `h` | `setMainWindowSize()`, in Mudlet's pixels |
| `getWindowText` | `window`, `first`, `count`, `id`? | `windowLines()`, answered by `windowText` with `re`, or `error` for a name it refuses |
| `placeUserWindow` | `name`, `x`, `y`, `w`, `h`, `floating` | `setUserWindowGeometry()` |
| `closeUserWindow` | `name` | `closeUserWindow()` |
| `pointer` | `window`, `type`, `x`, `y`, `button`?, `buttons`?, `modifiers`?, `wheelY`? | `pointer()`. `type` is upstream's enum name; `button` and `buttons` use Qt's values (1 left, 2 right, 4 middle) |
| `sendWindowCommandLine` | `name`, `text` | `setWindowCommandLineText()`, then `submitWindowCommandLine()` |
| `setTextBoxText` | `name`, `text` | `setTextBoxText()` (gap 4, carried): what the player typed; a refusal is `error`, then the box's `textBox` |
| `selectFrameTab` | `name` | `selectFrameTab()` (gap 5, carried): the player's tap on an MXP frame's tab, by the page's frame name; a refusal is `error` |
| `followLabelLink` | `label`, `href`, `id`? | `followLabelLink()` (gap 1, carried): the player's tap on a link in a label's text, followed as Mudlet's click does; a web link the app opens itself, and the engine refuses; a refusal is `error` |
| `getImage` | `path`, `w`?, `h`?, `frame`?, `id`? | Reads the file, or `QIcon(path)` at that size, or that frame of a movie (`QMovie`), answered by `image` |
| `pressButton` | `id` | `pressButton()`; a refusal is `error` |
| `activateCommand` | `id` | `activateAddonCommand()`; a refusal is `error` |
| `mapPointer` | `type`, `x`, `y`, `button`?, `modifiers`?, `wheelY`? | `mapPointer()` |
| `setMapViewOnly` | `viewOnly` | `setMapViewOnly()` |

Links in windows extend version 5's `getLink` and `followLink` with a `window` field, which `link` echoes. As an older
engine would follow the main console's link of that id, that change raised the version, to 12 (after F1P shipped
without it).

`setMainWindowSize` and version 4's `setScreenSize` both size the main window in upstream's API, which keeps the
later. The app sends both, and the engine keeps them apart: once a profile has the size `setMainWindowSize` gave, the
console's columns and rows go to `setGameScreenSize()`, carried in `scripts/mudlet-patches/` until Mudlet has it,
which tells the game (NAWS) that size and leaves the main window's alone. So, in whichever order the two messages
came, scripts read the main window's size the windows UI measured (`getMainWindowSize()`, and the borders and windows
placed by it), and the game is told the console the player sees, which in the stacked layout is not Mudlet's grid
(see "Layout").

The stand-in engine has no windows: it sends empty `windows`, `buttonBars` and `commands` on attach and answers the
window requests with `error`, so `test_engine.py` and the JVM test cover the framing. `FakeEngine` feeds the app's UI
tests.

## Compose rendering

### Layout

Scripts place windows in Mudlet's pixels against the main window's size, so the app gives Mudlet a size and draws
everything at one scale `s` (screen pixels per Mudlet pixel):

- `s` is the app console's character width over Mudlet's, which `mainArea` gives (`textW / columns`, from one whose
  grid is above Mudlet's 40-column floor, as the ratio depends only on the two fonts). The app sends
  `setMainWindowSize(areaPx / s)`, where `areaPx` is the game screen's area above the input line. Mudlet's grid is
  then the columns and rows the app's console shows, and a border a script sets takes the same
  share of the screen as of Mudlet's window. A font size change or rotation sends the size again.
- **Fitted**, when the game area is at least 600 dp wide (landscape, tablets, an unfolded phone): the main window's
  area is drawn as on the desktop, scaled by `s`: the console in `mainArea`'s text rectangle, the windows of the main
  area over it by `stack`. Docked user windows take a column on their `dockArea`'s side, a quarter of the width
  each, up to half of it; the app reports where with `placeUserWindow`.
- **Stacked**, in a narrower portrait: Mudlet is given the size the game area would have in landscape (its width and
  height swapped), so a layout made for a wide window keeps its proportions. The console fills the width, with the
  windows over its text rectangle still over it; what lies in the top and bottom borders is drawn above and below
  it, scaled to the width; the left and right borders' windows
  and the docks become pages of a window switcher, a row of tabs above the input line that opens a pane over the
  console, or a bottom sheet when the keyboard is up. The game is told the console's own columns and rows
  (`setScreenSize`), not the landscape grid, so it wraps its lines for the narrower console: a setting picks fitted or
  stacked for the player who prefers the true layout. (Mudlet never makes the grid narrower than 40 columns, so a
  fitted portrait phone with a wide border would have its console cut off.)
- A floating user window is a page of the switcher on a phone; on a free-form window (Chromebook, desktop mode) it may
  float, later.
- Script geometry is never trusted to fit: a window that falls outside the area is clipped to it, and a pane whose
  text would drop under 9 sp after scaling scrolls instead of shrinking further.

### Mini consoles and user windows

They reuse the main console's renderer: the rows of `windowText` as `UiLine`s through `ansiText()`, in a `LazyColumn`
the size of the window, with its `bg` and font scaled by `s`. Each window keeps the last 300 rows it was sent and asks
`getWindowText` for older ones as the player scrolls back. Scrolling stays local; a script's `scrollTo()` arrives as
`scrolledBackTo` and moves the list there. Links in them are asked about and followed with the extended `getLink` and
`followLink` (version 12). A console's own command line is an input line along its bottom, sending with
`sendWindowCommandLine`. MXP frames draw as mini consoles. A frame's tab header (`tabs`, gap 5), where Mudlet gives
it one, is a row of tabs above the page in front, at least 48 dp tall and as tall as Mudlet's 30 pixels scaled, kept
inside what may show of the page; stacked, where that page is a page of the switcher, the row is at the top of the
pane, and the rows of the frames opened in it are above their pages in the pane. Each tab is a `Role.Tab` with its
title, selected for the page in front; a tap on another sends `selectFrameTab`, and the page the engine then brings
forward is drawn there, the pane staying open on it. The switcher and the pane's header call such a page by its tab's
title.

### Labels

A `label` is a `Box` at its geometry, drawn in this order:

- **Style sheet**, a subset of Qt's for `QLabel`, parsed by the app: `background-color` and `background` (named,
  `#rgb`, `#rrggbb`, `rgb()`, `rgba()`, and `qlineargradient` as a gradient of its stops), `color`, `border`,
  `border-width`, `border-style` (solid only), `border-color`, `border-radius`, `padding`, `margin`, `font-size`
  (pt and px, scaled by `s`), `font-weight`, `font-style`, `font-family`, `qproperty-alignment`, and `border-image`
  or `background-image` with `url()` (through `getImage`). Bare declarations and a `QLabel { }` block apply; a rule
  with any other selector, state or pseudo-element is skipped. A user window's or the profile's sheet does not reach
  the app: only the label's own sheet is in `LabelContent`.
- **Image**: the raster image fills the label as `QLabel` scales it; an SVG one is drawn under the text with its tint,
  rotation and shear; a playing movie is an `AnimatedImageDrawable` (API 28, the app's minSdk). That cannot seek or
  change speed, so a paused movie shows `movieFrame` as a still the engine renders (`getImage` with `frame`), and a
  `movieSpeed` other than 100 is not followed.
- **Text**: Qt's rich text converted to an `AnnotatedString` by the app's own parser for the tags Geyser and scripts
  write: `b`, `i`, `u`, `s`, `font` (`color`, `size`, `face`), `span` with `style` (`color`, `background-color`,
  `font-size`, `font-weight`), `br`, `p`/`div` with `align`, `center`, `a`. Anything else keeps its text without its
  formatting. A link's `href` is read as Qt's `QTextHtmlParser` reads it, as the engine follows only that: a quoted
  value up to its own closing quote, `<`, `>` and the other quote included, and entities decoded as Qt decodes them
  (numeric ones, and the named ones of Qt's table, `HtmlEntities.kt`: HTML 4's, with Qt's own `&and;` and `&or;`, and
  `&AMP;`, `&GT;`, `&LT;`, `&QUOT;`, `&apos;` and `&percnt;`), but in a single-quoted value, which Qt keeps as written.

Taps reach the label with `pointer`, in its own Mudlet pixels: a tap is `enter`, `press` and `release`, a double tap
adds `doubleClick`, a long press is the right button's `press` and `release` (Geyser's right-click menus); `leave`
follows when another window is touched. A drag sends `move` with the button held, so sliders built of labels work.
Click-through and the grab are upstream's to resolve: the app names the topmost shown label under the finger.

### Text boxes

A `textBox` is a text field at its window's geometry, scaled by `s`, with the label style sheet's subset where a
`QPlainTextEdit` has it: bare declarations and `QPlainTextEdit`, `TTextBox` or `*` blocks give its background, text
colour, border (with its radius), padding, margin and font; one with no border has an outline, thicker while focused.
Where its style sheet sets no colours it has the theme's surface and text colours; the desktop's has the profile's
command-line colours (dark grey on black unless changed), which the engine does not send. Its text is never drawn
under 9 sp, and scrolls inside it. The player types in it unless `readOnly`, with the `placeholder` shown while it is
empty, and Tab moves on to the next control when `tabMovesFocus` (a hardware keyboard's; otherwise it types a tab).
Once the player has stopped typing for 300 ms, all of the box's text goes to the engine with `setTextBoxText`, as
plain text as Mudlet keeps it, and the app's copy of the box takes it, as the engine sends no `textBox` back. What is
not yet sent also goes when the player leaves the field, before a tap on a label, button, command, link or the map and
before a command, so that a script these run reads it, when the field goes (a turn of the screen), and again after the
link drops. A script's new text replaces the field's, the player's unsent typing with it, as does making it read-only;
the field tells one from the player's own text by `ScriptWindows.textBoxEdits`, which only the engine's change of the
text moves, so a key typed as the last text went is kept. It is replaced as the field is drawn, not in an effect a frame
before the field's editor has it, where an edit of the keyboard's that changes the field (the composing region it sets
on a word, or a key) would put the old text back, and a tap would send it. A `textBox` the engine sent before it had
the player's latest text (its `typed` short of what the app sent) keeps the player's text. The app counts what it sent
by the link's connection it went out on (`EngineLink.connection`), so typing sent on a new connection before the main
thread sees the attach stays counted, as the engine counts it. A refusal's `textBox` puts back what Mudlet holds.

### Gauges

Once labels draw, gauges draw: they are three labels. F3 adds their meaning: the app finds sibling labels
`<name>_back`, `<name>_front` and `<name>_text` and treats them as one gauge, with the value
`Geyser.Gauge:setValue()` gives the back label (gap 2, carried), and else the one read from the front label's width
against the back label's, as for a gauge drawn by a Mudlet without the patch or by hand.

### Button bars and commands

Bars with `place` `top` are a horizontally scrolling row above the console, `left`, `right` and `floating` ones pages
of the window switcher, each laid out by `columns` and `fillerOffset` as upstream describes. A button is a Material
button with its icon and name, drawn with its style sheet's subset as a label's; a push-down button is a toggle; a
folder opens a menu of its `children`, and an entry picked there is pressed, then the folder, as upstream says the
GUI does. Commands `onMenu` go into the top bar's overflow menu under "Extensions", nested by `menuPath`; those
`onToolbar` are icon buttons in the top bar, three at most, the rest in the overflow menu. A pulsing command shows a
dot instead of flashing. Shortcuts wait for hardware keyboard support (`pressKey()`), when the app's own keys go to
`setReservedShortcuts()`.

### The map

`map` is an `Image` at the `mapper` window's place. Taps go back as `mapPointer` presses, a double tap speedwalks, and
pinch and zoom buttons send wheel turns. The image is laid out at the window's size in Mudlet's pixels, so the
pointer lands where `renderMap()` drew, and drawn at the scale the app shows it at (`setMapScale`), so it is as sharp
as the screen.

## Touch and TalkBack

Phase E's requirements apply to every pull request here; in short, and what they mean for script windows:

- **Labels.** A label with `accessibleButton` is a button (`Role.Button`) whose label is its `accessibleName`, else
  its text without formatting, else its tool tip; its click is the tap's `pointer` sequence, and a right-click menu
  is a custom accessibility action. A label with text and no callbacks is text. One with only an image and no
  `accessibleName` is decorative and left out of the tree, unless it is a button, which then gets its name from its
  window name, and a lint-style warning in the log. A label with a live region (gap 3) is one node with
  `liveRegion`, blank or not, so TalkBack says its new text as the desktop does, its first text too. An unnamed one
  reads its text, its links as actions as a named label's are; a named one, or a named button, has its text as its
  `stateDescription` unless its name says the same, so its new text is said after its name. Unlike the desktop,
  TalkBack also reads a live label when a script makes it live or changes its region, when its name changes, and
  (its name alone) when a named one's text goes blank; and the app does not wait for the profile's "Make script
  buttons reachable with the keyboard" setting (`accessibleLabels`), which the desktop needs too. A label that is not
  a button and has an accessible value (`setLabelAccessibleValue()`, gap 2) is a progress bar, as on the desktop:
  `progressBarRangeInfo` with its value and range, its name, else its text, as its content description, and its
  percentage as its `stateDescription`, after its text where its name does not say it ("Level 3, 40%"), so the value
  is always said; not live, for the reason a gauge is not, so the `Announcer` says a live one's new text.
- **Gauges** are one node: `progressBarRangeInfo` with the value and range (the `_back` label's accessible value,
  else 0 to 100 from the front label's width), the `_text` label's text, else the percentage, as
  `stateDescription`, the three labels merged into it. That node is not live, as a live node is read again at every
  change of the value: when the `_text` label is live, the `Announcer` says its new text, as the desktop says only
  that label's (the others have no text); twice while TalkBack's focus is on the gauge, which reads its new state
  itself.
- **Text boxes** are editable fields named by their `placeholder`, else their window name, with ", read only" when
  the player cannot type in them (still focusable, so TalkBack reads their text). The name is on a node inside the
  field, as an editable field may not carry a content description itself (the accessibility checks'
  `EditableContentDescCheck`), and TalkBack reads it with the field. That node is as big as the field, its 48 dp
  touch area included: inside the box's frame and margins a small box leaves it no size, and a node of no size is not
  read, which leaves an empty field nothing to say. Their keyboard capitalises sentences.
- **Button bars and commands** are buttons with their names; push-down buttons and checkable commands are toggles
  (`Role.Switch` with `toggleableState`); a folder says it opens a menu. An icon-only one has its name as its content
  description.
- **Mini consoles** are lists TalkBack moves through line by line, as the main console after U6. Their new lines are
  not announced: Mudlet announces only the main console's (`announceIncomingText`).
- **48 dp.** Script geometry can make a clickable label or a text box small: its touch bounds grow to 48 dp around
  it, drawn at its own size, the higher in `stack` winning where two overlap.
- **No gesture alone.** Long press has its accessibility action; the map's pinch has zoom buttons; a script
  console's pinch and two-finger double tap have its "Larger text", "Smaller text" and "Reset text size"; the window
  switcher's swipe between pages has its tabs; a drag on a label has no other way, which is the script's design, so
  the label's actions say what a tap does.
- **Focus order**: the main console, the input line and the game screen's own buttons, as in Phase E; then the
  script windows in reading order (top to bottom, then left to right, by their scaled geometry, with
  `traversalIndex`); then the button bars; then the last announcement. The commands' Extensions button is in the title
  bar, read first with Back.
- **Strings** the app adds (the switcher, "Extensions", the gauge and menu wording) are in `strings.xml`. What
  scripts write is shown as they wrote it.
- **Tests**: every UI test rule calls `enableAccessibilityChecks()` (API 34 and later), so a label without a name or a
  small target fails CI.

## Pull request sequence

As in Phase E, protocol and engine pull requests (P) go one at a time; app pull requests (U) follow their P. All come
after Phase E's P5 (version 6). Versions are the next free one at the time; the numbers assume none other is taken.

| PR | What | Version | Needs |
|---|---|---|---|
| F0 | This plan | - | - |
| F1P | `windows`, `windowText`, `mainArea`, `windowCommandLine`; `setMainWindowSize`, `getWindowText`, `placeUserWindow`, `closeUserWindow`, `sendWindowCommandLine` (`window` on `getLink` and `followLink` came later, in version 12) | 7 | P5 |
| F1U | Fitted and stacked layouts, the window switcher, mini consoles, user windows and their command lines | - | F1P, U5, U7 |
| F2P | `label`, `image`, `pointer`, `getImage` | 8 | F1P |
| F2U | Labels: style sheet subset, images, movies, rich text, taps; the label semantics | - | F1U, F2P |
| F3 | Gauges: grouping, value, progress semantics (app only, inferring the value until upstream gap 2 lands) | - | F2U |
| F4P | `buttonBars`, `commands`, `pressButton`, `activateCommand` | 9 | F2P |
| F4U | Button bars, commands in the top bar | - | F2U, F4P |
| F5P | `map`, `mapPointer`, `setMapViewOnly` | 10 | F2P |
| F5U | The map window | - | F2U, F5P |

F4 and F5 are independent of each other and of F3.

**Tests for each:**

- **P pull requests:** `ProtocolSpecTest`; `protocol_client.py` and the JVM test against the stand-in (empty window
  state, refusals); in `test_mudlet_engine.py`, a Lua script that builds the windows through Geyser and checks the
  frames:
  - F1P: `Geyser.MiniConsole:new{name="chat", x=0, y=0, width="30%", height="40%"}` and `chat:echo()` give `windows`
    with its geometry and `windowText` with the coloured row; `Geyser.UserWindow:new{name="side"}` and
    `placeUserWindow` raise `sysUserWindowResizeEvent` (a handler `echo()`s into the main console); `setBorderLeft()`
    changes `mainArea`; `hideWindow()` gives `shown` false; a `windows` `full` on reattach; `enableCommandLine()` and
    `sendWindowCommandLine`.
  - F2P: `Geyser.Label` with `setClickCallback`, a `pointer` press and release run it (it `echo()`s); `echo()` on the
    label gives a `label` with that text; `setStyleSheet()` arrives verbatim; `setBackgroundImage()` and `getImage`
    give a PNG of the right size; `setLabelAccessibleName()` and `accessibleButton`.
  - F4P: `tempButtonToolbar()` and `tempButton()` give `buttonBars`; `pressButton` sends the button's command to
    `fake_telnet.py`; `addCommand{}` gives `commands`, `activateCommand` raises `sysCommandClicked`.
  - F5P: a map built with `addRoom()`, `setRoomCoordinates()` and `openMapWidget()` gives a `map` PNG of the mapper's
    size; a `mapPointer` press selects the room (`getMapSelection()` through `runLua` and an `echo()`).
- **U pull requests:** Compose UI tests against `FakeEngine` on the API 28 and 36 emulators, with the accessibility
  checks: a fake `windows` and `windowText` draw a mini console at its scaled place in both layouts; a label tap
  sends the `pointer` sequence; a link in a label sends `followLabelLink`; a gauge exposes its range; a button bar's button sends `pressButton`; an MXP frame's
  tab sends `selectFrameTab`, the page brought forward then drawn under its tabs; TalkBack's focus order. `android_smoke.py` with libmudlet runs one Geyser script on the emulator and checks a mini console's
  line reaches the UI. On-device checklist items for what only a person judges: a game's real GUI package (for
  example a Geyser layout from a game's own package) in both orientations, and TalkBack over it.

## Upstream gaps

Until each is merged, the interim in its last line holds. These go to Mudlet/Mudlet as pull requests. All five are
carried in `scripts/mudlet-patches/` until merged. Besides those
patches, `scripts/build-libmudlet.sh` carries the Lua 5.1 FORTIFY fix (Mudlet/Mudlet#12914), not in
`3rdparty/lua51/CMakeLists.txt` at `bfc932d9`.

Each prompt is ready to paste into a Claude Code session on a checkout of Mudlet/Mudlet's `development`.

### 1. Follow a label's link

> In Mudlet's headless session (`src/headless/THeadlessSession.h`, `THeadlessSessionWindows.cpp`), add
> `LinkResult followLabelLink(const QString& label, const QString& href)`, which does what
> `TLabel::slot_linkActivated()` (`src/TLabel.cpp`) does for a link the player clicks in a label: mark it visited and
> restyle it, then `send:`, `prompt:` (the main command line, through `commandLineTextRequested`), `http(s):`
> through `QDesktopServices`, or run it as Lua, with the same error-console note for a scheme that is neither. Move
> that logic out of `TLabel` into `TLabelModel` (or a free function) so both share it. Refuse it where `runLua()` is
> refused, for a name that is not a label's, and for an `href` that is not one of the label's links. A visited link's
> new style must change `labelContent().text` and name the label in `scriptWindowsChanged`. Document it in
> `docs/headless.md` ("the windows scripts make"), and add tests to the headless session's test suite: each scheme,
> the refusals, and the visited restyle. PR title: "Headless: follow a label's link". PR text: an app drawing labels
> itself has no `QLabel` to emit `linkActivated`, so a label link (`send:`, `prompt:`, Lua) does nothing on the
> Android client and any other headless frontend.

Interim: replaced. The app carries this as `scripts/mudlet-patches/0014-headless-label-links.patch` until Mudlet
merges it, in `THeadlessSessionLinks.cpp`, where the other links are followed, and with the scheme handling moved to
`TLabelModel::followLink()`. The engine takes the player's tap as `followLabelLink` (`protocol.md`, "Labels"),
but for a web link: the app opens that itself, with an intent, as the engine, a service, has no activity to start a
browser from. Nor does a web link take the visited style: the engine is not sent a link it does not open, and the
patch marks a link visited only as it follows it, so marking one alone would take a call of its own there and a way
to ask for it in the protocol. After a `prompt:` link the input line takes the focus, so the keyboard opens and
TalkBack moves there, as Mudlet's label gives its command line the focus. The engine follows only an `href` its
label's text has, as Qt reads it, so the app reads hrefs as Qt does (the "Text" bullet of "Labels").

### 2. A label's accessible value, for gauges

> Add an accessible value to Mudlet's labels: Lua `setLabelAccessibleValue(name, value, min, max)` and
> `getLabelAccessibleValue(name)` (`src/TLuaInterpreterUI.cpp`, registered beside `setLabelAccessibleRole`), stored in
> `TLabelModel`; `clearLabelAccessibleValue(name)` removes it. On the desktop, `TAccessibleLabel` implements
> `QAccessibleValueInterface` and reports the progress bar role while a value is set. `Geyser.Gauge:setValue()`
> (`src/mudlet-lua/lua/geyser/GeyserGauge.lua`) sets it on the gauge's `_back` label with 0 to 100 (or the max it was
> given), and its `_text` label's plain text as the accessible description. In the headless session, add
> `std::optional<AccessibleValue> accessibleValue` (`value`, `min`, `max`) to `THeadlessSession::LabelContent`, so a
> change names the label in `scriptWindowsChanged`. Tests: the Lua functions and their argument errors, Geyser's
> gauge setting it, `labelContent()` carrying it, the desktop accessible interface. Document the functions for the
> wiki and in `docs/headless.md`. PR title: "Give labels an accessible value, so screen readers read gauges". PR text:
> a Geyser gauge is three labels, so screen readers on the desktop and on Android cannot tell its value; this gives
> the gauge's value to both.

Interim: replaced. The app carries this as `scripts/mudlet-patches/0006-headless-label-accessible-value.patch` until
Mudlet merges it; the engine sends it as the `label`'s `value`, `valueMin` and `valueMax` (`protocol.md`, "Labels").
`Geyser.Gauge:setValue()` and `setGauge()` set it on the gauge's back label, and clear it for a reading they cannot
give. The app still finds a gauge by the `_back`, `_front` and `_text` names, and reads the front label's width only
for a gauge whose back label has no value. The patch leaves out the `_text` label's text as the accessible description,
which labels do not have yet; the app reads that text as the gauge's state as before.

### 3. A label's live region in `LabelContent`

> In `src/headless/THeadlessSession.h`, add the label's live region (`setLabelLiveRegion()`: off, polite or
> assertive, from `TLabelModel`) to `THeadlessSession::LabelContent`, filled where `accessibleName` is in
> `THeadlessSessionWindows.cpp`, so a change names the label in `scriptWindowsChanged`. Test it in the headless
> session's tests and document it in `docs/headless.md`'s list of `labelContent()` fields. PR title: "Headless: give
> a label's live region". PR text: a frontend that draws labels itself needs it to announce a label's changes as the
> desktop's screen reader support does.

Interim: replaced. The app carries this as `scripts/mudlet-patches/0004-headless-label-live-region.patch` until
Mudlet merges it; the engine sends it as the `label`'s `liveRegion` (`protocol.md`, "Labels").

### 4. Text boxes

> In the headless session, give an app what `createTextEdit()` windows hold: `std::optional<TextBoxContent>
> textBoxContent(const QString& name) const` with `text`, `readOnly`, `placeholder`, `styleSheet` and `tabMovesFocus`,
> as `getTextEditText()` and the `setTextEdit*()` functions (`src/TLuaInterpreterUI.cpp`) read and set them; a script
> setting them names the window in `scriptWindowsChanged`; and `bool setTextBoxText(const QString& name, const
> QString& text)` for what the player typed, refused for a read-only one, a box not shown, and where `runLua()` is
> refused, raising whatever the GUI's edit raises as its text changes. Tests and `docs/headless.md` as for the other
> window calls. PR title: "Headless: text boxes' content". PR text: `createTextEdit()` boxes are listed by
> `scriptWindows()` but an app cannot draw or edit them.

Interim: replaced. The app carries this as the `headless-text-boxes` patch in `scripts/mudlet-patches/` until Mudlet
merges it; the engine sends it as the `textBox` frame and takes the player's typing as `setTextBoxText`
(`protocol.md`, "Text boxes").

### 5. MXP frame tabs

> In the headless session, list an MXP frame's tab pages (title, and which is in front) in `ScriptWindow`, and add
> `bool showMxpFramePage(const QString& frame, int page)` as a click on the tab header, so `docs/headless.md`'s "What
> still needs a window" can drop its tab header item. Tests with an MXP `<FRAME>` and its pages. PR title:
> "Headless: MXP frame tabs". PR text: an app shows only the front page of a tabbed frame today.

Interim: replaced. The app carries this as `scripts/mudlet-patches/0008-headless-frame-tabs.patch` until Mudlet
merges it, with the page named rather than numbered: `frameTabs` on the header frame's `ScriptWindow` and `bool
selectFrameTab(const QString& page)`. The engine sends it as `tabs`, `tabTitles` and `tabCurrent` in `windows` and
takes the player's tap as `selectFrameTab` (`protocol.md`, "Script windows").
