# Engine socket protocol

How the UI process talks to the engine process, as implemented by:

- the engine: [`native/enginehost/SocketBridge.cpp`](../native/enginehost/SocketBridge.cpp), a socket's, and
  [`ControlBridge.cpp`](../native/enginehost/ControlBridge.cpp), the control socket's (C++, Qt);
- the app: [`protocol/`](../protocol/src/main/kotlin/org/mudlet/android/protocol) (`Protocol.kt` and `EngineLink.kt`);
- the host tests' client: [`native/tests/protocol_client.py`](../native/tests/protocol_client.py).

`ProtocolSpecTest` (in `:protocol`) checks that the message tables below, the C++ engine and the Kotlin client name
the same messages and fields, and that all of them, and the Python client, use the same protocol version. Change
this file in the same commit as the code.

The current version is **27**.

## Transport

- A Unix domain stream socket in the filesystem namespace.
  - On Android the path is `<noBackupFilesDir>/engine.sock`, in the app's private directory, so only the app's uid
    can reach it (`EngineSocket.kt`).
  - On the desktop the engine takes `--socket <path>`, else `MUDLET_ENGINE_SOCKET`, else
    `<AppDataLocation>/engine.sock`.
- The engine listens; the UI connects.
  - The engine removes a stale socket file before it listens, as a killed engine leaves one behind.
  - It listens with `QLocalServer::UserAccessOption`.
  - It checks each peer's uid with `SO_PEERCRED` against its own, and closes a connection from any other uid.
- The control socket is the one above. Each open profile has a socket of its own beside it, `engine-p<slot>.sock`
  ("Several profiles" below), which the engine makes and checks the same way, and removes when the profile closes.
- One UI at a time on each socket. A new connection replaces the current one, which the engine closes.
- The UI reconnects on its own when the engine goes away (`EngineLink`). It retries every 50 ms for 2 s, then backs
  off from 100 ms to at most 2 s.

## Framing

A frame is a 4-byte unsigned big-endian payload length, then the payload: one JSON object in UTF-8.

- At most 16 MiB (16,777,216 bytes) of payload.
  - The engine closes a connection whose frame announces more.
  - The UI treats a longer frame, or a length with the top bit set, as a lost connection and reconnects.
- Every object has `t`, a string naming the message type.
- The order of keys means nothing. The engine sends them sorted, as `QJsonDocument` writes them.
- A payload that is not a JSON object:
  - from the UI: the engine closes the connection;
  - from the engine: the UI treats it as a lost connection.
- Both sides ignore fields they do not know.
- Numbers are JSON numbers. Sequence numbers and ids fit in 53 bits, so a double holds them exactly.

## Messages

Fields marked `?` may be absent. The "Fields" column is read by `ProtocolSpecTest`: it lists every field of the
message, in backquotes, with `?` after an optional one, and the fields of the objects in it, after a colon.

### UI to engine

