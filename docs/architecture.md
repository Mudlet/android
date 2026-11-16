# Architecture

Mudlet for Android runs in two processes. The UI process is Kotlin and Jetpack Compose and loads no Qt. The engine
process (`:engine`) runs Qt and the engine (libmudlet, or the stand-in for fast tests) in a foreground service. They
talk over a local socket with the protocol in [`protocol.md`](protocol.md). Why it is built this way is in
[`decisions/hosting.md`](decisions/hosting.md).

This began as prototype C's report. Its measurements are at the end; its emulator results are in
[`decisions/hosting-results/c-device.md`](decisions/hosting-results/c-device.md).

The design's main cost is that every engine-to-UI interaction is an asynchronous message: no synchronous getters, no
shared objects. Mudlet's UI code calls into `Host`/`TConsole` directly everywhere, so that is real redesign work
(see "What building Mudlet on it costs").

Its main risk is starting a `QGuiApplication` inside a `QtServiceBase`, which Qt does not support: Qt pairs services
only with `QAndroidService`. Two fixes, found in prototype A, make it work on the emulator (see "Qt internals relied
on"):
- The engine releases Qt's private service-setup semaphore itself. Without this, `Service.onCreate()` never returns
  and the engine ANRs.
- `main()` waits for a "service ready" call before it creates `QGuiApplication`. Without this, Qt's
  `ApplicationHidden` state would stop the engine's timers and sockets.

Both rely on Qt private API (`Qt6::CorePrivate`), which ties the build to one exact Qt version.

## Source layout

| Where | What |
|---|---|
| `app/` (`:app`) | The UI process: `MudletApp`, `ui/MainActivity.kt` with the screens it navigates between (`ui/AppNavigation.kt`: the profile list and its editor in `ui/profiles/`, the game screen in `ui/game/` (the console with the windows, the map and the button bars the game's scripts draw around it, the input line with its suggestions, the quick bar with the history sheet and pinned commands, the connection bar and the `Announcer`, which has TalkBack read new lines and `announce`), the settings in `ui/settings/`, About and the licences in `ui/about/` (their data in `about/` and `assets/licences/`, [`licences.md`](licences.md)) and, in debug builds, the self-test in `ui/selftest/`), the renderer of game text (`ui/AnsiText.kt`), the theme (`ui/theme/`), the model of the engine (`ui/EngineClient.kt`, with the script windows' part in `ui/ScriptWindowsClient.kt` and the app's own map in `ui/AppMapClient.kt`), the app's own settings (`data/Settings.kt`, in SharedPreferences) and the profiles' passwords (`data/PasswordStore.kt`). Every string the app shows is in `res/values/strings.xml` |
| `engine-service/` (`:engine-service`) | The engine process's Kotlin side: `EngineService` (a `QtServiceBase`), its notifications, `EngineNative` (the JNI handshake). Its manifest declares the service and the permissions. It also carries the Qt libraries and jars that `scripts/build-apk.sh` stages into `engine-service/qtdeploy/`, with the engine's name and version (`res/values/engine_info.xml`) |
| `protocol/` (`:protocol`) | Plain Kotlin/JVM with no `android.*`: the frame codec, message types and `EngineLink`, the reconnecting client; the script windows' state (`ScriptWindows`) and where the game screen puts them (`WindowLayout`); what a label draws, read from its style sheet (`LabelStyle`) and rich text (`RichText`), its links (`LabelLink`) and Geyser's gauges (`Gauge`); where the button bars go and the commands' menu (`ButtonBarLayout`, `CommandMenu`); where the map is drawn, a pinch as wheel notches and how faintly the map shows behind text (`MapFit`, `ZoomNotches`, `MapBehindText`). Its tests run on the JVM, against the desktop engine too |
| `suggest/` (`:suggest`) | Plain Kotlin/JVM with no dependencies: the input line's command suggestions, the session that loads, feeds and saves them, and the pinned commands ("Command suggestions" below). Its tests run on the JVM, with Gradle or with kotlinc alone (`suggest/run-tests.sh`) |
| `native/enginehost/` | `libengine`'s `main()`, `SocketBridge` (the protocol's engine side), `MudletEngine` (libmudlet through `THeadlessSession`), and the Android glue: `AndroidServiceSetup` (the only private Qt API, so a rename in Qt fails the build) and `AndroidNotifier` |
| `native/api/` | `IEngine` and `IFrontend`, between the host and an engine |
| `native/standin/` | The stand-in engine for fast tests: minimal telnet, ANSI runs, Lua 5.1.5 (vendored in `native/third_party/lua-5.1.5`, tarball sha256 `2640fc56…695333`), a font check and a heartbeat |
| `native/tests/` | The fake game server, the Python protocol client, and the host tests of both engines |

## How it is wired
```
UI process (org.mudlet.mudlet)              :engine process (org.mudlet.mudlet:engine)
Kotlin + Compose, no Qt                     QtServiceBase + libengine.so (Qt Core/Gui/Network)

:app       MainActivity -> EngineClient     :engine-service    EngineService : QtServiceBase
:protocol  EngineLink (reader thread)       native/enginehost  QLocalServer (Qt's main thread)
              <======== LocalSocket ========>                  SocketBridge : IFrontend -> the engine
              frames: u32 BE length + JSON                       (MudletEngine, or the stand-in)
                                                               AndroidNotifier -> EngineNotifications.postTell (JNI)
```

Startup:
1. The UI calls `startForegroundService`. `MudletApp.onCreate` runs in the new
   `:engine` process and creates the notification channels.
2. `EngineService.onCreate` uses `Os.setenv` to set the socket path, the
   timestamps and `QT_QPA_PLATFORM`.
3. It calls `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` before
   Qt loads, so a slow library load cannot miss the start-foreground deadline.
4. It calls `super.onCreate()`. That is QtServiceBase, which loads the Qt
   libraries listed in the generated `libs.xml` and starts `libengine`'s
   `main()` on Qt's thread. It then blocks the service's main thread in
   `QtNative.waitForServiceSetup()`.
5. On Qt's thread, `main()` first releases that wait
   (`AndroidServiceSetup::releaseQtServiceSetup()`), so `super.onCreate()` can
   return.
6. `EngineService` then calls `EngineNative.nativeServiceReady()`, a JNI
   function bound by name in `libengine`. `main()` waits up to 10 s for this
   call. Only after it does `main()` create `QGuiApplication`, the `Engine` and
   the `SocketBridge`, and call `listen()` on `noBackupFilesDir/engine.sock`.
7. The UI's `EngineLink` retries the connection every 50 ms for 2 s, then backs
   off from 100 ms to 2 s, until the socket appears.

Threads:
- The engine is single-threaded on Qt's main thread: socket, telnet, Lua and
  timers.
- In the UI, one daemon reader thread per link decodes frames. Lines and
  statuses are batched and flushed to Compose state on the main thread every
  16 ms.
- Writes from the UI go out on the caller's thread through a synchronized
  `FrameWriter`. Each one is a small frame.

Crossing the boundary:
- UI to engine: requests (below).
- Engine to UI: pushed lines, statuses and notifies.
- There are no calls in either direction. A reply is a `status` frame carrying
  `re`, the request id.
- Inside `:engine`, the only JNI calls are:
  - Java to native: `nativeServiceReady`, once at startup.
  - Native to Java: `QJniObject::callStaticMethod` for `postTell`.

Foreground service declaration:
- In `:engine-service`'s manifest: `<service android:name=".EngineService" android:process=":engine"
  android:exported="false" android:foregroundServiceType="specialUse">`
- A `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining the long-lived MUD
  connection.
- The `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions.
- Qt's `android.app.lib_name=engine`, `android.app.arguments` and
  `android.app.background_running=true` meta-data on the service.

Security: the socket is a filesystem socket inside the app's private directory.
The server uses `QLocalServer::UserAccessOption` and checks the peer uid with
`SO_PEERCRED` against `getuid()`, so other apps cannot connect.

### Protocol
The socket protocol is specified in [`protocol.md`](protocol.md): frames of a 4-byte big-endian length and a UTF-8
JSON object; requests from the UI; lines, the tail, statuses and notifications from the engine; a 2000-line ring
replayed on attach; and flow control so that the engine never blocks on the UI.

This matters on Android because the cached-app freezer can freeze a
backgrounded UI process mid-read. Verified on the host:
- With a stalled reader, the engine kept running at 42-60 MB RSS. When the
  reader resumed it received 1999 lines and one `gap`.

### Command suggestions
`:suggest` ranks what the input line offers as the player types. It runs in the UI process and only on the
device: no network, no engine round trip, so it can answer every (debounced) keystroke. One `CommandSuggester`
per profile, from `SuggesterRegistry.forProfile(name)`:

- `recordSent(text, hidden)` after each send. With `hidden = true` (the game asked for a password) it stores
  nothing, and the next command is not linked to the one before it.
- `observeLine(text)` for each game line, without its colours.
- `suggest(input, cursor, limit)` returns `Suggestions`: `items`, best first and one per resulting text, each a
  `Suggestion` with `text` (the whole input line it leads to), `cursor`, `label`, `kind` (`COMMAND` or `WORD`) and
  `source` (`HISTORY`, `SEQUENCE`, `LINE` or `OUTPUT`); and `ghost`, the text to show greyed after the cursor
  when the best item only extends the input.
- `save(Appendable)` and `load(Reader)`, a line-based text (`SuggestCodec`) the app keeps in its private storage,
  one file per profile; a damaged line is skipped, and `load`'s `LoadResult` says how many were and whether the text
  was this format at all. `forget(command)` and `clear()` for the player.
- `search(query, limit)` for the history sheet: the commands sent, matched as the history source below and ranked by
  match plus frecency; a blank query lists them all, most frecent first.

What it ranks:

| Source | When | How |
|---|---|---|
| History | Input typed | Case-insensitive subsequence match (`FuzzyMatch`): a prefix first, then word starts, then runs; plus frecency, each use weighing half as much every 7 days |
| Sequence | Empty input; lifts history while typing | Counts of what the player sent after the previous command, and after any command with its first word ("kill rat", "kill orc", then "loot") |
| Line | Empty input; lifts history while typing | Counts of what the player sent after a game line, the last three lines since the previous send, with numbers as `#` and capitalised words after the first as `*` |
| Output | A word at the cursor | Words of the last 2000 words of output that extend it, newest first |

Everything is bounded (5000 commands, 4000 and 2000 prediction keys of 8 commands each), and counts halve as they
grow, so recent play outweighs old. All calls take one lock; `suggest` is O(entries × command length): 1 to 4 ms
for 5000 entries on a desktop JVM, so the app calls it debounced and off the main thread.

In the app, `ui/game/CommandSuggestions.kt` keeps a `SuggestionSession` (`:suggest`) for each profile the engine
has said it loaded (status `profile`, "<name> loaded"; `Android` until then), saved as `files/suggest/<name>.txt`
(the name URL-encoded) in the app's private storage. The session does all its work on one background thread, in the
order the UI asked for it:

- It loads the file when the game screen first shows the profile; a command sent meanwhile is learned on top.
- It records each send, and observes each game line but the echo of a send: one of the three lines after the send
  that ends with the command as a whole word.
- It answers the input line once the typing has paused for 120 ms, with up to five suggestions. `SuggestionStrip`
  shows them as buttons above the line, after an accept button for the ghost text. The ghost shows greyed through an
  `OutputTransformation`, and only for the line as it is now; the field's state and semantics keep just the typed
  text, so the keyboard does not see it and TalkBack does not read it. A hardware right arrow at the end of the line also accepts it.
- It saves 5 s after a send and when the app leaves the front (`MainActivity.onStop`), to a temporary file that
  then replaces the old one. An I/O error is logged (tag `MudletSuggest`) without any command and the suggestions
  carry on in memory; after a failed load nothing is saved, so the file is not replaced with less.
- While the game hides input there is neither strip nor ghost: nothing typed reaches the session, and the masked
  send is recorded only as `recordSent("", hidden = true)`.

The History button below the input line, or Ctrl+R on a hardware keyboard, opens `HistorySheet`, a
`ModalBottomSheet` whose search field takes the focus. The session answers each change of it at once with up to 100
of `search`'s commands, under the pinned ones that match. A row's text puts its command on the input line without
sending it; its buttons send it, pin or unpin it, and forget it (`forget`, saved as a send is). Picking, sending,
Back and the close button close the sheet.

Pinned commands (`PinnedCommands`, `:suggest`) are the buttons after History in `QuickBar`, which scrolls sideways;
one sends its command. Up to 12 per profile, in the order pinned, saved whole on the session's thread as
`files/suggest/<name>.pins` beside the suggestions, the same way and with the same handling of a damaged file or an
I/O error. They are unpinned from the sheet's Pinned section. While the game hides input there is no quick bar,
the sheet closes and Ctrl+R does nothing; nothing reaches the search, and a pin can only come from the history,
which a hidden send never enters.

### Profiles in the app
The profile list (`ui/profiles/ProfileListScreen.kt`) shows the engine's `profiles`, which `EngineClient` asks for
each time it attaches, and its editor (`ProfileEditor.kt`) sends `saveProfile` and `deleteProfile`. Each waits for its
answer, and a refusal shows the engine's reason, which is in English. Connect sends `openProfile` with the profile's
stored password and goes to the game screen once it is answered with `profiles`; the game screen's Reconnect then goes
to that profile's address. A profile's name cannot change, as the engine keeps the name a profile was created with.

The engine keeps no password, so the app does. `data/PasswordStore.kt` encrypts each with AES-GCM under a key that
never leaves the Android KeyStore (no user authentication, so a profile opens without a prompt), with the profile's
name as associated data, so one profile's cannot be moved to another's. The ciphertext is in
`files/passwords/profiles.json`, which `data_extraction_rules.xml` excludes from cloud backup and device transfer
(`android:allowBackup="false"` covers older releases), and no other device has the key to decrypt it. The editor never
shows a stored password: a new one replaces it, Forget password removes it, and so does deleting the profile. One that
can no longer be decrypted is forgotten, and the player types it again.

The self-test's Connect saves and opens the profile `Self-test` at the address typed. `openProfile` connects a running
profile only while it is not connected, so to connect it elsewhere, disconnect it first. `SmokeTestReceiver`'s
`connect` stores a password and opens a profile as the profile list does.

### Script windows' text: links and the screen reader
A mini console's or user window's rows are drawn by `ui/game/ConsoleLine.kt`, as the main console's lines are, so
both read alike:
- Each row is one node for TalkBack whose text is the row's characters without colours or styles. "Blank lines from
  the game" applies to an empty row as to the game's lines: Hide leaves it out, Space reads it as a space, labelled
  "Space" since a blank alone is nothing a screen reader can say; it is still drawn, as Mudlet applies the setting to
  the main console's game text only.
- A window's rows are never read aloud as they change, and there is no setting to have them read: a chat or status
  window rewrites its rows often, and reading them would bury the game's text. A script that wants a line heard calls
  `announce()`, which the app speaks as it does for the main console.
- A link's runs (`linkSpans` in `:protocol`) are asked about with `getLink` and the window's name once its row is
  shown, and asked again after each `windowText` of the window, as a followed link turns visited and comes as the
  window's text, not `linksChanged`; the last answer stands until the new one comes, and goes once the window's text no
  longer has the link. A tap on a link follows it (`followLink`), unless the engine says it is disabled; a long press on
  one with a menu opens it, its title a heading and a disabled item greyed out, showing the latest answer while its row
  has the link. TalkBack offers each link not disabled as an action, "Follow link:" and its tool tip, or its text where
  it has none, and one with a menu as another that opens it, as an inline link is too small for a 48 dp target. A tap,
  pick or action while no engine is attached says the link was not sent.
- The main console's links are not followed yet: its lines have their link runs, but no tap or action uses them.
  `ConsoleLine` takes them as it does a window's, once the main console asks the engine about them.

### Button bars and commands
The game screen draws `buttonBars` and `commands` (`ui/game/ScriptButtonBars.kt` and `ScriptCommands.kt`, laid out by
`ButtonBarLayout` and `CommandMenu` in `:protocol`):
- A `top` bar is a row above the game area, the console with its windows. In the fitted layout (600 dp and wider) a
  `left` or `right` bar is a strip at that side, as Mudlet's are beside its main console, and a floating bar, which the
  GUI lets the player dock, is a row below, or a strip at the end when it is vertical. Narrower, a `left` bar is a row
  above and a `right` or floating one a row below, lying along it. The windows are fitted when the bars are, and
  Mudlet's main window is what the bars leave, as the GUI's bars take their room from its main console. A row takes
  at most a quarter of the area's height and a strip 160 dp of its width; both scroll.
- A bar's buttons are in its grid, as `docs/protocol.md` gives it. A button is at least 48 dp square, with its icon
  (asked of the engine at 24 dp) and its name; style sheets are not drawn.
- A tap sends `pressButton`, and the next `buttonBars` shows what it did. A push-down button is a switch, on, with a
  tick, while it is down. A folder opens a menu of its entries, a folder among them opening its own in place with a
  Back entry; the entry picked is pressed and then the folder, whose script the GUI runs once its menu closes, so a menu
  closed without a pick presses the folder alone. A floating bar's folder shows its buttons at once, not itself.
- The commands are in the menu of an Extensions button in the top app bar, shown while one is on the GUI's menu or
  toolbar, nested by their menu paths; a tap sends `activateCommand`. A checkable command has a tick while checked, a
  disabled one is greyed out, and one that pulses has a dot, as the button has then. Shortcuts and pinning are not used.
- With TalkBack each bar is a group, "<name> button bar", read after the script windows. A button reads its name, or
  "Button <n> of <bar>" with only an icon; a push-down one reads as a switch, on or off; a folder says a tap opens its
  menu; a checkable command reads its state. The Extensions button is read with Back, before the console, as Material
  makes the top app bar a group read first. The buttons and commands are off while no engine is attached.

### The map
The game screen draws `map` in the mapper window (`ui/game/ScriptMap.kt`, fitted by `MapFit` in `:protocol`):
- A `Geyser.Mapper`, or another mapper a script put in the main window, is placed as any window. openMapWidget()'s map,
  a dock the main window's size, is a "Map" page in either layout, and a mapper beside the text is one when stacked.
- The frame is drawn only while it is the window's size, as the engine draws one at each new size; scaled alike both
  ways to fit and centred, so a touch maps back to the frame's pixel under it.
- A tap sends a `mapPointer` press with the left button, which selects the room there, on the finger's lift, so a
  pinch selects nothing; a second tap near it within the double-tap timeout then sends a double click, which walks
  there. A pinch turns the wheel a notch for each 7% it scales, as Mudlet's map zooms by notch, about the fingers'
  middle. A drag does nothing.
- Buttons in its corner zoom out and in by three notches about the map's middle, and a switch, on while the map is
  edited, sends `setMapViewOnly`; it follows the next `map`, not the tap. A refused request shows the engine's reason
  over the map's bottom, until dismissed or the next request.
- With TalkBack the map reads as "Map of the game", viewing or editing, in the windows' group. A double tap selects the
  room in its middle, and its actions walk there and zoom; the buttons and the switch read their names. Touches,
  buttons and actions are off while no engine is attached or no frame of the window's size has come.

### The map behind the text
The game screen also has a map of its own, which needs no script: `appMap`, the engine's second map view
(docs/protocol.md, "The app's map"; `ui/AppMapClient.kt`, drawn by `ui/game/AppMap.kt`).
- Behind the text, while the setting "Map behind the text" (`Settings.mapBehindText`, on by default) has it there: it
  fills the main console, between the console's background and its text (Console's `behindText`), and follows the player
  as the desktop's mapper does: asked for, it opens the map for scripts as opening the desktop's mapper does, so a
  mapper script's `centerview()` moves the player on it with no script mapper. Its alpha is the most, up to 30%, at
  which the console's default text colour (`consoleColors`) keeps WCAG's 4.5:1 over the map's lightest and darkest
  colours alike (`MapBehindText` in `:protocol`): 30% for the default grey on black, less for dimmer text or a light
  background, none for text already below 4.5:1. The console's colours, ANSI ones included, are not changed; text the
  game colours is not weighed, so a dim colour of the game's reads less well over the map. It takes no touches, which
  reach the text as before, and TalkBack does not read it.
- In front: the title bar's map button, a toggle read as "Map" with its state, covers the game area (not the input line)
  with the map as the mapper window draws it (`MapView`, shared with `ScriptMap`: tap, double tap, pinch, the zoom
  buttons, the edit switch, refusals and TalkBack's actions), under a search field. TalkBack does not read what it
  covers. Back or the button again sends it back; with a predictive back gesture it shrinks as the gesture goes.
- The search sends `findRooms` and puts the keyboard away, as on a small screen it leaves the results no room; each
  result, read as "<room>, <area> (<number>)", is a 48 dp row, and a tap sends `showRoom`, which centres the map on
  that room and selects it, until the player moves, so its actions then walk there. "Finding rooms…", no match and
  more matches than shown are read out as they come. The results sit under the map's buttons, which they would
  otherwise hide from touch and TalkBack, and show only where the map has the room for their head and one whole row
  (not with a keyboard up on a small screen) and while the search is not being edited, so that none of their touch
  targets is ever cut off.
- The UI asks for the map (`setAppMapSize`) at the size it shows it, in dp at the screen's density, only while it
  shows it: the map in front's size while it is open, else the console's while the map is behind the text, else
  0 by 0. A size that changes as the keyboard moves is sent once it has held for 200 ms. It asks again at each attach,
  as the engine stops drawing the map when its UI goes (in the background too) and forgets the size.
- With a script's mapper: the app's map is a view of its own, with its own centre and selection; zoom is the area's,
  so shared, as between two maps on the desktop, and so is viewing or editing. So that one map shows on the screen,
  none is drawn behind the text while a mapper window (`Geyser.Mapper`, openMapWidget()'s dock) is shown; the map in
  front is there whatever the scripts made. The app's map raises no script events (`sysMapWindowMousePressEvent`,
  `sysMapAreaChanged`), and getMapSelection() does not see what it selects.
- Known limits: a `centerview()` on the room the player is already in is taken by whichever view draws first, so a
  view moved by `showRoom` (or a pan) that draws second keeps its place until the player moves to another room; the
  stand-in engine has no map, so neither draws anything there.

### Downloading the map
A game can offer its map, as GMCP's `Client.Map` (docs/protocol.md, "Downloading the map"; a carried Mudlet patch),
which the desktop's mapper turns into a "Download from game" button. The app's map has it (`AppMapClient.download`,
drawn by `ui/game/AppMap.kt`):
- With no map drawn, the map in front says so in its middle, with a button "Download the map from <host>"; with a map,
  the same button is in a bar under the search field, as a game may offer a newer map. Neither shows while the game
  offers none, nor while a download runs.
- The button sends `downloadMap`. While it runs, the bar shows Mudlet's progress label, or "Downloading the map from
  <host>…", and a progress bar read as "Map download": how far, while the size is known, else indeterminate, as
  Mudlet's guess of the size can be passed. Its Cancel button, a 48 dp target, sends `cancelMapDownload` while Mudlet
  can still stop it.
- How it ended stays in the bar until dismissed or the next download, and is read out as it comes: "Map downloaded
  from <host>", "Map download cancelled", or "The map download failed: " with Mudlet's reason, in the error colour and
  interrupting TalkBack. A refusal shows as the map's others do. The loaded map is the profile's, so the map behind
  the text, the map in front and a script's mapper all show it.
- Only the URL's host is shown: the engine never sends the rest, which can hold a user name and password. Lua's `gmcp`
  table holds the whole URL, as the game sent it, as on the desktop.
- Known limits: the button is only in the map in front, not behind the text, which takes no touches; Mudlet sets no
  time limit, so a stalled download runs until cancelled; the stand-in engine refuses both requests.

## libmudlet as the engine
Built with `LIBMUDLET_BUILD_DIR`, the engine is `MudletEngine` (`native/enginehost/MudletEngine.*`):
one profile at a time in a `THeadlessSession` behind the same socket protocol. It uses only that class's
public API, from Mudlet's `development`: it includes no Mudlet header but
`THeadlessSession.h` (which brings `TDebug.h` and `utils.h`) and sets none of Mudlet's
defines. What the app needs of that API and Mudlet does not have yet is carried in
`scripts/mudlet-patches/`, each patch going upstream to Mudlet/Mudlet and dropped here once merged.
`scripts/apply-mudlet-patches.sh`, which both libmudlet build scripts run first, applies them to the
checkout, skips one already in it and stops the build at one that no longer applies. The app's engine
compiles against the checkout's headers too, so a checkout built elsewhere, as CI's libmudlet build from its cache,
is patched before `build-apk.sh` all the same (`.github/actions/libmudlet`). Today there
are two: the app's own map and the room search (see "The map behind the text"), and downloading the map the game
offers (see "Downloading the map").
[`c-device.md`](decisions/hosting-results/c-device.md) has the emulator run of the earlier build, which read Mudlet's console model
and needed a patch.

**Build.** The same scripts as CI's libmudlet job:
1. `scripts/build-mudlet-deps.sh <prefix>` builds the third-party libraries for Android.
2. `scripts/build-libmudlet.sh <mudlet> <build> <prefix>` builds `mudlet_core`, `mudlet_headless`
   and the runner, then writes `<build>/libmudlet.cmake`.
3. `LIBMUDLET_BUILD_DIR=<build> scripts/build-apk.sh` builds the APK.

Mudlet installs and exports nothing to link against. Everything `mudlet_headless` needs (the
core, Lua, communi, the Lua C modules, QtKeychain, PCRE2 and the rest) appears only on the
runner's link line. `scripts/export-libmudlet.py` reads that line from CMake's file API and
writes it as the imported target `libmudlet::headless`. Its Qt libraries become `Qt6::` targets,
so `androiddeployqt` deploys them and their plugins as usual. Upstream now covers what the old
`deps/` scripts, the patch, `LuaModules.c` and the `mudlet-lua` assets did. On Android,
`mudlet_core` carries Mudlet's Lua files and unpacks them into the app's cache. It also carries
the seven Lua C modules (`USE_BUILTIN_MUDLET_LUA`, `USE_BUNDLED_LUA_MODULES`). QtKeychain
keeps the profiles' passwords in the KeyStore.

**What the engine maps:**

| THeadlessSession | Socket protocol |
|---|---|
| `lineShown`: each line once its triggers ran, gagged ones left out | `line`. The last one is held as the `tail` until the next line or text that does not continue it, so an `echo()` after `deleteLine()` or a command typed at a prompt (`continuesLine`) still lands on its end |
| `localTextStyled`: `echo()`, Mudlet's messages, the command echo | `line` for each piece a line feed ends, `tail` for the rest. The line feed it repeats after `lineShown` ended its line is dropped, as `docs/headless.md` says |
| `mainConsoleCleared` | `clear`. The ring is emptied, and a UI that attaches later is sent `clear` too |
| `notificationRequested` (`showNotification()`) | `notify`, posted by the engine process as the stand-in's are |
| `connecting`, `connected`, `disconnected` | status `connection` |
| `outputLine`, `localTextStyled`, `errorConsoleText` | counters, and status `luaError` |
| `profileLoaded`, `startFinished` | status `profile`, `profileLoadMs`, `startMs` |
| `hiddenInputWanted` | `hiddenInput` |
| `commandHistory()`, read at `profileLoaded` and when a UI attaches | `history` |
| `commandLineTextRequested` (`printCmdLine()` and the like) | `commandLine` |
| `scriptWindows()`, read at `profileLoaded`, then at each `scriptWindowsChanged` | `windows`, only the windows that changed and those `removed`; all of them, with `full`, to a UI that attaches. A close sends an empty list |
| `windowLines()`, the last 300 rows, at `profileLoaded` and at each `windowTextChanged` | `windowText`; any rows on request with `getWindowText` |
| `setMainWindowSize()`, then `mainConsoleGrid()` and `mainConsoleTextRect()` at `profileLoaded` and at each `mainConsoleAreaChanged` | `setMainWindowSize`, kept and given again to each profile as it loads; `mainArea` |
| `setUserWindowGeometry()`, `closeUserWindow()` | `placeUserWindow`, `closeUserWindow` |
| `submitWindowCommandLine()`; `windowCommandLineTextChanged` | `sendWindowCommandLine`; `windowCommandLine` |
| `labelContent()` of each label, read with `scriptWindows()` | `label` after the `windows`, for each label whose content changed; every label to a UI that attaches |
| `pointer()` | `pointer`, answered only when refused |
| none: the engine reads the file itself, with `QImageReader` or as a `QIcon` | `getImage`, answered by `image` as a PNG |
| `buttonBars()` at `profileLoaded` and at each `buttonsChanged`; `pressButton()` | `buttonBars`, whole, when it changed, and to a UI that attaches; `pressButton`, answered only when refused |
| `addonCommands()` at `profileLoaded` and at each `addonCommandsChanged`; `activateAddonCommand()` | `commands`, the same way; `activateCommand`, answered only when refused |
| `renderMap()` at the mapper window's size, at each `mapRepaintRequested` and `mapViewOnlyChanged` and when the mapper shows or changes size; `mapPointer()`, `setMapViewOnly()` | `map`, when it changed, and to a UI that attaches while the mapper shows; `mapPointer`, `setMapViewOnly`, answered only when refused |
| `renderAppMap()` at the size and scale `setAppMapSize` asks, at the same cues; `appMapPointer()`, `findRooms()`, `showRoomOnAppMap()`, `closeAppMap()` (carried patch) | `appMap`, when it changed, empty while nothing is drawn, and not to a UI that attaches, as the size goes with the UI that asked (`closeAppMap()`, as at 0 by 0); `setAppMapSize`, `appMapPointer`, `showRoom`, answered only when refused; `findRooms` answered by `rooms` |
| `offeredMapUrl()` at `profileLoaded` and at each `offeredMapChanged`; `downloadOfferedMap()`, `cancelMapOperation()`; `mapProgressStarted`, `mapProgressChanged`, `mapProgressCancelDisabled`, `mapDownloadFinished` (carried patch) | `mapDownload`, with the URL's host only, when it changed (progress at most once a percent), and to a UI that attaches; `downloadMap`, `cancelMapDownload`, answered only when refused |

The tail is sent once per pass of the event loop, so a flood sends its lines and a single tail.

**Profiles.** `THeadlessSession` opens a profile by name but does not list, save or delete them, so until Mudlet's
headless API does, `native/enginehost/ProfileStore.*` reads and writes Mudlet's own files: a profile is a folder under
`<configDir>/profiles`, and `saveProfile` writes the files Mudlet's connection dialog writes there (`url`, `port`,
`ssl_tsl`, `login`, `autoreconnect`), each one `QString` in a `QDataStream` at `Qt_5_12`, through `QSaveFile`.
`deleteProfile` removes the folder (`QDir::removeRecursively()`). Mudlet records no order, so the engine keeps the
order profiles were last opened or created in, `<configDir>/profile-order.json`. `openProfile` closes a running
profile with `shutdownAsync(true)`, as Mudlet closes one, then starts the other with `startAsync()`, its password in
`Options::password` for that start only.

**Lifecycle.**
- `openProfile`, or the first `connect` or `runLua`, calls `startAsync()` with `Options::configDir` set to
  `<files>/mudlet` (`MUDLET_CONFIG_DIR` overrides it on the desktop). A `connect` or `runLua` starts the profile
  `MUDLET_PROFILE` names (`Android` unless set), or the one last opened.
- An `openProfile` while another profile runs closes that one first (status `profile` `<name> closing`, then
  `<name> closed`); a close Mudlet refuses, as while a script runs, leaves it running (`<name> not closed: ...`) and
  calls off the open.
- Requests that arrive during the start are queued, and run once `startFinished` reports
  `started`. Those that arrive while a profile is being opened are for that one.
- `start()` can create a profile only from a game's address, so Lua sent before the first
  connect of a new profile gets `no profile`.
- An engine started after the last one was killed loads that one's profile again, and connects it
  if the connection was wanted (see "Restoring the session" below).
- When the engine quits, it calls `shutdownAsync(true)` and waits up to 5 s for the profile's
  sysExitEvent and save.
- On the desktop, SIGTERM quits the engine the same way.
- `setAppState` with `active` false raises sysApplicationFocusChangeEvent, then saves the profile (`saveAsync()`), as
  the system may kill a process in the background without warning. `EngineService.onTrimMemory()` saves it too, through
  `EngineNative.nativeSave()`. A switch or quit asked during a save waits for it, as Mudlet refuses to close then.
- On Android this depends on QtServiceBase ending the event loop when the service is destroyed.
  That has not been tried on a device.

**Tests.** `native/tests/test_mudlet_engine.py` runs the host build of this engine against
`native/tests/fake_telnet.py` through the socket protocol. CI builds libmudlet for the host with the
Android options (`scripts/build-libmudlet-desktop.sh`) to run it. It checks:
- the banner's colours and styles, and the prompt as the tail;
- a prompt from a game that has sent no GA, as the tail after 300 ms of quiet; after a GA, an
  unfinished line held back until its line feed;
- a gag, a trigger's `echo()`, and an `echo()` after `deleteLine()`;
- `showNotification()`, `echo()` and `cecho()`;
- `clearWindow()`, and the replay after a clear;
- a 3000-line flood that arrives whole and in order;
- Lua errors;
- the profile's save on quit;
- `setAppState`: sysApplicationFocusChangeEvent to a Lua handler and a save each time the app leaves the front;
  `alert()` as `attention`, and new text while minimized as `attention` 0 once the profile alerts on new data; and
  `moveTypedLine` ahead of `hiddenInput` when a script's typed line is up as the game hides input;
- the profile requests: each answered with `profiles` or `error`; profiles written in Mudlet's own files; a switch
  of profiles while connected; the running profile refused to `deleteProfile`; a password that reaches the game
  through Mudlet's auto-login, after the profile's login, but no frame and not the engine's log;
- the input line: a game's `WILL ECHO` gives `hiddenInput`, `sendHidden` reaches the game and no frame or log,
  `dismissHidden`, `history` to a UI that attaches and after a restart, `complete` of a word the game sent and from the
  history, and `printCmdLine()` as `commandLine`;
- script windows: a Geyser `MiniConsole` as `windows` with its geometry, and its `cecho()` as `windowText` with its
  colours; a Geyser `UserWindow` placed by `placeUserWindow`, which raises `sysUserWindowResizeEvent`; the size from
  `setMainWindowSize` before the profile loads, and `setBorderLeft()`, as `mainArea`; `enableCommandLine()` with
  `printCmdLine()` as `windowCommandLine` and `sendWindowCommandLine` running `setCmdLineAction()`'s function;
  `getWindowText`; hiding, `closeUserWindow`, a `full` `windows` and each console's text to a UI that attaches, and
  `removed`;
- labels: a Geyser `Label`'s `echo()`, `setStyleSheet()`, `setAccessibleName()`, tool tip and click callback as
  `label`, its click callback run by a `pointer` press, and `pointer` refused off the window; a new `echo()` as
  `label` alone; `setBackgroundImage()` as the label's `image`, and that file as a PNG from `getImage`, scaled; an
  SVG one with `setSvgTint()` and `setSvgRotation()`; a Geyser `Gauge`'s three labels, whose front width is its value
  and whose text label is transparent; every label to a UI that attaches; `deleteLabel()` as `removed`;
- button bars and commands: a package's bar, with its grid, a push-down button and a folder's menu, and
  `tempButtonToolbar()` bars at the left and floating, as `buttonBars`; `pressButton` sending a push-down button's
  command for down, then up, and running its script, and a menu entry pressed; `addCommand()` and
  `setCommandChecked()` as `commands`, `activateCommand` raising `sysCommandClicked` and ticking it; a disabled or
  unknown one refused, both lists to a UI that attaches, and `removeCommand()`;
- the map: a map built with `addRoom()` and `setExit()` in a `Geyser.Mapper`, as `map` at the mapper's size with the
  rooms drawn where the layout puts them; a `mapPointer` press selecting the room under it and raising
  `sysMapWindowMousePressEvent`, a double-click walking to a room through `doSpeedWalk()`, a wheel turn zooming and less
  than half a notch refused; `setMapViewOnly` raising `mapModeChangeEvent`; `pointer` on the window `mapper`; the map to
  a UI that attaches, and none while the mapper hides;
- the app's map, with no script mapper: `setAppMapSize` drawing it as `appMap` at the size and scale asked, an empty
  one with no profile or no rooms, and one larger than a PNG may be refused; `appMapPointer` walking with a double-click
  and raising no `sysMapWindowMousePressEvent`; `findRooms` by name, ignoring case, and by number; `showRoom` centring
  the map on a room, and refused for one not in the map; `setMapViewOnly` with only this map open; a UI that goes
  stopping it, and the next sent none until it asks; and 0 by 0 stopping it;
- the map download: a game's GMCP `Client.Map` offering a map from a local web server, as `mapDownload` with only the
  host; `downloadMap` downloading and loading it, with `sysMapDownloadEvent`, its rooms then found and the app's map
  drawn; `mapDownload` to a UI that attaches; a 404 and a file that is no map failing with Mudlet's reasons, and a
  slow download sending progress and cancelled, each leaving the map as it was; the refusals; another profile taking
  the offer away; and the URL's user name and password in no frame and not the engine's log;
- TLS (see "TLS" below): a self-signed certificate refused, TLS to a port that does not speak it, then the same
  certificate accepted once the profile accepts self-signed ones.

`native/tests/test_mudlet_restore.py` checks the restore below the same way, killing the engine with SIGKILL.
`native/tests/profile_checks.py` holds the profile requests both host tests run, the stand-in's `test_engine.py` too.

`native/tests/android_smoke.py` runs the debug APK, built for x86_64 with this engine, on an emulator
(CI's emulator jobs) or a device, from the host through adb. It drives the UI through a debug-only hook,
`SmokeTestReceiver` (`app/src/debug`, which only the shell and the system may send to), and checks:
- that the UI starts the engine service, which runs in the foreground in `:engine` with Qt up, and attaches to it;
- that connecting to `fake_telnet.py` on the host (`10.0.2.2` from the emulator), as the profile list does, saves a
  profile with a character name, stores a password in `PasswordStore` and opens the profile with it read back: the
  profile starts, so Mudlet's Lua (`luaL_openlibs` and Mudlet's Lua files) is up, the engine lists it first, the
  banner's coloured lines reach the UI, and Mudlet's auto-login sends the game the login and then the password;
- that a Lua trigger fires, `cecho()`s on its line and has the engine post its `showNotification()`;
- that `am send-trim-memory` has the engine save the profile;
- that a Geyser `MiniConsole` and the line a script `cecho()`s on it reach the UI;
- that after `:engine` is killed, the UI starts a new engine and attaches to it;
- that logcat never holds the password.

If the engine crashes, it stops with the crash from logcat, symbolised with `ndk-stack`.

The instrumented tests, run on the same emulator boot by `native/tests/android_instrumented.py`, check what only a
device shows, below the UI's debug hook:
- `engine-service/src/androidTest` runs `EngineService` from its own test APK, against a small game server in the
  test's process: the service in its own `:engine` process in the foreground, Qt's `serviceReadyWait` and QPA, the
  socket handshake; the engine notification's Quit, after which the next start restores nothing; a killed engine,
  after which the next one reconnects to the game (see "Restoring the session" below); and a TLS connect whose first
  bytes are a ClientHello, so Qt's backend loaded OpenSSL.
- `app/src/androidTest` runs the app against a fake engine in the test's process (`EngineClient`'s `connector`): the
  way from the start screen to a profile's editor, the game screen and the self-test and back, through the labels
  and click actions TalkBack uses; the profiles (`ProfilesTest`): the list in the engine's order, each read as "name,
  host:port", adding, editing, deleting only once confirmed, the engine's refusals, and Connect sending
  `openProfile` with the stored password; the password field, which TalkBack does not read aloud; `PasswordStore`
  (`PasswordStoreTest`), whose passwords outlast a new store, go with Forget, are never in the clear, and are excluded
  from backups; the self-test's colours and styles of each run, in the semantics tree and in the pixels drawn; the
  reattach to a new engine after one is lost, and to the same one; what TalkBack is given (new lines read as one
  announcement, `announce`, each console line as its plain text) and the `setConfig` calls the screen reader settings
  send; the script windows: a mini console at its scaled place in the fitted and the stacked layout, the size the game
  screen gives Mudlet's main window, the window switcher, a window's command line both ways, a user window's close
  button, a window's rows read as their plain text, a blank one as "Blank lines from the game" says and none read
  aloud, and a link in one asked about with `getLink` and the window's name and followed by a tap, its menu and
  TalkBack's actions; the labels: a label's text at its scaled place, a clickable one as a button whose click and tap reach the
  engine as `pointer` events, its 48 dp touch bounds, a gauge read as its progress, a link's command, and a label's
  image asked of the engine; the button bars: a bar's buttons as 48 dp buttons above or beside the console and read
  after the windows, whose taps send `pressButton`, a push-down button as a switch, a folder's menu, an icon button's
  name and icon, and the Extensions menu of commands, whose tap sends `activateCommand`; the map: drawn in the mapper
  window, a tap, a double tap and a pinch sent as `mapPointer` at the frame's point under them, the zoom buttons and
  TalkBack's actions, the switch to editing, a refusal until dismissed, a frame of another size not drawn, and the
  docked map as a page; the app's map (`AppMapTest`): asked at the console's size, again at each attach, and drawn
  faintly behind its text, which TalkBack and touches still reach, none with the setting off or a mapper window shown,
  the title bar's toggle bringing it to the front, where taps, the zoom buttons, the edit switch and TalkBack's actions
  reach it as `appMapPointer`, and Back sending it back, the room search with its results, no match, more matches
  and a refusal, and the map download: no button with no offer, the offer with no map drawn sending `downloadMap`, its
  progress and Cancel, how it ended (cancelled, failed, read out at once, until dismissed, and loaded, the map then
  drawn and the offer above it), a refusal, and the offer gone with the profile; and the notification permission, explained and then asked on Android 13 and later, on a fresh install.
  Each Compose test rule has the Accessibility Test Framework's checks on (`enableAccessibilityChecks()`), which fail a
  test on a control with no label or a touch target under 48 dp; the library needs API 34, so they run on the API 36
  emulator only.

