# Roadmap

What comes after the foundation ([`foundation-checklist.md`](foundation-checklist.md)). November 2026.

## Phase E: the first usable client

**Goal:** a player installs the app, adds their game, connects, and plays it as their main client: they read the game
in colour, type commands with history, log in with a stored password, and leave the app in the background without
losing the connection. A blind player does all of that with TalkBack. The self-test screen stays, behind a debug
entry.

Phase E runs one profile at a time and draws only the main console. What scripts draw elsewhere (mini consoles,
labels, gauges, the map) is Phase F.

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
| Links (MXP, OSC 8, `echoLink()`) | Ids in runs; `link()`, `followLink()`, `linksChanged` | Ids in runs only |
| Screen size for NAWS | `setScreenSize()`, `setMainWindowSize()` | `setMainWindowSize` (version 7), which regrids the main console the game is told of; the game screen sends the size that gives Mudlet's grid the console's, once the engine's `mainArea` has told it Mudlet's font |
| Default colours, font, wrap | `consoleColors*`, `consoleFont()`, `consoleWrap()` and their signals | No |
| Command history, completion | `commandHistory()`, `completeCommandLine()`, `completeCommandLineFromHistory()` | `history`, `complete` (version 3) |
| Password prompts | `hiddenInputWanted`, `submitHiddenInput()` | `hiddenInput`, `sendHidden`, `dismissHidden` |
| Scripts setting the input line | `commandLineTextRequested`, `setCommandLineText()` | `commandLine` |
| Script windows (Phase F) | `scriptWindows()`, `windowLines()`, `mainConsoleGrid()`, `setUserWindowGeometry()`, `closeUserWindow()`, `submitWindowCommandLine()` and their signals; `labelContent()`, `pointer()` | `windows`, `windowText`, `mainArea`, `windowCommandLine`, `getWindowText`, `placeUserWindow`, `closeUserWindow`, `sendWindowCommandLine` (version 7); `label`, `image`, `pointer`, `getImage` (version 8). The game screen draws the mini consoles and user windows, fitted or stacked with a window switcher, and their command lines, and the labels, with their style sheets, text, images and taps, and Geyser's gauges as progress bars; no maps or text boxes yet |
| Screen reader announcements | `announcementRequested` (Lua `announce()`) | No |
| Backgrounded app | `setApplicationActive()`, `setWindowMinimized()`, `saveAsync()`, `attentionRequested` | No |
| Notifications | `notificationRequested` | `notify`, posted by the engine |
| Reconnect | The profile's own "Reconnect automatically"; `connecting`, `connected`, `disconnected` | Status `connection`; restore after a kill |
| GMCP, MSDP, triggers, aliases, timers, Lua | Run inside the profile as on the desktop | `runLua`, `send` |
| Packages | `installPackageAsync()`; Lua `installPackage()`, `uninstallPackage()`, `getPackages()` | Through `runLua` |
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
     (`printCmdLine()`), and a masked field while the game hides input.
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
8. **Translatable strings.** Every user-visible string is in `strings.xml` from the first screen, so translation can
   follow without touching layouts.

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

`announceIncomingText`, `advertiseScreenReader` and `blankLinesBehaviour` are `setConfig()` keys, so the app can set
them through `runLua`. The announcing itself is GUI code, so the app does its own.

Requirements for every Phase E pull request, not only the screen-reader one:

- Every control has a label TalkBack reads; icon buttons have a content description; touch targets are at least
  48 dp.