| Message | Fields | Meaning |
|---|---|---|
| `hello` | `v`, `client`, `instance`?, `lastSeq`?, `profileSockets`? | Must be the first frame. `v` is the protocol version, `client` names the client (the app sends `android-compose`; the engine ignores it). `instance` and `lastSeq` are what the UI already has: the engine instance it last attached to and the last line `seq` it holds. The app sends both or neither. `profileSockets`, false if absent and read only on the control socket, says the UI attaches to each profile's socket, so the control socket carries only the control traffic ("Several profiles" below). |
| `ping` | `id` | Asks for a `pong` with the same `id`. |
| `connect` | `host`, `port`, `tls`? | Connects the engine to a game server. `port` defaults to 23. `tls` true encrypts the connection, false does not; absent, the profile's own choice stands. libmudlet takes `tls` only when this `connect` starts the profile, and stores it with the profile, as Mudlet's connection dialog does: while the profile runs, a `tls` other than the one it started with gets status `connection` `error: ...`. A `tls` that is not a boolean gets `error`. |
| `send` | `text` | A command line. Taken as typed input (`THeadlessSession::submit()`). |
| `sendHidden` | `text` | What the player typed while `hiddenInput` holds, as a password: straight to the game, with its line breaks taken out, and no aliases, echo or history (`submitHiddenInput()`). A failure is status `sendError`, which never quotes the text. Neither engine logs it. |
| `dismissHidden` | | The player closed the masked line without sending, as Esc on Mudlet's empty hidden-input box (`dismissHiddenInput()`): `hiddenInput` false follows, and `send` is the way to the game again. libmudlet hides input again once the game answers the next line sent, as Mudlet does. Refused with status `sendError` while input is not hidden. |
| `complete` | `text`, `fromHistory`?, `id`? | Completes the input line `text`, answered by status `completion`: the whole completed line, or empty for none. `fromHistory` true completes it from the history, as the up arrow does in Mudlet with "Highlight history" on: the newest line that starts with `text`. Otherwise, as Tab, it completes the last word from the words the game sent lately and those scripts suggested. A `complete` whose `text` is the line the last one answered goes on to the next word or older line. libmudlet runs `setCommandLineText()`, then `completeCommandLine()` or `completeCommandLineFromHistory()`; nothing completes while input is hidden. A `fromHistory` that is not a boolean gets `error`. |
| `disconnect` | | Closes the game connection. |
| `runLua` | `code`, `id` | Runs Lua in the engine. The result comes back as status `luaResult` with `re` set to `id`. |
| `setScreenSize` | `columns`, `rows` | The UI's console in characters of the font it draws in, each a whole number from 1 to 65535 (else `error`): the UI does the measuring. A game that asks (telnet NAWS) is told it. libmudlet gives it to each profile as it loads and to the running one at once: to `setScreenSize()`, so the profile's scripts read it as the main window's size, until `setMainWindowSize` has given that size, and from then on to `setGameScreenSize()` (carried in `scripts/mudlet-patches/`), which tells the game the console's size and leaves the main window's alone; as in Mudlet, a width below 40 counts as 40, the game is told no more than the profile's wrap width, and of a change once the size has held for 0.3 s. Until the UI sends one, a game is told the main console's grid: that of the size `setMainWindowSize` gave, or 90 by 25. |
| `getLink` | `link`, `window`?, `id`? | Asks what the link with that id of the console `window` offers now, answered by `link`, or by `error` `getLink: unknown` for an id the console no longer holds, one from a main console line of a profile opened before the one running, a `window` that is no console's, or with no profile open. `window` is a mini console, user window or MXP frame as `windowText` names it, and the main console when absent, empty or `main`; one that is not a string gets `error`. `link` is the id a run carries, a whole number from 1 (else `error`). |
| `followLink` | `link`, `item`?, `window`?, `id`? | Follows the link of `window`, as for `getLink`, as a tap does, or picks `item`, counted from 0, from `link`'s `items`, as its menu does (`followLink()`): a spoiler not yet revealed is only revealed, else its command or function runs. Nothing answers a link that ran or was revealed; `linksChanged` follows what that changed of it, or for a script window's link that window's next `windowText`. Otherwise `error` answers with `followLink:` and the name of the `LinkResult`: `disabled` (a disabled link, or a menu's greyed-out item), `concealed`, `noSuchItem`, `unknown` (as for `getLink`), or `failed` or `refused` followed by a colon and why. A `link` that is not a whole number from 1, an `item` that is not one from 0, or a `window` that is not a string, gets `error`. |
| `setAppState` | `active`, `minimized` | The app is in front (`active`) or not, and its window is minimized or sent to the background (`minimized`), each a boolean (else `error`). libmudlet gives `active` to `setApplicationActive()`, so a change raises `sysApplicationFocusChangeEvent` once in each open profile, as the GUI's window gaining or losing focus does, and `minimized` to each profile's `setWindowMinimized()`, so that, with the profile's "notify on new data while minimized" on, game text then asks for `attention`. When `active` turns false, the open profiles are saved as Mudlet saves them at a close, but with no `sysExitEvent`, as the system may stop the engine without warning once the app has left the front: status `save` tells how that went. Until the first, `active` is true and `minimized` false; each holds for the profiles opened later. |
| `listProfiles` | `id`? | Asks for `profiles`. |
| `saveProfile` | `name`, `host`, `port`, `tls`, `login`, `autoReconnect`, `id`? | Creates the profile `name`, or updates the one of that name, ignoring case; an update keeps the name it was created with. All the fields are needed: `host` not empty, `port` from 1 to 65535, `tls` and `autoReconnect` booleans, `login` a string, which may be empty. A new `name` must be one Mudlet can make a folder of: only the letters A to Z, the digits, spaces and `. _ ( ) - # &`, no space at either end, and neither `.` nor `..` in it. A running profile is given its new `login` at once, for its next connection; its other details it read when it started, so those change at its next start. |
| `deleteProfile` | `name`, `id`? | Deletes the profile, with everything Mudlet keeps for it. Refused while it is running, being opened or closed, or waited for by a restore. |
| `openProfile` | `name`, `password`?, `connect`?, `alongside`?, `id`? | Starts the profile `name`. Without `alongside`, those open are first closed and saved, as Mudlet closes a profile; with it, the profile starts beside them, and `error` `openProfile: at most 4 open` refuses a fifth. `password` is for this run only: Mudlet's auto-login sends the profile's `login` and then this password once it connects, and nothing stores it. `connect`, true if absent, connects the profile to its own address. Opening the profile that is running leaves it running, gives it `password` in place of the one it had, none if absent, for its next connection, and connects it if `connect` and it is not connected; the UI sends it so, with `alongside` and `connect` false, when the player stores or forgets the password of a game that is open. While a profile is being opened, `connect`, `disconnect` and `runLua` are for it. |
| `setMainWindowSize` | `w`, `h` | The main window's size in Mudlet's pixels, whole numbers above 0. The borders scripts set take their room from it, and the main console's font decides the grid in what is left (`mainArea`). libmudlet gives it to the running profile at once (`THeadlessSession::setMainWindowSize()`, which raises `sysWindowResizeEvent`) and keeps it for each profile that loads later, which may still refuse it: `mainArea` then gives 0 for it. A `setScreenSize` before or after it leaves it the main window's size; the game is still told the console's columns and rows, not the grid Mudlet's font fits in this size. |
| `getWindowText` | `window`, `first`, `count`, `id`? | Up to `count` rows, at most 1000, of the console `window` (a mini console, user window or MXP frame; `main` is the main console, as in Mudlet) from row `first`, counted from 0. A negative `first` counts back from the end, so `-count` is the last `count` rows. Answered by `windowText`, or `error` for a name that is no console's. |
| `placeUserWindow` | `name`, `x`, `y`, `w`, `h`, `floating` | Where the UI put the user window `name`, in the main window's pixels, docked or `floating` (`setUserWindowGeometry()`). A new size raises `sysUserWindowResizeEvent`. `w` and `h` above 0. |
| `closeUserWindow` | `name` | The dock's close button: hides the user window, as `hideWindow()` does. |
| `sendWindowCommandLine` | `name`, `text` | Enter on the command line `name`, a console's own from `enableCommandLine()` or one from `createCommandLine()`, holding `text` (`submitWindowCommandLine()`): the line's action, or else the aliases, take each line of it. |
| `setTextBoxText` | `name`, `text` | What the player has typed in the text box `name` so far: the whole of its `text`, a string (`THeadlessSession::setTextBoxText()`, "Text boxes" below). Refused for a box that is read-only or not `shown`, with `error` and then the box's `textBox` as the engine holds it, so the UI puts back what it showed. |
| `selectFrameTab` | `name` | The player's tap on a tab of an MXP frame's tab header ("Script windows" below): brings the page `name`, an MXP frame, forward (`THeadlessSession::selectFrameTab()`, carried in `scripts/mudlet-patches/`), so the header's frame and the two pages come in the next `windows`. Nothing happens for the page already in front. |
| `followLabelLink` | `label`, `href`, `id`? | The player's tap on the link `href` in the text of the label `label` ("Labels" below): the engine follows it as Mudlet's click on it does (`THeadlessSession::followLabelLink()`, carried in `scripts/mudlet-patches/`): a `send:` link's command goes to the game through the aliases, a `prompt:` link's text comes back as `commandLine`, and anything else runs as Lua. The UI gives its input line the focus as it sends a `prompt:` link, as Mudlet's label gives its command line the focus. A `prompt:` link is refused while the game hides input (`hiddenInput`), and nothing runs: Mudlet's desktop puts its text in the hidden-input box then, which the engine does not pass on. `href` is the link's target as Qt reads it from the label's text: a quoted value whole, up to its own closing quote, its whitespace kept and its entities decoded as Qt decodes them (numeric ones, and the named ones of Qt's table: HTML 4's, with `&and;` and `&or;` as U+22A5 and U+22A6, not HTML's U+2227 and U+2228, and also `&AMP;`, `&GT;`, `&LT;`, `&QUOT;`, `&apos;` and `&percnt;`), but in a single-quoted value, which Qt keeps as written. A web link (`http:` or `https:`) is always refused, as the UI opens it. |
| `pointer` | `window`, `type`, `x`, `y`, `button`?, `buttons`?, `modifiers`?, `wheelY`? | What the UI's pointer did at `x`, `y` (numbers, which may have fractions) in the pixels of the window `window`, from its top left; `main` is the main console's area (`THeadlessSession::pointer()`). `type` is `press`, `release`, `doubleClick`, `move`, `enter`, `leave` or `wheel`. `button` is the one that changed, for a press, release or double-click, and `buttons` those held after it, as Qt's flags: 1 left, 2 right, 4 middle. `modifiers` are Qt's: 0x02000000 Shift, 0x04000000 Control, 0x08000000 Alt, 0x10000000 Meta. `wheelY` is the wheel's turn in eighths of a degree, 120 a notch, positive away from the player. Each of those four is 0 when absent. A label's callback runs as the GUI's would, with the event table it gives (`button` `LeftButton` and the rest, `x`, `y`); a press or release no label takes goes on to the console under it. |
| `getImage` | `path`, `w`?, `h`?, `frame`?, `id`? | The image file at `path`, as a label's `image` or `movie` names it, as a PNG, answered by `image` or `error`. With `w` and `h`, which go together, from 1 to 4096, it is scaled to that size; a path that is no image file is then taken as an icon file, or the name of one in the icon theme, at that size. `frame`, from 0, is that frame of an animated image. Without `w` and `h`, an image larger than 4096 pixels a side is refused, as is a PNG too large for one frame. |
| `pressButton` | `button` | A click on the button or menu entry of `buttonBars` whose `id` is `button`, a whole number (`THeadlessSession::pressButton()`): it sends the button's command through the aliases and runs its script, as the GUI's button does, and a push-down button goes down, or up if it was down. To pick an entry of a folder's menu, the UI presses the entry, then the folder, as the GUI does. |
| `activateCommand` | `command` | A click on the command of `commands` whose `id` is `command`, a whole number (`THeadlessSession::activateAddonCommand()`): a checkable command is ticked or unticked, then the event `sysCommandClicked` is raised with its id. Refused for a disabled command. |
| `mapPointer` | `type`, `x`, `y`, `button`?, `modifiers`?, `wheelY`? | What the UI's pointer did on the map at `x`, `y`, in the pixels of the last `map` from its top left (`THeadlessSession::mapPointer()`, "The map" below). `type` is `press`, `doubleClick` or `wheel`; `button`, `modifiers` and `wheelY` are as for `pointer`, and 0 when absent. |
| `setMapViewOnly` | `viewOnly` | `true` to only view the map, `false` to edit it, as the GUI's mapper menu does (`THeadlessSession::setMapViewOnly()`): the event `mapModeChangeEvent` is raised with `viewing` or `editing`. |
| `setMapScale` | `scale` | Draws `map` from now on with `scale` device pixels to each of the mapper window's, a number from 1 to 8, so that it is as sharp as the UI shows it ("The map" below). libmudlet keeps it until the UI that asked goes, and draws at most 4096 pixels a side, at a smaller scale where that one would be larger. |
| `setAppMapSize` | `w`, `h`, `scale` | Asks for the app's own map, drawn at `w` by `h` of the map's pixels with `scale` device pixels to each, and sent as `appMap` whenever it changes ("The app's map" below); `w` and `h` 0 stop it. `w` and `h` are whole numbers, both above 0 or both 0; `scale` a number from 1 to 4. libmudlet keeps it for each profile that loads later, until the UI that asked goes, and refuses a map of more than 4096 device pixels a side. |
| `appMapPointer` | `type`, `x`, `y`, `button`?, `modifiers`?, `wheelY`? | As `mapPointer`, at `x`, `y` in the map's pixels of the last `appMap` (`THeadlessSession::appMapPointer()`). |
| `findRooms` | `text`, `id`? | Looks for the rooms of the map whose name holds `text`, ignoring case, as `searchRoom()` matches, and the room whose id `text` is (`THeadlessSession::findRooms()`), answered by `rooms`, best first. `text` must not be blank. |
| `showRoom` | `room` | Centres the app's map on the room whose id is `room`, a whole number, and selects it, as a pan on the desktop's mapper does (`THeadlessSession::showRoomOnAppMap()`): it stays until the player moves or a script centres the map. |
| `moveMap` | `map`, `dx`, `dy`, `zoom`, `x`, `y`, `seq` | A drag and a pinch on a map (`THeadlessSession::moveMapView()`, "Moving a map" below): `map` is `app` for `appMap` or `mapper` for `map`; what its last frame drew moves by `dx`, `dy` of its pixels, then zooms by `zoom`, a number above 0 (2 shows the rooms twice as large), about the point `x`, `y` of it. `seq` is a whole number from 1, which the UI counts up for each `moveMap`; the frames of that map drawn after it carry it in `view.moved`. |
| `showMapLevel` | `map`, `level` | Shows another level, a whole number, of the area the map `map` (as in `moveMap`) drew last, about the same point, as the desktop mapper's level buttons do (`THeadlessSession::showMapLevel()`): until the player moves to another room or a `followPlayer`. A level is one of `view.levels`. |
| `showMapArea` | `map`, `area` | Shows the area whose id is `area` on the map `map`, as picking it in the desktop mapper's area box does (`THeadlessSession::showMapArea()`): following the player where they are in it, else about the room nearest the middle of the level shown, or of its level with the most rooms, until the player moves to another room or a `followPlayer`. |
| `roomInfo` | `room`, `id`? | Asks for a room of the map, whose id is `room` (`THeadlessSession::roomInfo()`), answered by `room`. |
| `walkToRoom` | `room` | Walks the player to the room whose id is `room`, as a double-click on it on the desktop mapper does (`THeadlessSession::walkToRoom()`), with the profile's `doSpeedWalk()` or the shortest path by known exits. |
| `mapAreas` | `id`? | Asks for the map's areas that have rooms (`THeadlessSession::mapAreas()`), answered by `areas`. |
| `editRoom` | `room`, `name`?, `symbol`?, `env`?, `weight`?, `locked`?, `x`?, `y`?, `z`?, `id`? | Sets what it carries of the room whose id is `room`, as the desktop mapper's room properties and drag do (`THeadlessSession::editRoom()`): its `name`, `symbol` (empty clears it), environment `env`, `weight` (from 1), whether it is `locked`, and its place `x`, `y` (north up) and `z`, all three or none. Answered by `room`, the room as it now is. |
| `setRoomExit` | `room`, `direction`, `to`, `bothWays`?, `id`? | Leads the room's exit `direction`, one of the twelve as `room` names them, to the room whose id is `to`, or removes it for 0 (`THeadlessSession::setRoomExit()`); with `bothWays` true the exit back from that room the other way too (`in` for `out`, `up` for `down`), and when removing, the exit back that led to the room. Answered by `room`. |
| `lockRoomExit` | `room`, `direction`, `locked`, `id`? | Locks the room's exit `direction`, which the speedwalk then avoids, or unlocks it (`THeadlessSession::lockRoomExit()`). Answered by `room`. |
| `addRoom` | `room`, `direction`, `id`? | Makes a room one place from the room whose id is `room`, `direction` one of the twelve but `in` and `out`, in its area, with exits between them both ways (`THeadlessSession::addRoomBeside()`). Answered by `room`, the new room. |
| `deleteRoom` | `room` | Deletes the room and its exits (`THeadlessSession::deleteRoom()`). |
| `setPlayerRoom` | `room` | Places the player in the room, as a script's `centerview()` does (`THeadlessSession::setPlayerRoom()`): the maps follow them there. |
| `renameArea` | `area`, `name` | Renames the area whose id is `area` (`THeadlessSession::renameArea()`); spaces are trimmed from the ends of `name`. |
| `mapEnvs` | `id`? | Asks for the environments a room can be given (`THeadlessSession::mapEnvs()`), answered by `envs`. |
| `followPlayer` | `map` | Centres the map `map` (`app` or `mapper`, as in `moveMap`) on the player's room, following the player again (`THeadlessSession::followPlayerOnMap()`). |
| `closeProfile` | `name`, `id`? | Saves and closes that open profile, with its `sysExitEvent`; its socket closes. Answered by `openProfiles`, or `error` (`closeProfile: "<name>" is not open`). |
| `setFront` | `name` | The open profile the player is looking at: a UI that left out `profileSockets` sees it and its requests go to it. |
| `markSeen` | `name`, `seq` | The player has seen that open profile's lines up to `seq`: its `attention` clears, and its `unread` counts from there. |
| `downloadMap` | | Downloads the map the game offers, which `mapDownload` tells of, and loads it in place of the profile's, as the desktop mapper's "Download from game" does (`THeadlessSession::downloadOfferedMap()`, "Downloading the map" below). `mapDownload` follows how it goes. |
| `cancelMapDownload` | | Stops that download, as the Abort of Mudlet's progress dialog does (`THeadlessSession::cancelMapOperation()`), while `mapDownload`'s `cancelable` holds: `mapDownload` with `state` `cancelled` follows. |
| `scriptItems` | `kind`, `id`? | Asks for the open profile's triggers, aliases, scripts, timers, keys or buttons, `kind` `trigger`, `alias`, `script`, `timer`, `key` or `button`, as the desktop's script editor lists them (`THeadlessSession::scriptItems()`), answered by `scriptItems`. |
| `scriptItem` | `kind`, `item`, `id`? | Asks for all the fields of the item of that kind whose id is `item` (`THeadlessSession::scriptItem()`), answered by `scriptItem`. |
| `addScriptItem` | `kind`, `parent`, `position`, `folder`, `name`, `id`? | Makes an item of that kind, or a folder for `folder` true, named `name`, as the desktop editor's add does (`THeadlessSession::addScriptItem()`): in the folder whose id is `parent`, or at the top level for 0, at `position` among the items shown there (0 first; past the end appends); switched on, but not running until its first `editScriptItem`, or switched off for a timer or a button. Answered by `scriptItem`, the new item. |
| `editScriptItem` | `kind`, `item`, `fields`, `id`? | Sets the fields `fields` holds of the item, by the names `scriptItem` gives them, keeps the rest, and saves it as the desktop editor's save does (`THeadlessSession::editScriptItem()`; "Editing triggers, aliases and scripts" below). Answered by `scriptItem`, the item as it now is. |
| `enableScriptItem` | `kind`, `item`, `enabled`, `id`? | Switches the item on or off, as the desktop editor's checkbox does (`THeadlessSession::setScriptItemEnabled()`). Answered by `scriptItem`. |
| `moveScriptItem` | `kind`, `item`, `parent`, `position` | Moves the item into the folder whose id is `parent`, or to the top level for 0, at `position` among the items shown there, as a drag in the desktop editor does (`THeadlessSession::moveScriptItem()`). |
| `deleteScriptItem` | `kind`, `item` | Deletes the item, and all in it for a folder, as the desktop editor's delete does (`THeadlessSession::deleteScriptItem()`). |
| `checkLua` | `code`, `id`? | Compiles `code` as Lua without running it, as a script editor checks what is typed (`THeadlessSession::checkLua()`). Answered by `luaCheck`. Needs no open profile. |
| `getSettings` | `keys`, `id`? | Asks for the open profile's settings named in `keys`, a list of the keys Lua's `getConfig()` takes, answered by `settings` (`THeadlessSession::settings()`, carried in `scripts/mudlet-patches/`). `keys` must be a list of names, not empty. |
| `setSettings` | `values`, `id`? | Changes the open profile's settings: `values` is an object of `getConfig()`'s keys, each with its new value, true or false, a number or a string, and each is set as Lua's `setConfig()` sets it (`THeadlessSession::setSettings()`), so the profile keeps it and saves it with the rest. Answered by `settings`. `values` must be an object, not empty. |

Any request may carry `id`. The engine copies it, as it came, into the `re` of every `status` it sends while
handling that request, of the `profiles`, `openProfiles`, `windowText`, `image`, `rooms`, `room`, `areas`, `settings`, `scriptItems`, `scriptItem`, `luaCheck` or `error` that answers it,
and into `pong`.
Statuses that come later, such as `connection` once a connect succeeds, carry no `re`.

Each profile request is answered with `profiles` (`openProfile` from a UI that sent `profileSockets`, with
`openProfiles`), or with `error` whose `message` is the request's name, a colon and
why it was refused: a bad name or field, a profile that does not exist, or one that is in use. An `openProfile`
answered with `profiles` has begun: status `profile` tells how the close and the start go. In libmudlet a `connect`
before any `openProfile` starts the profile `MUDLET_PROFILE` names (`Android` in the app), making it if it does not
exist, as in version 1; after one, it connects the profile opened.

The script windows' requests are answered only when refused, with `error` whose `message` is the request's name, a
colon and why; `getWindowText` and `getImage` are always answered. libmudlet refuses all but `setMainWindowSize` and
`getImage` while no profile is open, and `pointer` as `THeadlessSession::pointer()` does: for a window that is not
shown, a point outside it, or another window than the one a held button went down on. It refuses `setTextBoxText` as
`THeadlessSession::setTextBoxText()` does: for a box there is not, one not shown and one read-only, and
`selectFrameTab` as `THeadlessSession::selectFrameTab()` does: for a name that is no page of a tab header, and for a
header whose page in front is not shown, as inside a hidden external frame. It refuses `followLabelLink` as
`THeadlessSession::followLabelLink()` does: for a label there is not, a link its text does not have now, Lua that
fails, with its error, a scheme Mudlet does not handle (`mailto:`, say) on what is no Lua command, and a `prompt:`
link while the game hides input; and for any web link.

`pressButton` and `activateCommand` too are answered only when refused, with `error` as above: libmudlet refuses
them while no profile is open, and for an id that is not one the last `buttonBars` or `commands` would list.

`mapPointer` and `setMapViewOnly` are answered only when refused, with `error` as above: libmudlet refuses them while no
profile is open, `mapPointer` also while no script has opened a map, `setMapViewOnly` also while no script's map shows
and the app's map is not open (no `setAppMapSize` above 0 by 0 since the profile loaded, or 0 by 0 or the UI that asked
gone since), and `mapPointer` as `THeadlessSession::mapPointer()` does: before the map has been drawn, for a
double-click of another button than the left, and for a wheel turn of less than half a notch or with no area to zoom.

