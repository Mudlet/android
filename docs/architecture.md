# Architecture

Mudlet for Android runs in two processes. The UI process is Kotlin and Jetpack Compose and loads no Qt. The engine
process (`:engine`) runs Qt and the engine, libmudlet, in a foreground service. They
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
| `app/` (`:app`) | The UI process: `MudletApp`, `ui/MainActivity.kt` with the screens it navigates between (`ui/AppNavigation.kt`: the profile list and its editor in `ui/profiles/`, the game screen in `ui/game/` (the console with the windows, the map and the button bars the game's scripts draw around it, the input line with its suggestions, the row below it with the game pill (`GamePill.kt`), its connection chip and the game sheet (`GameSheet.kt`), the quick bar with the history sheet and pinned commands, and the `Announcer`, which has TalkBack read new lines and `announce`), the settings in `ui/settings/` ("Settings" below), About with its Help links and the licences in `ui/about/` (their data in `about/` and `assets/licences/`, [`licences.md`](licences.md)) and, in debug builds, the self-test in `ui/selftest/`, the script library and an item's page in `ui/scripts/` ("The script library" below) and the package manager in `ui/packages/` ("The package manager" below)), the renderer of game text (`ui/AnsiText.kt`), the theme (`ui/theme/`) and the parts every screen shares (`ui/MudletTopBar.kt`, `ui/MudletList.kt`, `ui/ListDetail.kt`: "How the app looks" below), the model of the engine (`ui/EngineClient.kt`, holding a `ui/ProfileSession.kt` per open profile, with the script windows' part in `ui/ScriptWindowsClient.kt` the app's own map in `ui/AppMapClient.kt`, the triggers, aliases, scripts, timers, keys and buttons the library edits in `ui/ScriptItemsClient.kt`, the packages in `ui/PackagesClient.kt` and the script consoles' pinched text sizes in `ui/ConsoleZoomClient.kt`), the app's own settings and what new games start with (`data/Settings.kt`, in SharedPreferences) and the profiles' passwords (`data/PasswordStore.kt`). Every string the app shows is in `res/values/strings.xml`, translated on Crowdin ([`translations.md`](translations.md)) |
| `engine-service/` (`:engine-service`) | The engine process's Kotlin side: `EngineService` (a `QtServiceBase`), its notifications (their text in `res/values/strings.xml`), `EngineNative` (the JNI handshake). Its manifest declares the service and the permissions. It also carries the Qt libraries and jars that `scripts/build-apk.sh` stages into `engine-service/qtdeploy/`, with Mudlet's version and commit (`res/values/engine_info.xml`) |
| `protocol/` (`:protocol`) | Plain Kotlin/JVM with no `android.*`: the frame codec, message types and `EngineLink`, the reconnecting client; the script windows' state (`ScriptWindows`) and where the game screen puts them (`WindowLayout`); what a label draws, read from its style sheet (`LabelStyle`) and rich text (`RichText`), its links (`LabelLink`) and Geyser's gauges (`Gauge`); where the button bars go, what their buttons draw of their style sheets and the commands' menu (`ButtonBarLayout`, `ButtonStyle`, `CommandMenu`); where the map is drawn, a pinch as wheel notches and how faintly the map shows behind text (`MapFit`, `ZoomNotches`, `MapBehindText`); the text size the player pinches a script console to (`ConsoleZoom`). Its tests run on the JVM, against the desktop engine too |
| `suggest/` (`:suggest`) | Plain Kotlin/JVM with no dependencies: the input line's command suggestions, the session that loads, feeds and saves them, and the pinned commands ("Command suggestions" below). Its tests run on the JVM, with Gradle or with kotlinc alone (`suggest/run-tests.sh`) |
| `native/enginehost/` | `libengine`'s `main()`, the protocol's engine side (`ControlBridge` on the control socket, which keeps the open profiles, and a `SocketBridge` per open profile), `MudletEngine` (libmudlet through `THeadlessSession`), and the Android glue: `AndroidServiceSetup` (the only private Qt API, so a rename in Qt fails the build) and `AndroidNotifier` |
| `native/api/` | Between the host and an engine: `IEngineControl` (the profiles, app state, saving) and `IEngineHost` (profiles opening and closing, the engine-wide statuses) for the engine as a whole; `IEngine` and `IFrontend` for one profile's session |
| `native/tests/` | The fake game server, the Python protocol client, and the engine's host tests |

## How it is wired
```
UI process (org.mudlet.mudlet)              :engine process (org.mudlet.mudlet:engine)
Kotlin + Compose, no Qt                     QtServiceBase + libengine.so (Qt Core/Gui/Network)

:app       MainActivity -> EngineClient     :engine-service    EngineService : QtServiceBase
             -> ProfileSession, per profile
:protocol  EngineLink (reader thread)       native/enginehost  QLocalServer (Qt's main thread)
             one per socket
              <======== LocalSocket ========>                  ControlBridge -> IEngineControl
              frames: u32 BE length + JSON                     SocketBridge : IFrontend -> IEngine, per profile
                                                                 (MudletEngine)
                                                               AndroidNotifier -> EngineNotifications (JNI)
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
   the `ControlBridge`, and call `listen()` on `noBackupFilesDir/engine.sock`.
   Each profile that opens gets a `SocketBridge` on `engine-p<slot>.sock` beside it.
7. The UI's `EngineLink` retries the connection every 50 ms for 2 s, then backs
   off from 100 ms to 2 s, until the socket appears. `EngineClient` holds the
   link to the control socket, with the engine's own statuses and its profiles;
   for each profile that `openProfiles` names it keeps a `ProfileSession`
   (`ui/ProfileSession.kt`) with its own link to that profile's socket, its
   console, statuses, input line state, script windows (`ScriptWindowsClient`)
   and map (`AppMapClient`). The game screen shows `EngineClient.front`.

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
  `re`, the request id. Every sender on a socket takes its ids from that
  socket's `EngineLink.newRequestId()`, so no sender takes another's answer.
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
- It answers the input line once the typing has paused for 120 ms. The rest of the best suggestion shows greyed
  after the cursor (ghost text, `ui/game/GhostText.kt`) through an `OutputTransformation`, and only for the line as
  it is now; the field's state and its text for TalkBack keep just what was typed, so the keyboard does not see the
  ghost. There is no row of suggestions: the keyboard shows its own. An empty line has no ghost (`Suggestions.ghost`),
  so what usually comes next only ranks what is typed. A tap on the ghost while the line has the focus,
  a hardware right arrow at the end of the line, or the line's TalkBack action ("Accept the suggestion: ...") puts it
  on the line; TalkBack reads it as the line's state ("Suggestion: ..."). A tap on the ghost is told apart from one on
  the typed text by where the field then puts the cursor, which it handles first: still at the end means the ghost.
- It saves 5 s after a send and when the app leaves the front (`MainActivity.onStop`), to a temporary file that
  then replaces the old one. An I/O error is logged (tag `MudletSuggest`) without any command and the suggestions
  carry on in memory; after a failed load nothing is saved, so the file is not replaced with less.
- While the game hides input there is no ghost: nothing typed reaches the session, and the masked
  send is recorded only as `recordSent("", hidden = true)`.

The History button below the input line, or Ctrl+R on a hardware keyboard, opens `HistorySheet`, a
`ModalBottomSheet` whose search field takes the focus. The session answers each change of it at once with up to 100
of `search`'s commands, under the pinned ones that match. A row's text puts its command on the input line without
sending it; its buttons send it, pin or unpin it, and forget it (`forget`, saved as a send is). Picking, sending,
Back and the close button close the sheet.

Pinned commands (`PinnedCommands`, `:suggest`) are the buttons after History in `QuickBar`, which scrolls sideways;
one sends its command. Up to 12 per profile, in the order pinned, saved whole on the session's thread as
`files/suggest/<name>.pins` beside the suggestions, the same way and with the same handling of a damaged file or an
I/O error. They are unpinned from the sheet's Pinned section. While the game hides input the quick bar's row has only
the game pill and its chip,
the sheet closes and Ctrl+R does nothing; nothing reaches the search, and a pin can only come from the history,
which a hidden send never enters.

### Settings
The settings (`ui/settings/`) are a hub and pages. The hub has a search field, then the categories of the game it was
opened from (from the profile list: of every open game, then "New games"), then "App · all games"; each category is a
page whose changes apply at once. `SettingsCatalogue.kt` lists every setting with its category.
- A game's categories hold Mudlet's own settings of that game (`Settings.mudletSettings`), which Mudlet keeps in the
  profile, where desktop Mudlet does, so a profile copied between the two keeps them. `ProfileSession` asks for them
  with `getSettings` as the profile loads and as a page opens (a script may have changed them), and a change sends
  `setSettings` ([`protocol.md`](protocol.md)). A game added in the app is given, as it first loads, those of the
  app's values the player changed ("New games"): Mudlet picks some for the game it knows, such as fixing the line
  breaks of IRE's, which unchanged values would undo. After that it keeps its own, as a game copied in or there
  before does from the start.
- The game categories are Display (undoing the game's own wrapping and the width it wraps at), Accessibility (with
  what becomes of blank lines, and captions for sounds, as on desktop Mudlet's Accessibility page), Input (the
  commands the console shows, Unix line endings, the command separator, highlighting a command brought back from the
  history, and showing passwords as they are typed, each as on desktop Mudlet's Input line tab), Connection (the
  server data encoding, picked from a list of Mudlet's names for its encodings, `ServerEncodings`, with desktop
  Mudlet's labels; offering a secure connection, and the protocols Mudlet negotiates with the game: GMCP, MSDP, MSSP,
  MSP, MXP, MTTS, MNES, NAWS, CHARSET, NEW-ENVIRON), Sound (the mutes), Security (the certificate errors a secure
  connection lets past, whether the game may install its packages or play its media, and desktop Mudlet's "Clear
  stored media", which runs `purgeMediaCache()` in the game and puts desktop's message in its text) and Advanced (desktop
  Mudlet's "Special options", and a script's errors shown in the game's text, as the app has no error console). A
  number's slider changes it as the finger lifts, so a game's is sent once a slide. Mudlet's settings for how its own
  console draws text (control characters, East Asian characters of either width) are not among them, as the app draws
  the text itself.
- The Sound page holds desktop Mudlet's two mutes, "Mute sounds from Mudlet (triggers, scripts, etc.)" and "Mute
  sounds from the game (MCMP, MSP)" (`muteMediaAPI`, `muteMediaGame`). Unlike the others, Mudlet keeps them for every
  profile in `Mudlet.ini`, as the desktop does, so turning one on a game's page turns it for every game open, the
  engine's `sounds` message keeps each open game's page in step ([`protocol.md`](protocol.md)), and "New games" has
  no Sound page (`SettingsCategory.sharedByGames`).
- Mudlet reads some settings only as a game connects (`Settings.readAsItConnects`: the protocols, compression, the
  go-ahead signal, the version in the terminal type, the certificate errors let past and the offer of a secure
  connection), so one changed while the game is connected waits for its next connection. The switch turns all the
  same, and a snackbar says so (`SettingsNotices`, from `ProfileSession.settingAwaitsConnection`): one for the
  switches turned while it shows, none for a game that is not connected or for "New games".
- The search matches each word of the query against a setting's label, description, choices, category and translated
  keywords, whatever the case and accents (`searchMatches()`). A result says where it lives ("Aardwolf ›
  Accessibility"), chips narrow it to one game, the new games or the app, a switch turns where it is found and any
  other opens its page at the setting, tinted for a moment.
- On an expanded screen a page opens beside the hub (`ListDetail`); a game's page closes once the game does.
- "Clear the input line after sending", on the Input page, is desktop Mudlet's "Auto clear the input line after you
  sent text" (`autoClearInputLine`). Off, `InputLine` leaves the command sent on the line, selected whole, as
  `TCommandLine` does: typing replaces it, Send sends it again, and the up button goes to the command before it. A
  script window's command line keeps its own the same way. TalkBack reads a selection as it changes, which would be
  the command again after every send, so it is given the line's state ("Sent. Typing replaces it") and a cursor at
  the end in the selection's place (`keptCommand`). Mudlet starts a profile with the setting off and the app starts a
  game with it on ([`divergences.md`](divergences.md)), so it is among the values a game added in the app is given
  whatever the player changed (`Setting.mudletDefault`). The app emptied every game's line before it had the setting,
  so the games there the first time it runs with it (`Settings.noteGamesBeforeInputClear`, by their profiles'
  directories) are each told once, as they next load, to go on clearing theirs; a profile copied in later keeps its
  own.
- "Highlight history" (`highlightHistory`, on unless the game says not) selects a command the up and down buttons
  bring back the same way, read as "Selected. Typing replaces it". The command separator (`commandSeparator`) is a
  field sent as it is left or its Done key pressed, not on each key, as the engine's answer to one change could land
  between two keys; Mudlet splits a sent line at it. "Show passwords as you type them" (`disablePasswordMasking`) is
  Mudlet's: with it on, the engine sends no `hiddenInput` for a game's WILL ECHO.

### The Sound button
Desktop Mudlet's toolbar Sound button, "Mute all media", is on the game screen's bottom row, after the game pill and
its chip (`ui/game/SoundButton.kt`), where a thumb reaches it and it covers no text. It is there once the engine's
`sounds` message says the game has played a sound, music or video since it loaded (`played`), or while either mute is
on, so a mute can always be undone; leaving the game and coming back keeps it, as `ProfileSession.sounds` lives as long
as the game is open. A tap does what the desktop's does (`ProfileSession.toggleAllSoundsMuted()`): both mutes off when
both are on, otherwise both on, in one `setSettings`. Muted, Mudlet goes on playing silently, so music is heard again
from where it has got to. TalkBack reads it as "Mute all media", a checkbox checked while both are on, and says the new
state once as it turns; its coming takes no focus and says nothing, so the game's text is read on undisturbed.
`SoundButtonTest` tries each of these against the fake engine.

### How the app looks
The app's own screens are Material 3 (`ui/theme/`), light or dark as the system is set, in Material's type and shape
scales; text sizes are in sp, so the system font size scales them.
- Colours: the wallpaper's (Material You) on Android 12 and later while the setting "Colours from my wallpaper"
  (`Settings.wallpaperColours`, on by default, offered on those releases only) is on, else Mudlet's own: the tonal
  spot schemes seeded with the sandstone of the launcher icon's arch, `#D39F41` (`ui/theme/Color.kt`, generated with
  Material's colour utilities; regenerate them rather than edit a role by hand). Only the app's chrome takes them: the
  console's colours, ANSI ones included, the map behind the text and what the scripts draw (windows, labels, gauges,
  button bars, their own map) keep theirs.
- Every screen but the game's is a `MudletScaffold`: the same top bar, tinted while the content under it is scrolled.
  Lists are groups of `ListSegment`s, Material 3 Expressive's look: each item on a container of its own, 2 dp apart,
  the group's ends rounder, under a `SectionHeader` that TalkBack reads as a heading. The settings are at most 640 dp
  wide.
- Motion is Material 3 Expressive's springs (`MudletMotion`): screens slide a little and fade, forward from the end,
  Back the other way, and a predictive back gesture (`android:enableOnBackInvokedCallback`) seeks it as it goes.
- On an expanded screen (840 dp wide and more, Material's window size classes, measured from the space the app has), a
  profile's editor, a settings page and a component's licence open beside their list (`ListDetail`), the list's item tinted; Back,
  predictive too, closes them there, and the detail's top bar has Close in place of Back. Narrower, they are screens of
  their own, and one open beside the list moves to its own screen when the window narrows (a foldable folding, say).
- Material 3 Expressive's own components (`MaterialExpressiveTheme`, the shape morphs, button groups, the flexible top
  bars, `LoadingIndicator`) are experimental in the material3 the Compose BOM brings, so the app has none of them, only
  Expressive's springs and segmented lists, built with stable components.

### Profiles in the app
The profile list (`ui/profiles/ProfileListScreen.kt`) shows the engine's `profiles`, which `EngineClient` asks for
each time it attaches, and its editor (`ProfileEditor.kt`) sends `saveProfile` and `deleteProfile`. Each waits for its
answer, and a refusal shows the engine's reason, which is in English. Connect sends `openProfile` with the profile's
stored password and goes to the game screen once it is answered with `openProfiles`; while games are open, it sends
`openProfile` with `alongside`, so they stay open, and the list shows them first ("Open games"), each a tap from the
front. On the game screen the game pill, at the start of the row below the input line, shows the front game's name,
its connection as an icon whose shape says the state, and how many games are open; a tap opens the game sheet, with
Reconnect and Disconnect, a row per open game (a tap brings it to the front, its close button sends `closeProfile`,
asking first while it is connected) and "Open another game", which goes back to the list. Beside the pill, while the
game is not connected, one chip does what the state needs: Connect, Reconnect after a drop, Reconnect now while
Mudlet's own "Reconnect automatically" retries (which read as one state, not each attempt), Try again after an error,
Stop while connecting. A drop is the connection ending without the player's Disconnect, which the UI tells from the
status as it changes. The game screen's Reconnect then
sends `connect` with the front profile's address on its socket, and, while no profile is open, `openProfile` with the
last one's stored password, as on an engine started again after the notification's Quit, where a bare `connect` would
have libmudlet make a profile named `MUDLET_PROFILE` with that address, a copy of the player's. A profile's name cannot
change, as the engine keeps the name a profile was created with.

Below the profiles, under "More games", the list offers the games of Mudlet's connection dialog
(`ui/profiles/DefaultGames.kt`, from `assets/games/games.json` and its logos, which `scripts/sync-mudlet-games.py`
copies from Mudlet's `src/TGameDetails.h` at a pinned commit), each with its logo, name and the first paragraph of its
description; its info button opens the whole description, the address and the game's links. A game the player has a
profile for, of its name or at its address (any of its hosts, and its port), is left out. A tap saves a profile of the
game's name, address and TLS choice, then opens it as Connect does. The name is the desktop's own, so Mudlet treats the
profile as that game, as it does a profile made from its dialog, and picks settings for it, such as joining Achaea's
split lines (`Host::setupIreDriverBugfix`), which the app's "New games" defaults leave alone unless the player changed
them (`ProfileSession.giveDefaults`).

The engine keeps no password, so the app does. `data/PasswordStore.kt` encrypts each with AES-GCM under a key that
never leaves the Android KeyStore (no user authentication, so a profile opens without a prompt), with the profile's
name as associated data, so one profile's cannot be moved to another's. The ciphertext is in
`files/passwords/profiles.json`, which `data_extraction_rules.xml` excludes from cloud backup and device transfer
(`android:allowBackup="false"` covers older releases), and no other device has the key to decrypt it. The editor never
shows a stored password: a new one replaces it, Forget password removes it, and so does deleting the profile. One that
can no longer be decrypted is forgotten, and the player types it again. The engine is told a password only in an
`openProfile`, and the game screen's Reconnect is a bare `connect`, so a password stored or forgotten for a game that
is open goes to it at once (`EngineClient.givePassword`: `openProfile` with `alongside` and `connect` false), for its
next connection. A profile the engine restores after it was killed comes back without one, so `openProfiles` says of
each open profile whether it holds a password (`passwordGiven`), and the UI gives a game that holds none its stored
one the same way, once for each time the engine says so (`EngineClient.givePasswords`). Mudlet reads the password two
seconds into a connection, at its login step, so a restore the UI is attached for logs in with it; one made with no
UI attached, as when Android has stopped the app too, connects without logging in, and the password is for the
connection after it. Keeping the password where the engine could read it alone would close that, and is not done.

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

### The main console's links
The game's links in the main console (MXP `<SEND>`, OSC 8, `echoLink()`, `insertLink()`) are followed as a script
window's are, through the same `ConsoleLine`: a tap, a long press for the menu, TalkBack's actions. The session's
`ConsoleLinksClient` keeps what `getLink` (with no `window`) last said of each:
- A line asks about its links once it is shown. `linksChanged` names the links whose state changed, or with no ids any
  of them; those are asked again by the lines that show them, and the last answer stands until the new one comes. An
  attach asks again too, and a new engine instance forgets every answer. Lines from before a new instance offer no
  links, as it counts its links from 1 again and their ids would name its own.
- A link the engine no longer holds (`getLink: unknown`) loses its answer; that, and a refused `followLink`, only goes to
  the log, as a link a script let go of or one tapped as it changed is no fault of the player's. Once many links are
  kept, those no line holds any more are let go of.
- A line is drawn with its links as the last answers say they look: their `fg`, `bg` and `styles`, as a visited link or
  one chosen in its selection group is painted, over the line's own runs, as desktop repaints them; TalkBack's actions
  add ", visited" and ", selected" to its name, as desktop's screen reader announcement does. A `concealed` link is
  blanks: TalkBack reads spaces there, and neither a tap nor TalkBack's link actions reach it; a long press there offers
  the line's trigger, as anywhere else on the line.
- What a link runs is the engine's: an OSC 8 `send:` link sends its command, and a `prompt:` link runs
  `sendCmdLine()`, whose `commandLine` puts the text in the input line (dropped while the game hides input, as for any
  `commandLine`). An OSC 8 web link runs `openUrl()`, which the engine, a service, cannot open yet.

### Copying the game's text
A long press on a line of the main console, anywhere but on a link with a menu, opens a menu with "Copy line", "Share
line" and the trigger (`ConsoleLine`, `copyable`); TalkBack has "Copy line" as the line's action. The line is copied as
it reads, a concealed link as blanks; a blank line offers neither. Copying goes through `copyText()` and sharing
through `shareText()` (`ui/ShareText.kt`), as the problem report's buttons do. Script windows offer neither yet.

### Script windows' text size
A two-finger pinch on a mini console, user window or MXP frame sets its font size as a script's `setFontSize(name,
size)` does, in the same whole points, so `getFontSize()` gives it, the engine's grid (and with it the columns the app
fits in the window's width) follows, and scripts get `sysFontChangeEvent` as for a script's change. The app sends it
with `runLua` once the fingers lift (`ConsoleZoom` in `:protocol`, `ConsoleZoomClient`), drawing the size reached
meanwhile; a Geyser console with auto wrap then wraps to its new width (its `resetAutoWrap()`, not Geyser's
`setFontSize()`, which can resize a window sized in characters). A pinch reaches 4 to 40 points, or as far as the size
a script gave beyond them; a console whose font is sized in pixels, or that the engine names `main`, is not pinched.
- **The player's size wins.** It is kept for each profile and console name, across restarts. When the engine reports
  another size for that console, as after a script's `setFontSize()` or when a script makes the console again, the
  app sends the player's size again, and keeps the script's as the one to go back to. A script that sets its size
  again in answer (from `sysFontChangeEvent`, say) keeps it, rather than the two trading sizes without end.
- **Reset**: a two-finger double tap, or TalkBack's "Reset text size", forgets the player's size and gives the
  console the size its scripts last gave it. TalkBack's "Larger text" and "Smaller text" change it a size at a time.
- One finger is left to the window's list, its sideways scroll and its links: the pinch takes only two.

### Searching the game's text
The title bar menu's search, or Ctrl+F on a keyboard, opens a bar over the main console that searches its scrollback (the
lines the app keeps, `ProfileSession.MAX_LINES`) as desktop's search box does (`TConsole::slot_searchBufferUp`):
- Case is ignored. The first search goes up from the bottom, and each next one up (older, also the keyboard's search
  key, as desktop's Enter) or down (newer) from the line the last one found, which the console scrolls to. A found line
  since dropped from the scrollback counts as above all of it. Matches, overlapping ones too, are drawn black on yellow
  as desktop draws them, while the bar is open.
- What the bar says is a polite live region, so TalkBack reads it: once typing pauses, how many lines match; after each
  step, the line found, or that there is no more that way. `nextMatchingLine` and `matchStarts` (`ScrollbackSearch` in
  `:protocol`) are the search, tested on the JVM; `ConsoleSearch` holds its state.
- Script windows are not searched, as on desktop, where only the main console has the search box.

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
  (asked of the engine at 24 dp) and its name, drawn with its own style sheet over its bar's, the touch subset of
  what the desktop draws as `ButtonStyle` reads it with the label's parser (`docs/phase-f.md`, "Button bars and
  commands"); the bar draws its own sheet's background, images and border. Where they set nothing a button keeps the
  app's look. Whatever its sheets and its name ask, a button is no bigger than the game area, and a vertical bar no
  wider than Compose can measure.
- A tap sends `pressButton`, and the next `buttonBars` shows what it did. A push-down button is a switch, on, with a
  tick, while it is down. A folder opens a menu of its entries, a folder among them opening its own in place with a
  Back entry; the entry picked is pressed and then the folder, whose script the GUI runs once its menu closes, so a menu
  closed without a pick presses the folder alone. A floating bar's folder shows its buttons at once, not itself.
- The commands are in the menu of an Extensions button in the top app bar, shown while one is on the GUI's menu or
  toolbar, nested by their menu paths; a tap sends `activateCommand`. A checkable command has a tick while checked, a
  disabled one is greyed out, and one that pulses has a dot, as the button has then. Shortcuts and pinning are not used.
- With TalkBack each bar is a group, "<name> button bar", read after the script windows and before the last
  announcement. A button reads its name, or "Button <n> of <bar>" with only an icon; a push-down one reads as a switch,
  on or off; a folder says a tap opens its menu; a checkable command reads its state. The Extensions button is read with
  Back, before the console, as Material makes the top app bar a group read first. The buttons and commands are off while
  no engine is attached.

### The map
The game screen draws `map` in the mapper window (`ui/game/ScriptMap.kt`, fitted by `MapFit` in `:protocol`):
- A `Geyser.Mapper`, or another mapper a script put in the main window, is placed as any window. openMapWidget()'s map,
  a dock the main window's size, is a "Map" page in either layout, and a mapper beside the text is one when stacked.
- The frame is drawn only while it is the window's size, as the engine draws one at each new size; scaled alike both
  ways to fit and centred, so a touch maps back to the frame's pixel under it.
- The engine is asked (`setMapScale`) to draw it at the scale it is shown at, `MapFit.renderScale`, as Mudlet's pixels
  are scaled to the screen's, several times over for a small mapper that fills a page's pane: its PNG has that many
  more pixels, so the map is as sharp as the screen, and its `w`, `h` and points stay the window's.
- A tap sends a `mapPointer` press with the left button on the finger's lift, so a pinch selects nothing, at the
  middle of the room nearest it within 24 dp or its own cell (from the frame's `view.rooms`), so a room smaller than a
  finger at a low zoom is still hit, or else at the point itself; a second tap near it within the double-tap timeout
  then sends a double click there, which walks to it, with a confirming tick.
- A tap on a room also asks for it (`roomInfo`), shown at the map's bottom once the engine answers (`RoomCard`, held
  by `MapRooms`): its name in bold, its area and number, how far it is ("You are here", so many steps, or no known
  way), its exits, each a chip naming the way and the room it leads to, which shows that room, and "Walk here"
  (`walkToRoom`, with a tick), off where there is no way or the player is there. It is read out as it comes, takes at
  most half the map's height, never reaching up over the map's buttons, scrolls past that (another room shows from
  its top), and closes with its button, a tap on no room, or Back on the map
  in front, and as that map closes, but not as a rotation remakes it; it is asked for again when the player moves, as
  its steps change. Each map's answers carry the `id` it
  asked with, so the mapper window's and the app's map never show each other's room.
- While the map is edited, the room shown also has chips (`RoomEditActions` in `MapEdit.kt`), each but one opening a
  dialog: its details (name, symbol, a colour from the map's environments, `mapEnvs`, weight and lock, saved as one
  `editRoom` of what changed), its exits (one per direction on a compass, a way with an exit filled and naming the
  room it leads to, with a lock where locked, and one with none outlined with a plus; led to a room by number, by
  picking it from the first rooms `findRooms` finds for part of its name, typed into the same field, or by tapping it
  on the map next, both ways or not, locked, unlocked or removed: `setRoomExit`, `lockRoomExit`, each closing the dialog
  so a refusal shows on the map; that search is `AppMapClient.destinations`, apart from the room search's results, and
  its refusal shows in the dialog), a room beside it a way no exit goes (`addRoom`, which then shows), moving it a place
  at a time (`editRoom`'s place), placing the player in it (`setPlayerRoom`), renaming its area (`renameArea`) and
  deleting it after asking (`deleteRoom`). While an exit waits for its room a banner over the map says which way it goes, with
  Cancel, and the next tap on a room leads it there; viewing the map cancels it. A drag that starts on the room shown
  moves it by whole places, outlined where it will go as the finger moves (`MapMoves.roomsMoved`), and sent as one
  `editRoom` as the finger lifts. Each edit answers with the room as it now is, which the card shows.
- A bar at the map's bottom start (`MapPlaceBar`), where the map is at least 144 dp high, names the area shown, a
  button that lists the map's areas (`mapAreas`, asked each time it opens) to show another (`showMapArea`), and,
  while the area has more than one level (`view.levels`), the level shown between buttons to the level above and below
  (`showMapLevel`). Either stops the map following the player, as a pan does.
- A drag pans the map and, let go quickly, glides on as a fling slows; a pinch pans with the fingers and zooms about
  their middle; a double tap whose second touch drags zooms about it, in as it goes down (`mapGestures`). Each is
  drawn at once by moving the frame shown (`MapMoves` in `:protocol`, held by `MapMover`), and sent as `moveMap`, at
  most one every 50 ms while the finger moves and the rest as it lifts; the frame is shown moved by what the engine
  has not drawn yet until a frame's `view.moved` reaches it, and as it was when a move is refused. The zoom stays
  within the frame's `minZoom` and `maxZoom`, as the engine keeps it, so what is shown is what it draws next.
- A pan, or a zoom about a point other than the middle, stops the map following the player (`view.following`):
  a button at its bottom end then centres it on the player again (`followPlayer`), until the player moves to another
  room, which follows them again anyway. The map in front sends `followPlayer` as it goes back behind the text, so
  the map behind the text follows the player.
- Buttons in its corner (`MapButtons`) zoom out and in by a quarter about the map's middle (`moveMap`, which keeps
  following the player; three wheel notches while a frame has no `view`), and a switch, on while the map is edited,
  sends `setMapViewOnly`; it follows the next `map`, not the tap. A refused request shows the engine's reason at the
  map's bottom, above the bar and the button that centres it, until dismissed or the next request; a refusal of
  `roomInfo`, `walkToRoom`, `mapAreas`, `showMapLevel`, `showMapArea` or an edit of the map (`MapRooms.REQUESTS`) is
  shown on the map that sent the last of them.
- With TalkBack the map reads as "Map of the game", viewing or editing, and "not following you" while it does not
  follow the player, in the windows' group. A double tap selects the room in its middle and shows it, and its actions
  walk there, zoom, scroll the map up, down, left and right by a third of it, show the level above or below, and
  centre it on the player while it does not follow them; the buttons and the switch read their names. Touches, buttons and actions are off while no engine is
  attached or no frame of the window's size has come.

### The map info
The desktop paints the map info box into the map: the room's name, number and area from the built-in `Short`
contributor, more from `Full`, and whatever a script adds with `registerMapInfo()`. The engine leaves it out of the
pictures it sends (`0016-headless-map-info.patch`) and sends its parts as `info` in `map` and `appMap`
(`docs/protocol.md`, "The map info"); `MapInfoBox` (`ui/game/MapInfoBox.kt`) shows them at the top of the map
(`MapView`), under the buttons of a script's mapper:
- Each part keeps the colour, bold and italic the engine gives it, over the box's own colour, so a script's colours
  mean what they do on the desktop; the text is the app's, at the size Android's font scale gives.
- It stays where it is while a drag or a pinch moves the picture under it, and takes no touches, which reach the map.
  It takes at most two fifths of the map's height.
- TalkBack reads it as one, "Map info:" and the parts in order.
- The map behind the text shows none: the picture there is a backdrop TalkBack skips, and the box's text would lie
  under the game's.
- Which contributors are on is the profile's, saved with it: `enableMapInfo()` and `disableMapInfo()` switch them, and
  the engine draws the map again. Painted into the picture, the box cost the engine nothing to speak of (0.2 to 0.5
  ms of a frame whose PNG takes 80 to 140 ms to encode on a desktop CPU), so this is for reading it, not for speed.

### The map behind the text
The game screen also has a map of its own, which needs no script: `appMap`, the engine's second map view
(docs/protocol.md, "The app's map"; `ui/AppMapClient.kt`, drawn by `ui/game/AppMap.kt`).
- Behind the text, while the setting "See-through map and text" (`Settings.mapBehindText`, on by default) has it there: it
  fills the main console, between the console's background and its text (Console's `behindText`), and follows the player
  as the desktop's mapper does: asked for, it opens the map for scripts as opening the desktop's mapper does, so a
  mapper script's `centerview()` moves the player on it with no script mapper. Its alpha is the most at which the
  console's default text colour (`consoleColors`) keeps WCAG's 4.5:1 over the colours the frame has (`MapBehindText` in
  `:protocol`). As each frame is decoded, off the main thread, a grid of at most 128 rows by 256 columns of its pixels
  is sampled, and each channel's lightest and darkest value taken, leaving out the 1% of samples beyond each, so a few
  bright pixels, or thin exit lines, do not decide it; transparent pixels show the console's background, which is
  weighed too. Every colour between those, channel by channel, blends to no lighter and no darker than they do, so the
  text keeps 4.5:1 over all of the frame but those few. Up to 54%: there the default grey text, where it crosses an exit
  line in Mudlet's default LightGray, still keeps the 3:1 WCAG asks of large text and graphics. Behind the default grey
  on black, Achaea's map in Mudlet's default colours (dark green and cyan rooms, grey exits) shows at 41% to 54%, as its
  grey room-name box and exits take more or less of the frame, a frame with white rooms in it at 31%; dimmer text or a
  light background gets less, and text already below 4.5:1 none. A stronger frame's alpha eases in, a fainter one's at
  once. Before the first frame the map is weighed as any colour at all. The console's
  colours, ANSI ones included, are not changed; text the game colours is not weighed, so a dim colour of the game's
  reads less well over the map. It takes no touches, which reach the text as before, and TalkBack does not read it.
- In front: the title bar's map button, a toggle read as "Map" with its state, swaps the map and the text. The map takes
  the console's place, between the title bar and the input line, with the map as the mapper window draws it (`MapView`,
  shared with `ScriptMap`: tap, double tap, drag, pinch, the zoom buttons, the edit switch, refusals and TalkBack's
  actions), under a search field. The buttons are beside the field, not over the map, whose top line is Mudlet's name
  of the room, which they would hide. It takes every touch on the console's place; TalkBack does not read the console
  and the script windows under it, and a hardware keyboard's focus does not reach them, while new lines are still
  announced (`Announcer`). The input line, the row with the game pill and History stay below it, to type and pick
  commands in, and Ctrl+R opens the history. The swap opens no keyboard: one that was up goes down, so the map has the
  room, but the input line keeps the focus, so a hardware keyboard still types in it and a tap brings the keyboard back.
  Back or the button again sends it back, with the keyboard if it went with the swap; with a predictive back gesture the
  map shrinks as the gesture goes, over the game screen under it. A switch to another open game keeps the map in front,
  with that game's map.
- The text behind the map, with the same setting; without it the map in front covers the console. The engine paints the
  frame's background opaque (TMapPainter fills it with the map's background colour), so text could never show through
  it: the console and its script windows are drawn over that colour instead, faintly, and the frame over them with each
  pixel of exactly that colour made transparent (`TextBehindMap.cutOut`), so the text shows where the map draws nothing,
  as behind a transparent map; a frame that is transparent itself is drawn as it is, over the console's background. The
  alpha is the most at which every colour the map draws its rooms and exits in keeps WCAG 1.4.11's 3:1 against every
  colour between the console's text and backgrounds blended over that ground (`TextBehindMap` in `:protocol`, beside
  `MapBehindText`), up to the same 54%. `MapBehindText`'s tones do not serve here: they leave out thin exit lines and
  take in the background, while this needs each drawn colour against the ground. The colours are found off the main
  thread, as the frame is decoded while the map is in front, and ahead of the swap while it is behind the text (below):
  the colour of at least 64 pixels that have it on both sides along a row, a column or a diagonal, so a line a pixel
  wide counts, but not an antialiased edge, a pixel between the background and the colour beside it across the line,
  which is seen as part of what it edges. A colour already below 3:1 against the ground is not weighed, as no alpha
  could give it that. Mudlet's default map (dark green and cyan rooms, grey exits, on black) shows the default grey text
  at 19%, held down by the dark green; white rooms leave it to the grey exits, at 54%; rooms barely at 3:1 leave none.
  It eases in and drops at once as the map behind the text does, comes back to full with a predictive back gesture, and
  is none until a cut-out frame has come. Text the game colours, and the script windows, are not weighed, as for the map
  behind the text.
- The search sends `findRooms`, from its button or the keyboard's Search key, and puts the keyboard away, as on a
  small screen it leaves the results no room. The engine sends the best first: the name the text, then names that
  begin with it, then those that hold it, and within each the rooms of the player's area, then the nearest. The
  results sit right under the field, over the download bar and the map, at most three quarters of the room below it,
  and show only where there is room for their head and one whole row (not in a short window) and while the search is
  not being edited, so that none of their touch targets is ever cut off. Their head says how many ("12 rooms", "100+
  rooms"), "Finding rooms…" or no match, read out as it comes. Each result is a button of two lines: the room's name in
  bold, with the text found in it marked (`matchRange` in `:protocol`), over its area, number and how far it is; a tap
  sends `showRoom`, which centres the map on that room and selects it, and shows it as a tap on it does, closing the
  results but keeping the field's text. Its walk button, "Walk to <room>", sends `walkToRoom` and closes them, off
  where there is no known way or the player is there. Where they are in three areas or more, chips filter them by
  area (`roomAreas`). Back closes them, then the room shown, before it sends the map back.
- The UI asks for the map (`setAppMapSize`) in dp at the screen's density: at the map in front's size while it is
  open, else at the console's for as long as the game screen is up and no script's mapper is drawn, whether or not the
  map is drawn behind the text (the setting off, or colours that leave it no room), else 0 by 0. So the engine has drawn
  the map when the button is tapped, at the cost of drawing it as the player moves with the setting off too. It asks
  again at each attach, as the engine stops drawing the map when its UI goes (in the background too) and forgets the
  size.
- The swap waits for nothing. At 0 by 0 the engine deletes its view and sends an empty frame, and draws the next one
  from nothing, so the map is not let go between the two: the one that comes on the screen holds the request until it
  knows its size (`AppMapClient.show` with no size), and asks at once. Until the engine's frame for the new size comes,
  the last one is drawn as the engine will draw the map there (`MapFit`'s `asRedrawn`): Mudlet sizes rooms by the
  shorter side of what it draws in and keeps the map's middle in its middle, so the frame is scaled by the shorter sides
  of the two and centred, cut off or short at the edges, and the map neither moves nor changes size when the new frame
  comes. While the map is behind the text its frame is cut out for the text behind the map too, on a thread of its own
  below the others (`AppMapClient.cutOutAhead`), so the text is behind the map from its first frame in front. Finding a
  frame's colours makes no object for each pixel (`TextBehindMap.cutOut`): for a frame of 1080 by 1740 on the host's
  JVM, 133 KB and 12 to 16 ms where it was 36 to 50 MB and 24 to 37 ms with only the first JIT compiler, which removes
  no allocation, and 7 to 9 ms where it was 8 to 12 ms with both.
  The cut-out works on the frame's pixels as the bitmap keeps them, copied out and the cut frame's copied back
  (`copyPixelsToBuffer`, `copyPixelsFromBuffer`; `TextBehindMap.Layout` is the order of their bytes), where `getPixels`
  and `createBitmap(pixels)` convert every pixel, and in an array kept from one frame to the next (`KeptPixels`), as
  the first write to each page of a new one of a frame's 10 MB costs more than the copy. For a frame of 1079 by 2342
  on the emulators, in a build that is not debuggable (ART inlines nothing in one that is), the cut-out takes 10 to
  15 ms on API 28 where it took 24 to 28, and 26 to 29 on API 36 where it took 34 to 42; most of what is left is the
  finding of the colours, and on API 36 the first write to the cut frame's new bitmap.
  The colours are found and the background cut out in one pass over the frame, as a second pass over its 10 MB costs
  about as much as the first one's passing of the background: a run of the background along a row is passed in a
  tight loop, and a pixel within a room, its colour on all four sides, is taken as solid without weighing its lines.
  Measured in one run beside the two-pass code, on the same three frames, that takes a fifth to over a quarter off the
  whole cut-out: 7 to 15 ms on API 28 where it was 10 to 20, and 20 to 22 % on API 36 (whose runner was slow that
  run, 37 to 65 ms before); times move by a third from runner to runner, so only those of one run compare.
- The keyboard moves the map's box at each frame of its animation. The map is laid out, and asked for, at the size the
  box has once the keyboard is where it is going (`MapBox`, from `WindowInsets.imeAnimationTarget`), so it stays still
  while the keyboard slides and is drawn anew once. As the map comes to the front the input line lets its keyboard go,
  which the keyboard says only some frames later: for half a second the map in front takes it for gone. Any other
  change of size is sent once it has held for 200 ms.
- With a script's mapper: the app's map is a view of its own, with its own centre and selection; zoom is the area's,
  so shared, as between two maps on the desktop, and so is viewing or editing. So that one map shows on the screen,
  none is drawn behind the text while a mapper window (`Geyser.Mapper`, openMapWidget()'s dock) is on the screen:
  placed, or its page open (`WindowLayout.mapperDrawn`). A mapper that is a page no one opened, only its tab on the
  screen, as the starter UI's is in the stacked layout, leaves the map behind the text. The map in front is there
  whatever the scripts made. The app's map raises no script events (`sysMapWindowMousePressEvent`,
  `sysMapAreaChanged`), and getMapSelection() does not see what it selects.
- Known limits: a `centerview()` on the room the player is already in is taken by whichever view draws first, so a
  view moved by `showRoom` (or a pan) that draws second keeps its place until the player moves to another room.

### Downloading the map
A game can offer its map, as GMCP's `Client.Map` (docs/protocol.md, "Downloading the map"; a carried Mudlet patch),
which the desktop's mapper turns into a "Download from game" button. The app's map has it (`AppMapClient.download`,
drawn by `ui/game/AppMap.kt`):
- With no map drawn, the map in front says so in its middle, with a button "Download the map from <host>". It scrolls
  where the map has no room for it. While a search's results or a refusal at the map's bottom would share the map with
  it, the same button is in a bar under the search field. Neither shows while a map is drawn, while the game offers
  none, nor while a download runs.
- The button sends `downloadMap`, and waits for the engine's answer, so a second tap sends no second. While the
  download runs, the bar shows Mudlet's progress label, or "Downloading the map from <host>…", and a progress bar read
  as "Map download": how far of `maximum`, which is Mudlet's guess of 4 MB until the server sends the size, and
  indeterminate once a download passes it. Its Cancel button, a 48 dp target that the progress bar's 10 dp reach for
  TalkBack is kept clear of, sends `cancelMapDownload` while Mudlet can still stop it.
- How it ended stays in the bar until dismissed, the next download or the profile closes, and is read out once, as it
  comes (`Announcer`): "Map downloaded from <host>", "Map download cancelled", or "The map download failed: " with
  Mudlet's reason, in the error colour and interrupting TalkBack. Its close button is "Dismiss the map download", not
  the bare "Dismiss" of a refusal that may show at the same time. "Map downloaded" goes by itself 5 s after the map in
  front shows it, as the map shows the load. A UI that saw a download run and went before it ended reads the end out
  as it comes back; any other UI that attaches later shows how the last download ended without reading it out, unless
  the map loaded. A restarted engine's UI forgets the last engine's offer and download. A refusal shows as the map's
  others do. The loaded map is the profile's, so the map behind the text, the map in front and a script's mapper all
  show it; as on the desktop, Mudlet then shows its docked mapper, where a script has made one (`showLoadedMap()`).
- Of the URL, only its host is shown, and no message or log line carries more than its scheme and host, the reason of a
  failure included: the rest can hold a user name, a password or a token. The engine masks the password in that reason
  wherever it shows and the query wherever it follows a `?`, decoded, and percent-encoded as the game wrote them and as
  Qt shows and encodes them, with the hex digits in either case, and cuts any URL to its scheme and host, even in the
  server's own words, where a user name can be an e-mail address: the host is what follows the last `@` before the path,
  query or fragment, as a browser reads a URL, and the URL's own user name is taken whole, even with a space in it. What
  the server repeats in another form can still show, such as a path it repeats on its own or decoded with a space in
  it, without its query; the query, or a part of it, repeated without the `?` before it, and a part of it even right
  after one; the password or the query percent-encoded otherwise than the game's URL and Qt write them, such as `%21`
  for a `!` or `+` for a space; and a user name it repeats outside a URL. Lua's `gmcp` table holds the whole URL, as the game sent it, and Mudlet's console line the
  server's words, as on the desktop.
- Known limits: the button is only in the map in front, not behind the text, which takes no touches, and only while
  no map is drawn, so a newer map the game offers over a loaded one is not offered; Mudlet sets no
  time limit, so a stalled download runs until cancelled.

### The package manager
The game screen's title bar menu, More options, has a Packages item that opens the front profile's packages
(`ui/packages/PackagesScreen.kt`) as the desktop's Package Manager shows them, over `packages`, `installPackage` and
`uninstallPackage` in `docs/protocol.md`, kept by `PackagesClient`:
- Each package in the order installed, with its icon (`getImage`), its title or else its name, its name, version and
  author, and the start of its description, which a tap shows whole. The description is shown as plain text; the
  desktop renders its Markdown.
- Install from a file picks one with the system's picker (`OpenDocument`, any type, as Android knows none for
  `.mpackage`), copied under the name it was picked by into a folder of its own in the cache's `packages/`, since
  Mudlet names an `.xml` package after its file and the engine cannot read a picker's `content://` URI; the copy goes
  once the engine has answered. Each install shows with a progress bar until `packageInstalled`, and how it went shows in a snackbar.
- Install from a URL runs Mudlet's own Lua `installPackage(url)`, as the desktop does for a URL dropped on it, which
  downloads it and tells the console how it went; the list follows from `packagesChanged`.
- Remove asks first, then sends `uninstallPackage`, whose answer is the list without it.
- What the engine refuses shows in a snackbar with its reason, in English as Mudlet gives it. The list is asked for
  again on `packagesChanged` and after a reattach.
- Not yet: modules, and the package repository's browser.

### The script library
The game screen's title bar has a Scripts button, before the map's, that opens the front profile's script library
(`ui/scripts/ScriptLibraryScreen.kt`), over the requests in `docs/protocol.md`, "Editing triggers, aliases and scripts",
kept by `ScriptItemsClient`:
- A chip for each kind (triggers, aliases, scripts, timers, keys, buttons) with how many items it has; the kind's
  items one folder at a time under a breadcrumb, where Back goes up a folder, each row with its name, its summary
  (`scriptItems`' `summary`: a trigger's patterns, an alias's pattern, a timer's time, a key's name, the command a
  button sends), then why it does not run (Mudlet's error), how many items a folder has, that it is off, that it is on
  but not running (in a folder that is off, or new and not applied yet) or the package it came with, and a switch that
  sends `enableScriptItem`. An item with others in it (a trigger that others chain from, a timer with offset timers)
  is gone into as a folder is, with Edit in the title bar for its own page. A search finds names and summaries of
  every kind, with the folders each is in. New items and folders go last in the folder shown; a new item opens, and a
  new folder is gone into. Nothing is added inside an item that is not a folder, as Mudlet adds only to folders, and
  the buttons' top level, as a package's folder of them, takes toolbars, since a button outside one is never drawn.
  At the foot, the game's last two lines go back to the game. The title bar's Help menu opens in the browser the
  manual's introduction to the kind shown (the page desktop's script editor links to), the Lua functions and the
  manual (`ScriptHelp.kt`, the addresses in `ui/about/Help.kt`). The rules are in `ScriptTree.kt` (`holdsItems`,
  `rowStatus`, `adds`), apart from the screen.
  - The search also asks the engine where else the text is, as the desktop editor's search box finds it
    (`searchScriptItems`: commands, patterns, event handlers, a button's style sheet, Lua), and shows under each
    item the first three places, each with what it is in and the text marked. The desktop's two options, Match case
    and Whole words, are chips; with either on, only the engine's answer counts.
- An item's page (`ScriptItemScreen.kt`) edits its name, what its kind has and its Lua: a trigger's patterns, each
  with a type picked from a sheet that says what each matches, moved up and down; Match any or all within some lines;
  Every occurrence, Keep firing, Only pass matches, Highlight with its colours, Sound with its file and what it sends;
  an alias's pattern and what it sends; a script's event handlers; a timer's time, in the desktop's four fields, and
  what it sends; a key binding's key, picked from a sheet, the keys held with it, and what it sends; a button's
  commands, and a toolbar's place, direction and columns, with a style sheet for each (`ScriptKindFields.kt`; which
  of them a button's folder is, `buttonPart`). A new timer, button or toolbar is switched off, as on the desktop, and
  its row's switch starts it; a toolbar shows once it and a button in it are on. A colour pattern's text and
  background are each picked from a sheet (`ScriptColorPattern.kt`), as the desktop's colour trigger dialog offers
  them: any, the game's default, the 16 named or a number up to 255.
  The edit is
  a draft kept by `ScriptItemsClient` until Apply sends `editScriptItem`, whose snackbar offers Undo: a rotation keeps
  it, and so does the page's Game button, which goes to the game to come back from, the library marking the item
  "Draft" meanwhile; Back with a draft asks whether to discard it. Delete hides the item with Undo, and sends
  `deleteScriptItem` once the snackbar goes or the library does.
- The Lua is a field of the page (`LuaField.kt`, its edits in `LuaEditing.kt`), last in it and part of the same draft:
  the lines numbered and not wrapped, a new line indented as the one above it and a step deeper under a line that
  opens a block. The field is as tall as its lines, so only the page scrolls up and down, and it keeps the cursor's
  line in what shows over the keyboard; a long line scrolls sideways. While the field has the keyboard, a row over it
  has what Lua types most, and Mudlet's functions are offered as a name is typed, from `assets/lua/functions.json`
  (Mudlet's `lua-function-list.json`, which `scripts/sync-mudlet-games.py` writes and `scripts/lint.sh games` checks).
  Nothing is checked as it is typed. Apply sends `checkLua` with the `editScriptItem`: the item is saved whether or
  not its Lua compiles, as the desktop editor's save does, and then does not run, which the top of the page says;
  what Lua finds wrong shows over the page's buttons, read out by TalkBack, with a button that goes to its line,
  until the code is changed. Nothing runs until Apply.
- A trigger is also made from a line of the game's text: a long press on a line of the main console opens a menu with
  "Make a trigger from this line", and TalkBack has the same as the line's action (`ConsoleLine`; not on a blank line,
  nor on one of the app's own). The page it opens (`TriggerFromLineScreen.kt`) has the line's words, each a chip that
  a tap makes captured, then optional, then fixed again, and two switches: only a whole line, and a capture of several
  words. From them `TriggerFromLine` makes the pattern, apart from the screen: a substring while every word is fixed,
  an exact match for a whole line, and else a Perl regex in which a captured number is `(\d+)`, a word `(\w+)`,
  anything else `(\S+)`, or `(.+)` for several words, an optional word is `(?: word)?`, and the rest is the line's own
  text, escaped. It is made only of what PCRE and `java.util.regex` read alike, so the page tries it there: it shows
  what the line gives the trigger's Lua as `matches[2]` and on, and how many of the console's lines, as they were when
  it opened, the pattern matches. "Make trigger" sends `addScriptItem`, with the line as the trigger's name, and then
  `editScriptItem` with the pattern, so the trigger runs at once, and the trigger's page opens in the builder's place,
  to name it and say what it does; it opens too where the engine made the trigger but refused the pattern, and says
  why.
- On an expanded screen an item's page opens beside the library (`ListDetail`). What the engine refuses shows in a
  snackbar with its reason, in English as Mudlet gives it, and an item it will not open, on the item's page. After a
  reattach the lists and the item shown are asked for again; a new engine instance has new ids, so the drafts go.
- An item's page moves it, last, into a folder picked from a sheet (`moveScriptItem`), each folder with those it is
  in: not where it is, nor into itself, and among the buttons a button only into a toolbar or a menu; a package's
  own folder stays put. The snackbar says where it went once the list after the move has come.
- Not yet: moving an item onto another
  to make an offset timer or a trigger's chain, a button's icon and rotation, a key picked by pressing it, and in the
  Lua field colours for Lua's words and what each function does (Mudlet's list has only how each is called).
- A hardware keyboard's key that the profile's key bindings take (`keyBindings`, by Qt's key code and modifier bits,
  which `HardwareKeys` makes from Android's, the number pad's by Num Lock as on the desktop) goes to the engine as
  `pressKey`, from the input line or anywhere on the game screen but a script's text box, which on the desktop offers
  no key to the bindings either; any other key types as before. A binding beats the app's Ctrl+R and Ctrl+Tab, as
  desktop's command line claims a bound profile-switching shortcut. Not yet: Input's "Run all matching key
  bindings" (`runAllKeyMatches`, which no setting the engine takes carries), so the first binding that matches runs.
- Desktop's main-window shortcuts off macOS (`mudlet.cpp`) beat a key binding, as a QShortcut does there, for the
  screens the app has: Alt+E the scripts, Alt+P the settings, Alt+M the map, Alt+C the games, Alt+D, Alt+R and Alt+W
  disconnect, reconnect and close the game. Not yet: the caret key, mute, notepad, packages, modules, multi-view, the
  input line's toggle and the Ctrl+Alt ones.

## libmudlet as the engine
Built with `LIBMUDLET_BUILD_DIR`, the engine is `MudletEngine` (`native/enginehost/MudletEngine.*`), behind the
same socket protocol: each open profile is a `MudletProfile` (`native/enginehost/MudletProfile.*`) in a
`THeadlessSession` of its own, and the sessions share the process's pool of profiles
([`multi-profile.md`](multi-profile.md)). `MudletEngine` keeps what is the whole engine's: the profiles' store, the
app's state, the save and the restore. It uses only `THeadlessSession`'s
public API, from Mudlet's `development`: it includes no Mudlet header but
`THeadlessSession.h` (which brings `TDebug.h` and `utils.h`) and sets none of Mudlet's
defines. What the app needs of that API and Mudlet does not have yet is carried in
`scripts/mudlet-patches/`, each patch going upstream to Mudlet/Mudlet and dropped here once merged.
`scripts/apply-mudlet-patches.sh`, which both libmudlet build scripts run first, applies them to the
checkout, skips one already in it and stops the build at one that no longer applies; it takes an earlier run's off
first, as a later patch may change the lines an earlier one is checked against. The app's engine
compiles against the checkout's headers too, so a checkout built elsewhere, as CI's libmudlet build from its cache,
is patched before `build-apk.sh` all the same (`.github/actions/libmudlet`). Today there
are fourteen: the app's own map and the room search (see "The map behind the text"), downloading the map the game
offers (see "Downloading the map"), `setGameScreenSize()`, which tells the game the app console's columns and
rows without resizing the main window that `setMainWindowSize` gave, a label's live region in its
`labelContent()`, a text box's content (`textBoxContent()`) with the player's typing in it (`setTextBoxText()`),
a label's accessible value, which Geyser's gauges set and screen readers read as a progress bar's, several
headless sessions sharing one pool of profiles, each with one profile (`0007-headless-shared-pool.patch`; see
[`multi-profile.md`](multi-profile.md)), and an MXP frame's tab header, its pages' titles and the one in front
(`frameTabs`), with the player's tap on a tab (`selectFrameTab()`), moving a map by touch with what each frame
laid out (`0010-headless-map-moves.patch`; see "Moving a map" in [`protocol.md`](protocol.md)), a profile's settings
as `getConfig()` and `setConfig()` have them (`settings()`, `setSettings()`; see "Settings"), a map's rooms:
a room's details, a walk to it, its area's levels, another area or level shown, and the room search ranked best first
with each room's distance (`0012-headless-map-rooms.patch`), editing the map: a room's details, exits and place,
a room added beside another or deleted, the player placed, an area renamed and the environments' colours listed
(`0013-headless-map-edits.patch`), following a link in a label as the GUI's click does (`followLabelLink()`,
`0014-headless-label-links.patch`), the triggers, aliases and scripts listed, added, saved, switched on or off,
moved and deleted as the desktop's script editor does (`0015-headless-script-items.patch`; see "Editing triggers,
aliases and scripts" in [`protocol.md`](protocol.md)), the map info box as its parts, left out of the map's
picture (`0016-headless-map-info.patch`; see "The map info"), Lua compiled without being run, for an editor to
say what is wrong with it (`checkLua()`, `0017-headless-check-lua.patch`), a running profile's login and password
changed for its next connection (`setLogin()`, `setPassword()`, `0018-headless-login-change.patch`), its server,
TLS choice and automatic reconnect changed for the connections after the one that is open (`setAddress()`,
`setAutoReconnect()`, `0019-headless-connection-change.patch`), and the timers,
keys and buttons edited as the triggers are, with what each item's row shows under its name
(`0020-headless-script-items-more.patch`), the error console's entries, each with the item that raised it
(`0023-headless-error-console-entries.patch`), the certificate a secure connection was refused for
(`refusedCertificate()`, `0024-headless-refused-certificate.patch`), the keys the profile's key bindings take, told
again as they change, for a keyboard's key to go to the engine or to the input line (`keyBindings()`,
`0025-headless-key-bindings.patch`; see "Key bindings" in [`protocol.md`](protocol.md)),
and the map loaded from and saved to a file as the desktop's Map settings do (`loadMapFile()`, `saveMapFile()`,
`0026-headless-map-files.patch`; see "Map files" in [`protocol.md`](protocol.md)).
It carries too the items searched as the desktop editor's search box does (`searchScriptItems()`,
`0027-headless-script-search.patch`).
And the log options `getConfig()` and `setConfig()` lacked: timestamps, how a log is named and its name
(`0028-headless-log-settings.patch`).
And a script's clipboard, read and written by the app rather than the engine's process (`THeadlessSession::setClipboard()`,
`0029-headless-clipboard.patch`; see "A script and the clipboard" in [`divergences.md`](divergences.md)).
And the file a script asks for, chosen in the app (`THeadlessSession::setFilePicker()`, `0030-headless-file-picker.patch`;
see "A script asks for a file" in [`divergences.md`](divergences.md)).
And the map emptied as the desktop's Map settings' "Delete map" does (`deleteMap()`, `0031-headless-delete-map.patch`).
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
| `notificationRequested` (`showNotification()`) | `notify`, posted by the engine process |
| `connecting`, `connected`, `disconnected` | status `connection` |
| `outputLine`, `localTextStyled`, `errorConsoleText` | counters, and status `luaError` |
| `profileLoaded`, `startFinished` | status `profile`, `profileLoadMs`, `startMs` |
| `hiddenInputWanted` | `hiddenInput` |
| `commandHistory()`, read at `profileLoaded` and when a UI attaches | `history` |
| `commandLineTextRequested` (`printCmdLine()` and the like) | `commandLine` |
| `scriptWindows()`, read at `profileLoaded`, then at each `scriptWindowsChanged` | `windows`, only the windows that changed and those `removed`; all of them, with `full`, to a UI that attaches. A close sends an empty list |
| `windowLines()`, the last 300 rows, at `profileLoaded` and at each `windowTextChanged` | `windowText`; any rows on request with `getWindowText` |
| `setMainWindowSize()`, then `mainConsoleGrid()` and `mainConsoleTextRect()` at `profileLoaded` and at each `mainConsoleAreaChanged` | `setMainWindowSize`, kept and given again to each profile as it loads; `mainArea` |
| `setScreenSize()` until the profile has the main window's size, then `setGameScreenSize()` (carried), at `profileLoaded`, at the start's end and at each change | `setScreenSize`, kept and given again to each profile as it loads |
| `setUserWindowGeometry()`, `closeUserWindow()` | `placeUserWindow`, `closeUserWindow` |
| `submitWindowCommandLine()`; `windowCommandLineTextChanged` | `sendWindowCommandLine`; `windowCommandLine` |
| `labelContent()` of each label, read with `scriptWindows()` | `label` after the `windows`, for each label whose content changed; every label to a UI that attaches |
| `followLabelLink()` (carried patch); a web link is the UI's | `followLabelLink`, answered only when refused, and always for a web link |
| `textBoxContent()` of each text box, read with `scriptWindows()`; `setTextBoxText()` (carried patch) | `textBox` after the `windows`, for each text box whose content changed, and every text box to a UI that attaches; `setTextBoxText`, answered only when refused, by `error` and then the box's `textBox`, each counted in its `typed` |
| `pointer()` | `pointer`, answered only when refused |
| `frameTabs` of each MXP frame's script window, read with `scriptWindows()`; `selectFrameTab()` (carried patch) | `tabs`, `tabTitles` and `tabCurrent` in the header frame's `windows` entry; `selectFrameTab`, answered only when refused |
| none: the engine reads the file itself, with `QImageReader` or as a `QIcon` | `getImage`, answered by `image` as a PNG |
| `buttonBars()` at `profileLoaded` and at each `buttonsChanged`; `pressButton()` | `buttonBars`, whole, when it changed, and to a UI that attaches; `pressButton`, answered only when refused |
| `addonCommands()` at `profileLoaded` and at each `addonCommandsChanged`; `activateAddonCommand()` | `commands`, the same way; `activateCommand`, answered only when refused |
| `renderMap()` at the mapper window's size onto an image larger by `setMapScale`'s scale, at each `mapRepaintRequested` and `mapViewOnlyChanged` and when the mapper shows or changes size; `mapPointer()`, `setMapViewOnly()` | `map`, when it changed, and to a UI that attaches while the mapper shows; `mapPointer`, `setMapViewOnly`, answered only when refused |
| `renderAppMap()` at the size and scale `setAppMapSize` asks, at the same cues; `appMapPointer()`, `findRooms()`, `showRoomOnAppMap()`, `closeAppMap()` (carried patch) | `appMap`, when it changed, empty while nothing is drawn, and not to a UI that attaches, as the size goes with the UI that asked (`closeAppMap()`, as at 0 by 0); `setAppMapSize`, `appMapPointer`, `showRoom`, answered only when refused; `findRooms` answered by `rooms` |
| `moveMapView()`, `followPlayerOnMap()`, `mapView()` after each `renderMap()` and `renderAppMap()` (carried patch) | `moveMap`, `followPlayer`, answered only when refused; `view` in `map` and `appMap`, with the last `moveMap` drawn as `moved` |
| `roomInfo()`, `mapAreas()`, `walkToRoom()`, `showMapLevel()`, `showMapArea()`, `findRooms()` ranked with steps, `MapView::levels` (carried patch) | `roomInfo` answered by `room`, `mapAreas` by `areas`; `walkToRoom`, `showMapLevel`, `showMapArea`, answered only when refused; `levels` in `view`; `areaId`, `match` and `steps` in `rooms` |
| `editRoom()`, `setRoomExit()`, `lockRoomExit()`, `addRoomBeside()`, `deleteRoom()`, `setPlayerRoom()`, `renameArea()`, `mapEnvs()` (carried patch) | `editRoom`, `setRoomExit`, `lockRoomExit`, `addRoom` answered by `room`; `deleteRoom`, `setPlayerRoom`, `renameArea` answered only when refused; `mapEnvs` by `envs`; `symbol`, `env`, `color`, `weight` and `locked` in `room` |
| `offeredMapUrl()` at `profileLoaded` and at each `offeredMapChanged`; `downloadOfferedMap()`, `cancelMapOperation()`; `mapProgressStarted`, `mapProgressChanged`, `mapProgressCancelDisabled`, `mapDownloadFinished` (carried patch) | `mapDownload`, with the URL's host only, when it changed (progress at most once a percent), and to a UI that attaches; `downloadMap`, `cancelMapDownload`, answered only when refused |

