# Roadmap

What comes after the foundation ([`foundation-checklist.md`](foundation-checklist.md)). November 2026.

## Phase E: the first usable client

**Goal:** a player installs the app, adds their game, connects, and plays it as their main client: they read the game
in colour, type commands with history, log in with a stored password, and leave the app in the background without
losing the connection. A blind player does all of that with TalkBack. The self-test screen stays, behind a debug
entry.

Phase E runs one profile at a time and draws only the main console. What scripts draw elsewhere (mini consoles,
labels, gauges, the map) is Phase F ([`phase-f.md`](phase-f.md)).

## What the engine can do already

Upstream Mudlet's `THeadlessSession` (`src/headless/THeadlessSession.h` on `development`) covers more than this
repository uses. What Phase E needs, and where it stands:

| Feature | `THeadlessSession` | Over the protocol today |
|---|---|---|
| Create a profile from an address | `Options::profile`, `host`, `port`, `tls` (stored with the profile) | `saveProfile` (version 2) |
| Character name for auto-login | `Options::login` (stored) | `saveProfile` `login` |
| Password for auto-login | `Options::password`, for that run only; a stored one is read from the credential store (the KeyStore on Android) as the profile loads | `openProfile` `password`, for that run only |
| List, delete profiles; store or forget a password | None | `listProfiles`, `deleteProfile`; no stored password |
| Switch profile | One profile pool per process: `shutdownAsync()`, then `startAsync()` | `openProfile` closes the running one |
| Lines with colours and styles | `lineShown`, `localTextStyled`, `mainConsoleCleared` | `line`, `tail`, `clear` |
| A line changed after it was shown | None: `lineShown` sends each line once | No |
| Links (MXP, OSC 8, `echoLink()`) | Ids in runs; `link()`, `followLink()`, `linksChanged` | `getLink`, `followLink`, `linksChanged` (version 5); a script window's, with `window` (version 12). The game screen follows the main console's and a script window's links by tap, menu and TalkBack's actions |
| Screen size for NAWS | `setScreenSize()`, `setMainWindowSize()` | `setScreenSize` (version 4), the console's columns and rows the game is told; `setMainWindowSize` (version 7), the main window's size in pixels that scripts see: the game screen sends the size that gives Mudlet's grid the console's, once the engine's `mainArea` has told it Mudlet's font. Once a profile has that size, the engine gives the console's columns and rows to `setGameScreenSize()` (a carried Mudlet patch), so a later `setScreenSize` tells the game without resizing the main window |
| Default colours, font, wrap | `consoleColors*`, `consoleFont()`, `consoleWrap()` and their signals | Colours only: status `consoleColors` |
| Command history, completion | `commandHistory()`, `completeCommandLine()`, `completeCommandLineFromHistory()` | `history`, `complete` (version 3) |
| Password prompts | `hiddenInputWanted`, `submitHiddenInput()` | `hiddenInput`, `sendHidden`, `dismissHidden` |
| Scripts setting the input line | `commandLineTextRequested`, `setCommandLineText()` | `commandLine` |
| Button bars and commands (Phase F) | `buttonBars()`, `pressButton()`, `buttonsChanged`; `addonCommands()`, `activateAddonCommand()`, `addonCommandsChanged` | `buttonBars`, `commands`, `pressButton`, `activateCommand` (version 9). The game screen draws the bars around the console and their buttons, folders' menus and push-down buttons, and lists the commands in an Extensions menu; the bars and buttons draw their style sheets' touch subset |
| The 2D map (Phase F) | `renderMap()`, `mapPointer()`, `setMapViewOnly()`, `mapRepaintRequested`, `mapViewOnlyChanged` | `map`, `mapPointer`, `setMapViewOnly` (version 10). The game screen draws it in the mapper window or, docked, as a page; a tap selects a room, a double tap walks there, a pinch or the zoom buttons zoom, and a switch edits it. The app's own map, with a Mudlet patch until merged (`renderAppMap()`, `appMapPointer()`, `showRoomOnAppMap()`, `findRooms()`, `closeAppMap()`): `setAppMapSize`, `appMap`, `appMapPointer`, `findRooms`, `rooms`, `showRoom` (version 11), behind the console's text and, from the title bar, in front with a room search |
| The map a game offers | `offeredMapUrl()`, `downloadOfferedMap()`, `cancelMapOperation()`, `offeredMapChanged`, `mapProgress*`, `mapDownloadFinished`, with a Mudlet patch until merged | `mapDownload`, `downloadMap`, `cancelMapDownload` (version 13). The app's map offers the download, with its progress, Cancel and how it ended; only the URL's host is shown |
| Script windows (Phase F) | `scriptWindows()`, `windowLines()`, `mainConsoleGrid()`, `setUserWindowGeometry()`, `closeUserWindow()`, `submitWindowCommandLine()` and their signals; `labelContent()`, `pointer()`; `textBoxContent()`, `setTextBoxText()`, an MXP frame's `frameTabs` and `selectFrameTab()`, `followLabelLink()`, with Mudlet patches until merged | `windows`, `windowText`, `mainArea`, `windowCommandLine`, `getWindowText`, `placeUserWindow`, `closeUserWindow`, `sendWindowCommandLine` (version 7); `label`, `image`, `pointer`, `getImage` (version 8); `textBox`, `setTextBoxText` (version 14); `selectFrameTab`, with `tabs`, `tabTitles` and `tabCurrent` in `windows` (version 16); `followLabelLink` (version 22). The game screen draws the mini consoles and user windows, fitted or stacked with a window switcher, and their command lines, their rows read by TalkBack as plain text and their links followed, and the labels, with their style sheets, text, images, taps and links, and Geyser's gauges as progress bars, and the map (the row above), and the text boxes, typed in by the player, and an MXP frame's tabs, a tap bringing its page forward |
| Screen reader announcements | `announcementRequested` (Lua `announce()`) | `announce` |
| Backgrounded app | `setApplicationActive()`, `setWindowMinimized()`, `saveAsync()`, `attentionRequested` | `setAppState` (version 6), which saves when the app leaves the front; `attention` |
| Notifications | `notificationRequested` | `notify`, posted by the engine |
| Reconnect | The profile's own "Reconnect automatically"; `connecting`, `connected`, `disconnected` | Status `connection`; restore after a kill |
| GMCP, MSDP, triggers, aliases, timers, Lua | Run inside the profile as on the desktop | `runLua`, `send` |
| Packages | `installPackageAsync()`, `installedPackages()`, `uninstallPackage()`, `packagesChanged`, with a Mudlet patch until merged | `packages`, `installPackage`, `uninstallPackage`, `packagesChanged`, `packageInstalled` (version 28). The package manager, from the game's title bar, lists, installs from a file or a URL, and removes |
| Lua results | `runLua()` answers only whether the chunk ran | Status `luaResult`: `ran` or `refused: ...` |