`setMapScale` is answered only when refused, for a `scale` that is not a number from 1 to 8.

`setAppMapSize`, `appMapPointer` and `showRoom` too are answered only when refused, and `findRooms` by `rooms` or
`error`, as above. libmudlet refuses all but `setAppMapSize` while no profile is open, `appMapPointer` before the
app's map has been drawn and as `mapPointer`, and `showRoom` for a room that is not in the map.

`moveMap` and `followPlayer` too are answered only when refused, with `error` as above. libmudlet refuses both while no
profile is open, before that map has been drawn (for `mapper`, while no script has opened a map), for a `map` other
than `app` or `mapper`; `moveMap` for numbers that are not numbers, a `zoom` not above 0, a `seq` not a whole number from
1, and while the map drawn last shows no area; `followPlayer` while the map has no room for the player.

`showMapLevel`, `showMapArea` and `walkToRoom` too are answered only when refused, and `roomInfo` by `room` or `error`,
`mapAreas` by `areas` or `error`, as above. libmudlet refuses all five while no profile is open; `showMapLevel` and
`showMapArea` as `moveMap` is, but `showMapArea` not while the map drawn last shows no area, as it can show one then,
and for a `level` or `area` that is not a whole number; `showMapLevel` for a level the area has no rooms on, `showMapArea` for an area not in the
map or with no rooms; `walkToRoom` and `roomInfo` for a `room` that is not a whole number or not in the map, and
`walkToRoom` while the map has no room for the player. A walk that finds no path is not refused: Mudlet says so in the
console, as on the desktop.

`deleteRoom`, `setPlayerRoom` and `renameArea` too are answered only when refused, and `editRoom`, `setRoomExit`,
`lockRoomExit` and `addRoom` by `room` or `error`, `mapEnvs` by `envs` or `error`, as above; a refused edit changes
nothing. libmudlet refuses all eight while no profile is open, and the edits for a `room` or `to` that is not a whole
number or not in the map, an `area` not in the map or the default area, and as `THeadlessSession` does: `editRoom` for
a `weight` below 1, for fields of the wrong kind, for only some of `x`, `y` and `z`, and for none of its fields;
`setRoomExit` and `lockRoomExit` for a `direction` that is not one of the twelve, `lockRoomExit` also for one the room
has no exit in; `addRoom` for `in`, `out` or another `direction`, and where the area already has a room in that
place; `deleteRoom` while a script's map callback runs; `setPlayerRoom` while no map is open (no script's and not the
app's); `renameArea` for a `name` that is empty once trimmed or another area's.

`downloadMap` and `cancelMapDownload` too are answered only when refused, with `error` as above. libmudlet refuses both
while no profile is open; `downloadMap` while the game offers no map, and while the map downloads; `cancelMapDownload`
while no map downloads, and once the map has arrived and is being loaded. A script's map import or export runs within
its Lua call, so none is under way when a request is read; its progress is not sent as `mapDownload`, not even the last
of it, which Mudlet relays after a `downloadMap` read in the same pass.

`getSettings` and `setSettings` are answered by `settings`, or by `error` whose `message` is the request's name, a colon
and why: a `keys` or `values` that is not as above, and in libmudlet no profile loaded (`no profile running`). A value in
`setSettings` that is not true or false, a number or a string is refused in `settings`, and the rest are set.

`moveScriptItem` and `deleteScriptItem` are answered only when refused, and `scriptItems` by `scriptItems` or `error`,
`scriptItem`, `addScriptItem`, `editScriptItem` and `enableScriptItem` by `scriptItem` or `error`, as above; a refused
change changes nothing. libmudlet refuses all seven while no profile is open (`no profile running`), for a `kind` other
than the six, an `item` that is not a whole number from 1, a `parent` or `position` that is not a
whole number from 0, a `folder` or `enabled` that is not true or false, a `name` that is not a string and `fields` that
are not an object; and as `THeadlessSession` does: for an `item` not of that kind, or one a script made for a while,
which the desktop editor does not show; the changes for a `parent` that is not a folder of that kind, and while a
trigger, alias, timer, key, button or event handler runs; `editScriptItem` for a field not of that kind or that cannot
be changed (`id`, `folder` and the rest that `scriptItems` gives, a timer's `offset` and a key's `keyName`), a value of
the wrong type or out of its range, an alias whose `command` matches its own `pattern`, which would call itself
forever, and the folder a package or module's buttons came in, which Mudlet keeps as the package made it;
`enableScriptItem` for an item that cannot run (its Lua does not compile, a timer has no time, a key none chosen),
which it leaves off, with its error; `moveScriptItem` for a folder moved into itself or a folder inside it.

`checkLua` is answered by `luaCheck`, or by `error` for a `code` that is not a string. Code that does not compile is
no refusal: `luaCheck` says what is wrong with it.

### Engine to UI