- The UI tests run the Accessibility Test Framework checks (`enableAccessibilityChecks()` on the
  `AndroidComposeTestRule`, from Compose's `ui-test-junit4-accessibility`, Compose 1.8 and later), so CI fails on a
  missing label or a small target.
- No gesture is the only way to do something: pinch-to-zoom has the text size setting, long press has an
  accessibility action.
- Focus order is console, then input line, then the buttons; after a send, focus stays on the input line.

The screen-reader pull request adds:

- new game lines announced as they arrive while TalkBack is on and `announceIncomingText` holds, coalesced so a burst
  is read once, not queued line by line; a setting to turn it off;
- Lua `announce()` spoken, assertive or polite as the engine says;
- the console as a list TalkBack can move through line by line, each line its text without the styling, links as
  actions;
- a setting for "tell games I use a screen reader" (`advertiseScreenReader`), offered on first run when TalkBack is on;
- the masked password field, which TalkBack does not read aloud.

## Deliberately later

- **Phase F, what scripts draw:** mini consoles, user windows, labels, Geyser gauges and buttons (`scriptWindows()`,
  `windowLines()`, `labelContent()`, `pointer()`), button bars (`buttonBars()`), commands packages add
  (`addonCommands()`). Many games' GUI packages need these.
- The 2D map (`renderMap()`, `mapPointer()`), behind Phase F.
- A script editor for triggers, aliases, timers, keys and scripts.
- A package manager screen. Until then `lua installPackage("<url>")` works from the input line.
- More than one profile at once: upstream allows one profile pool per process, so it needs either several engine
  processes or upstream work.
- The game's secure-port offer (`secureConnectionOffered`), the dialogs for a bad TLS certificate, a proxy, logging,
  replays, the debug console, the error console as a screen, spell-check, Discord, IRC.
- Tablets' and Chromebooks' layouts beyond what a resizable phone layout gives, hardware keyboard bindings
  (`pressKey()`), a custom key bar.

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

The stand-in engine implements what it can, so `test_engine.py` and the JVM test cover the protocol path; the real
engine is covered by `test_mudlet_engine.py` and the emulator.

## Upstream gaps

Changes Mudlet needs, to go to Mudlet/Mudlet as pull requests. Until each is merged, the interim in the last column
holds.

| Gap | Needed for | Interim |
|---|---|---|
| **Line updates:** a stable id for each main console line in `lineShown`, a `linesUpdated` signal with a `LineUpdate` struct (id, text, runs, prompt, deleted) for a line changed or deleted after it was shown | A trigger's later `replace()`, `deleteLine()` on an earlier line, a link's text restyled | Lines already sent stay as sent (`architecture.md`, "Gaps") |
| **Profile management:** list the profiles in a config folder with their stored address, TLS, character name and auto-reconnect; delete a profile with its stored passwords; store and forget the character password; set auto-reconnect; Mudlet's list of predefined games | P1 without reading profile files directly; a password that survives an engine restore | The engine reads and writes the profile's `url`, `port`, `ssl_tsl`, `login` and `autoreconnect` files as `MudletEngine::profileReconnects()` reads one now, and deletes the folder; the UI keeps the password (Android KeyStore) and sends it with `openProfile`, so a restored engine has none until the player opens the profile |
| **Lua results:** `runLua()` giving the values the chunk returned (its error is in `errorString()` already) | Reading `getConfig()` settings and `getPackages()` into the UI | The app keeps its own copy of the settings it sets |

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
| P1 | Profiles over the protocol, both engines | #31 |
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

```
#31 ─┬─ U1 ─┬─ U2 ─┬───────────────── U4, U5, U6, U7, U8 (each also needs its P)
     │      └──────┼── U3
     └─ P1 ────────┴── P2 ── P3 ── P4 ── P5
```

P1 can run beside U1 and U2, and U3 beside U2 once P1 is in. U4 to U7 all change the game screen, so they go one at
a time in the order listed; U8, the notification and the app's lifecycle, can run beside them.

**How each is verified:** CI only. The protocol pull requests through `ProtocolSpecTest`, the JVM test against the
stand-in, `test_engine.py`, and `test_mudlet_engine.py` against libmudlet on the host; the app pull requests through
Compose UI tests against `FakeEngine` on the API 28 and 36 emulators, with the accessibility checks, and
`android_smoke.py` with libmudlet. What only a person can judge, TalkBack's speech and the keyboard's feel on a phone,
goes on the on-device checklist in [`architecture.md`](architecture.md#on-device-checklist), item by item, in the pull
request that adds it.