CI runs both on API 28, the app's minSdk, and on API 36.

**Gaps:**
- Game text that no line feed or GA has ended shows as in Mudlet's desktop GUI. While the game
  has sent no GA, Mudlet ends the line after the profile's network packet timeout (300 ms by
  default) of quiet, and `lineShown` sends it, not as a prompt, so a `Login: ` prompt shows.
  Once the game has sent a GA, an unfinished line shows only when it ends.
- A change to a line already sent isn't sent again (`lineShown` sends each line once). That
  needs new `THeadlessSession` API; a Mudlet PR for it is being prepared.
- Links are marked by id, and the engine answers `getLink` and `followLink` and sends `linksChanged`. The app follows
  the links of script windows (see "Script windows' text" above), not yet the main console's.
- The game screen and the self-test each send `setScreenSize` from their own console, so the one laid out last
  sets what a game that asks is told.
- No bundled fonts.
- TLS is chosen when the profile starts (see "TLS" below).
- A change `saveProfile` makes to the running profile takes effect at its next start, as Mudlet read its details then.
- `saveProfile` makes a new profile's folder before its first start, so that start does not remove the passwords an
  earlier profile of the same name may have left in the credential store, as Mudlet does for a new name, and
  `deleteProfile` does not remove them either. The engine stores no password (the app keeps its own, encrypted, and
  sends it with each `openProfile`), so only one stored by Mudlet's own GUI under the same config dir could be left.
  Profile management in `THeadlessSession` would close this.