| Message | Fields | Meaning |
|---|---|---|
| `hello` | `v`, `pid`, `instance`, `profile`? | The answer to the UI's `hello`. `pid` is the engine process's id. `instance` is an opaque string that differs for each run of the engine and each profile socket (now `<pid>-<ms since the epoch>`, and `-<slot>` after it on a profile's socket); its sequence numbers start again at 1. `profile` names the profile on a profile's socket. |
| `line` | `seq`, `text`, `runs`, `prompt`, `replay`? | A finished line, which never changes. `seq` counts lines from 1 for each instance, by one. `prompt` is true for a prompt (a line ended by a telnet go-ahead or end-of-record). `replay` is true on lines sent from the ring buffer in answer to `hello`. |
| `tail` | `text`, `runs`, `prompt` | The console's bottom line, which may still change or be ended. It replaces the previous tail. Empty `text` means none. |
| `clear` | | The console was emptied: the UI drops its lines and the tail. |
| `status` | `key`, `value`, `re`? | A named value of the engine's state. The engine keeps the last value of each key. `re` is the `id` of the request being handled. |
| `notify` | `title`, `text` | A notification the engine's Lua asked for (`showNotification()`). The engine process posts it to Android itself; the UI only learns of it. |
| `pong` | `id` | The answer to `ping`. `id` is absent if the ping had none. |
| `gap` | `skipped` | `skipped` lines fell out of the ring buffer before the UI could get them. They come just before the next line. |
| `synced` | `seq` | Ends the answer to `hello`: the newest line's `seq` (0 for none) when the engine greeted the UI. |
| `error` | `message`, `re`? | The engine could not handle a frame. After a bad `hello` it then closes the connection. |
| `openProfiles` | `profiles`: `name`, `slot`, `socket`, `state`, `connection`, `lastSeq`, `unread`, `attention`, `front`; `re`? | Every open profile, in the order opened, to a UI that sent `profileSockets`: on attach, on each change (new lines at most every 500 ms), and as the answer to its `openProfile` and to `closeProfile`. `socket` is the path of its socket, `state` is `starting`, `restoring`, `loaded` or `closing`, `connection` its last status `connection`, `lastSeq` its newest line, `unread` the lines after the last `markSeen`, `attention` whether it asked for `attention` since, and `front` true for the front one. |
| `profiles` | `profiles`: `name`, `host`, `port`, `tls`, `login`, `autoReconnect`; `re`? | Every profile, the last opened or created first, then any the engine has not opened, by name. Each is an object with the fields `saveProfile` takes; there is no password in it. |
| `hiddenInput` | `wanted` | The game hides what the player types, as for a password (`wanted` true), or has stopped: the UI masks its input line and sends what is typed there with `sendHidden`. libmudlet sends it from `hiddenInputWanted`, which a disconnect or the profile closing also ends, and never while the profile shows passwords as they are typed. Sent on each change, and to each UI that attaches. |
| `history` | `lines` | The input line's history, newest first, each line once, as Mudlet's up arrow walks it (`commandHistory()`): the lines `send` sent, as their aliases left them, and those the profile's earlier sessions kept, but nothing sent while input was hidden. Sent to each UI that attaches, and by libmudlet when a profile has loaded. In between, the UI adds the lines it sends itself; the next attach corrects what it guessed. |
| `announce` | `text`, `assertive` | What a script's `announce()` asks screen readers to say, as plain text (libmudlet's `announcementRequested`, which also carries Mudlet's own, as when a game's link is hidden or revealed). `assertive` is true for the `importantall` and `importantmostrecent` styles, which interrupt what is being said; otherwise it waits its turn. Not kept for a UI that attaches later. |
| `link` | `link`, `window`?, `tooltip`, `menu`, `menuTitle`, `spoiler`, `revealed`, `disabled`, `concealed`, `visited`, `selected`, `fg`?, `bg`?, `styles`?, `items`: `label`, `enabled`, `separator`; `re`? | The answer to `getLink`, from `THeadlessSession::link()`, or `windowLink()` for a script window's link: `window` is the console `getLink` named, absent for the main console. `tooltip` is plain text, empty for none. A tap follows the first of `items`; where `menu` is true, a long press lists them all, under `menuTitle` if it is not empty. An item has a `label`, is greyed out where `enabled` is false, and is a line between items, with no label, where `separator` is true. `spoiler` is a link a tap first reveals, `revealed` once it has been. A `disabled` link runs nothing; a `concealed` one the game's visibility settings hide, which the UI shows as blanks no tap reaches. `visited` it has been followed; `selected` it is the one chosen in its selection group. `fg`, `bg` (colours as in runs) and `styles` (attribute bits) are how it is painted now, absent where the run's own apply; `styles` replaces only the run's bold, italic, underline, overline and strike-out. |
| `linksChanged` | `ids` | What `link` says of these links of the main console may have changed (libmudlet's `linksChanged`): one was followed, revealed, chosen or deselected in its group, concealed or revealed by the game, expired, or let go of with the lines holding it, after which `getLink` answers `unknown`. A script window's links are not in it: a change to one of them comes as that window's next `windowText`. Empty `ids` means any link may have changed: the engine sends that to a UI that attaches, or catches up after lagging, when it missed a change. |
| `attention` | `ms` | A script's `alert()` or the game's bell asks for the player's attention for `ms` milliseconds (3000 for the bell), or, with 0, until they come back to the app; 0 is also game text that arrived while `setAppState` had the app minimized and the profile notifies on new data while minimized, once each time the app is minimized (libmudlet's `attentionRequested`). Not kept for a UI that attaches later. |
| `moveTypedLine` | | What the player typed ahead of the game hiding input has left Mudlet's copy of the input line, as the GUI's password box takes it from its command line (libmudlet's `typedLineMoveRequested`): the UI moves what is on its input line into its masked one and empties the input line. It comes just before `hiddenInput` true, and only when the input line held text Mudlet took as typed (`complete` sets that). Not kept for a UI that attaches later. |
| `commandLine` | `text` | A script set the whole input line, as `printCmdLine()`, `appendCmdLine()` and `clearCmdLine()` do, a `prompt:` link that `followLabelLink` followed put its text there, or Mudlet emptied it after a `send` (`commandLineTextRequested`): the UI replaces what is on its line. None comes while the game hides input (`hiddenInput`): Mudlet's desktop then writes a script's text to its hidden-input box, which the engine does not pass on, and the engine refuses a `prompt:` link. Not kept for a UI that attaches later. |
| `windows` | `full`?, `windows`: `name`, `kind`, `parent`, `parentKind`?, `x`, `y`, `w`, `h`, `visible`, `shown`, `stack`, `bg`?, `bgAlpha`?, `fontFamily`?, `fontSize`?, `inDock`, `autoDock`?, `dockArea`?, `dockFloating`?, `dockTitle`?, `scrolledBackTo`?, `columns`?, `rows`?, `scrollBar`?, `timeStamps`?, `tabs`?, `tabTitles`?, `tabCurrent`?; `removed`?: `name`, `kind` | The windows the profile's scripts made ("Script windows" below). With `full` true, all of them, replacing what the UI has; otherwise those that changed, and in `removed` those gone. |
| `windowText` | `window`, `lineCount`, `first`, `lines`: `text`, `runs`, `prompt`, `wrapped`; `re`? | Rows of a console: it holds `lineCount`, and `lines` starts at row `first`. Each row is a line after the console's wrap; `wrapped` is true for one that carries on from the row above. Sent with the last 300 rows when they may have changed, and for each console to a UI that attaches; with `re`, the answer to `getWindowText`. |
| `mainArea` | `w`, `h`, `columns`, `rows`, `textX`, `textY`, `textW`, `textH` | The main window's area: the size `setMainWindowSize` gave (0 for none), the main console's grid in characters, and the rectangle its text goes in, in Mudlet's pixels, which is the area less the borders scripts set. Sent as a profile loads, on each change and to each UI that attaches. |
| `windowCommandLine` | `name`, `text` | A script set the command line `name`, as `printCmdLine()` and the rest do given its name, or Mudlet emptied it after a command: the UI replaces what is on it. Not kept for a UI that attaches later. |
| `label` | `name`, `text`, `image`, `svg`, `svgTint`?, `svgTintAlpha`?, `svgRotation`?, `svgShearX`?, `svgShearY`?, `movie`, `moviePaused`, `movieFrame`, `movieSpeed`, `movieW`?, `movieH`?, `toolTip`, `clickThrough`, `accessibleName`, `accessibleButton`, `liveRegion`?, `value`?, `valueMin`?, `valueMax`?, `styleSheet` | What the label `name` shows ("Labels" below), after the `windows` that has it, each time it changes, and for each label to a UI that attaches. |
| `textBox` | `name`, `text`, `readOnly`, `placeholder`, `styleSheet`, `tabMovesFocus`, `typed` | What the text box `name` holds ("Text boxes" below), after the `windows` that has it, each time a script changes it, for each text box to a UI that attaches, and after a refused `setTextBoxText`; `typed` is how many `setTextBoxText` for it the engine had from this UI, since its `hello`, when it sent this. |
| `image` | `path`, `png`, `w`, `h`, `re`? | The answer to `getImage`: `png` is the image as a PNG, in base64, of `w` by `h` pixels. |
| `buttonBars` | `bars`: `id`, `name`, `styleSheet`, `place`, `vertical`, `columns`, `fillerOffset`, `buttons`: `id`, `name`, `icon`, `styleSheet`, `pushDown`, `checked`, `menu`, `children` | The profile's button bars, whole ("Button bars and commands" below): to each UI that attaches, and on each change. Empty with no profile open. |
| `map` | `png`, `w`, `h`, `viewOnly`, `view`?: `area`, `areaName`, `level`, `zoom`, `minZoom`, `maxZoom`, `following`, `player`, `roomSize`, `moved`, `rooms`: `id`, `x`, `y`, `name`; `levels`; `info`?: `lines`: `text`, `bold`, `italic`, `color`; `bg`, `bgAlpha` | The profile's 2D map as its mapper window shows it ("The map" below), with its map info as text ("The map info" below): `png` is a PNG, in base64, of `w` by `h` pixels at the scale `setMapScale` asked for, so of `w` times `scale` by `h` times `scale` pixels (`w` by `h` until it asks), and `viewOnly` false while the map is edited. Sent when it changes, and to a UI that attaches while the mapper shows. |
| `appMap` | `png`, `w`, `h`, `viewOnly`, `view`?: `area`, `areaName`, `level`, `zoom`, `minZoom`, `maxZoom`, `following`, `player`, `roomSize`, `moved`, `rooms`: `id`, `x`, `y`, `name`; `levels`; `info`?: `lines`: `text`, `bold`, `italic`, `color`; `bg`, `bgAlpha` | The app's own map, as `setAppMapSize` asked for it ("The app's map" below), with `info` as in `map`: `png` is a PNG, in base64, of `w` by `h` of the map's pixels at the scale asked for, so of `w` times `scale` by `h` times `scale` pixels, and `viewOnly` as in `map`. While there is nothing to draw, with no profile open or a map with no rooms, `png` is empty and `w` and `h` are 0. Sent when it changes, and only while asked for: not to a UI that attaches until it asks. |
| `settings` | `values`, `refused`?, `re`? | The answer to `getSettings` or `setSettings`: `values` holds each key it asked for or set with the value the profile holds now, as `getConfig()` gives it with `true` for its second argument: true or false, a number, or a string, an option of several by its name (`showSentText` as `never`, `always` or `script`). A key the engine does not know is left out. `refused`, only after `setSettings`, holds each key not changed with why: `setConfig()`'s message, its Lua error for a value of the wrong type, or that the value was not true or false, a number or a string. |
| `rooms` | `text`, `rooms`: `id`, `name`, `areaId`, `area`, `match`, `steps`; `more`, `re`? | The answer to `findRooms`: the `text` it looked for, and up to 100 rooms, each with its `id`, `name`, its area's id `areaId` and name `area`, how it matched in `match` (`exact` for a name that is the text ignoring case, `prefix` for one that starts with it, `contains`, or `id` for the room whose id the text is and whose name does not hold it) and `steps`, the fewest exits from the player's room to it by rooms and exits that are not locked, as the speedwalk goes (0 for the player's room, -1 for no way there or no room for the player). Best first: `exact` and `id`, then `prefix`, then `contains`; within each, the rooms in the player's area first, then by `steps` with -1 last, then by name ignoring case, then id. `more` is true when more rooms matched than were sent. |
| `room` | `id`, `name`, `areaId`, `area`, `x`, `y`, `z`, `steps`, `exits`: `direction`, `to`, `toName`, `locked`; `symbol`, `env`, `color`, `weight`, `locked`; `re`? | The answer to `roomInfo` and the edits that give a room: the room's `id`, `name`, its area's id and name, its coordinates, `steps` as in `rooms`, and its `exits`: the twelve directions first (`direction` `n`, `ne`, `nw`, `e`, `w`, `s`, `se`, `sw`, `up`, `down`, `in`, `out`), then the special exits by command, each with the id of the room it leads to `to`, that room's name `toName` (empty where it is not in the map) and whether it is `locked`; then what `editRoom` sets: its `symbol`, environment `env` and the colour that draws it `color`, as `#rrggbb`, its `weight` and whether it is `locked`. |
| `scriptItems` | `kind`, `items`: `id`, `parent`, `name`, `folder`, `enabled`, `active`, `package`, `error`, `summary`; `re`? | The answer to `scriptItems`: the `kind` asked for, and its `items` depth first, each folder before what is in it, in the desktop editor's order; each with its `id`, the id of the folder it is in, `parent` (0 for the top level), its `name`, whether it is a `folder`, `enabled` (switched on, as the editor's checkbox shows it), `active` (switched on, compiled, and in folders that run), the `package` or module it came with (empty for the player's own), `error`, why it cannot run, as plain text, or empty, and `summary`, what a list shows under its name, as plain text: a trigger's patterns and a script's event handlers, one a line, an alias's pattern, a timer's time as `hh:mm:ss.zzz`, a key's name as the desktop writes it (`control + F1`) and the command a button sends, or empty. The temporary items scripts make are left out, as the editor leaves them. |
| `scriptItem` | `kind`, `fields`, `re`? | The answer to `scriptItem` and the changes that give an item: its `kind` and its `fields`, an object of what `scriptItems` gives of it, `script`, its Lua, and those of its kind: a trigger's `patterns`, each with its `text` and its `type` (`substring`, `regex`, `startOfLine`, `exact`, `lua`, `spacer`, whose text is the number of lines, `color`, whose text is as the profile keeps it, as `ANSI_COLORS_F{1}_B{IGNORE}`, or `prompt`), `command`, `lineDelta` (the lines a trigger's patterns may be spread over, -1 for a trigger of one line), `matchAll`, `filter`, `fireLength` (the lines it stays open for), `sound`, `soundFile`, `highlight`, and `highlightFg` and `highlightBg` as `#rrggbb`, or empty to keep the text's colour; an alias's `pattern` and `command`; a script's `eventHandlers`, a list of event names; a timer's `command`, `time` and `offset`; a key's `command`, `key`, `modifiers` and `keyName`; a button's `commandDown`, `commandUp`, `pushDown`, `location`, `orientation`, `columns` and `css` ("Editing triggers, aliases and scripts" below). |
| `luaCheck` | `ok`, `line`?, `message`?; `re`? | The answer to `checkLua`: `ok` when the code compiles; else `line`, the line Lua names, from 1, or 0 where it names none, and `message`, what Lua says is wrong, in English. |
| `areas` | `areas`: `id`, `name`; `re`? | The answer to `mapAreas`: the areas that have rooms, by name ignoring case, then id. |
| `envs` | `envs`: `env`, `color`; `re`? | The answer to `mapEnvs`: the environments the map gives a colour of its own, by number, as Mudlet's room colour dialog lists them (the profile's sixteen colours as 257 to 272, and those a script or the player added), each with the colour it draws rooms in as `#rrggbb`. |
| `mapDownload` | `offered`, `host`, `state`, `label`, `value`, `maximum`, `cancelable`, `error` | The map the game offers and the download of it ("Downloading the map" below): `offered` is true while the game offers one, and `host` is its URL's host, nothing more of it. `state` is `none`, `downloading`, `loaded`, `failed` or `cancelled`; while `downloading`, `label` is what Mudlet's progress dialog says, `value` how far it has got of `maximum` (0 until Mudlet's progress starts, then its guess of the size until the server says it), and `cancelable` true until the map has arrived; `error` is why it `failed`, naming no more of a URL than its scheme and host, else empty. Sent on each change and to each UI that attaches, once the engine has sent one. |
| `commands` | `commands`: `id`, `package`, `name`, `icon`, `tooltip`, `menuPath`, `shortcut`, `onMenu`, `onToolbar`, `enabled`, `checkable`, `checked`, `pinned`, `pulse` | The commands the profile's packages added with `addCommand()`, whole ("Button bars and commands" below): to each UI that attaches, and on each change. Empty with no profile open. |

### Script windows

What `windows` and `windowText` carry, from libmudlet's `THeadlessSession::scriptWindows()` and `windowLines()`:

- A window is known by its `name` and `kind`, as a console's own command line has its console's name. `kind` is
  upstream's: `miniConsole`, `userWindow`, `label`, `commandLine`, `textBox`, `scrollBox`, `mapper` or `mxpFrame`.
  The app leaves out a kind it does not know.
- They come parents first, each parent's windows from the lowest up, those of the main window's area before the
  docks. `stack` counts from 0 among a parent's windows; a higher one is drawn over a lower one.
- `parent` is the user window or scroll box a window is in, the console whose command line it is, or the MXP frame it
  was opened in, with its kind in `parentKind`. It is empty, with no `parentKind`, for the main window's area, a dock
  and an external MXP frame.
- `x`, `y`, `w` and `h` are in the parent's pixels, a dock's in the main window's. They are all 0 for a console's own
  command line, which goes along the console's bottom.
- `visible` is the window's own flag, as `hideWindow()` and `showWindow()` set it; `shown` is whether it and every
  window it is in are visible.
- `bg` is a console's background colour, or a label's, as a run's colour. `fontFamily` and `fontSize`, in points, are
  what a console, label or text box draws its text in.
- `inDock` is true for a dock beside the main window's area rather than a window in it. A user window has `autoDock`
  (whether the player may dock it, or only float it), `dockArea` (`left`, `right`, `top` or `bottom`),
  `dockFloating` and `dockTitle`.
- A console (a mini console, user window or MXP frame) has `columns` and `rows`, the grid of text it has room for,
  `scrollBar` and `timeStamps`, and `scrolledBackTo`, the row it is scrolled back to, while it does not follow new
  lines.
- An internal MXP frame given a `TITLE`, not `FLOATING`, and at least 50 of Mudlet's pixels tall (or sized in
  characters) has a tab header, which Mudlet's GUI draws along its top, in the 30 of its pixels above its console.
  That frame has `tabs`, the pages by the names of the MXP frames they show, the first being the frame's own console,
  `tabTitles`, the title on each page's tab, as many as `tabs`, and `tabCurrent`, the index of the page in front,
  from 0. Each page is a window of its own, at the place and size of the console under the header, and only the one
  in front is `shown`. A page, and a frame with no header, has none of the three. `selectFrameTab` brings a page
  forward, as a tap on its tab.