The tail is sent once per pass of the event loop, so a flood sends its lines and a single tail.

**Profiles.** `THeadlessSession` opens a profile by name but does not list, save or delete them, so until Mudlet's
headless API does, `native/enginehost/ProfileStore.*` reads and writes Mudlet's own files: a profile is a folder under
`<configDir>/profiles`, and `saveProfile` writes the files Mudlet's connection dialog writes there (`url`, `port`,
`ssl_tsl`, `login`, `autoreconnect`), each one `QString` in a `QDataStream` at `Qt_5_12`, through `QSaveFile`.
`deleteProfile` removes the folder (`QDir::removeRecursively()`). Mudlet records no order, so the engine keeps the
order profiles were last opened or created in, `<configDir>/profile-order.json`. `openProfile` without `alongside`
closes the running profiles with `shutdownAsync(true)`, as Mudlet closes one, then starts the other with
`startAsync()`, its password in `Options::password` for that start only; with `alongside`, it starts the profile in a
new session beside them, up to `ControlBridge::maxOpenProfiles`.

**Lifecycle.**
- `openProfile`, or the first `connect` or `runLua`, calls `startAsync()` with `Options::configDir` set to
  `<files>/mudlet` (`MUDLET_CONFIG_DIR` overrides it on the desktop). A `connect` or `runLua` starts the profile
  `MUDLET_PROFILE` names (`Android` unless set), or the one last opened.