- On a device, Lua 5.1 must be built with `-U_FORTIFY_SOURCE`, as the stand-in's is
  (`native/standin/CMakeLists.txt`). Otherwise bionic's FORTIFY check aborts the engine in
  `luaL_openlibs`. Mudlet does not do this yet; `scripts/build-libmudlet.sh` carries it in this
  repository's libmudlet build, and CI's emulator smoke test fails without it.

### Restoring the session
Android kills `:engine` when it needs the memory, and the user can kill it from the self-test. The engine is started
again within about half a second while the UI is shown (the UI starts it, and the system may too), but a new engine
knew nothing of the profile, so the player was silently left disconnected. Now the engine keeps what it needs to
start the profile again on disk, and a new engine restores it. Only the engine with libmudlet does this; the
stand-in does not.

**What is kept.** `native/enginehost/SessionStore.*` keeps one JSON file, `<files>/engine-session.json`
(`MUDLET_ENGINE_STATE`; the app names it in `EngineSession.kt`). It holds:
- the profile, while one runs, the one `openProfile` named included, which is the only `THeadlessSession::Options`
  field the engine sets besides the connection's and an `openProfile`'s password, which is never kept, so a restored
  profile does not log in (`configDir` comes from the app's files, as at any start);
- the host and port the UI last connected it to, passed again as `Options::host` and `port` (an empty host leaves
  the address stored in the profile);
- whether the connection is wanted;
- the TLS choice the profile was started with, if the `connect` that started it made one, passed again as
  `Options::tls`, so that the UI's next `connect` with that choice is not refused;
- when each restore within the last 10 minutes started.

The file is rewritten whenever one of these changes, through `QSaveFile`, so a kill part-way through leaves the last
whole file.

**When the connection is wanted:**
- It is, from a connect from the UI, or once the profile is connected for any reason.
- A disconnect from the UI ends it.
- A connection the game closed or refused ends it too, unless the profile reconnects automatically. That is
  Mudlet's "Reconnect automatically" in the connection dialog, which the engine reads from the profile's own
  `autoreconnect` file. So a restore reconnects exactly when Mudlet itself would still be trying to.

**When the engine restores.** At start-up, a file that names a profile means the engine before it was killed:
- An orderly stop deletes the file first: the engine's destructor, run when Qt's event loop ends (SIGTERM on the
  desktop, the service's destruction on Android).
- The app also writes a stop marker, `<files>/engine-stopped` (`MUDLET_ENGINE_STOPPED`), from
  `EngineService.onDestroy` and from the UI's Stop engine. A process kill never runs `onDestroy`. The next engine
  takes the marker and restores nothing, so a stop is never undone, even if `System.exit(0)` ends the process before
  the destructor runs, or the engine was not running when the user stopped it.
- A profile that a script closed, or that failed to load, is forgotten.

**How it restores.** The profile is loaded with `startAsync()` from the event loop, once the engine listens, and
connected if that was wanted. It does not wait for a UI. Status `restore`
([`protocol.md`](protocol.md), "The restore status") says what happened, and the engine keeps the status, so a UI
that attaches later is told too. The UI shows it as a marker line.

**Not hammering a game.**
- Each restore counts itself in the file before it starts, so one that kills the engine still counts.
- The first restore within 10 minutes connects at once, the second after 10 s, the third after 30 s.
- A start that finds three restores within 10 minutes restores nothing (`gaveUp`) and forgets the profile. A crash
  loop therefore calls a game at most three more times. The profile comes back when the user starts it again.
- A failed reconnect is not retried by the engine. Mudlet retries it on its own, with its own back-off from 5 s,
  only for a profile that reconnects automatically.

**Not covered:**
- A script's `disconnect()` counts as the game closing the connection, so a profile that reconnects automatically
  would be reconnected after a kill.
- A game a script connected to with `connectToServer()` is reconnected at the UI's last address, or the profile's.
- The engine cannot tell the user's force-stop, or an app update, from a kill, so it restores the profile when the
  app next starts it.
- The engine's 2000-line scrollback is not kept; the UI keeps its own lines.

## Qt internals relied on
- **`org.qtproject.qt.android.QtServiceBase`** (from `Qt6Android.jar`).
  - Qt documents extending `QtService`, which is in the bindings sources only and
    not in the jar. I extend `QtServiceBase` directly.
  - The behaviour I depend on, read with `javap`:
    - `onCreate` refuses to start if `QtNative` is already started.
    - It calls `QtNative.setService`, then `QtServiceLoader.loadQtLibraries`,
      then `QtNative.startApplication`.
    - `onBind` returns `QtNative.onBind`.
    - `onDestroy` calls `terminateQtNativeApplication` and then **`System.exit(0)`**.
  - It would break if the class is renamed or made final, or if the order of
    `onCreate` changes, which would invalidate my "start foreground first" call
    before `super.onCreate()`.
  - The separate process is exactly what makes `System.exit(0)` harmless here.
- **Qt's service-setup semaphore**, which uses private API from
  `QtCore/private/qjnihelpers_p.h` through `Qt6::CorePrivate`. Qt's configure
  prints a warning that the build is now "tied to this specific Qt module
  build version".
  - In Qt 6.12.0, `QtNative.startApplication()` calls `waitForServiceSetup()`
    whenever `QtAndroidPrivate::service().isValid() &&
    QtAndroid::isQtApplication()` is true (androidjnimain.cpp:555-562). That
    holds for any `QtServiceBase`.
  - The semaphore is released only by `setOnBindListener()`, once the locker
    count is zero. Only `QAndroidService` does that (qandroidextras.cpp:551/620),
    so a `QGuiApplication` never releases it, and the QPA plugin makes no
    difference.
  - At the top of `main()`, `native/enginehost/AndroidServiceSetup.cpp` calls
    `QtAndroidPrivate::acuqireServiceSetup(0)` (Qt's misspelling) and then
    `setOnBindListener()` with a listener that returns null.
  - It breaks if Qt renames these functions or changes the locker logic. The
    symptom would be an ANR in `Service.onCreate` with no engine.
  - The source line numbers above come from prototype A's reading of Qt's
    source. The private header is checked here; the source is not installed.
- **The ordering of application state.**
  - `QtServiceBase.onCreate()` sets `Qt::ApplicationHidden`. If that state
    reaches `updateApplicationState()` after the platform integration exists,
    Qt calls `QAndroidEventDispatcherStopper::goingToStop(true)`. The event
    dispatcher then excludes timers and socket notifiers, and the engine stops
    silently.
  - Before the integration exists, the state is only stored as pending and is
    applied later without `goingToStop`.
  - So `main()` does not create `QGuiApplication` until
    `EngineNative.nativeServiceReady()` arrives, and that is sent after
    `super.onCreate()` returns. This depends on the pending-state code path.
  - The self-test shows `appState` (1 = Hidden) and the service-ready result,
    and the heartbeat and line counters show whether timers and sockets still
    run.
  - `nativeServiceReady` is bound by its JNI name. That relies on `QtLoader`
    loading `libengine` through the app's class loader.
- **`QtLoader` metadata lookup.**
  - It reads `android.app.lib_name`, `android.app.arguments` and
    `android.app.background_running` from `ServiceInfo`, through
    `ComponentName(context, context.getClass())`.
  - So the meta-data must sit on the service that is the concrete subclass.
  - `background_running` sets `QT_BLOCK_EVENT_LOOPS_WHEN_SUSPENDED`. Its meaning
    for a service is not documented.
- **The resource arrays in `libs.xml`:** `qt_libs`, `bundled_libs`,
  `load_local_libs`, `use_local_qt_libs` and `bundle_local_qt_libs`.
  - They are generated by `androiddeployqt --aux-mode`, which copies the
    libraries, plugins and jars into `engine-service/qtdeploy/` without building.
  - `scripts/build-apk.sh` then deletes the generated `build.gradle`,
    `settings.gradle` and `AndroidManifest.xml`, and `:engine-service`'s Gradle build
    consumes `qtdeploy/libs` and `qtdeploy/res`, which reach the APK through `:app`.
  - Both the libs.xml keys and aux-mode's output layout are internal to Qt and
    can change in any release.
- **Qt's `src/android/java/res`** is added as a resource directory, for the
  values QtLoader expects.
- **The `android` QPA plugin in a process with no Activity.** Qt only
  documents `QAndroidService`, which is `QCoreApplication`-based, for services.
  `QGuiApplication` in a service process is **not verified**, even with the two
  fixes above.
  - The fallback is `QT_QPA_PLATFORM=offscreen`: the `qoffscreen` plugin is
    deployed and a button toggles it.
  - The fallback does not help with the semaphore, which is independent of the
    QPA plugin.
- **`QNativeInterface::QAndroidApplication::context()`** returns the Service
  inside the service process. This one is public API.
- **Build versions.** The Qt 6.12 templates expect AGP 9.2.1, but the system
  Gradle here is 8.14.3, so the app uses AGP 8.13.2 and Kotlin 2.3.21. The
  compile and target SDK are set to 36 in both CMake and Gradle, overriding
  Qt's default of 37.

## Lifecycle
- **Activity finished ("Kill UI") or recreated (rotation).**
  - *Verified on the host; expected on the phone.*
  - `onStop` stops the UI's link. Qt is in another process and does not notice
    anything except the socket closing. The game connection stays up (host test:
    "game connection survived the UI detaching").
  - `onStart` restarts the **same** `EngineLink`. Because it sends its
    `instance` and `lastSeq`, the engine replays only the lines the UI missed
    (JVM test: `synced.seq == seqBefore`).
  - A quick `stop()` followed by `start()` used to leave two reader threads
    fighting over the engine. It is fixed with a per-thread generation; the JVM
    test reproduces the bug and fails without the fix.
  - Killing the UI process instead gives a new UI the full 2000-line ring plus a
    `gap` marker, in 44 ms on the host.
- **Service stopped** (the "Stop engine" button, or the notification's Quit action).
  - *Expected.*
  - `stopService` leads to `QtServiceBase.onDestroy`: Qt terminates and then
    `System.exit(0)` ends **only** `:engine`.
  - The UI sees EOF (3-16 ms on the host) and shows "engine connection lost;
    reconnecting". Because `engineWanted` is now false, it does not restart the
    engine; "Start engine" starts a fresh process.
  - Qt cannot be restarted inside the same process, but that never comes up here.
- **Process killed and restarted.**
  - Killing `:engine` ("Kill engine process", or the low-memory killer): the UI
    sees EOF and retries. After 3 failed connects, while an Activity is started
    and the engine is wanted, it calls `startForegroundService`; that is allowed
    because the app is in the foreground.
  - The service is `START_NOT_STICKY`. On the emulator the system started
    `:engine` again about 300 ms after the kill all the same
    ([`c-device.md`](decisions/hosting-results/c-device.md), 9b).
  - The new engine has a new `instance`. The UI keeps its old lines and adds a
    "new engine instance" marker. The engine's scrollback is gone.
  - The engine with libmudlet loads the profile again and reconnects it, and the
    UI shows that as marker lines ("Restoring the session" above). The
    stand-in's game connection is gone.
  - Host timings: the restarted engine is reachable 33 ms after it starts, and
    the JVM link reattaches 110 ms after the kill.
  - On a phone, expect a few hundred ms for the process fork and Qt library load.
    The self-test shows "loss->attach ms" and "UI start->hello ms".
  - Killing the UI process does not touch the engine: the notification stays and
    tells are still posted. Host test: with no UI attached, every line still
    reached Lua `onLine`.
- **Screen off for a long time.**
  - *Expected; check the heartbeat on the phone.*
  - `:engine` is a foreground service, so it is not cached or frozen and keeps
    network access. Doze may still delay its timers, and the heartbeat's
    "largest gap" will show it.
  - The UI process is backgrounded and may be frozen. That is harmless, because
    it detached in `onStop` and the engine never waits for it.

### Which notification work is in which process
- **`:engine`:**
  - The foreground-service notification, which must come from the service
    calling `startForeground`.
  - All "Tells" notifications: Lua `notify()` goes through `SocketBridge::notify`
    and `AndroidNotifier` (JNI) to `EngineNotifications.postTell`. They work
    while the UI is dead or frozen.
  - The Quit action is a service `PendingIntent` handled in `onStartCommand`.
  - The counters are sent to the UI as statuses.
- **UI process:**
  - The `POST_NOTIFICATIONS` runtime request, which needs an Activity. A dialog says what the notifications are for
    first. The engine's notification posted before the user allows is dropped, so on Allow the UI sends the service
    another start, whose `startForeground` posts it.
  - The tap target: the notification's content intent opens `MainActivity`.
  - The UI only counts the `notify` frames it sees; it never posts.
- **Both:** `Application.onCreate` runs in both processes, and channel creation
  is idempotent.

### Memory: two-process estimate
- **Measured on the desktop engine:**
  - Idle: RSS 30.5 MB, PSS 15.5 MB.
  - After floods: RSS 43.7 MB, PSS 28.6 MB.
  - Peak while a UI stalls: RSS 60 MB, bounded by the 4 MB high-water mark plus
    the ring.
- **Estimated on Android:** `:engine` is a full ART process forked from zygote,
  plus Qt.
  - Compared with running everything in one process, the extra cost is a second
    ART runtime with its own heap and JIT. That is typically about 8-15 MB PSS
    for a process that runs only a service.
  - The scrollback is held twice, by the UI and by the engine ring, at about
    0.5-1 MB each for 2000 lines.
  - Qt is mapped only in `:engine`, so nothing large is duplicated.
- **Total estimate: 10-20 MB PSS** more than an in-process design. Not measured.
  On the phone, run `adb shell dumpsys meminfo org.mudlet.mudlet` and
  `... org.mudlet.mudlet:engine`.

## What building Mudlet on it costs
- **The engine side is straightforward.** `libengine.so` would become
  `mudlet_core` plus a socket-backed `TConsoleFrontend`/`TAppFrontend`. The
  bridge is a few hundred lines: a frame codec, a ring buffer and request
  dispatch. It already builds for desktop, so the whole engine-plus-protocol
  stack can be tested headless with no phone, as done here.
- **The UI side costs more.** Everything the UI needs must be pushed as state or
  answered by request id, because there is no synchronous getter such as
  "what is the current prompt".
  - Mudlet's frontends reach into `Host`, `TConsole`, the mapper and the editors
    synchronously.
  - Every one of those becomes a protocol message and a Kotlin model: consoles,
    mini-consoles, labels, gauges, the map, trigger and alias editors, settings,
    and the package manager.
  - Large payloads, such as the map or a long scrollback, need incremental
    messages rather than snapshots.
  - Versioning the protocol (`v` in hello) is required from day one, because the
    two halves ship together but must reattach across an app update's process
    restarts.
  - The upside: these are the same seams a remote, headless or multi-client
    Mudlet would need.
- **Daily development.**
  - Two languages and two build systems: CMake builds `libengine` and
    `androiddeployqt --aux-mode` stages it; then Gradle builds an APK that has
    no Qt Gradle plugin in it. This is easier than embedding Qt's Gradle
    template, because the Android side is a normal Compose project that Android
    Studio understands.
  - Debugging is per process. The engine can be debugged on desktop against the
    fake server, and Kotlin with ordinary tools. Native debugging on the phone
    means attaching lldb to `:engine`.
  - Builds: about 1.5 min clean, and 33 s for a Kotlin-only change on this
    shared machine.
- **Lower lifecycle risk.** Qt's lifecycle assumptions, such as one start per
  process and `System.exit` on destroy, never meet the Activity. A Qt crash
  cannot take down the UI; the UI shows "reconnecting" and restarts the engine.

## Native libraries: loaded from the APK, or extracted
By default the APK stores its `.so` files uncompressed and 16 KB aligned, and Android loads them
from the APK in place (`useLegacyPackaging = false`, AGP's default for minSdk 23 and up).
`scripts/build-apk.sh -PextractNativeLibs=true` turns on `useLegacyPackaging`: the libraries are
compressed in the APK and the installer extracts them to the app's `nativeLibraryDir`. Qt starts
either way, as Qt's `QtLoader` loads from `nativeLibraryDir` whenever that folder holds libraries.

Extraction matters only for a Lua C module that `require()` is to find on `package.cpath` as
`lib<name>.so`. Lua's `require()` opens each candidate path with `fopen()`, which cannot read a
file inside the APK, so such a module loads only when extracted. With Mudlet/Mudlet#12909, Mudlet
then puts `<nativeLibraryDir>/lib?.so` on `package.cpath`. A module the app hands to
`THeadlessSession::preloadLuaModule()` needs no extraction, and neither do Mudlet's own C modules
(lfs, lpeg, rex_pcre2 and the rest), which `mudlet_core` carries on Android and preloads.

What extraction costs, measured on CI's debug APK of prototype C with the stand-in engine (Prototype
APKs run 43482451881, commit 0dd127f). The "extracted" column is a repack of that APK with the same 16
libraries deflated at zlib level 6, not an APK AGP built; the `APK (extracted libs)` CI job builds
that variant on every run and reports its real sizes. MB here is 10^6 bytes, as in
the CI summary, so the libraries' 20,419,784 bytes are 20.4 MB (19.5 MiB).

| | Loaded from the APK (default) | Extracted |
|---|---|---|
| `lib/arm64-v8a` in the APK | 20.4 MB, stored | 7.6 MB, deflated |
| APK file | 48.3 MB | 35.4 MB (-27%) |
| On the device: APK plus extracted libraries | 48.3 MB | 55.8 MB (+7.5 MB, +16%) |

The extra space on the device is the compressed copy that stays in the APK, here 37% of the
libraries' size, so it grows with libmudlet's engine. A store download is compressed in transit
either way, so its size should change little; that was not measured. The default stays off while
the APK ships no Lua C module that `require()` must find on `package.cpath`.

## TLS
Qt for Android has no TLS of its own: its OpenSSL backend (`plugins/tls/qopensslbackend`) loads OpenSSL at run
time, and Android has none an app may load. Without it the engine logs `qt.network.ssl: No functional TLS backend`,
`QSslSocket::supportsSsl()` is false, Mudlet refuses a profile with TLS on ("Mudlet can not load support for secure
connections"), and https downloads (packages, maps) fail. The APK therefore carries OpenSSL.

**What Qt 6.12.0 expects**, read from its sources (`qtbase/src/plugins/tls/openssl/qsslsocket_openssl_symbols.cpp`)
and checked in the prebuilt plugin:
- OpenSSL 3. The plugin is built against OpenSSL 3 headers and refuses a library older than 3.0
  ("Incompatible version of OpenSSL").
- The files `libcrypto_3.so` and `libssl_3.so`. On Android it `dlopen`s `crypto` and `ssl` with the suffix `_` plus the
  major version it was built with, so it never picks up the system's own `libcrypto.so`. `ANDROID_OPENSSL_SUFFIX` in
  the environment would change the suffix; nothing sets it. Each library's SONAME must be its file name, and
  `libssl_3.so` must need `libcrypto_3.so`, not `libcrypto.so.3`.
- Packaging through the target property `QT_ANDROID_EXTRA_LIBS`. `androiddeployqt` copies those libraries into
  `libs/<abi>/`, `--aux-mode` included, and lists them in `libs.xml` as `extra_libs`, so Qt's loader loads them at
  start, before the engine.

**The build.** [`scripts/build-openssl.sh`](../scripts/build-openssl.sh) builds OpenSSL 3.5.9, an LTS release
supported until April 2030, from its release tarball, checked against a pinned sha256. It uses OpenSSL's own Android
targets with the NDK at API 28 for `arm64-v8a` or `x86_64`. A target of its own adds `shlib_variant => "_3"`, which
gives the files, their SONAMEs and the dependency the suffix. The libraries are linked with
`-z max-page-size=16384` and stripped. The script fails unless their SONAMEs, `libssl_3.so`'s dependency and every
LOAD segment's 16 KB alignment are as Qt and Android need. Engines, the legacy provider and loadable modules are
left out, as Qt uses none of them. `native/enginehost/CMakeLists.txt` refuses to configure for Android without
`ANDROID_OPENSSL_DIR`, so no APK ships without TLS. `scripts/build-apk.sh` passes it, building OpenSSL into
`native/openssl-<abi>` on its first run. CI builds OpenSSL once and caches it under the script's hash
(`.github/actions/android-openssl`).

The cost is 6.6 MB of `lib/arm64-v8a`, stored uncompressed: `libcrypto_3.so` 5.5 MB and `libssl_3.so` 1.0 MB. CI's
job summary gives the size of each APK with them.

**Certificates.** The engine checks a game's certificate as Mudlet's GUI does. Qt verifies the chain and the host
name; a certificate that fails ends the connection, the reasons are printed in the main console ("[ ALERT ] - Socket
got disconnected", then reasons such as "The certificate is self-signed, and untrusted"), status `connection`
becomes `disconnected`, and the profile does not reconnect. A profile that accepts expired or self-signed certificates, or any error (the GUI's three boxes, saved
with the profile), connects anyway. The app has no screen for those boxes yet, so a self-signed game needs a
profile from the GUI or edited by hand. A secure connection needs the game's name, not an IP address, as Mudlet
refuses TLS to a raw address.

On Android Qt does not read a certificate folder. The first TLS connection asks Java's default `TrustManagerFactory`
for its accepted issuers (`QtNative.getSSLCertificates()`), Android's system CAs, and Qt keeps them for the process.
Whether CAs the user installed are among them is for a device to show. On the desktop Qt reads the system's
certificate folders, such as `/etc/ssl/certs`.

**The protocol.** `connect` carries `tls`, which libmudlet passes to `THeadlessSession::Options::tls` when that
connect starts the profile; Mudlet stores it with the profile, as its connection dialog does. `THeadlessSession`
takes the choice only at start, so while the profile runs a `connect` with a different `tls` is refused with status
`connection` `error: ...`; stopping and starting the engine changes it. An engine restored after a kill starts
the profile with the choice it was started with (see "Restoring the session"). The stand-in engine has no TLS and refuses
`tls` true the same way. The self-test screen has a TLS box beside the port.

**Tests.** `native/tests/test_mudlet_engine.py` runs the real engine on the host, with the host's Qt and OpenSSL,
against `fake_telnet.py` behind Python's `ssl` with a certificate it makes with the `openssl` command:
- a self-signed certificate is refused, with Mudlet's reasons, no game text, and a handshake the server sees fail;
- TLS to the plain port fails as "Secure handshake failed";
- a `connect` without TLS to the running TLS profile is refused;
- after the profile's `mSslIgnoreSelfSigned` is set in its save, the same server's banner arrives and the game sees
  a TLS 1.x connection.

CI's APK check (`.github/actions/check-apk`) looks in each APK for the files Qt will load: `libcrypto_3.so`,
`libssl_3.so` and Qt's OpenSSL plugin, that plugin's `_3` suffix, the SONAMEs, `libssl_3.so`'s dependency on
`libcrypto_3.so`, a few of the functions Qt resolves, OpenSSL 3's version string, and 16 KB alignment.

**Only a device can show** that the handshake works on Android: that Qt's loader loads both libraries, that the
plugin finds them, and that `supportsSsl()` is true. The same goes for the system CA store: whether Qt gets
Android's CAs, how long fetching them takes at the first connection, and whether a CA the user installed is
trusted.
Item 13 of the on-device checklist covers this.

## On-device checklist
1. Install the APK with `adb install -r app-debug.apk`. Open it and allow notifications. It opens on "Profiles";
   tap "Self-test" for the checks below (debug builds only: a release build has no self-test).
2. **The critical check: does Qt start in a service-only process?**
   - There must be no ANR, and no "isn't responding" dialog, in the first 10 s.
     An ANR in `Service.onCreate` means the semaphore release did not work.
   - The top line should show "Qt up N ms after Application.onCreate (:engine)".
   - The font line should show a real family name (expect Roboto Mono or Droid
     Sans Mono) with QPA `android`.
   - "service-ready handshake" should read `ok`. `timeout` means
     `nativeServiceReady` did not bind; check logcat for `UnsatisfiedLinkError`.
   - Note the `appState` value.
   - Wait 30-60 s. Heartbeat ticks must keep rising, and after Connect, lines
     must arrive. A frozen heartbeat with a live process means Qt's
     `ApplicationHidden` stopped the dispatcher.
   - If the link stays "engine not reachable", collect
     `adb logcat | grep -iE "qt|Mudlet|engine"`.
   - Then press "QPA next start: offscreen", "Stop engine" and "Start engine",
     and see whether the engine comes up with `offscreen`.
3. Check the foreground service. The line should read
   `FGS: foreground=true process=org.mudlet.mudlet:engine`, the "Keeping your game connected"
   notification should be visible, and the two process ids should differ.
4. Connect to `aardmud.org:23`. Expect coloured output, a prompt and rising line
   counters.
   - Send `lua return lineCount` and check the result.
   - Have someone send you a tell, or use a MUD that echoes one. A "Tells"
     notification should appear.
5. Press "Ping x300" and note the median and p99. The host gives about 50-110 µs;
   a phone will be slower.
6. Rotate the phone. The link should read "same engine, delta replay", with no
   duplicated lines and no repeated attaches.
7. Press "Kill UI", then reopen from the launcher or the notification. The game
   is still connected, and only the missed lines arrive.
8. Press "Kill UI process" and reopen. The game is still connected, and the
   scrollback is replayed.
9. Press "Kill engine process". Expect "engine connection lost", then an
   automatic restart and "new engine instance"; note the loss-to-attach time.
   - With the stand-in, the game connection is gone, as expected.
   - With libmudlet, expect the marker lines "profile restored, reconnecting to
     ..." and "Reconnected to ...", and the game's output again. Note how long
     after the kill it reconnects.
   - Kill it twice more within 10 minutes: the second restore waits 10 s and the
     third 30 s before connecting, and a fourth kill brings back no profile ("The
     engine keeps stopping").
   - Kill it with `kill <enginepid>` from `run-as` while another app is in
     front. Note whether the system starts the engine again on its own, and
     whether it then reconnects with no UI shown.
10. Press "Stop engine". The `:engine` process and its notification should
    disappear, and the UI stays alive.
    - Check with `adb shell ps -A | grep mudlet`.
    - Then press "Start engine". With libmudlet, no profile comes back and the
      game is not called.
    - Repeat with the notification's Quit action.
11. Leave the phone with the screen off for 30 minutes or more, connected to the
    game.
    - Afterwards, check the heartbeat's largest gap and whether the game is
      still connected.
    - Also check whether tells arrived as notifications while the screen was off.
12. Measure memory with `adb shell dumpsys meminfo org.mudlet.mudlet:engine`, and
    the same for the UI process.
13. TLS, with the libmudlet APK (the stand-in has no TLS).
    - Check that `adb logcat | grep -i ssl` shows no "No functional TLS backend".
    - Tick TLS and connect to a game from Mudlet's list that has TLS on, such as MUME (`mume.org`, 4242). Expect the
      game's text and `connected`, then note the time from Connect to the first line.
    - https, as package and map downloads use it: send
      `lua registerAnonymousEventHandler("sysDownloadDone", function(_, f) echo("got " .. f .. "\n") end)
      registerAnonymousEventHandler("sysDownloadError", function(_, e) echo("failed: " .. e .. "\n") end)
      downloadFile(getMudletHomeDir() .. "/tls-check.html", "https://www.mudlet.org/")` as one line, and expect
      "got".
    - With TLS ticked, connect to a game whose port has no TLS. Expect "Secure handshake failed".

14. The app's screens, with TalkBack on.
    - The start screen reads "Profiles", then each game as "name, host:port", "Edit name, button" and "Connect to
      name, button", then "Add a game, button". "Connect to name" opens the game screen, and its "Back, button"
      returns.
    - In the editor, each field reads its label. Typing in the password field, TalkBack says "dot" or nothing, never
      the letters, and the field reads as a password field, without the text. The switches read their labels and
      their state, and a double tap anywhere on a switch's row toggles it.
    - "Delete" opens a dialog that TalkBack moves into and reads; Cancel returns to the editor with the game kept.
    - After a refused save (a name with a `/`, say), TalkBack reads the engine's reason without moving focus.
    - Add your own game with its character name and password, and Connect: Mudlet logs in. Force-stop the app in the
      system's settings, reopen it and Connect: it logs in again. Forget password, then Connect: the game asks for it.
    - With the system's dark theme on and off, every screen follows it, the window behind the first frame included.
    - At the system's largest font size, the start and game screens' text grows and nothing is cut off.
15. The game screen, connected to a game (Connect on one of the start screen's games).
    - The keyboard: tapping the input line opens it, and the console shrinks above it with its newest line still in
      view. Send on the keyboard sends the line and leaves the keyboard open, ready for the next. Note the keyboard's
      action key: it should read Send, and suggestions, corrections or a capital first letter should not change what is
      typed. Type half a word, then the up button: the earlier command replaces it whole, and typing goes on from the
      end of it.
    - The suggestions: send a few commands, then type the start of one. Up to five buttons above the line offer the
      commands that match, and the rest of the best one shows greyed after the cursor. A button puts its command on
      the line without sending it; the arrow button at the start of the row, or a hardware keyboard's right arrow at
      the end of the line, takes the greyed text. After a send, the empty line's buttons offer what usually came next.
      With nothing to offer the row is gone. Close the app from the recent apps and reopen it: they are still there.
    - The history: "History" below the line opens a sheet with the commands sent, best first, and the keyboard on
      its search field; typing "od" keeps "open door". A row's text puts the command on the line without sending it
      and closes the sheet; its Send button sends it. Pin one: it shows as a button below the line, which sends it
      at a tap, and it is still there after the app is closed from the recent apps and reopened. Unpin it from the
      sheet's Pinned section. With a hardware keyboard, Ctrl+R opens the sheet; Back closes it.
    - Scroll up while the game sends text: the console stays put and "Jump to the bottom: N new lines" counts up; the
      button goes back to the newest line, which the console then follows again. Rotate the phone while scrolled up,
      and again with a half-typed line: both stay. The same in split screen.
    - With TalkBack on: the input line reads as "Command, edit box"; the up, down and Send buttons read their names;
      the connection state is read out when it changes (connecting, connected, disconnected), without moving focus.
      The line reads only what was typed, never the greyed text. Swiping goes from the console's lines to the input
      line, then "Accept the suggestion: ..." and each "Suggestion: ...", then the up, down and Send buttons, then
      "History" and each "Pinned: ...", then the connection bar. History moves the focus into the sheet: its title,
      "Close the history", "Search the history", then each row's command ("double tap to put it on the command line")
      and its "Send ...", "Pin ..." or "Unpin ..." and "Forget ..." buttons. Back closes it and the focus goes back.
16. The input line's engine features, connected to a game with the libmudlet APK.
    - At a password prompt (any game's login), the input line becomes a "Password" field with the keyboard's password
      layout, still open. TalkBack reads it as a password field and says "dot" or nothing for what is typed, never the
      letters. There are no suggestions, no greyed text and no quick bar while it shows, and Ctrl+R opens nothing.
      Send logs in; the field goes back to the command line, with what was typed there before, and the password is
      not among the suggestions or in the history sheet afterwards. Its close button gives the command line back
      without sending.
    - The up button walks the commands of the profile's earlier sessions too (close and reopen the app first).
    - Type the start of a word the game sent, then the Tab button (or Tab on a hardware keyboard): the word is
      completed, and again goes on to the next match. TalkBack reads "Complete the word, button", and "No completion"
      when nothing matches.
    - `lua printCmdLine("say hi")` puts "say hi" on the line. Type a command and start the next one at once: what
      was typed is not wiped when the game answers.
    - Disconnect, then send a command: "Not sent: ..." shows over the foot of the console, above the keyboard, and
      TalkBack reads it without moving focus.
17. About and licences, with TalkBack on.
    - The start screen's top bar has "About and licences, button". The About screen reads the app's version and the
      engine (with the libmudlet APK: "Engine: Mudlet" and its version, and the commit), then the headings ("Free
      software", "Licences" and a heading for each group) as headings: TalkBack's headings navigation jumps between
      them.
    - Each source code button opens the browser at that source: this app's and Mudlet's at the commit shown, Qt's
      download page, and docs/licences.md. A row of the list reads its name, version and licence, and "double tap to
      read its licence"; its screen reads the full licence texts paragraph by paragraph, and Back returns to the list
      where it was.
    - Far down the list and far into a licence text, swipe through: every element TalkBack lands on reads its text,
      including a line half scrolled off at the top (the emulator's accessibility check reports one there as
      unlabelled, which `AboutScreenTest` therefore does not check).
18. Text size and the screen size the game is told, connected to a game with the libmudlet APK.
    - Pinch the game's text with two fingers: it grows and shrinks smoothly, and one finger still scrolls. The
      settings button in the game screen's title bar opens "Settings", whose text size slider, "Smaller text" and
      "Larger text" buttons change it too; TalkBack reads the slider as "Text size", with its size, and swiping up or
      down on it changes the size.
    - On a game that shows the width it was told (many have a screen-width option), a line of that many characters
      fills the console's width. Open and close the keyboard, and rotate the phone: the game is told the new size
      once, after the change, not at every step.
    - At the system's largest font size, the console's text is larger, the game is told fewer columns, and the
      settings screen's text is not cut off.
    - `lua setBackgroundColor("main", 0, 0, 80)` turns the console's background dark blue; text in default colours
      keeps them.
19. Screen reader output, with TalkBack on, connected to a game with the libmudlet APK.
    - On the first run with TalkBack on (clear the app's data first), the app asks whether to tell games you use a
      screen reader; the answer shows in Settings as "Tell games I use a screen reader".
    - New text is read as it arrives, without moving focus. A burst (`look` in a busy room, or
      `lua for i = 1, 20 do echo("line " .. i .. "\n") end`) is read once, as its count and its last lines, not
      queued line by line. With "Read new text aloud" off in Settings, nothing is read.
    - `lua announce("polite")` is read after what TalkBack is saying; `lua announce("now", "importantall")`
      interrupts it. The same announcement twice is read twice.
    - Swiping through the console moves line by line, and each line is read as its text, without colour or style
      names. Swiping past the connection bar reaches the last announcement.
    - "Blank lines from the game": Hide them, then send a command whose answer has blank lines: they are gone from
      the console, and TalkBack does not stop on them.
20. Script windows, with the libmudlet APK and a game's own GUI package (a Geyser layout with a chat mini console and
    a docked user window, for example), in portrait and in landscape, on a phone and on a tablet or a foldable.
    - At 600 dp and wider the windows sit where the package put them, the console in the space it left, and the
      game's lines fill the console's width without wrapping early or leaving a gap (the game is told the console's
      grid). Narrower, the borders become bands above and below the console, and the side windows and docks become
      tabs below it; a tab opens its window over the console, its back button or the system's Back closes it.
    - A window's text is readable, its newest line in view; scrolled back, it stays put.
    - With TalkBack on: swiping goes from the console to the input line, the quick bar and the connection bar, then
      the windows, each read as "<name> window", then the tabs; a window's command line, its Send button and a user
      window's close button read their names.
21. Labels and gauges, with the same package and the libmudlet APK.
    - Labels look as on the desktop: their colours, borders, rounded corners, text and its alignment, and images,
      scaled with their windows. A gauge's front follows its value.
    - A tap on a clickable label does what a click does on the desktop, a double tap what a double click does, and a
      long press opens a Geyser right-click menu and shows the label's tool tip. A slider made of labels follows a
      drag. A link in a label's text runs its command.
    - With TalkBack on: a clickable label reads as a button with its name, and its actions offer the right-click and
      its links; a label's text reads as text, an image alone not at all; a gauge reads its text, or its percentage.
22. Button bars and commands, with the libmudlet APK. `lua tempButtonToolbar("Moves", 0, 0) tempButton("Moves",
    "North", 0)` adds a top bar, `lua tempButtonToolbar("Side", 1, 1) tempButton("Side", "Go", 1)` a left bar, and
    `lua addCommand{name = "Speech", menuPath = "Tools/Voices", surfaces = {"menu"}}` a command; a push-down button
    and a folder come with a package whose bar has them, made in the desktop's Buttons editor.
    - At 600 dp and wider a left bar is beside the console and a top bar above it; narrower, both are rows above it.
      A tap on a button does what a click does on the desktop; a push-down button stays down until tapped again; a
      folder's menu runs the entry picked.
    - The Extensions button in the top bar lists the commands under their menu paths, and a tap runs one.
    - With TalkBack on: swiping goes from the console to the input line and the bar, then the windows, then each
      button bar, read as "<name> button bar" and then its buttons, a push-down one as a switch, on or off.
23. The map, with the libmudlet APK and a profile with a map (one downloaded by its package, or rooms made with
    `addRoom()` and `setExit()`), in a `Geyser.Mapper` and, with `lua openMapWidget()`, docked.
    - The map looks as on the desktop, scaled to the window, and follows the player; the docked one is a "Map" tab.
    - A tap selects the room under it, a double tap walks there, a pinch zooms about the fingers, and the zoom buttons
      about the middle. The edit switch turns on and raises `mapModeChangeEvent` (`lua registerAnonymousEventHandler(
      "mapModeChangeEvent", function(_, mode) echo(mode .. "\n") end)` prints `editing`), and off again.
    - With TalkBack on: the map reads as "Map of the game, Viewing" after the console, and its actions walk to the
      room in the middle and zoom; the zoom buttons and the switch read their names.
24. The map behind the text, with the libmudlet APK and a profile with a map but no `Geyser.Mapper` (rooms made with
    `addRoom()`, `setExit()`, `setRoomArea()` and `centerview()` do).
    - The map shows faintly behind the console's text, filling it, and follows the player as they walk
      (`lua centerview(<room>)` moves it there); the text and its ANSI colours read as without it, and a swipe scrolls
      the text and a pinch sizes it, as before.
    - Turning "Map behind the text" off in the settings takes it away; on, it comes back. Opening a mapper
      (`lua myMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})`) takes it away
      too, and closing it (`lua myMapper:hide()`) brings it back.
    - The title bar's map button brings it to the front: a tap selects, a double tap walks there, a pinch and the zoom
      buttons zoom, and the edit switch edits it. "Find a room" with part of a room's name lists the rooms that match;
      a tap on one centres the map on it. Back and the button again send it back; a predictive back gesture shrinks
      the map as it goes, and a cancelled one leaves it as it was.
    - With TalkBack on: the map behind the text is never read; the button reads "Map" and whether it is on; in front,
      the field, its results and the map read as the mapper window's does, and the console under it is not read.
    - With the system font at its largest, the search field and its results still fit and read whole.
25. Links and the screen reader in script windows, with the libmudlet APK: `lua win = Geyser.MiniConsole:new({name =
    "win", x = "50%", y = 0, width = "50%", height = "30%"})`, then `lua echoLink("win", "go north", [[send("north")]],
    "Go north", true) echo("win", "\n")` and `lua echoPopup("win", "stew", {[[send("look stew")]], [[send("eat
    stew")]]}, {"Look", "Eat"}, true) echo("win", "\n\nend\n")`: the rows "go north", "stew", a blank one and "end".
    - A tap on "go north" sends `north`, and the link turns the visited colour; a tap beside it does nothing. A long
      press on "stew" opens a menu of Look and Eat, and Eat sends `eat stew`; a tap outside closes it.
    - With TalkBack on: swiping through the window reads each row as its text, the blank row too; its actions offer
      "Follow link: Go north" on the first row, and on the second "Follow link" and "Menu of link" with the stew link's
      tool tip (its hints joined, as Mudlet does with as many hints as commands), whose menu TalkBack moves into.
      Nothing in the window is read aloud as it comes; `lua announce("from win")` is.
    - With "Blank lines from the game" on Hide, swiping skips the window's blank row, still drawn; on Space it reads as
      "Space".
26. Downloading the map, with the libmudlet APK and a game that offers its map over GMCP (`Client.Map`), as many
    IRE-style games do, or a profile whose `lua gmcp.Client.Map` shows a URL. Start with no map (a new profile).
    - The map button's map says there is no map yet, with "Download the map from <host>"; a tap shows the progress and
      Cancel, then "Map downloaded from <host>", and the map, in front and behind the text, then follows the player.
      The bar under the search field then offers the download again.
    - Cancel stops a download (on a slow connection, or a large map), and says so; the map is as it was. With the
      phone offline, the download fails with Mudlet's reason, shown in red; dismissing it takes it away.
    - With TalkBack on: the button, the progress bar (as "Map download" and how far), Cancel and the dismiss button
      read their names; the end of the download is read out as it comes, a failure at once.
    - Over https (as most games' map URLs are), the download works: OpenSSL is in the APK (see "TLS").
    - `adb logcat` shows the map's host but never a user name or password from its URL.

## Problems hit
- **`QJsonDocument` sorts keys**, so `"t"` is not the first key. A test that
  matched on the frame prefix `{"t":"line"` hung, and the heartbeat statuses
  kept resetting its timeout. Match on the content instead.
- **The first backpressure design dropped any client** above 8 MB queued, so a
  normal flood disconnected the UI. Replaced with lag mode: a ring buffer,
  sequence numbers and a `gap` message. That also fits the Android freezer.
- **Replies were swallowed while the UI lagged.** Replies (`re`, `pong`) are now
  always written.
- **A hello with a huge `lastSeq` poisoned the resend cursor.** `lastSeq` is now
  clamped to `[0, nextSeq - 1]` in `greet()`.
- **The UI reattach replayed the whole ring** because a new `EngineLink` was made
  per `onStart` and sent no `instance`. It also raced a quick stop and start
  (rotation) into two reader threads. Fixed with one link per process and a
  per-thread generation.
  - JDK `SocketChannel.close()` waits for a blocked reader, which hid the race
    on the JVM. The test's transport delays the reader's wake-up after a close,
    so the race shows every time.
- **Maven Central returned HTTP 429** for kotlin-stdlib 2.3.21 while three agents
  built at once. Added Google's Maven Central mirror
  (`maven-central.storage-download.googleapis.com/maven2/`) before
  `mavenCentral()`.
- **The Qt 6.12 templates want AGP 9.2.1**, which needs a newer Gradle than the
  installed 8.14.3. Used AGP 8.13.2 and our own Gradle project, with
  `androiddeployqt --aux-mode` for the Qt parts.
- **The engine would ANR at startup on a phone** (reported by the coordinator,
  from prototype A's on-device analysis). Two problems:
  - Qt's service-setup semaphore is released only by `QAndroidService`.
  - The `ApplicationHidden` state, arriving once the integration exists, stops
    timers and sockets.
  Both A fixes are ported: the semaphore is released at the top of `main()`,
  and `main()` waits for `nativeServiceReady()` before creating
  `QGuiApplication`. Neither failure shows on the desktop build, so the host
  tests cannot catch them.
- **Running `clang-format` without this repo's own `.clang-format`** applies
  LLVM style and rewrites whole files. Use
  `--style=file:/home/user/Mudlet/.clang-format`.
- **`QtService` from Qt's docs is not in `Qt6Android.jar`**; it exists only as
  source. Extend `QtServiceBase`.
- **`QAndroidApplication::context()` needs
  `#include <QtCore/qcoreapplication_platform.h>`.**
- **An Android module's unit tests compile against `android.jar`**, which lacks
  `UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, `ProcessHandle` and
  `Redirect.DISCARD`. The JVM test now lives in `:protocol`, a plain JVM module,
  so it uses the JDK's Unix sockets directly.
- **The JVM test runner needs `ANDROID_HOME`**, as Gradle configures the Android
  modules too; `native/tests/run_jvm_client_test.sh` sets it.
- **`pkill -f test_engine.py` from a shell whose own command line contains that
  string kills the shell.** Use the pid.
- **`/usr/bin/time` is not installed.** Use the bash `time` builtin.
- **A sent line came back on the input line** (`GameScreenTest`, once on API 28: "northdraft"). The field took a
  `TextFieldValue` and `onValueChange`; its copy of the text, which the keyboard edits, catches up with the value only
  when it recomposes. A keyboard edit between the send's clear and the next frame (here LatinIME marking the word
  before the cursor) was applied to the old text, and `onValueChange` put it back. `InputLine` now uses
  `TextFieldState` fields, which the keyboard edits directly; `anEditTheKeyboardSendsJustAfterASendLeavesTheLineEmpty`
  sends and edits in one turn of the main thread.

## Measurements (prototype C, October 2026)

From prototype C's report, on the host, before it became this app; the paths are the app's.

| Check | Result |
|-------|--------|
| Engine desktop build and fake-server test | **pass** (rerun after the startup fixes). `native/tests/test_engine.py`: 0 failures ([`c-desktop-test.txt`](decisions/hosting-results/c-desktop-test.txt)). The JVM test of the app's Kotlin client against the same engine also passes ([`c-jvm-test.txt`](decisions/hosting-results/c-jvm-test.txt)). |
| Real engine (libmudlet from Mudlet's `development`) on the host | **pass**. `native/tests/test_mudlet_engine.py` against the fake server; CI's libmudlet workflow runs it, and builds and checks the APK with this engine. See "libmudlet as the engine". |
| APK builds (`assembleDebug`) | **yes** |
| Clean build time | **1 min 27 s** wall time with Gradle dependencies cached: 20 s CMake configure plus native build (44 steps, `ninja -j2`) and aux-mode deploy, then 1 min 7 s Gradle. The machine was shared with two other agents' builds. The first build, which downloaded dependencies, took 4 min 58 s. An incremental Kotlin-only rebuild takes 33 s. |
| APK size (download estimate and uncompressed `lib/arm64-v8a` total) | Debug APK 48.6 MB, of which 27 MB is unminified debug dex (Compose and Kotlin). `lib/arm64-v8a`: 16 files, **19.5 MB** uncompressed, stored uncompressed (`useLegacyPackaging=false`). The whole APK xz-compresses to 11.8 MB (libs 5.0 MB, dex 6.6 MB). A minified release build would be about **6-7 MB to download** (estimate: libs plus about 1 MB of R8-shrunk dex). |
| Largest native libraries | `libQt6Gui` 7.8 MB, `libQt6Core` 6.2 MB, `libQt6Network` 1.8 MB, `libc++_shared` 1.2 MB after AGP strips it (8.8 MB unstripped), `qtforandroid` plugin 0.7 MB, `libQt6Svg` 0.6 MB, `libengine` 0.3 MB (the engine plus Lua 5.1.5) |
| 16 KB page alignment (`zipalign -c -P 16 -v 4`, and `readelf -lW` LOAD alignment of each .so) | **pass**. `zipalign` reports "Verification successful", with every `lib/arm64-v8a/*.so` marked OK. All 15 deployed `.so` have every LOAD segment at `0x4000`. |

Measured from the app's Kotlin code (`EngineLink`, `FrameReader`/`FrameWriter`)
against the desktop engine, `native/tests/run_jvm_client_test.sh`, three runs:

| | Result |
|---|---|
| ping round trip (Kotlin, then Unix socket, then engine, then back) | median 45-114 µs, p99 268-397 µs |
| paced flood 25k and 50k lines/s | every line delivered |
| paced flood 100k and 200k lines/s | 53-67% and 18-20% delivered; the rest is reported in `gap` messages, and nothing is lost silently |
| engine SIGKILL, until the UI sees EOF | 6-16 ms |
| engine SIGKILL, until reattached to a restarted engine | 108-112 ms. This includes the test spawning a new engine (about 40 ms) and the 100 ms reconnect backoff. |

From the Python client in `native/tests/test_engine.py`:

| | Result |
|---|---|
| ping | median 83 µs, p99 211 µs |
| `runLua` round trip | median 106 µs, p99 226 µs |
| send, game echo, line back (includes the telnet hop) | median 160 µs, p99 401 µs |
| engine CPU per line | 4.05 µs with a UI attached, 2.65 µs with none |
| engine alone, no UI attached | 908k lines/s through telnet, ANSI parsing and Lua `onLine` |
| wire size | about 146 B per coloured line |
| full replay of the 2000-line ring on attach | 44 ms |