- The runs of a console's rows are a `line`'s, with two differences: `fg` is always a colour, never -1, as the UI is
  not told a console's own; a `bg` of -1 is the window's `bg`. Their `link` ids are the window's own, which `getLink`
  and `followLink` take with the window's name, and a change to one of those links sends the window's `windowText`
  again.

The engine keeps the list. Between attaches it sends one `windows` for each turn of its event loop that changed
some, with only what changed. libmudlet sends every window of a profile as it loads, and `removed` for all of them
as it closes. `windowText` is not kept: each console's last 300 rows come again on attach.

A label's `bg` may be partly or wholly transparent: `bgAlpha`, from 0 to 254, is its alpha, absent for an opaque
one. A `Geyser.Gauge` is three labels in the gauge's parent, `<name>_back`, `<name>_front` and `<name>_text`, drawn
in that order: the front's width (or height, for a vertical gauge) against the back's is the gauge's value, and the
text label, transparent, holds the text.

### Labels

What `label` carries, from libmudlet's `THeadlessSession::labelContent()`, which scripts set with `echo()`,
`setLabelStyleSheet()`, `setBackgroundImage()` and the rest:

- `text` is the rich text `echo()` or `setLabelText()` gave it, which Qt's rich text draws; it is empty while an
  image or movie shows in its place.
- `image` is the file `setBackgroundImage()` gave it while that shows: a raster image, which text or a movie takes
  down, or with `svg` true an SVG drawn under the text. An SVG may have `svgTint`, a colour as a run's with its alpha
  in `svgTintAlpha` when below 255, and `svgRotation` in degrees, `svgShearX` and `svgShearY`, each absent for 0.
- `movie` is the file `setMovie()` gave it, shown in place of the text. `moviePaused` is true once `pauseMovie()`
  paused it or it played to its end, `movieFrame` the frame it then shows (-1 while it plays), `movieSpeed` its
  speed in percent, and `movieW` and `movieH` the size `scaleMovie()` gave it, absent for the file's own.
- `toolTip` is the label's tool tip. With `clickThrough` true the pointer passes through it to what is under it, and
  the UI follows none of its links, as Mudlet's label then takes no clicks or keys on its text.
- `accessibleName` is what a screen reader says for it, and `accessibleButton` is true once it has a click or release
  callback, so a screen reader is told it is a button.
- `liveRegion` is `polite` or `assertive` once `setLabelLiveRegion()` asked for its changes to be announced, as ARIA's
  `aria-live` does: after what the screen reader is saying, or at once; absent for `off`. The UI takes a value it does
  not know as `off`. libmudlet has it from `scripts/mudlet-patches/` until Mudlet does.
- `value`, `valueMin` and `valueMax` are the label's accessible value once `setLabelAccessibleValue()` gave it one, as
  `Geyser.Gauge:setValue()` gives its gauge's back label the gauge's value: a screen reader reads the label as a
  progress bar at `value`, from `valueMin` to `valueMax`. `valueMin` is less than `valueMax` and `value` between them;
  all three are absent for none. The UI takes a value with an empty range or one wider than a double holds
  (`valueMax - valueMin` not finite), or that is not a finite number, as none. libmudlet has it from
  `scripts/mudlet-patches/` until Mudlet does.
- `styleSheet` is what `setLabelStyleSheet()` gave it, which the engine does not interpret; drawing it is the UI's.

The files are the engine's: the UI asks for one with `getImage`. A label's cursor is not sent. The engine keeps each
label's content and sends a `label` only when it differs from what it last sent; a label that goes is in the
`windows` message's `removed`, with no `label`.

A link the engine takes with `followLabelLink` is marked visited before it runs, even one then refused for failing Lua
or a scheme Mudlet does not handle, so where `setLinkStyle()` gave the label a visited colour, its `text` is sent
again in that colour (once: a visited link followed again sends nothing). That is only for an anchor Mudlet's link
styling restyles: `<a href="...">` or `<a href='...'>`, in lower case, with `href` first and quoted, and no entity in
a double-quoted `href` (Mudlet compares the `href` as written with the one followed, which Qt decoded), in a label
with a link Mudlet's own check finds (not one whose every link has a raw `<` in its `href` or a space after the tag's
`<`). Any other link is followed and marked, but keeps its colour. A web link, a `prompt:` link while the game hides
input, and a link or label there is not, are refused before that and not marked.