## Features

The first usable client has:

1. **Profiles.** A list of the player's games, each with its name, host, port, TLS, character name, password and
   "reconnect automatically". Add, edit, delete (with a confirmation), connect. The last-used profile is first.
2. **Game screen.**
   - The main console: scrollback (the 2000 lines the app keeps now), ANSI and 24-bit colours and styles, the
     prompt as the tail. It follows new lines while at the bottom; scrolled up, it stays put and offers a "jump to
     the bottom" button with a count of new lines.
   - The input line: Send on the keyboard's action key, the keyboard staying open after a send, history (the
     engine's, so the profile's earlier sessions count), Tab completion as a button, the text a script sets
     (`printCmdLine()`), and a masked field while the game hides input. Suggestions as the player types, since typing
     is the slowest part of playing on a phone: history matched loosely ("gc" for "get coins from corpse"), the
     command that usually comes next, and words from the game's output (`:suggest`, architecture.md, "Command
     suggestions"). A searchable history (the History button, or Ctrl+R on a hardware keyboard) and up to 12 pinned
     commands as one-tap buttons below the line.
   - The soft keyboard: the console shrinks above it and keeps its bottom line in view; rotation and split screen
     keep the scroll position and the typed text.
   - A connection bar: connecting, connected, disconnected, with Reconnect and Disconnect.
3. **Links.** A tap follows a link; a long press shows its menu when it has one; the tooltip is the label a screen
   reader reads.
4. **Text size.** A setting, and pinch-to-zoom on the console with the setting as its accessible equivalent. It
   follows the system font scale. The columns and rows that fit go to the game (NAWS).
5. **Screen reader output** (see the next section).
6. **Background running.** The engine keeps the connection in its foreground service, as now. Its notification shows
   the profile and the connection state, opens the game screen when tapped, and has Disconnect. The engine is told
   when the app is in the background, so Mudlet's "notify on new data while minimized" and
   `sysApplicationFocusChangeEvent` work, and it saves the profile as the app goes to the background, since the system
   may then kill it.
7. **Reconnect.** Mudlet's own "Reconnect automatically", per profile; a Reconnect button; the restore after a
   killed engine, which the foundation has.