- An `openProfile` without `alongside` while other profiles run closes them first (status `profile` `<name>
  closing`, then `<name> closed`); a close Mudlet refuses, as while a script runs, leaves that profile running
  (`<name> not closed: ...`) and calls off the open. `closeProfile` closes one, and the others run on.
- While one profile's start loads it, `THeadlessSession` refuses every other session of the pool, so what the others
  are asked meanwhile (a close, a save, the app's state, the front, a command typed) waits for that load to end.
- `setFront` runs Lua `setActiveProfile()` in the new front profile once it has started, which raises
  `sysProfileFocusChangeEvent`, lost in the one before and gained in it, as a desktop tab change does.
- Requests that arrive during the start are queued, and run once `startFinished` reports
  `started`. Those that arrive while a profile is being opened are for that one.
- `start()` can create a profile only from a game's address, so Lua sent before the first
  connect of a new profile gets `no profile`.
- An engine started after the last one was killed loads that one's profile again, and connects it
  if the connection was wanted (see "Restoring the session" below).
- When the engine quits, it closes each profile with `shutdownAsync(true)` in turn, and waits up to 5 s for each
  one's sysExitEvent and save.
- On the desktop, SIGTERM quits the engine the same way.
- `setAppState` is the pool's: with `active` false it raises sysApplicationFocusChangeEvent once in every profile,
  then saves them all (`saveAsync()`, which writes the whole pool), as the system may kill a process in the
  background without warning; `minimized` goes to every profile. `EngineService.onTrimMemory()` saves them too,
  through `EngineNative.nativeSave()`. A switch or close asked during a save waits for it, as Mudlet refuses to close
  then.
- On Android this depends on QtServiceBase ending the event loop when the service is destroyed.
  That has not been tried on a device.

**Tests.** `native/tests/test_mudlet_engine.py` runs the host build of this engine against
`native/tests/fake_telnet.py` through the socket protocol. CI builds libmudlet for the host with the
Android options (`scripts/build-libmudlet-desktop.sh`) to run it. It checks:
- the bridges' refusals before the engine is called: a wrong `hello` version, an unknown type, each request with a
  field missing or out of range, a fifth profile opened alongside four, a UI that never says `hello`, and a new UI
  replacing the old one;
- the banner's colours and styles, and the prompt as the tail;
- a prompt from a game that has sent no GA, as the tail after 300 ms of quiet; after a GA, an
  unfinished line held back until its line feed;
- a gag, a trigger's `echo()`, and an `echo()` after `deleteLine()`;
- `showNotification()`, `echo()` and `cecho()`;
- `clearWindow()`, and the replay after a clear;
- a 3000-line flood that arrives whole and in order, and a UI that attaches after it replayed the ring's last 2000
  lines after a `gap`, or only those after its `lastSeq`;
- Lua errors;
- the profile's save on quit;
- `setAppState`: sysApplicationFocusChangeEvent to a Lua handler and a save each time the app leaves the front;
  `alert()` as `attention`, and new text while minimized as `attention` 0 once the profile alerts on new data; and
  `moveTypedLine` ahead of `hiddenInput` when a script's typed line is up as the game hides input;
- the profile requests: each answered with `profiles` or `error`; profiles written in Mudlet's own files; a switch
  of profiles while connected; the running profile refused to `deleteProfile`; a password that reaches the game
  through Mudlet's auto-login, after the profile's login, but no frame and not the engine's log; a new login and
  password for the running profile, which its next connect logs in with, without closing it, neither typed into
  the connection it has, and a password withdrawn or a login cleared left out of the next; a port, a TLS choice and
  the automatic reconnect saved for the running profile, which Mudlet's own reconnect, Lua's `reconnect()` and the
  next `connect` are made with, without closing it, and an address Mudlet refuses, which its `connect` then reports;
  `passwordGiven` as a password is given and withdrawn, and a password handed over leaving the list's order;
- several profiles: a second opened `alongside` the first, on a game of its own, with its own socket; a trigger in the
  profile behind whose `raiseGlobalEvent()` reaches the other's script; `setFront` raising
  `sysProfileFocusChangeEvent` in both; `setAppState` raising `sysApplicationFocusChangeEvent` once in each, with
  one save; and each closed while the other runs on;
- the input line: a game's `WILL ECHO` gives `hiddenInput`, `sendHidden` reaches the game and no frame or log,
  `dismissHidden`, `history` to a UI that attaches and after a restart, `complete` of a word the game sent and from the
  history, and `printCmdLine()` as `commandLine`;
- the screen size: `setScreenSize` as the game's NAWS, and as the main window's grid in `mainArea` until
  `setMainWindowSize` gives a size; after it, while the profile runs, `getMainWindowSize()` and `mainArea` keep that
  size and the game is told the console's columns and rows (below 40 as 40), not that size's grid, also after a new
  main window's size; given both before the profile loads, the same for `getMainWindowSize()` and the game; and with
  a main window's size the loading profile refuses, `setScreenSize` sizing the main window and reaching the game;
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
  and whose text label is transparent, and whose back label carries its value and maximum, sent again with each new
  value, kept through `setStyleSheet()`, stopped at the maximum, and cleared for an infinite one, and a
  `createGauge()` gauge's back label carrying the value `setGauge()` gave it through `moveGauge()`; a label's live
  region, from Geyser's `liveRegion` and each `setLiveRegion()` or `setLabelLiveRegion()` alone, as `label`; its
  accessible value, from each `setLabelAccessibleValue()` or `clearLabelAccessibleValue()` alone, as `label`, clamped
  to its range, and a refused one sending nothing; every label to a UI that attaches; `deleteLabel()` as `removed`;
- label links: `followLabelLink` sending a `send:` link's command through the aliases, a `prompt:` link's text as
  `commandLine`, its space kept, and running any other link as Lua, none of them answered; the hrefs the app reads
  from quoted values and entities, and from links with a raw `<` (the same as `RichTextTest`'s), followed; refused
  before a profile is open, for a web link, Lua that fails, a `mailto:` link, a `prompt:` link while the game hides
  input, and a link, or a label, there is not, none of them running anything; a link followed sent again in the
  visited colour of `setLinkStyle()`, and only that one, not a web link;
- text boxes: `createTextEdit()` with `setTextEditText()`, `setTextEditPlaceholder()`, `setTextEditStyleSheet()` and
  `setTextEditTabMovesFocus()` as `textBox` after its `windows`, with its `typed`; `setTextBoxText` read back by
  `getTextEditText()` and not sent back, by a later `windows` either, and a script's text after it sent, the text from
  before it included; a paste with a non-breaking space, CRLF and a line separator kept as plain text, and not sent
  back; `setTextEditPlaceholder()`, `setTextEditTabMovesFocus()` and `setTextEditReadOnly()` alone as `textBox`;
  `setTextBoxText` refused for a hidden box and a read-only one, by `error` and the box as Mudlet holds it; an unknown
  box refused; every text box to a UI that attaches, its `typed` counted afresh; `deleteTextEdit()` as `removed`;
- MXP frame tabs: a titled frame and the `DOCK` frames in it, fed through `feedTriggers()`, as `windows` with the
  header's `tabs`, `tabTitles` and `tabCurrent`, only the page in front `shown`; `selectFrameTab` bringing a page
  forward where the one in front was, and the header's own console; refused before a profile is open, for a name
  that is no page and for a header in an `EXTERNAL` frame that is hidden; the tabs to a UI that attaches; the page in
  front closing and the one to its left coming forward;
- button bars and commands: a package's bar, with its grid, a push-down button and a folder's menu, and
  `tempButtonToolbar()` bars at the left and floating, as `buttonBars`; `pressButton` sending a push-down button's
  command for down, then up, and running its script, and a menu entry pressed; `addCommand()` and
  `setCommandChecked()` as `commands`, `activateCommand` raising `sysCommandClicked` and ticking it; a disabled or
  unknown one refused, both lists to a UI that attaches, and `removeCommand()`;
- the map: a map built with `addRoom()` and `setExit()` in a `Geyser.Mapper`, as `map` at the mapper's size with the
  rooms drawn where the layout puts them, on twice the pixels after `setMapScale` 2, and at the window's again for the
  next UI; a `mapPointer` press selecting the room under it and raising
  `sysMapWindowMousePressEvent`, a double-click walking to a room through `doSpeedWalk()`, a wheel turn zooming and less
  than half a notch refused; `setMapViewOnly` raising `mapModeChangeEvent`; `pointer` on the window `mapper`; the map to
  a UI that attaches, and none while the mapper hides;
- the app's map, with no script mapper: `setAppMapSize` drawing it as `appMap` at the size and scale asked, an empty
  one with no profile or no rooms, and one larger than a PNG may be refused; `appMapPointer` walking with a double-click
  and raising no `sysMapWindowMousePressEvent`; `findRooms` by name, ignoring case, and by number; `showRoom` centring
  the map on a room, and refused for one not in the map; `setMapViewOnly` with only this map open; a UI that goes
  stopping it, and the next sent none until it asks; and 0 by 0 stopping it;
- a map's rooms (`map_rooms`): `roomInfo` with a room's area, place, exits (locked ones marked, special exits by
  command) and steps from the player's room over unlocked exits, -1 where there is no way; `findRooms` ranked exact,
  prefix, then within the name, the player's area then the nearest first, and by id; `mapAreas` by name;
  `showMapLevel` and `showMapArea` with the `view` they draw, and refused for a level or area with no rooms;
  `walkToRoom` speedwalking the path, and saying when there is none;
- editing a map (`map_edits`): `editRoom` setting a room's name, symbol, environment, weight, lock and place, and
  refusing a weight under 1 or nothing to set; `setRoomExit` both ways and removing both; `lockRoomExit`, refused with
  no exit that way; `addRoom` beside a room with exits both ways, refused where a room is already; `deleteRoom`;
  `setPlayerRoom`; `renameArea`, refused for an empty or taken name; `mapEnvs` with the profile's sixteen colours and a script's;
- the map download: a game's GMCP `Client.Map` offering a map from a local web server, as `mapDownload` with only the
  host; `downloadMap` downloading and loading it, a Mudlet binary map and an XML one, with `sysMapDownloadEvent`, no
  Cancel once the map has arrived, its rooms then found and the app's map drawn; `mapDownload` to a UI that attaches;
  a 404 failing with Mudlet's reason, which names the host but not the path or query, and a file that is no map
  failing, and a slow download sending its progress at most once a percent and cancelled, each leaving the map as it
  was; the refusals; a script's JSON map export and import sending no `mapDownload`, even the export's last progress
  after a `downloadMap` read with it, whose download keeps its own label and Cancel; another profile taking the offer
  away; the URL's user name, password (percent-encoded in the URL) and token in no frame and not the engine's log; and,
  from a server whose 404 reason repeats them, the password decoded, as Qt shows and encodes it, and as the game wrote
  it (in lower case, with an encoded `~`), as it is and with its hex digits in the other case, the user name (an e-mail
  address) and the token in no frame but Mudlet's console text and not the engine's log, the password masked in each
  form and the URL cut to its host, as is another URL whose user name is an e-mail address; a user name with a space
  that the reason repeats in the URL cut with it, and the query it repeats after the path masked; and a password that
  is a word of the reason in another case masked as it is, the word kept;
- TLS (see "TLS" below): a self-signed certificate refused and passed on with its details, TLS to a port that does
  not speak it, then the same certificate accepted once the profile accepts self-signed ones, while an expired one
  and one for another server are still refused.

`native/tests/test_mudlet_restore.py` checks the restore below the same way, killing the engine with SIGKILL, with
one profile open and with two: a profile opened with a password comes back without it (`passwordGiven`), the
password handed to it while it is restored cancels nothing and logs the restored connection in, and an address saved
for a running profile is the one kept for its restore.
`native/tests/profile_checks.py` holds the profile requests `test_mudlet_engine.py` runs.

`native/tests/android_smoke.py` runs the debug APK, built for x86_64 with this engine, on an emulator
(CI's emulator jobs) or a device, from the host through adb. It drives the UI through a debug-only hook,
`SmokeTestReceiver` (`app/src/debug`, which only the shell and the system may send to), and checks:
- that the UI starts the engine service, which runs in `:engine` with Qt up, out of the foreground while no profile
  is open, and attaches to it;
- that connecting to `fake_telnet.py` on the host (`10.0.2.2` from the emulator), as the profile list does, saves a
  profile with a character name, stores a password in `PasswordStore` and opens the profile with it read back: the
  profile starts, so Mudlet's Lua (`luaL_openlibs` and Mudlet's Lua files) is up, the engine lists it first and goes
  into the foreground, the banner's coloured lines reach the UI, and Mudlet's auto-login sends the game the login and
  then the password;
- that a Lua trigger fires, `cecho()`s on its line and has the engine post its `showNotification()`;
- that `am send-trim-memory` has the engine save the profile;
- that a Geyser `MiniConsole` and the line a script `cecho()`s on it reach the UI;
- that a sound a script plays (`playSoundFile()`, a tone its Lua writes) plays from `:engine` as Android's media
  stream (`dumpsys audio` lists Mudlet's one output, `TAudioMixer`, with `USAGE_MEDIA` or `USAGE_GAME`, both on the
  media volume), and plays on with the screen off; what `ttsSpeak()` raised, and the device's text to speech engines,
  go to its log, not yet checked;
- that two more profiles open beside the first, recording the engine's PSS (`dumpsys meminfo`) with one, two and
  three open in `memory.json` among its output, which "Memory per profile" below takes its figures from;
- that after `:engine` is killed, the UI starts a new engine and attaches to it;
- that logcat never holds the password.

If the engine crashes, it stops with the crash from logcat, symbolised with `ndk-stack`.

The instrumented tests, run on the same emulator boot by `native/tests/android_instrumented.py`, check what only a
device shows, below the UI's debug hook:
- `engine-service/src/androidTest` runs `EngineService` from its own test APK, against a small game server in the
  test's process: the service in its own `:engine` process, Qt's `serviceReadyWait` and QPA, the socket handshake;
  the foreground and its notification only while a game is open; a tell filed under its game; the engine
  notification's Quit, after which the next start restores nothing; a killed engine,
  after which the next one reconnects to the game (see "Restoring the session" below); and a TLS connect whose first
  bytes are a ClientHello, so Qt's backend loaded OpenSSL.
- `app/src/androidTest` runs the app against a fake engine in the test's process (`EngineClient`'s `connector`), which
  serves the control socket and a socket for each profile it has open: two profiles' state kept apart
  (`ProfileSessionsTest`); a tap on a game's notification showing that game (`NotificationTapTest`); the game
  switcher (`GameSwitcherTest`): the profile list's open games, the game pill's reading and its next and previous
  actions with the announcement and the focus after a switch, the chip for each state of the connection with a drop
  told once and Mudlet's own retries read as one state, the game sheet's Reconnect, Disconnect, switch and close
  (asking first while connected, and back to the list after the last), and "Open another game" opening one beside
  the others until four are open; the swipe on the pill (`GameSwipeTest`, mirrored in ar-XB): a drag far enough or a
  fling brings the game beside with a tick, a short slow one springs back, nothing goes past the first or the last
  game, Ctrl+Tab and Ctrl+Shift+Tab switch too, and a sideways swipe on the console or the quick bar switches nothing;
  the badges (`GameBadgesTest`): the engine's unread count ("99+" past 99) and attention bell on the open games, read
  by TalkBack there and as "new lines in other games" on the pill, `markSeen` for the game in front as it comes to
  the front and as its lines come, "New activity in" said once for a game in the background, and its `announce()`
  said politely after its name; the
  way from the start screen to a profile's editor, the game screen and the self-test and back, through the labels
  and click actions TalkBack uses; the profiles (`ProfilesTest`): the list in the engine's order, each read as "name,
  host:port", adding, editing, deleting only once confirmed, the engine's refusals, Connect sending
  `openProfile` with the stored password, a password stored or forgotten for an open game given to it, and to
  no game that is not open, and a game the engine says holds no password given its stored one, once; the password
  field, which TalkBack does not read aloud; `PasswordStore`
  (`PasswordStoreTest`), whose passwords outlast a new store, go with Forget, are never in the clear, and are excluded
  from backups; the self-test's colours and styles of each run, in the semantics tree and in the pixels drawn; the
  reattach to a new engine after one is lost, and to the same one; what TalkBack is given (new lines read as one
  announcement, `announce`, each console line as its plain text) and the settings: a game added in the app given
  the app's values the player changed and the input line's clearing (`setSettings`), a game from before the app could keep a sent command told once to go on clearing its line, and any other keeping its own (`getSettings`), read again as a page opens and changed there
  alone, a search's switches
  for the game and for new games, the Input, Connection, Security and Advanced pages' changes, a game's number sent once a slide, and a setting read as the game connects saying once, while it is connected, that it waits for the next connection; the order TalkBack moves in (`TalkBackOrderTest`), as Compose gives it to TalkBack, over a mini console, a
  button bar and the map behind the text: the title bar, the console, the input line and the screen's buttons, then the
  windows, then the bars, then the last announcement, with every control in it and nothing of the map, and with the
  map in front the title bar, its search, the map, the input line and the row with the game pill, and nothing it
  covers; the script windows: a mini console at its scaled place in the fitted and the stacked layout, the size the
  game screen gives Mudlet's main window, the window switcher, a window's command line both ways, a user window's close
  button, a window's rows read as their plain text, a blank one as "Blank lines from the game" says and none read
  aloud, and a link in one asked about with `getLink` and the window's name and followed by a tap, its menu and
  TalkBack's actions; the labels: a label's text at its scaled place, a clickable one as a button whose click and tap reach the
  engine as `pointer` events, its 48 dp touch bounds, a gauge read as its progress and its live text label's new text
  said, politely or not, but not its first nor a new value, the accessible value of a gauge's back label as its
  progress, and of a label as a progress bar's with its text and percentage as its state, a button's not, a live
  label with a value not live, its new text said but not a new value, a live label as one live node from blank (no
  stop) to text, a named one with its text as its state,
  a label in a scroll box scrolled into view by the box's scroll action and tapped at its middle, a drag on a
  clickable one scrolling the box and sending the label nothing, also on one tapped before the box came to scroll, a
  narrow one's touch bounds kept but for the box's cut,
  a link sent for the engine to follow, by its action or a tap, with the name of the label tapped, of two with the same
  link, and the link as Qt reads it from the text, a `prompt:` link giving the input line the focus and a `send:` one
  not, and neither sent again nor run by the app on a refusal, a web link opened with an intent and not sent, a
  click-through label's links neither tapped nor actions, also once a label already drawn is made click-through, and
  both again once it no longer is, and a label's image asked of the engine; the button bars: a bar's buttons
  as 48 dp buttons above or beside the console and read
  after the windows, whose taps send `pressButton`, a push-down button as a switch, a folder's menu, an icon button's
  name and icon, a bar's and a button's style sheets drawn, with a sheet's own frame, its padding, border and margin
  and its font size, a push-down button's `:checked` style while it is down and still a switch, with its tick,
  `:pressed` while a button is touched or its menu is open, a 48 dp button whatever its sheet asks and one no bigger
  than the game area whatever its sheet's lengths or its name ask, and the Extensions menu of commands, whose tap sends
  `activateCommand`; the map: drawn in the mapper
  window, a tap, a double tap and a pinch sent as `mapPointer` at the frame's point under them, the zoom buttons and
  TalkBack's actions, the switch to editing, a refusal until dismissed, a frame of another size not drawn, and the
  docked map as a page; an MXP frame's tabs (`ScriptFrameTabsTest`): each page a tab by its title, at least 48 dp,
  selected for the page in front, a tap on another sending `selectFrameTab` and the page brought forward then drawn,
  and stacked, the page called by its tab's title, the tabs at the top of the pane, which stays open on that page,
  with its chip in the switcher selected; the app's map (`AppMapTest`): asked at the console's size, again at each attach, and drawn
  faintly behind its text, which TalkBack and touches still reach, none with the setting off or a mapper window shown,
  the title bar's toggle bringing it to the front, over the input line down to the row with the game pill, without History,
  with the input line's keyboard gone and its focus back after, where taps, the zoom buttons, the edit switch and
  TalkBack's actions reach it as `appMapPointer`, and Back sending it back, the bar showing another area or level,
  the room search with its results, no match, more matches and a refusal, its count read out, the match marked, the
  filter by area, a walk from a result, Back closing them and then the room shown, and the map download: no button with no offer, the offer with no map drawn, covering no
  other control, sending one `downloadMap` however often tapped, its progress, indeterminate past Mudlet's guess, and
  Cancel, how it ended (cancelled, failed, read out at once and not again to a UI that comes back, until dismissed,
  and loaded, the map then drawn, saying so for a moment, with no download offered over it), a refusal, and a new UI
  showing neither the last load nor the offer; a failure while the UI was away read out as it comes back, and a
  restarted engine's UI showing neither it nor the offer; the notification permission, explained and then asked on
  Android 13 and later, on a fresh install; and a PNG from the engine's own writer for maps read by Android's decoder,
  each pixel as the writer was given it (`MapPngTest`).
  Each Compose test rule has the Accessibility Test Framework's checks on (`enableAccessibilityChecks()`), which fail a
  test on a control with no label or a touch target under 48 dp; the library needs API 34, so they run on the API 36
  emulator only. `ScriptLibraryTest` and `PseudoLocaleTest` turn them on through `AccessibilityChecks.kt`, which
  leaves out a result about something wholly outside the scrolling area it is in: Compose hands accessibility a node
  that ends within half a touch target of the area that clips it, so an empty text field scrolled to just above an
  item's page counted as an edit box with no label.

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
- A scroll box scrolls in the app alone, so the engine places a window in one as though the box were not scrolled:
  `getMousePosition()` and a label callback's `globalX` and `globalY` are off by the box's scroll (`inMainArea` in
  `THeadlessSessionWindows.cpp`), where the desktop's positions, which Qt gives (`mapFromGlobal(QCursor::pos())` and
  the event's `globalPosition()`), count it. The callback's `x` and `y`, in the label's own pixels, are right.
- A scroll box at a side border has no page in the stacked layout, where only consoles and the map are pages, so it
  and the windows in it are not shown there.
- No bundled fonts.
- A label's movie does not play: while it plays the label shows its first frame, still, and once `pauseMovie()` paused
  it or it played to its end, the frame it stopped at, so `setMovieSpeed()` changes only which frame a pause stops at
  and how soon one that plays to its end shows its last frame (`docs/phase-f.md`, "Labels"). The desktop's `QMovie`
  plays it from `setMovie()` on, at the speed `setMovieSpeed()` gives. The app draws a label's image as one PNG the
  engine reads from the file (`getImage`), and nothing asks for the frames after the first while a movie plays.
- TLS is chosen when the profile starts, and by a `saveProfile` of it after that (see "TLS" below).
- Mudlet reads a profile's details as it loads, so libmudlet hands a running profile the ones `saveProfile` changes,
  and the password `openProfile` gives it (`THeadlessSession::setLogin()`, `setPassword()`, `setAddress()` and
  `setAutoReconnect()`). None closes the profile or touches a connection that is open. The next connection logs in
  with the login and password, and is made to the saved server with the saved TLS choice, whoever makes it: the UI's
  `connect`, Mudlet's automatic reconnect and its retry of a connection that failed, or Lua's `reconnect()`. A
  connection still looking its server up takes the TLS choice as it is when the lookup ends, as on the desktop when
  the profile preferences change it. The automatic reconnect counts from the next time the connection is lost or
  fails: a retry Mudlet is already waiting to make is still made, and Disconnect calls it off. Mudlet refuses an
  address its connection dialog would, such as TLS to an IP address: the files keep what was saved, the running
  profile the address it had, and its `connect` is refused with the reason until the profile is saved with one
  Mudlet takes. A profile that waits for its restore, in the seconds after the engine was killed, is not running
  yet, and that restore still connects to the address the UI last connected it to.
- `saveProfile` makes a new profile's folder before its first start, so that start does not remove the passwords an
  earlier profile of the same name may have left in the credential store, as Mudlet does for a new name, and
  `deleteProfile` does not remove them either. The engine stores no password (the app keeps its own, encrypted, and
  sends it with each `openProfile`), so only one stored by Mudlet's own GUI under the same config dir could be left.
  Profile management in `THeadlessSession` would close this.
- On a device, Lua 5.1 must be built with `-U_FORTIFY_SOURCE`. Otherwise bionic's FORTIFY check aborts the engine in
  `luaL_openlibs`. Mudlet does not do this yet; `scripts/build-libmudlet.sh` carries it in this
  repository's libmudlet build, and CI's emulator smoke test fails without it.

### Restoring the session
Android kills `:engine` when it needs the memory, and the user can kill it from the self-test. The engine is started
again within about half a second while the UI is shown (the UI starts it, and the system may too), but a new engine
knew nothing of the profiles, so the player was silently left disconnected. Now the engine keeps what it needs to
start each open profile again on disk, and a new engine restores them.

**What is kept.** `native/enginehost/SessionStore.*` keeps one JSON file, `<files>/engine-session.json`
(`MUDLET_ENGINE_STATE`; the app names it in `EngineSession.kt`), version 2 (a version 1 file, of one profile, is
still read). It holds, for each profile open, in the order opened:
- the profile, the one `openProfile` named included, which is the only `THeadlessSession::Options`
  field the engine sets besides the connection's and an `openProfile`'s password, which is never kept, so a restored
  profile has none until the UI gives it the one it stores (see "Profiles in the app"; `configDir` comes from the
  app's files, as at any start);
- the host and port the UI last connected it to, passed again as `Options::host` and `port` (an empty host leaves
  the address stored in the profile);
- whether the connection is wanted;
- the TLS choice the profile was started with, if the `connect` that started it made one, passed again as
  `Options::tls`, so that the UI's next `connect` with that choice is not refused. A `saveProfile` that changes the
  running profile's host, port or TLS choice forgets all three, so a restore connects to what is stored;

and once for the engine:
- the front profile;
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

**How it restores.** The front profile is loaded with `startAsync()` from the event loop, once the engine listens,
and connected if that was wanted. Each of the others starts `SessionStore::staggerMs` (2 s) after the one before it
has loaded, so the games are not all called at once, and stays behind the front one. It does not wait for a UI. A UI
that opens a profile without `alongside` calls off what has not started yet. Status `restore`
([`protocol.md`](protocol.md), "The restore status") says what happened, and the engine keeps the status, so a UI
that attaches later is told too. The UI shows it as a marker line.

**Not hammering a game.**
- Each restore counts itself in the file before it starts, so one that kills the engine still counts.
- The first restore within 10 minutes connects at once, the second after 10 s, the third after 30 s.
- The count is the engine's, not each profile's: one start that restores three profiles counts once.
- A start that finds three restores within 10 minutes restores nothing (`gaveUp`) and forgets the profiles. A crash
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
  - `onStart` restarts the **same** `EngineLink`s, the control link's and each
    `ProfileSession`'s. Because each sends its
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
  - The engine loads the profile again and reconnects it, and the
    UI shows that as marker lines ("Restoring the session" above).
  - When the engine before it was stopped by Android in the background, the UI
    explains once that a battery saver or Android stopped Mudlet, and opens the
    app's page in Android's settings on "Battery settings" (`EngineStopped.kt`).
    It reads why from the `:engine` process's last `ApplicationExitInfo`
    (Android 11 and later): a low-memory kill, a signal, `REASON_OTHER`, excessive
    resource use, the freezer or an unknown reason; not a crash, an ANR, a force
    stop or an update, nor an exit before the device last started (the engine
    restores after a reboot too). Android 10 and earlier record no reason, so
    there any restore counts. "Battery settings" or "Not now" is kept as
    `engineStoppedAnswered` in the `ui` preferences, and it is never shown again;
    Back hides it until the next engine. The app does not ask for
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play allows only for a
    few kinds of app.
  - When the engine before it crashed (`REASON_CRASH`, `REASON_CRASH_NATIVE` or
    an ANR, since the device last started; `EngineCrash.kt`), the UI offers once
    to report it. "Report" opens Report a problem, where the player sees the
    report and chooses whether to send it; nothing is sent by the offer, and no
    crash is uploaded. "Report", "Not now" or closing it keeps that crash's time
    as `crashReportOffered` in the `ui` preferences, so each crash is offered
    once.
    Android 10 and earlier record no reason, so there it is never offered.
  - Host timings: the restarted engine is reachable 33 ms after it starts, and
    the JVM link reattaches 110 ms after the kill.
  - On a phone, expect a few hundred ms for the process fork and Qt library load.
    The self-test shows "loss->attach ms" and "UI start->hello ms".
  - Killing the UI process does not touch the engine: the notification stays and
    tells are still posted. Host test: with no UI attached, every line still
    reached Lua `onLine`.
- **Screen off for a long time.**
  - *Expected; check the heartbeat on the phone.*
  - While a game is open `:engine` is a foreground service, so it is not cached
    or frozen and keeps network access. Doze may still delay its timers, and the heartbeat's
    "largest gap" will show it.
  - The UI process is backgrounded and may be frozen. That is harmless, because
    it detached in `onStop` and the engine never waits for it.

### Which notification work is in which process
- **`:engine`:**
  - The foreground-service notification, which must come from the service
    calling `startForeground`. `ControlBridge` gives `AndroidNotifier::showProfiles`
    the open profiles and their connections whenever they change, and
    `EngineNotifications.showProfiles` names the one connected game, or counts
    them, and with several open lists each with its connection. With no game
    connected the title says so ("Achaea is not connected"), and the service
    stays in the foreground, as a disconnected game stays open with its scripts
    and timers, and Mudlet may reconnect it on its own, which Android 12 and
    later would not let a background service take back into the foreground.
    Its updates come at least a second apart, the last change always posted, as
    Android drops an app's updates that come faster than five a second.
    With no game open the service leaves the
    foreground and the notification goes; the next game to open brings both back,
    which Android allows because only the UI, while it is shown, opens one. Until
    the engine first says what it has open, as while a restore waits, the service
    stays in the foreground. A start request with no game open is still answered
    with `startForeground`, with a deferred notification that Android 12 and later
    never show, and then left. Out of the foreground, Android stops the service
    about a minute after the UI leaves; the UI starts it again when it is shown.
  - All "Tells" notifications: Lua `notify()` goes through `SocketBridge::notify`
    and `AndroidNotifier` (JNI) to `EngineNotifications.postTell`. They work
    while the UI is dead or frozen. A profile's tells carry its name as their sub
    text and are grouped under it, with a summary per profile.
  - The Quit action is a service `PendingIntent` handled in `onStartCommand`.
  - The counters are sent to the UI as statuses.
- **UI process:**
  - The `POST_NOTIFICATIONS` runtime request, which needs an Activity. A dialog says what the notifications are for
    first. The engine's notification posted before the user allows is dropped, so on Allow the UI sends the service
    another start, whose `startForeground` posts it.
  - The tap target: the notification's content intent opens `MainActivity`. A tell's
    names its profile (`EngineNotifications.EXTRA_PROFILE`): `EngineClient.showProfile`
    makes that profile the front, once the engine has listed it as open if the tap
    started the UI, and the game screen shows.
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

### Memory per profile
Each profile libmudlet opens beside another adds its own Lua state, Mudlet's Lua files, console and map to `:engine`.
The emulator smoke test opens three on one game and writes the engine's total PSS with one, two and three open to
`memory.json` in its output (CI's artifact), and prints it. On CI's x86_64 emulators (PR #92) each profile after the
first added about 10 MB, against an engine of about 100 MB with one:

| Emulator | One open | Two | Three | Each after the first |
|---|---|---|---|---|
| API 36 | 110,575 kB | 118,594 kB | 131,387 kB | about 10.4 MB |
| API 28 | 98,907 kB | 107,173 kB | 118,547 kB | about 9.8 MB |

So `ControlBridge::maxOpenProfiles` stays at 4: four profiles cost the engine about 30 MB more than one. A profile's
scripts, packages and map add to this, so measure on a phone with real games before raising it.

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
libraries deflated at zlib level 6, not an APK AGP built. CI builds no extracted variant: nothing installs or ships
one while the default stays off, so the APK that turns it on gets its own budget line and CI job then. MB here is
10^6 bytes, as in the CI summary, so the libraries' 20,419,784 bytes are 20.4 MB (19.5 MiB).

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
with the profile), connects anyway; the app has them as the three switches of the game's Security settings. The
engine then passes on the certificate refused (`certificateRefused`, `0024-headless-refused-certificate.patch`): its
names, expiry, serial number and the errors, and whether only "Ignore all certificate errors" would let it past. The
game screen shows them in a dialog. "Accept and connect" turns on the switch for each error ("Accept an expired
certificate", "Accept a self-signed certificate") and connects again; a certificate with any other error, such as one
for another server, offers the game's settings instead, so ignoring every error is never one tap from the dialog.
Nothing is accepted without the player, and what is accepted stays visible and can be turned off in the game's
Security settings. Back or Cancel accepts nothing, and the same certificate is not asked about again until a later
connection is refused (divergences.md). A secure connection needs the game's name, not an IP address, as Mudlet
refuses TLS to a raw address.
Until the next connection is tried, the game's Security page lists the refused certificate above the switches, as
desktop's Connection page does, and marks each switch that would let it past ("Lets the refused certificate through",
which TalkBack reads with the switch).

**The game's secure port.** A game connected without TLS may say in MSSP that it has a secure port. Mudlet then
asks, as its GUI does, whether to move there, unless the profile's "Offer a secure connection" (`askTlsAvailable`) is
off or its server is an address; the engine passes the question on (`secureOffer`) and the game screen asks it in a
dialog of the app's own words, as Mudlet's are not translated in the engine. "Connect securely" stores the port with
TLS on and connects there; "No, don't ask again" connects again where it was and turns the setting off, both as on
the desktop (`answerSecureOffer`). Back answers neither, and the offer is asked again with the game's next one
(divergences.md). The engine sends `profiles` after a yes, so the UI's next connect asks for the new port with TLS,
and the UI forgets the address it remembered for Reconnect.

On Android Qt does not read a certificate folder. The first TLS connection asks Java's default `TrustManagerFactory`
for its accepted issuers (`QtNative.getSSLCertificates()`), Android's system CAs, and Qt keeps them for the process.
Whether CAs the user installed are among them is for a device to show. On the desktop Qt reads the system's
certificate folders, such as `/etc/ssl/certs`.

**The protocol.** `connect` carries `tls`, which libmudlet passes to `THeadlessSession::Options::tls` when that
connect starts the profile; Mudlet stores it with the profile, as its connection dialog does. `THeadlessSession`
takes the choice at start, and from `setAddress()` once it runs, which the engine calls for a `saveProfile` of the
profile and for nothing else: a `connect` with a `tls` other than the one the running profile connects with is
refused with status `connection` `error: ...`, so a login is never sent over a connection the stored profile does
not ask for, and saving the profile with the other choice changes it. An engine restored after a kill starts
the profile with the choice it was started with (see "Restoring the session"). The self-test screen has a TLS box beside the port.

**Tests.** `native/tests/test_mudlet_engine.py` runs the real engine on the host, with the host's Qt and OpenSSL,
against `fake_telnet.py` behind Python's `ssl` with a certificate it makes with the `openssl` command:
- a self-signed certificate is refused, with Mudlet's reasons, no game text, and a handshake the server sees fail;
- TLS to the plain port fails as "Secure handshake failed";
- a `connect` without TLS to the running TLS profile is refused;
- after the profile's `mSslIgnoreSelfSigned` is set in its save, the same server's banner arrives and the game sees
  a TLS 1.x connection.
- the game's offer of its secure port (`fake_telnet.py`'s `mssp`) is passed on, lapses with its connection, and is
  answered: no reconnects and turns `askTlsAvailable` off, after which an offer is not passed on; yes stores the port
  with TLS on, says so in `profiles` and connects there over TLS.

CI's APK check (`.github/actions/check-apk`) looks in each APK for the files Qt will load: `libcrypto_3.so`,
`libssl_3.so` and Qt's OpenSSL plugin, that plugin's `_3` suffix, the SONAMEs, `libssl_3.so`'s dependency on
`libcrypto_3.so`, a few of the functions Qt resolves, OpenSSL 3's version string, and 16 KB alignment.

**Only a device can show** that the handshake works on Android: that Qt's loader loads both libraries, that the
plugin finds them, and that `supportsSsl()` is true. The same goes for the system CA store: whether Qt gets
Android's CAs, how long fetching them takes at the first connection, and whether a CA the user installed is
trusted.
Item 13 of the on-device checklist covers this.

## On-device checklist

For developers with `adb` and the debug APK. [`device-pass.md`](device-pass.md) is the plain-words version for
testers, and says which parts each kind of phone needs. A `lua ...` command typed on the game screen needs the `lua`
alias from its Part 2 first: the engine installs none of desktop Mudlet's default packages. The Self-test takes
`lua ...` itself.

1. Install the APK with libmudlet (CI's `mudlet-libmudlet-debug-apk`) with `adb install -r app-debug.apk`. Open it
   and allow notifications. It opens on "Profiles"; tap "Self-test" for the checks below (debug builds only: a release
   build has no self-test).
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
3. Check the service. With no profile open the line should read
   `FGS: foreground=false process=org.mudlet.mudlet:engine`, with no engine notification,
   and the two process ids should differ.
4. Connect to `aardmud.org:23`. Expect coloured output, a prompt and rising line
   counters, `FGS: foreground=true`, and a "Keeping Self-test connected" notification.
   - Disconnect: the notification reads "Self-test is not connected", and the line
     still `FGS: foreground=true`. Connect again and it says "Keeping" once more.
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
   - Expect the marker lines "profile restored, reconnecting to
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
    - Then press "Start engine". No profile comes back and the game is not called.
    - Repeat with the notification's Quit action.
11. Leave the phone with the screen off for 30 minutes or more, connected to the
    game.
    - Afterwards, check the heartbeat's largest gap and whether the game is
      still connected.
    - Also check whether tells arrived as notifications while the screen was off.
12. Measure memory with `adb shell dumpsys meminfo org.mudlet.mudlet:engine`, and
    the same for the UI process.
13. TLS.
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
    - With the game open and disconnected, edit it from the list: change the character name and password, Save, go
      back to the game and Reconnect: Mudlet logs in with the new ones, and the game's text so far is still there.
    - With the game open and connected, edit it from the list: change the port to another game's, Save and go back:
      the connection is as it was. Disconnect and Reconnect: the other game answers, below the text so far. Turn
      "Secure connection (TLS)" on for a game that offers it, Save and Reconnect: it connects.
    - With a game that has a character name and password open and connected, "Kill engine process" in the self-test:
      the game comes back, connects and logs in again.
    - With the system's dark theme on and off, every screen follows it, the window behind the first frame included.
    - At the system's largest font size, the start and game screens' text grows and nothing is cut off.
15. The game screen, connected to a game (Connect on one of the start screen's games).
    - The keyboard: tapping the input line opens it, and the console shrinks above it with its newest line still in
      view. Send on the keyboard sends the line and leaves the keyboard open, ready for the next. Note the keyboard's
      action key: it should read Send, and suggestions, corrections or a capital first letter should not change what is
      typed. Type half a word, then the up button: the earlier command replaces it whole, and typing goes on from the
      end of it.
    - The suggestions: send a few commands, then type the start of one. The rest of the best match shows greyed
      after the cursor, and nothing shows above the line. A tap on the greyed text, or a hardware keyboard's right
      arrow at the end of the line, puts it on the line without sending it; a tap on the typed text only moves the
      cursor, and a tap that gives the line the focus only opens the keyboard. Close the app from the recent apps and
      reopen it: the suggestions are still there.
    - The history: "History" below the line opens a sheet with the commands sent, best first, and the keyboard on
      its search field; typing "od" keeps "open door". A row's text puts the command on the line without sending it
      and closes the sheet; its Send button sends it. Pin one: it shows as a button below the line, which sends it
      at a tap, and it is still there after the app is closed from the recent apps and reopened. Unpin it from the
      sheet's Pinned section. With a hardware keyboard, Ctrl+R opens the sheet; Back closes it.
    - Scroll up while the game sends text: the console stays put and "Jump to the bottom: N new lines" counts up; the
      button goes back to the newest line, which the console then follows again. Rotate the phone while scrolled up,
      and again with a half-typed line: both stay. The same in split screen.
    - With TalkBack on: the input line reads as "Command, edit box"; the up, down and Send buttons read their names;
      the game pill reads "<profile>, <state>, game 1 of 1", and each change of the connection is read out once, with
      the profile's name first ("Achaea: Connection lost"), without moving focus.
      The line reads what was typed, then "Suggestion: ..." for the greyed text, and its actions (three-finger tap, or
      the reading controls) include "Accept the suggestion: ...", which puts it on the line. Swiping goes from the
      console's lines to the input line, then "Complete the word", the up, down and Send buttons, then
      the game pill, its chip if any, "History" and each "Pinned: ...". History moves the focus into the sheet: its title,
      "Close the history", "Search the history", then each row's command ("double tap to put it on the command line")
      and its "Send ...", "Pin ..." or "Unpin ..." and "Forget ..." buttons. Back closes it and the focus goes back.
    - Two games open (the game sheet's "Open another game", then Connect on a second profile): the pill shows "2" and
      reads "<profile>, <state>, game 2 of 2", with the actions "Reconnect to ...", "Disconnect from ..." and
      "Previous game: ..."; that action, or a row in the game sheet, brings the other game to the front, says its
      name and state, and puts the focus on its input line. Closing a connected game asks first; closing the last one
      goes back to the profile list. Turn off the network for a moment: the pill turns red, reads "Connection lost"
      and offers Reconnect, or "Reconnect now" when the profile reconnects by itself.
    - Three games open, TalkBack off: drag the pill sideways. Its name and the console follow the finger, the next
      game's name comes in from the right; let go past about 40% of the pill's width, or flick, and it switches with
      a tick; let go short and it springs back. At the first and the last game it only gives a little. The console
      still scrolls up and down, the quick bar still scrolls sideways, and a swipe in from the screen's edge is the
      system's Back. In Arabic (or ar-XB) it is mirrored. With a hardware keyboard, Ctrl+Tab and Ctrl+Shift+Tab switch.
16. The input line's engine features, connected to a game with the libmudlet APK.
    - At a password prompt (any game's login), the input line becomes a "Password" field with the keyboard's password
      layout, still open. TalkBack reads it as a password field and says "dot" or nothing for what is typed, never the
      letters. There is no greyed text and no quick bar while it shows, and Ctrl+R opens nothing.
      Send logs in; the field goes back to the command line, with what was typed there before, and the password is
      not among the suggestions or in the history sheet afterwards. Its close button gives the command line back
      without sending.
    - The up button walks the commands of the profile's earlier sessions too (close and reopen the app first).
    - Type the start of a word the game sent, then the Tab button (or Tab on a hardware keyboard): the word is
      completed, and again goes on to the next match. TalkBack reads "Complete the word, button", and "No completion"
      when nothing matches.
    - `lua printCmdLine("say hi")` puts "say hi" on the line. Type a command and start the next one at once: what
      was typed is not wiped when the game answers.
    - On the game's Input page turn "Clear the input line after sending" off, then send `look`: it stays on the line,
      selected. Send sends it again; a letter typed replaces it; the up button gives the command before it. A script
      window's command line keeps its command too. With TalkBack on, the line reads "Sent. Typing replaces it" after
      the first send and nothing more for each send after. `lua echo(tostring(getConfig("autoClearInputLine")))` says
      `false`. Turn it on again: the line empties after each send, as it does in a game just added.
    - Up gives the last command selected whole: a letter typed replaces it, and TalkBack reads "Selected. Typing
      replaces it". Turn "Highlight history" off on the Input page: Up puts the cursor after the command instead.
    - Set the Input page's command separator to `|`, press Done, then send `look|look`: the game gets two commands.
      Empty it and the same line goes as one.
    - Turn "Show passwords as you type them" on, then log in to a game that asks for a password: there is no masked
      field, and the password shows on the input line as typed.
    - Disconnect, then send a command: "Not sent: ..." shows over the foot of the console, above the keyboard, and
      TalkBack reads it without moving focus.
17. About and licences, with TalkBack on.
    - The start screen's top bar has "About and licences, button". The About screen reads the app's version and the
      engine (with the libmudlet APK: "Engine: Mudlet" and its version, and the commit), then the headings ("Help",
      "Free software", "Licences" and a heading for each group) as headings: TalkBack's headings navigation jumps
      between them.
    - Each Help link opens the browser at the page desktop Mudlet's Help menu opens: the manual, the Lua functions,
      the video tutorials, Discord, the live help chat and the forum.
    - Each source code button opens the browser at that source: this app's and Mudlet's at the commit shown, Qt's
      download page, and docs/licences.md. A row of the list reads its name, version and licence, and "double tap to
      read its licence"; its screen reads the full licence texts paragraph by paragraph, and Back returns to the list
      where it was.
    - Help's "Show changelog" opens the changelog: a heading for each stable release in docs/release-notes.md (which
      the build copies into the APK's assets) with its lines, or, before the first, a line saying there are none yet.
    - Far down the list and far into a licence text, swipe through: every element TalkBack lands on reads its text,
      including a line half scrolled off at the top (the emulator's accessibility check reports one there as
      unlabelled, which `AboutScreenTest` therefore does not check).
18. Text size and the screen size the game is told, connected to a game with the libmudlet APK.
    - Pinch the game's text with two fingers: it grows and shrinks smoothly, and one finger still scrolls. The
      settings button in the game screen's title bar opens "Settings", whose Appearance page's text size slider, "Smaller text" and
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
      screen reader; the answer shows in Settings, on the game's Accessibility page, as "Tell games I use a screen
      reader".
    - New text is read as it arrives, without moving focus. A burst (`look` in a busy room, or
      `lua for i = 1, 20 do echo("line " .. i .. "\n") end`) is read once, as its count and its last lines, not
      queued line by line. With "Read new text aloud" off on the game's Accessibility page, nothing is read.
    - On the game's Accessibility page, under "Blank lines from the game", choose "Hide them":
      `lua echo(getConfig("blankLinesBehaviour"))` says `hide`. After `lua setConfig("blankLinesBehaviour", "show")`,
      the page opened again shows "Show them". Close the game and open it again: the choice stays. A game added
      afterwards starts with the values of "New games"; a profile copied in from desktop Mudlet keeps its own.
    - Connected, turn GMCP off on the game's Connection page: the switch turns and a message says it takes effect
      the next time you connect, once however many protocols are turned while it shows. TalkBack reads it.
      Disconnected, no message shows.
    - On the game's Connection page, "Server data encoding" reads its choice (UTF-8 (Recommended) for a new game);
      double tap opens the list at it, and picking "ISO 8859-1 (Western European)" closes it:
      `lua echo(getServerEncoding())` says `ISO 8859-1`, a game in Latin-1 (accented letters) reads right at once,
      and the choice stays after the game is closed and opened again.
    - `lua announce("polite")` is read after what TalkBack is saying; `lua announce("now", "importantall")`
      interrupts it. The same announcement twice is read twice.
    - Swiping through the console moves line by line, and each line is read as its text, without colour or style
      names. Swiping past the game pill's row, and past the script windows and button bars when there are any,
      reaches the last announcement.
    - "Blank lines from the game": Hide them, then send a command whose answer has blank lines: they are gone from
      the console, and TalkBack does not stop on them.
20. Script windows, with the libmudlet APK and a game's own GUI package (a Geyser layout with a chat mini console and
    a docked user window, for example), in portrait and in landscape, on a phone and on a tablet or a foldable.
    - At 600 dp and wider the windows sit where the package put them, the console in the space it left, and the
      game's lines fill the console's width without wrapping early or leaving a gap (the game is told the console's
      grid). Narrower, the borders become bands above and below the console, and the side windows and docks become
      tabs below it; a tab opens its window over the console, its back button or the system's Back closes it.
    - A window's text is readable, its newest line in view; scrolled back, it stays put.
    - A console a script gave a scroll bar (`lua enableScrollBar("<name>")`) with more rows than fit has a thumb at
      its right edge, which moves as the rows scroll and scrolls them when dragged; `disableScrollBar` takes it away.
    - With TalkBack on: swiping goes from the console to the input line, then the game pill and the quick bar, then
      the windows, each read as "<name> window", then the tabs; a window's command line, its Send button and a user
      window's close button read their names.
    - A scroll box: `lua sb = Geyser.ScrollBox:new({name = "sb", x = 0, y = 0, width = 300, height = 150})
      Geyser.Label:new({name = "near", x = 0, y = 0, width = 300, height = 100, message = "near"}, sb)
      Geyser.Label:new({name = "far", x = 0, y = 300, width = 300, height = 100, message = "far"}, sb)
      setLabelClickCallback("near", function() echo("near tapped\n") end)
      setLabelClickCallback("far", function() echo("far tapped\n") end)`, on one line. A drag up that starts on
      "near" brings "far" into view and shows no "near tapped", and a tap on "far" shows "far tapped". With TalkBack
      on, the box is a scrollable node with Compose's scroll forward and back actions, and TalkBack's scroll forward
      on it does the same; swiping then reaches "far".
21. Labels and gauges, with the same package and the libmudlet APK.
    - Labels look as on the desktop: their colours, borders, rounded corners, text and its alignment, and images,
      scaled with their windows. A gauge's front follows its value.
    - A tap on a clickable label does what a click does on the desktop, a double tap what a double click does, and a
      long press opens a Geyser right-click menu and shows the label's tool tip. A slider made of labels follows a
      drag. A link in a label's text runs its command (item 30).
    - With TalkBack on: a clickable label reads as a button with its name, and its actions offer the right-click and
      its links; a label's text reads as text, an image alone not at all; a gauge reads its text, or its percentage.
      `lua news = Geyser.Label:new({name = "news", x = 0, y = 0, width = 200, height = 30, liveRegion = "polite"})`
      makes a blank live label, which swiping skips; `lua news:echo("a dragon!")`: TalkBack says "a dragon!" after
      what it is saying, without moving focus, and again for each new text; with `"assertive"` it says it at once.
      After `lua news:setAccessibleName("News")`, a new text is said after "News". A gauge whose text label is live
      (for a Geyser gauge `hp`, `lua hp.text:setLiveRegion("polite")`) says its new text, and nothing when only its
      value changes. `lua mp = Geyser.Gauge:new({name = "mp", x = 0, y = 40, width = 200, height = 20})
      mp:setValue(30, 200)`: TalkBack reads "mp", a progress bar at 15%, the value the desktop's screen reader reads;
      `lua setLabelAccessibleValue("news", 40)` makes the news label a progress bar read as "News", its state "a
      dragon!, 40%", and `lua clearLabelAccessibleValue("news")` makes it the label it was.
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
    - Turning "See-through map and text" off in the settings takes it away; on, it comes back. Opening a mapper
      (`lua myMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})`) takes it away
      too, and closing it (`lua myMapper:hide()`) brings it back.
    - The title bar's map button brings it to the front: a tap selects, a double tap walks there, a pinch and the zoom
      buttons zoom, and the edit switch edits it. A tap on a room shows its name, area, distance and exits at the
      bottom, with "Walk here"; the bar there lists the areas and moves between levels. "Find a room" with part of a
      room's name lists the rooms that match, how many, the best first, with the text marked; a tap on one centres the
      map on it and shows it, and its walk button walks there. The console's text shows faintly behind it where it draws
      no room or exit, and the rooms and exits stay clear against it; with the setting off it is opaque. The input line
      stays below it and no keyboard opens; a tap on the line opens it, and a command typed there is sent with the map
      still in front, as are History and the pinned commands. Back and the button again send it back, and a keyboard
      that went with the swap comes back; a predictive back gesture shrinks the map as it goes, and a cancelled one
      leaves it as it was.
    - The swap is at once both ways, with the setting on and off and with the keyboard open: the map is never blank,
      and neither moves nor changes size when the engine's frame for its new size comes; with the setting on the text
      is behind the map from the first.
    - With TalkBack on: the map behind the text is never read; the button reads "Map" and whether it is on; in front,
      the field, its results and the map read as the mapper window's does, then the input line, and the console under
      it is not read, though new lines are still said.
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
    - With TalkBack on: the button, the progress bar (as "Map download" and how far), Cancel and "Dismiss the map
      download" read their names; the end of the download is read out as it comes, a failure at once.
    - Over https (as most games' map URLs are), the download works: OpenSSL is in the APK (see "TLS").
    - `adb logcat` shows the map's host but never more of its URL: no user name, password, path or query.
27. Text boxes, with the libmudlet APK: `lua createTextEdit("notes", 0, 0, 300, 120) setTextEditPlaceholder("notes",
    "Write here")`, then `lua createTextEdit("tiny", 320, 0, 20, 20)`.
    - "notes" is a field with an outline, "Write here" in it; a tap brings up the keyboard, which starts a sentence with
      a capital. Type two lines, wait a moment, then `lua echo(getTextEditText("notes") .. "\n")` prints both. A
      long text scrolls inside the box.
    - `lua setTextEditText("notes", "from the script")` replaces what is in the field; rotating the phone keeps what
      was typed. `lua setTextEditStyleSheet("notes", "QPlainTextEdit { background-color: navy; color: yellow;
      font-size: 14pt }")` restyles it, scaled with its window.
    - `lua setTextEditReadOnly("notes", true)`: the keyboard no longer types in it, and the text can still be selected.
    - With a hardware keyboard, `lua setTextEditTabMovesFocus("notes", true)`: Tab moves on instead of typing a tab.
    - With TalkBack on: swiping reaches "notes" after the console's controls, read as "Write here" and an edit box;
      read-only, as "Write here, read only"; "tiny", touched easily though drawn small, reads as "tiny".
28. MXP frame tabs, with the libmudlet APK: `lua setConfig("specialForceMXPProcessorOn", true)
    feedTriggers([[<FRAME Name="tabs" Title="Tabs" Align="left" Width="300" Height="300">]] .. "\n")
    feedTriggers([[<FRAME Name="chat" Title="Chat" DOCK="tabs" Align="client">]] .. "\n")`, on one line.
    - Fitted (a tablet, or a phone in landscape): a row of tabs, "Tabs" and "Chat", along the frame's top, "Chat"
      selected. A tap on "Tabs" selects it and shows the frame's own console there, and a tap on "Chat" goes back.
    - Stacked (a phone in portrait): the frame is a tab of the window switcher; opened, its tabs are at the top of the
      pane, and a tap on the other keeps the pane open on that page.
    - With TalkBack on: swiping reaches "Tabs of Tabs" after the console's controls, then each tab read by its title
      as a tab, the one in front as selected.
29. A script window's text size, with the libmudlet APK: `lua win = Geyser.MiniConsole:new({name = "win", x = "50%",
    y = 0, width = "50%", height = "30%", wrapAt = "auto"})`, then `lua registerAnonymousEventHandler("sysFontChangeEvent",
    function(_, name, _, size) echo(name .. " " .. size .. "\n") end)` and `lua echo("win", string.rep("word ", 40))`.
    - Pinch the window with two fingers: its text grows or shrinks as the fingers move, the main console's does not,
      and once they lift `win <size>` shows on the main console and `lua echo(getFontSize("win"))` gives that size. New
      text in the window wraps to its new width. One finger still scrolls it, and a tap on a link in it still follows.
    - `lua win:setFontSize(8)` changes nothing that stays: the window goes back to the pinched size. Close and reopen the
      profile, or kill the app and start it again: the window comes back at the pinched size.
    - Tap the window twice with two fingers: it goes back to 8 (the size its script last gave it), and a later
      `lua win:setFontSize(12)` holds.
    - With TalkBack on: the window's actions offer "Larger text" and "Smaller text", which change it a size at a time,
      and once changed "Reset text size".
30. Label links, with the libmudlet APK: `lua createLabel("nav", 0, 0, 400, 40, 1) setLinkStyle("nav", "#00ff00",
    "#ff00ff") echo("nav", [[<a href="send:look">look</a> <a href="prompt:say ">say</a> <a
    href="https://www.mudlet.org/">site</a> <a href="echo('ran\n')">run</a>]])`, on one line.
    - A tap on "look" sends `look` to the game, through the aliases; on "say", the input line reads "say ", its space
      kept, and has the focus, with the keyboard open, as Mudlet's command line does; on "run", "ran" shows in the
      console. Each turns magenta once tapped, and green again after `lua clearVisitedLinks("nav")`.
    - A tap on "site" opens the page in the browser, and the link stays green.
    - With TalkBack on: the label's actions offer "Follow link: look" and the others, which do the same as the taps;
      after "Follow link: say", TalkBack is on the input line.

31. The script library, with the libmudlet APK: on the game screen, tap Scripts.
    - The triggers' chip shows how many there are; a folder opens with a breadcrumb back; a switch turns an item off
      and its row says "Off", and the trigger no longer fires. The game's last lines show at the foot, and a tap on
      them goes back to the game.
    - Tap New trigger: its page opens. Add a pattern "You see", tap in the Lua field at the page's end, type
      `echo("seen\n")` and Apply: "seen" shows after a line with "You see"; Undo puts back what it had. Type
      `if x then` and Enter: the next line is indented, the row of symbols shows over the keyboard, and nothing is
      said of the code yet. Type `sel`: functions are offered, and a tap completes one. Apply: "'end' expected"
      shows over the buttons, with Go to putting the cursor on its line, the page says why the trigger is not
      running, and the library's row does too. Type a key: the line over the buttons goes. Fill the field with thirty
      lines and type at its end: the page scrolls with the cursor, which stays over the keyboard.
    - Change the name and tap Game: the game shows; back in Scripts the item is marked "Draft" and its page has the
      change. Press Back there: a dialog asks to discard; Keep editing keeps the change. Delete: the item goes, with
      Undo bringing it back; let the snackbar go and reopen the library: it is gone.
    - Tap Timers, New timer: its page opens, and its row says "Off". Set Seconds to 5 and "Send to the game" to
      `look`, Apply, go back and switch it on: `look` is sent every 5 seconds, and the row shows "00:00:05.000".
      Switch it off again.
    - Tap Buttons, New toolbar: the library goes into it. Tap New button, set "Send when pressed" to `score`, Apply,
      go back, switch the button on, go up and switch the toolbar on: back in the game the toolbar shows at the top,
      and its button sends `score`. In the toolbar, tap Edit, choose "At the left" and Apply: it moves to the left.
    - Tap Keys, New key: tap "Key", choose F1, tap Control, Apply: the row shows "control + F1". The page says the
      app does not pass keys on yet.
    - Rotate on an item's page with a change not applied: the change is still there. On a tablet in landscape, an item
      opens beside the library.
    - With TalkBack on: the title bar's button reads "Scripts of" and the game's name; each row reads its name and
      state, its switch is read by the item's name, a pattern's type button reads "Type of pattern 1: Substring", and the
      move and remove buttons say which pattern.
    - In the game, long-press a line such as "A rat bites you.": a menu offers "Make a trigger from this line". Tap
      it, then tap "rat": it shows as "(rat)", the pattern becomes `A (\w+) bites you\.`, and the test says
      matches[2] is "rat" and how many of the console's lines match. Tap "Make trigger": the trigger's page opens
      with the pattern; type `echo("got " .. matches[2] .. "\n")` in its Lua and Apply: the next "A bat bites you."
      is followed by "got bat". Back goes to the game. A long press on a blank line opens nothing.
    - With TalkBack on: a line's actions offer "Make a trigger from this line"; on its page a word reads "Kept as it
      is", "Captured" or "Optional", and a double tap changes it.

32. The package manager, with the libmudlet APK: on the game screen, tap More options, then Packages.
    - The bundled packages are listed, each with its icon, title, version and author; a tap on one shows its whole
      description.
    - Tap "Install from a file" and pick a `.mpackage` from Downloads: "Installing" shows with a progress bar, then
      "Installed" in a snackbar, and the package is in the list; its scripts run. Pick it again: "already installed".
      Pick a text file: "Could not install" with Mudlet's reason.
    - Tap "Install from a URL" and give a package's `https://` address: the console says how it went, and the package
      is listed. `lua uninstallPackage("<name>")` on the input line: it goes from the list.
    - Tap Remove on a package: a dialog asks; Cancel keeps it, Remove takes it out, and its triggers and aliases go
      from the script library.
    - With TalkBack on: the title bar menu's item reads "Packages of" and the game's name; a row reads its title, details
      and description, and whether its description is cut short; Remove reads "Remove" and the package's name.

33. How the app looks, on Android 12 or later, in light and in dark mode.
    - The screens take the wallpaper's colours; turning "Colours from my wallpaper" off in the settings gives
      Mudlet's own (sandstone) at once, and on again the wallpaper's. The game's text keeps its colours either way.
    - Back from any screen, with the predictive gesture, shows the screen it goes back to as the finger moves; let go
      of early, it stays.
    - On a tablet or an unfolded foldable in landscape, Edit opens the editor beside the profile list, and About's
      components their licence beside the list; Back closes it, and folding the device moves an open editor to its own
      screen.
    - In a game's Settings, Connection: with GMCP turned off, after disconnecting and connecting again, the game sends
      no GMCP; on, it does again. Input: with "Show the commands sent" at None, what you send no longer shows in the
      game's text. Accessibility: with "Captions for sounds and music" on, a game's sounds (MSP or `playSoundFile`) are
      named in its text as they play. Advanced: with "Show script errors in the game's text" on, `lua error("x")`
      shows its error in the game's text. Security: with "Let the game play sounds and music" off, a game that sends
      GMCP `Client.Media` plays nothing.
    - On a tablet, Settings opens a page beside the list of pages, and a search's result opens its page there with the
      setting tinted for a moment.
    - With TalkBack on: the settings' section titles read as headings, and the editor beside the list has
      "Close <name>" at its top.

34. Several profiles, with the libmudlet debug APK: connect one game, then open two more beside it with the game sheet's
    "Open another game" (or each with `adb shell am broadcast -n org.mudlet.mudlet/org.mudlet.android.debug.SmokeTestReceiver
    --es cmd openAlongside --es profile <name> --es host <host> --ei port <port>`).
    - After each loads, note `adb shell dumpsys meminfo org.mudlet.mudlet:engine`'s TOTAL PSS, against
      "Memory per profile"; a real game's scripts and map weigh more than the smoke test's.
    - `lua raiseGlobalEvent("x")` in one reaches a handler for `x` in each of the others.
    - Press "Kill engine process": all three come back, the front one first and each other one about 2 s after the one
      before it, and the game shown is still the front one.

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
- **`pkill -f test_mudlet_engine.py` from a shell whose own command line contains that
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
| Engine desktop build and fake-server test | **pass** (rerun after the startup fixes). The stand-in engine's `test_engine.py` (since removed): 0 failures ([`c-desktop-test.txt`](decisions/hosting-results/c-desktop-test.txt)). The JVM test of the app's Kotlin client against the same engine also passes ([`c-jvm-test.txt`](decisions/hosting-results/c-jvm-test.txt)). |
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

From the Python client in the stand-in engine's `test_engine.py`, against the stand-in:

| | Result |
|---|---|
| ping | median 83 µs, p99 211 µs |
| `runLua` round trip | median 106 µs, p99 226 µs |
| send, game echo, line back (includes the telnet hop) | median 160 µs, p99 401 µs |
| engine CPU per line | 4.05 µs with a UI attached, 2.65 µs with none |
| engine alone, no UI attached | 908k lines/s through telnet, ANSI parsing and Lua `onLine` |
| wire size | about 146 B per coloured line |
| full replay of the 2000-line ring on attach | 44 ms |