### Text boxes

What `textBox` carries, from libmudlet's `THeadlessSession::textBoxContent()`, for a box a script made with
`createTextEdit()`:

- `text` is what `getTextEditText()` reads: plain text, its lines split by newlines, as `setTextEditText()` or
  `clearTextEdit()` left it, or as the player typed it.
- With `readOnly` true, as `setTextEditReadOnly()` set it, the player cannot type in it; a script still sets its text.
- `placeholder` is what `setTextEditPlaceholder()` gave it, shown while the box is empty.
- `styleSheet` is what `setTextEditStyleSheet()` gave it, which the engine does not interpret, as a label's.
- With `tabMovesFocus` true, as `setTextEditTabMovesFocus()` set it, Tab moves on to the next control rather than
  typing a tab.

Its place and font are its window's. The UI sends what the player types with `setTextBoxText`, the whole text each
time, which a script then reads with `getTextEditText()`; on the desktop that raises no event, so it raises none here.
The engine keeps it as the desktop's box would, as plain text (a non-breaking space as a space, a carriage return, line
or paragraph separator as a newline), and does not send it back, so a `textBox` comes only for a script's change, which
the UI shows in place of what the player typed. The UI sends the text as the engine keeps it, and before a `pointer`,
`pressButton`, `activateCommand`, `sendWindowCommandLine`, `followLink`, `mapPointer`, `appMapPointer` or `send`, so that
a script these run reads what the player typed. A refused `setTextBoxText`, for a box a script made read-only or hid
before the request came, is followed by the box's `textBox` as the engine holds it.

A `textBox` can cross a `setTextBoxText` on the way: one whose `typed` is below the number of `setTextBoxText` the UI
has sent for the box since its `hello` was sent before the engine had the player's latest text, which then replaced its
`text`, so the UI keeps what the player typed and takes the rest. libmudlet has all of this from
`scripts/mudlet-patches/` until Mudlet does.

### Button bars and commands

`buttonBars` is libmudlet's `THeadlessSession::buttonBars()`: the bars of the profile's buttons, from its button
editor, its packages and `tempButtonToolbar()`, in the order of its actions, each with its active buttons, as the GUI
draws them.

- `place` is where the GUI puts the bar: `top`, `left` or `right`, a strip of the main console, or `floating`, a
  toolbar the player docks or floats. `vertical` is true for a vertical bar. A horizontal bar fills each column top to
  bottom with `columns` buttons, then the next column to its right; a vertical bar fills each row left to right with
  `columns`, then the next row below. The first `fillerOffset` places of that grid, in the order it fills them, are
  left empty. `styleSheet` is the bar's, which the engine does not interpret.
- A button's `id` is what `pressButton` takes. `icon` is its icon's file, as `getImage` reads it, empty for none, and
  `styleSheet` its own, over its bar's. A `pushDown` button stays down from one press to the next, `checked` while
  down. With `menu` true it is a folder, whose press opens a menu of the entries in `children`: buttons too, with no
  style sheet, which may be folders themselves. On a `top`, `left` or `right` bar the menu holds the folder's active
  buttons, each with the folder's icon; on a `floating` bar it holds one entry, the folder itself, whose `children`
  are all of its buttons, active or not, each with its own icon, as the GUI's toolbar menu does.

`commands` is libmudlet's `THeadlessSession::addonCommands()`, in the order they were added:

- `id` is what `addCommand()` returned, which `activateCommand` takes and `sysCommandClicked` carries. `package` is
  the package whose code added it, empty for none.
- `name` is its label, `icon` its icon's file and `tooltip` its tool tip. With `onMenu` it goes on the menu, in the
  submenus `menuPath` names, separated by `/`, with `shortcut`, its keyboard shortcut as the script wrote it; both are
  empty off the menu. With `onToolbar` it goes on the toolbar too.
- `enabled` is false once `disableCommand()` disabled it. `checkable` is true once `setCommandChecked()` was called
  for it, and `checked` while it is ticked. `pinned` keeps it on the GUI's menu whichever profile is in front.
  `pulse` is true while `setCommandPulse()` makes its toolbar button flash; its colours and interval are not sent.

The engine keeps both lists and sends each whole, only when it differs from what it last sent.

### The map

`map` is the profile's 2D map, as libmudlet's `THeadlessSession::renderMap()` draws it, at the size of the mapper
window: the `windows` entry of kind `mapper`, named `mapper`, which a script makes with `Geyser.Mapper` or
`createMapper()`, inside a window, or with `openMapWidget()`, as a dock the size of the main window. A point of the
map is the same point of that window, so the UI draws the PNG over it, and sends what the pointer does there as
`mapPointer`; `pointer` on the window `mapper` does the same for a press, double-click or wheel turn.

- The engine draws the map again on upstream's `mapRepaintRequested`, at most once a turn of its event loop, when
  `viewOnly` changes, when the mapper shows or changes size, and on a new `setMapScale`. It sends `map` only when the PNG, `viewOnly`, `view`
  or `info` differs from what it last sent.
- Much that has Mudlet ask for the map again leaves its picture as it was, as a change to `info` does, and the engine
  then draws both `map` and `appMap`, whichever changed; it can tell only once it has drawn them. So it keeps the
  pixels it last drew of each, 4 bytes a pixel, while that map shows: a map that comes out as those pixels is not
  made a PNG again, and a frame that is the last one over again is not made a message to compare with the last.
- The PNG of `map` and of `appMap` is the engine's own (`MapPng.cpp`), as Qt's writer took several times as long as
  drawing the map did: the pixels Qt's PNG would have, unfiltered, in a deflate stream that only says where a pixel
  repeats the one before it or the one above it. It is some three times as large and made in a tenth of the time. A
  map that is mostly a picture, as one with a photograph for a label, would be as large as its pixels that way, so
  that one is Qt's PNG. A reader of any PNG reads both.
- In base64 the PNG is most of its frame. The app takes it from the frame's bytes and reads only the rest as JSON
  (`FrameReader.parse`), as the PNG read as part of the JSON's text took four times as long. A frame whose `png` is
  written another way than the engine writes it, with an escape in it or space around its colon, is read whole as
  JSON, to the same result.
- While the mapper does not show (its `shown` false, or no such window), or has no size, as `Geyser.Mapper`'s `hide()`
  leaves it, nothing is sent: the UI hides what it drew from the `windows` message. A mapper opened before
  `setMainWindowSize` has no size, so nothing is drawn. The UI draws a `map` only while its `w` and `h` are the
  mapper's: one of another size is from before the mapper changed, and stays so when the new size cannot be drawn.
- A left `press` selects the room under it, as `getMapSelection()` then reads, and any press raises
  `sysMapWindowMousePressEvent`. A left `doubleClick` on a room walks to it: Mudlet finds the path and calls
  `doSpeedWalk()`, or echoes that there is none. A `wheel` turn zooms about its point, as the GUI's wheel does. A
  drag pans by `moveMap` instead ("Moving a map"); the right-click menu is the GUI's alone.
- The map is laid out in the window's pixels, as the GUI draws it at a scale of 1, and drawn onto a PNG larger by the
  scale `setMapScale` asked for, as the GUI draws it on a screen of that many device pixels to each, so `w`, `h` and
  `mapPointer`'s points stay the window's. The UI asks for the scale it shows the window at, as its pixels are
  Mudlet's main window's scaled to the screen, and a mapper that is a page fills its pane. A scale that would make
  the PNG larger than 4096 pixels a side is lowered to fit. A mapper smaller than 10 pixels a side or larger than
  4096, or a map too large for a frame, gives none.

### The app's map

`appMap` is a map of the app's own, apart from any mapper a script opened: libmudlet's second map view, which the
session owns (`THeadlessSession::renderAppMap()`, a Mudlet patch the app carries in `scripts/mudlet-patches/` until
Mudlet has it). The app draws it behind the main console, or full screen when the player brings it forward, so a
profile with a map has one without a script that opens a mapper.

- It is drawn for the size the last `setAppMapSize` asked for, with the profile's map and the player's room in the
  middle, and drawn again as `map` is: on `mapRepaintRequested`, when `viewOnly` changes, on a new size, and when a
  profile loads. The engine sends `appMap` only when it differs from what it last sent.
- It is the attached UI's: when that UI goes, the engine stops drawing it as at 0 by 0, so none is drawn while no UI
  is attached, its `showRoom` and selection go with it, and a UI that attaches, a new one or the same again, is sent
  none until it asks with `setAppMapSize`.
- Once asked for, it opens the map for scripts as opening the desktop's mapper does (`Host::mapOpen()`), until the
  profile closes, 0 by 0 or not: with no script mapper, a mapper script's `centerview()` moves the player on it, which
  it follows, and `getPlayerRoom()` answers.
- It has its own centre and selection: a `showRoom`, or a press, moves only it. The zoom is the area's, as on the
  desktop, so a `wheel` on it zooms a mapper of the same area too. `setMapViewOnly` switches both.
- A left `press` selects the room under it and a left `doubleClick` walks to it, as on the mapper; neither raises
  `sysMapWindowMousePressEvent` or `sysMapAreaChanged`, as these are for a script's map, and `getMapSelection()` does
  not see its selection.
- `showRoom` holds until the player moves, or a script centres the map (but see the next point), as the desktop
  mapper's pan does.