8. **Translatable strings.** Every user-visible string is in `strings.xml` from the first screen, with a comment for
   translators, and Crowdin translates them as it does Mudlet's ([`translations.md`](translations.md)); CI fails on
   text written into the Kotlin, and the UI tests run the screens in the pseudo-locales en-XA and ar-XB.

### TalkBack, a first-class requirement

Mudlet's desktop app has a large community of blind players, and its support for them is part of the engine and the
GUI:

- the main console is exposed to screen readers (`TAccessibleConsole`, `TAccessibleTextEdit`);
- `announceIncomingText` (on by default) has new game lines announced as they arrive (`TTextEdit`), only for the
  profile in front and only while a screen reader is active;
- Lua `announce(text, style)` asks the screen reader to say something;
- `advertiseScreenReader` tells the game a screen reader is in use (the MTTS bit and NEW-ENVIRON `SCREEN_READER`);
- `blankLinesBehaviour` (show, hide or replace blank lines with a space), and `caretShortcut`, a key that moves
  between the input line and the console, "for visually impaired players" as `Host.h` puts it.

`announceIncomingText`, `advertiseScreenReader` and `blankLinesBehaviour` are `getConfig()` and `setConfig()` keys, so
the app reads and sets each game's with `getSettings` and `setSettings`. The announcing itself is GUI code, so the app does its own.

Requirements for every Phase E pull request, not only the screen-reader one:

- Every control has a label TalkBack reads; icon buttons have a content description; touch targets are at least
  48 dp.
- The UI tests run the Accessibility Test Framework checks (`enableAccessibilityChecks()` on the
  `AndroidComposeTestRule`, from Compose's `ui-test-junit4-accessibility`, Compose 1.8 and later), so CI fails on a
  missing label or a small target.
- No gesture is the only way to do something: pinch-to-zoom has the text size setting, long press has an
  accessibility action.
- Focus order is console, then input line, then the buttons; after a send, focus stays on the input line.

A known gap, the toolkit's: an empty text field with a label (Material 3's `OutlinedTextField`: the profile editor's,
the settings' and the script library's) is named to TalkBack only by its label, a child of the field, since Compose
gives the field's own accessibility node its text, which is empty. The field has 8 dp over its outline, above the
label; while only that strip shows at the foot of a scrolling page, the label is left out with what is out of sight,
and the field is an edit box with no name. The Accessibility Test Framework finds it there (`SpeakableTextPresentCheck`),
which is why `ScriptLibraryTest` checks a script's page at its end. Compose's `hintText` semantics, which reaches
TalkBack as the field's hint, is not in the Compose BOM the app is pinned to (1.10); once it is, set it to the label on
every labelled field, and drop that scroll from the test.

The screen-reader pull request adds:

- new game lines announced as they arrive while TalkBack is on and `announceIncomingText` holds, coalesced so a burst
  is read once, not queued line by line; a setting to turn it off;
- Lua `announce()` spoken, assertive or polite as the engine says;
- the console as a list TalkBack can move through line by line, each line its text without the styling, links as
  actions;
- a setting for "tell games I use a screen reader" (`advertiseScreenReader`), offered on first run when TalkBack is on;
- the masked password field, which TalkBack does not read aloud.

## Deliberately later

What Phase E left for later, and everything else the app still needs, is planned lane by lane in
[`plan.md`](plan.md), with a status table of the lanes and their pull requests.

## Protocol changes

Each change follows "Versioning and compatibility" in [`protocol.md`](protocol.md): `docs/protocol.md`,
`SocketBridge`, `Protocol.kt` and `native/tests/protocol_client.py` in one commit, with `ProtocolSpecTest` green. A
pull request that adds a request the UI depends on raises the version by one; version numbers are cheap, as the UI
and engine ship in one APK.

| PR | Adds | Version |
|---|---|---|
| P1 | `listProfiles`, `saveProfile`, `deleteProfile`, `openProfile` requests; a `profiles` message; `openProfile` carries the password for that run, which neither engine logs nor writes to disk | 2 |
| P2 | `hiddenInput` message, `sendHidden` (not logged) and `dismissHidden` requests; `history` message; `commandLine` message (a script set the input line); `complete` request, answered by status `completion` | 3 |
| P3 | `setScreenSize` (columns, rows) in place of `setViewWidthPx`; status `consoleColors`; `announce` message | 4 |
| P4 | `getLink` request and `link` message; `followLink` request; `linksChanged` message | 5 |
| P5 | `setAppState` (active, minimized), which also saves the profile when the app leaves the front; `attention` message | 6 |

`test_mudlet_engine.py` and the JVM test cover the protocol path against libmudlet on the host, and the emulator
jobs the engine on Android.