- A `centerview()` on the room the player is already in is taken by whichever view is drawn first: a view moved by
  `showRoom` (or a script's map moved by a pan) that is drawn second keeps its place until the player moves to another
  room.

### The map info

The desktop paints a box over the top of the map: what each map info contributor the profile has on says of the room
shown, the built-in `Short` and `Full` and those a script adds with `registerMapInfo()`. The engine leaves that box
out of `png` (libmudlet's `THeadlessSession::setMapInfoPainted()`, a Mudlet patch the app carries until Mudlet has
it) and sends what it holds as `info`, in each `map` and `appMap` whose box has something in it, for the UI to show
as text over the map.

- `lines` are the contributors' parts, in the box's order: the `text` as the contributor gave it, which may hold line
  breaks and for the built-in ones ends with one, whether it is `bold` and `italic`, and its `color` as `#rrggbb`.
  A contributor that gives no colour has the map's own: black or white, whichever shows on the map's background.
- `bg` is the box's colour as `#rrggbb` and `bgAlpha` its alpha, 0 to 255: what `setConfig("mapInfoColor", ...)`
  set, grey at 120 until a script does.
- No `info` means no box: no contributor is on, none of them gives any text, or the map shows no room they speak
  of.
- The contributors run in the engine each time it draws the map, a script's in Lua, as on the desktop, so `info`
  changes only with a frame: `enableMapInfo()`, `disableMapInfo()`, `registerMapInfo()`, `killMapInfo()` and
  `updateMap()` each have the map drawn again. A frame whose `png` is as before but whose `info` differs is sent.

### Moving a map

A finger drags and pinches the map on the phone, which the desktop's mouse cannot show in a PNG: the UI moves what it
drew at once, and tells the engine with `moveMap`, which draws the map moved (libmudlet's `THeadlessSession::moveMapView()`,
a Mudlet patch the app carries until Mudlet has it).

- `view`, in each `map` and `appMap` that shows an area, is what the frame laid out: the area's id `area` and name
  `areaName`, the `level` (z) shown, `zoom`, how many rooms fit across the frame's narrow side, as Mudlet's zoom
  counts, and `minZoom` and `maxZoom`, the least and most of it a `moveMap` gives (the least shows the rooms largest).
  `following` is false while the map does not follow the player: after a `moveMap` that moved it off the player, a
  `showRoom`, a `showMapLevel` or `showMapArea`, or a wheel turn off the middle, until the player moves to another
  room or a `followPlayer`. `player` is
  the id of the player's room (0 for none), `roomSize` a room's cell in the frame's pixels, `moved` the `seq` of the
  last `moveMap` on this map the frame draws (0 for none since the profile loaded), and `rooms` the rooms of the area
  and level drawn in the frame or within a room of its edges, with each room's `id`, its middle at `x`, `y` in the
  frame's pixels and its `name`: at most 500, the nearest the middle. `levels` are the levels of the area that have
  rooms, lowest first, which `showMapLevel` takes.
- A `moveMap` acts on what the last frame drew, as the UI shows it, and is drawn in the next one: the UI keeps showing
  the moves it sent, from the frame it has, until a frame's `moved` reaches them, and drops them when one is refused.
- A pan, or a zoom about a point other than the middle, stops following the player, as the desktop's drag does; a
  zoom about the middle keeps following. The zoom is the area's, as with a wheel turn.
- A `zoom` that would go past `minZoom` or `maxZoom` stops there.

### Editing a map

The pencil on a map switches it to editing (`setMapViewOnly`), as on the desktop, where the mapper then moves rooms
by drag and edits them by its menus and dialogs, none of which the phone has. The UI edits the room the player picked
instead, with the edits above, which act whether the map is edited or viewed, as Mudlet's Lua API does:

- Each edit that gives a room answers with it as it now is, so the UI shows what the engine made of it; a new room
  from `addRoom` is the one the UI then shows, so a chain of rooms is a tap each.
- The map is drawn again after each, as on `mapRepaintRequested`, and marked unsaved: the profile's save keeps it, as
  on the desktop.
- `editRoom`'s place is the room's, in its area: a drag of the picked room on an edited map moves it there, a whole
  number of rooms from where it was by the frame's `roomSize`.

### Editing triggers, aliases and scripts

The desktop edits a profile's triggers, aliases, scripts, timers, keys and buttons in its script editor, a dialog
libmudlet does not have. The UI edits them with the requests above instead, which do what the editor's add, save,
checkbox, drag and delete do:

- Items are known by their ids, which the profile keeps while it runs and which are unique within a kind, not across
  kinds. A UI that has an item's fields sends back those it changed, or all of them.
- `editScriptItem` saves as the editor's save does: the name trimmed, the patterns and the Lua compiled, and a new item
  set running if that worked (but not during the emergency stop, which starts it as it ends). A trigger that does not
  compile is switched off with its error kept; that is no refusal, and the `scriptItem` that answers gives the error.
  Saving a script runs it, as on the desktop. An alias given no name takes its pattern.
- A timer has its `command`, which it sends each time it fires, and its `time`, in milliseconds, from 0 to
  86399999. One with no time cannot run, which its `error` says. One that is switched on counts from its save.
  `offset`, which is only read, is true for an offset timer, one inside another timer, which runs its time after that
  timer fires; `active` is true for it while that timer runs. The requests make no offset timer, as a `parent` must be
  a folder, but read and change one the profile has as any other.
- A key has its `command`, its `key`, a `Qt::Key` as a number (0 for none), and its `modifiers`, the
  `Qt::KeyboardModifiers` held with it, as a number: the sum of 0x02000000 for Shift, 0x04000000 for Control,
  0x08000000 for Alt, 0x10000000 for Meta, 0x20000000 for a key of the number pad and 0x40000000 for Qt's group
  switch. `keyName`, which is only read, is the two as the desktop writes them, or empty for no key. A key with none
  chosen cannot run, which its `error` says, and a key given no name takes its key's.
- A button has `commandDown`, which it sends when pressed, `commandUp`, which a push-down button sends when released,
  `pushDown`, whether it is one, and `css`, its style sheet. A folder at the top level, or in the folder a package's
  buttons came in, is a toolbar, which has its `location`, one of `top`, `left`, `right` and `floating`, its
  `orientation`, `horizontal` or `vertical`, and its `columns`, from 1; every button has the three, and they mean
  something only for a toolbar. A new button or toolbar is switched off, as on the desktop, and a new toolbar is at the
  top and horizontal. A change to a button is followed by the `buttonBars` it gives.
- A `scriptItems` sent after a `moveScriptItem` or `deleteScriptItem` lists the items as they are after it, as the
  engine reads the requests in order.
- What changes is written with the profile's next save, as the desktop editor's changes are: when the app leaves the
  front and when the profile closes.

### Downloading the map

A game offers its map with GMCP's `Client.Map`, `{"url": "..."}` (the MMP's map location), which the desktop's mapper
turns into a "Download from game" button. libmudlet tells the UI with `mapDownload` (`THeadlessSession::offeredMapUrl()`
and `downloadOfferedMap()`, a Mudlet patch the app carries in `scripts/mudlet-patches/` until Mudlet has it).

- `offered` turns true as the game offers a map, with `host` its URL's host. No more of the URL is sent or logged,
  by the engine or by Mudlet's messages the patch changes, which name only its scheme and host: the rest can hold a
  user name, a password or a token. If the game offers another map while one downloads, `offered` follows the offer,
  but `host` stays the download's: its end names where the map came from, and a `mapDownload` with the new offer's
  host follows it.
- `downloadMap` downloads it as the desktop does (`TMap::downloadMap()`): a URL whose path ends in `xml` to the
  profile's `map.xml`, read as an XML map, any other to its map folder as `map.dat`, loaded as Mudlet's own map. The
  profile's map is replaced only by a map that arrived whole and reads, which raises `sysMapDownloadEvent`. Mudlet
  also writes its usual lines about it to the main console.
- While it runs, `state` is `downloading`, with the `label`, `value` and `maximum` of Mudlet's progress dialog, sent as
  they change, at most once a percent. They count bytes, and `maximum` is a guess of 4000000 until the server says the
  size, so `value` can pass it; while an XML map is read, they count its steps and rooms. `cancelable` is true until
  the map has arrived, and `cancelMapDownload` stops it until then. Mudlet sets no time limit: a download that stalls
  runs until it is stopped.
- It ends `loaded`, and `appMap` and `map` then show the new map (an `appMap` can come just before `loaded`, as the
  map is redrawn once it is in place); `failed`, with `error` Mudlet's console message without its tag (such as
  `[ ERROR ] - `), on one line, with no more of any URL in it than its scheme and host, and the URL's password and
  query as `***` elsewhere, as in the server's own words (which Mudlet's console line keeps as they are): the password
  wherever it shows and the query wherever it follows a `?`, each decoded, as it is, and percent-encoded as the game's
  URL writes it and as Qt shows and encodes it (`QUrl`'s `PrettyDecoded` and `FullyEncoded`, which normalise the
  encoding), with the hex digits in either case; or `cancelled`. The host of a URL is what follows the last `@` before
  its path, query or fragment, as a browser reads a URL, and the URL's own user name, in any of those forms, is taken
  whole, so a space, `/`, `?` or `#` in it does not end the URL early. What the server repeats in another form
  can still show, such as a path it repeats on its own or decoded with a space in it, the query, or a part of it,
  repeated without the `?` before it, a part of it even right after one, the password or the query percent-encoded
  otherwise than the game's URL and Qt write them (such as `%21` for a `!` or `+` for a space), and a user name it
  repeats outside a URL. A download that failed or was stopped
  leaves the profile's map as it was.
- A profile starts at `none`, offered or not as the game has offered by then, and `offered` false and `none` follow
  its close.

### Status keys

`status` is how the engine reports everything that is not a line. The keys are not part of the protocol's version:
a UI shows what it knows and ignores the rest. Now in use:

- **The engine (`MudletEngine`, `MudletProfile`):** `connection` (`connecting` or `connecting <host>:<port>`,
  `connected`, `disconnected`, `error: ...`), `luaResult`, `luaError`, `sendError`, `completion`, `fontFamily`,
  `advanceM`, `platform`, `linesReceived`, `promptsReceived`, `heartbeatTicks`, `heartbeatLastGapMs`,
  `heartbeatMaxGapMs`, `profile` (`<name> loaded`, `<name> (not started)`, `<name> starting`, `<name> closing`,
  `<name> closed`, `<name> closed without its save: ...`, `<name> not closed: ...` and `start failed ...`),
  `engineKind` (`libmudlet`), `configDir`, `profileLoadMs`, `startMs`, `localLines`, `errorConsoleLines`, `restore`,
  `save` (`saved` or `not saved: ...`, once a save the profile made while running has ended: when `setAppState`
  `active` turned false, or when Android told the engine's process that memory is short), and `consoleColors`:
  `<fg> <bg>`, each `#rrggbb`, the colours of the console where the game asks for none (the runs' -1), from `consoleForeground()` and `consoleBackground()` when a
  profile loads, and on each change (`consoleColorsChanged`: a script's `setBackgroundColor()` on the main console,
  or a package or save with colours of its own).
- **The engine host (`main.cpp`):** `enginePid`, `socketPath`, `appState`, `qtStartMsAfterAppOnCreate`,
  `qtStartMsAfterServiceOnCreate`, and on Android `serviceReadyWait` (`ok` or `timeout`).

### The restore status

An engine started after the one before it was killed brings back the profiles that one had open, the front one first,
and each one's game connection if that was wanted ([`architecture.md`](architecture.md), "Restoring the session").
Status `restore`, the engine's, says how that goes, for each profile in turn.
It is never sent by an engine that has nothing to restore, as after an orderly stop. Its value is a phase, then a
space and what the phase is about, if anything; `<target>` is `<host>:<port>`, or the profile's name when the profile's
own address is used. The app shows each new value as a marker line (`EngineRestore.describe`), and `ProtocolSpecTest`
checks that this table, `MudletEngine.cpp` with `MudletProfile.cpp`, and `EngineRestore.PHASES` name the same phases.

| Value | Meaning |
|---|---|
| `restoring <profile>` | Loading the profile again. It was not connected, so it is not connected now. |
| `restored <profile>` | The profile is loaded, and stays disconnected. |
| `waiting <seconds> <target>` | This is not the first restore within 10 minutes: the profile is loaded and connected only after `<seconds>` (10, then 30), so a game is not called again at once by an engine that keeps dying. Lua sent meanwhile runs once the profile is loaded; a `connect` or `disconnect` calls off the wait. |
| `reconnecting <target>` | Loading the profile and connecting it. |
| `reconnected <target>` | Connected again. |
| `retrying <target>` | The connection failed, and Mudlet tries again on its own, as the profile reconnects automatically. `reconnected` follows when it gets through. |
| `notReconnected <target>` | The connection failed. The profile does not reconnect automatically, so nothing tries again. |
| `cancelled` | The UI connected or disconnected before the restore had finished: it reports no more. |
| `failed <error>` | The profile could not be loaded again. |
| `gaveUp <restores> <minutes>` | The engine was restored `<restores>` times within `<minutes>` and died each time: this start restores nothing, and forgets the profiles. |

## Runs

`runs` is a JSON array of runs, each an array of numbers:

- `[start, length, fg]`
- `[start, length, fg, bg, attributes]`, when the run has a background or attributes
- `[start, length, fg, bg, attributes, link]`, when the run is part of a link

`start` and `length` count UTF-16 code units of `text`, as Qt's `QString` and Kotlin's `String` index it. Text
outside every run has the default colours and no attributes. A missing `bg` is -1, missing `attributes` and `link`
are 0. The app flattens runs to six ints each (`RUN_STRIDE`).

A colour (`fg`, `bg`) is one of:

- -1, the default;
- `0x1000000 | 0xRRGGBB`, an exact colour (libmudlet; `RUN_RGB` in Kotlin, `ColorRun::rgbFlag` in C++).

`attributes` is a bit set:

| Bit | Value |
|---|---|
| bold | 1 |
| italic | 2 |
| underline | 4 |
| strike-out | 8 |
| reverse | 16 |
| overline | 32 |
| blink | 64 |
| fast blink | 128 |
| concealed | 256 |

`link` is libmudlet's id of the link the run is part of, 0 for none, which `getLink` and `followLink` take. Each id
names one link for the life of the engine instance, as libmudlet's ids, which each profile counts from 1, are offset by
those of the profiles before it; a UI drops the links of lines from an earlier instance, as it does their statuses. In
a script window's rows, `link` is among that window's links, counted on their own and not offset: `getLink` and
`followLink` take it with the window's name. So an id from an earlier profile's window may name a link of the running
profile's window of the same name: a UI drops a window's links with its text, when `windows` removes the window, as
libmudlet does for each window of a profile that closes, and when the window's next `windowText` no longer has them.

## Several profiles

Each open profile has its socket, `engine-p<slot>.sock` beside the control socket, where `slot` is the lowest number
from 1 that no open profile has; [`multi-profile.md`](multi-profile.md) has the design. On it go the requests and
messages of one profile, as in version 14: everything but `listProfiles`, `saveProfile`, `deleteProfile`,
`openProfile`, `closeProfile`, `setFront`, `markSeen` and `setAppState`, which it refuses with `error`
(`<t>: send it on the control socket`). Its ring, attach and flow control are its own, and its lines count from 1.

What the control socket carries depends on the UI's `hello`:

- With `profileSockets`, the control requests above, `ping`, `openProfiles`, and the engine's own statuses (`enginePid`,
  `socketPath`, `appState`, the font, the heartbeat, `save`, `restore`, `engineKind`, `configDir`); any other request
  gets `error` (`<t>: send it on the profile's socket`).
- Without it, as in version 14: the front profile's lines, statuses and windows too, and the other requests go to the
  front profile, or, with none open, to the engine (in libmudlet a `connect` or `runLua` starts a profile). When the
  front changes, the new front's statuses that differ are sent. No `openProfiles` is sent unasked.

The profile opened last becomes the front, except one a restore brings back behind the front one; when the front
closes, the next one opened after it takes over, else the one before. The engine runs up to 4 profiles at once,
each in a session of its own sharing one pool, and `setFront` raises `sysProfileFocusChangeEvent` in
the profile that lost the front and the one that took it.

## Attaching: hello, instance and lastSeq

The engine keeps the last 2000 lines in a ring buffer, its current tail, and the last value of every status key. When
a UI says `hello`, the engine sends, in this order:

1. its own `hello`;
2. every status, each as a `status` without `re`, in no particular order, then `hiddenInput` and `history`, and
   `linksChanged` with empty `ids` if a change of a link went to no UI since the last attach, then `mainArea` if the
   engine has sent one, `windows` with `full` true, a `label` for each label, a `textBox` for each text box, `map` while
   the mapper shows, `mapDownload` if the engine has sent one, `buttonBars`, `commands`, and a `windowText` of each
   console's last 300 rows;
3. `clear`, if the console was emptied after the line the UI says it has, or ever, for a UI that has none;
4. `gap`, if lines the UI does not have already fell out of the ring;
5. the lines in the ring after the UI's, each with `replay` true. That is all of them unless the UI's `instance`
   matches the engine's, in which case it is those after `lastSeq`, clamped to the lines this instance sent. None
   from before a clear is sent;
6. `tail`, possibly empty;
7. `synced`.

The app's `EngineLink` keeps `instance` and `lastSeq` for the life of the UI process:

- it sends them in every `hello` after the first attach;
- `lastSeq` follows each `line`'s `seq`, and then `synced`'s `seq` when that is larger;
- a `hello` from a different instance means a new engine: the UI resets `lastSeq` to 0, and the app drops the old
  engine's statuses and counters, keeps its lines and adds a marker line.

So a UI that only detached (Activity stopped, rotation) gets just the lines it missed. A new UI process gets the whole
ring, and a `gap` if the ring has dropped lines.

## Flow control

The engine never waits for the UI, as Android can freeze a backgrounded UI process mid-read:

- When more than 4 MiB is queued unsent, the UI is lagging. The engine stops writing lines, tails, clears, notifies
  and statuses to it, but keeps them in the ring and its status table.
- Once the queue drains below 256 KiB, the engine sends every status, `hiddenInput` and `history` again (and
  `linksChanged`, as on attach), and the script windows' state, the maps, the map download, the button bars and the
  commands, as on attach, then the lines the UI has not had (with `clear` and `gap` as on attach, but without `replay`),
  then the tail.
- Answers are always written, even to a lagging UI: `pong`, `profiles`, `link`, `error`, the `windowText` that answers
  `getWindowText`, `image`, `rooms`, `room`, `areas`, `settings`, and a `status` that carries `re`.

A `notify` sent while the UI lags is not sent again, but the engine has already posted the notification on Android;
nor is a `commandLine`, a `windowCommandLine`, an `announce`, an `attention` or a `moveTypedLine`.
`synced.seq` is the newest line when greeting even if lag cut the replay short; the rest follows once the UI drains,
and `EngineLink` then tracks `lastSeq` from those lines.

## Versioning and compatibility

As implemented in version 27, which added timers, keys and buttons to the script items (`timer`, `key` and `button`
for `kind`) and `summary` to `scriptItems`; version 25 added checking that Lua compiles (`checkLua`, `luaCheck`);
version 24 took the map info box out of the map's picture and sends it as text (`info` in
`map` and `appMap`); version 23 added editing the triggers, aliases and scripts (`scriptItems`, `scriptItem`,
`addScriptItem`, `editScriptItem`, `enableScriptItem`, `moveScriptItem`, `deleteScriptItem`); version 22 added
following a label's link (`followLabelLink`); version 21 added editing
the map (`editRoom`, `setRoomExit`, `lockRoomExit`, `addRoom`, `deleteRoom`, `setPlayerRoom`, `renameArea`, `mapEnvs`,
`envs`, and `symbol`, `env`, `color`, `weight` and `locked` in `room`); version 20 added a room's details and the map's
levels and areas (`roomInfo`, `room`, `walkToRoom`, `showMapLevel`, `showMapArea`, `mapAreas`, `areas`, `levels` in
`view`, and `areaId`, `match` and `steps` in `rooms`, which come best first); version 19 added a profile's settings
(`getSettings`, `setSettings`, `settings`); version 18 added moving a map by touch (`moveMap`, `followPlayer`, and
`view` in `map` and `appMap`); version 16 added an MXP frame's tabs (`selectFrameTab`, and `tabs`, `tabTitles` and
`tabCurrent` in `windows`); version 15 added several profiles at once: a socket each, `profileSockets` and `profile`
in `hello`, `alongside` in `openProfile`, `closeProfile`, `setFront`, `markSeen` and `openProfiles`; version 14 added
the text boxes (`setTextBoxText`, `textBox`), and later `setMapScale`, which needs no new version as a UI does without
it: an engine without it refuses it as unknown, the UI ignores that, and the map stays at the window's pixels; version
13 added the map
download (`downloadMap`, `cancelMapDownload`, `mapDownload`); version 12 added `window` to `getLink`, `followLink` and
`link`, so that a script window's links can be asked about and followed; version 11 added the app's map
(`setAppMapSize`, `appMapPointer`, `showRoom`, `appMap`) and the room search (`findRooms`, `rooms`); version 10 added
the map (`map`, `mapPointer`, `setMapViewOnly`); version 9 added the button bars and commands (`buttonBars`, `commands`,
`pressButton`, `activateCommand`); version 8 added the labels' messages (`label`, `image`, and `bgAlpha` in `windows`)
and requests (`pointer`, `getImage`); version 7 added the script windows' requests (`setMainWindowSize`,
`getWindowText`, `placeUserWindow`, `closeUserWindow`, `sendWindowCommandLine`) and messages (`windows`, `windowText`,
`mainArea`, `windowCommandLine`); version 6 added `setAppState` and the messages `attention` and `moveTypedLine`;
version 5 added the link requests (`getLink`, `followLink`) and messages (`link`, `linksChanged`); version 4 replaced
`setViewWidthPx` (and its statuses `columns` and `viewWidthPx`) with `setScreenSize` and added `announce`; version 3
added the input line's requests (`sendHidden`, `dismissHidden`, `complete`) and messages (`hiddenInput`, `history`,
`commandLine`); version 2 added the profile requests and `profiles`:

- The UI sends its version in `hello` `v`, and the engine sends its own in its `hello`.
- The engine accepts only a `hello` whose `v` equals its own. Otherwise it sends `error` (`expected hello with v=`
  and its version) and closes the connection. A first frame that is not `hello` is refused the same way.
- The app does not check the engine's `v`. A refused `hello` reaches `EngineLink` as an `error` where it expects
  `hello`, so it reports the link lost and keeps retrying.
- An unknown `t` from the UI gets `error` (`unknown type <t>`), and the connection stays open.
- An unknown `t` from the engine becomes `EngineMessage.Unknown` in the app, which ignores it.
- Unknown fields are ignored on both sides.

The UI and the engine ship in the same APK, and Android stops both processes when the app is updated, so on a phone a
UI only meets an engine of its own build. Mixed versions come up during development: a desktop engine against a
newer JVM test, or a Python client. The rules for changing the protocol:

- **No version change needed:**
  - a new optional field that the other side may ignore;
  - a new engine-to-UI message that an older UI may ignore;
  - a new status key.
- **Increase the version** (`PROTOCOL_VERSION` in `Protocol.kt`, `SocketBridge::protocolVersion`, and
  `PROTOCOL_VERSION` in `protocol_client.py`):
  - a message or field removed or renamed;
  - a field's type or meaning changed;
  - a new field that the receiver must have;
  - a new UI-to-engine message the UI depends on, which an older engine would only answer with `error`.
- Update this file in the same change. `ProtocolSpecTest` fails when the message tables, the C++ engine, the
  Kotlin client or the versions disagree.