## Upstream gaps

Changes Mudlet needs, to go to Mudlet/Mudlet as pull requests. Until each is merged, the interim in the last column
holds.

| Gap | Needed for | Interim |
|---|---|---|
| **Line updates:** a stable id for each main console line in `lineShown`, a `linesUpdated` signal with a `LineUpdate` struct (id, text, runs, prompt, deleted) for a line changed or deleted after it was shown | A trigger's later `replace()`, `deleteLine()` on an earlier line, a link's text restyled | Lines already sent stay as sent (`architecture.md`, "Gaps") |
| **Profile management:** list the profiles in a config folder with their stored address, TLS, character name and auto-reconnect; delete a profile with its stored passwords; store and forget the character password; set auto-reconnect; Mudlet's list of predefined games | P1 without reading profile files directly; a password that survives an engine restore | The engine reads and writes the profile's `url`, `port`, `ssl_tsl`, `login` and `autoreconnect` files as `MudletEngine::profileReconnects()` reads one now, and deletes the folder; the UI keeps the password (Android KeyStore) and sends it with `openProfile`, so a restored engine has none until the player opens the profile |
| **Lua results:** `runLua()` giving the values the chunk returned (its error is in `errorString()` already) | Reading a chunk's values into the UI | `getConfig()` settings have `THeadlessSession::settings()` instead, and `getPackages()` `installedPackages()` (`scripts/mudlet-patches`) |

The Lua 5.1 FORTIFY fix stays carried in `scripts/build-libmudlet.sh` (README, "CI") until Mudlet takes it.

## Pull request sequence

Two tracks. Protocol and engine pull requests (P) touch `native/`, `protocol/` and `docs/protocol.md`, so they go one
at a time. App pull requests (U) touch `app/`; those that only add new files can run beside each other. All of them
come after #31, whose fake engine (`FakeEngine`) and emulator jobs run the app's UI tests.

| PR | What | Needs |
|---|---|---|
| E0 | This roadmap | - |
| U1 | App structure: navigation between a profile list placeholder, the game screen and the self-test (debug); theme; `strings.xml`; the accessibility checks in the UI tests | #31 |
| U2 | Game screen: console, follow and jump to bottom, input line with local history, soft keyboard, connection bar with Reconnect | U1 |
| P1 | Profiles over the protocol | #31 |
| U3 | Profile list and editor; password kept in the Android KeyStore and sent with `openProfile`; the self-test and smoke test connect through a profile | U1, P1 |
| P2 | Input line protocol | P1 |
| U4 | Hidden input, engine history, script-set input line, completion | U2, P2 |
| P3 | Screen size, console colours, announcements | P2 |
| U5 | Text size and pinch-to-zoom; columns and rows to the engine | U2, P3 |
| U6 | Screen reader output: announcements of new lines and `announce()`, the console's semantics, the settings | U2, P3 |
| P4 | Links protocol | P3 |
| U7 | Links: tap, menu, accessibility actions | U2, P4 |
| P5 | App state and save on background | P4 |
| U8 | Background: notification with profile and connection state, Disconnect action, app state to the engine | U2, P5 |
| S1 | Command suggestions' ranking core, `:suggest`: plain Kotlin, no UI | - |
| S2 | Suggestions in the input line: ghost text that a tap, the right arrow or TalkBack's action accepts, learned and saved per profile on the device, none while input is hidden | U4, S1 |
| S3 | History search and pinned quick buttons: a sheet that searches the profile's history and sends, pins or forgets a command; pinned commands as buttons below the input line, saved per profile; neither while input is hidden | S2 |

```
#31 ─┬─ U1 ─┬─ U2 ─┬───────────────── U4, U5, U6, U7, U8 (each also needs its P)
     │      └──────┼── U3
     └─ P1 ────────┴── P2 ── P3 ── P4 ── P5
```

P1 can run beside U1 and U2, and U3 beside U2 once P1 is in. U4 to U7 all change the game screen, so they go one at
a time in the order listed; U8, the notification and the app's lifecycle, can run beside them.

**How each is verified:** CI only. The protocol pull requests through `ProtocolSpecTest`, and the JVM test and
`test_mudlet_engine.py` against libmudlet on the host; the app pull requests through
Compose UI tests against `FakeEngine` on the API 28 and 36 emulators, with the accessibility checks, and
`android_smoke.py` with libmudlet. What only a person can judge, TalkBack's speech and the keyboard's feel on a phone,
goes on the on-device checklist in [`architecture.md`](architecture.md#on-device-checklist), item by item, in the pull
request that adds it.
