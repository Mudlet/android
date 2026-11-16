# Several games at once

Status: in progress, November 2026; the pull request plan at the end says what lands in which order. P1 has landed
protocol 15 and the stand-in engine runs several profiles. E1 has libmudlet run several, each in a `THeadlessSession`
of its own sharing the pool (UP1, carried as `0007-headless-shared-pool.patch`), restored together. U1 has the app
attach to the control socket and to each open profile's socket, with a `ProfileSession` for each. U2 has the game
pill and its sheet: several games open, switching, closing, "Open another game", the TalkBack actions and
announcements, and the profile list's "Open games"; the swipe (U3) and the badges (U4) are still to come. N1 has the engine notification list the open games, files each
game's tells under it, opens the game a tell's tap is about, and leaves the foreground with no game open.

The aim: several profiles open at once, each with its own connection, and a profile button that lists them on a tap and
moves to the next or the previous one on a horizontal swipe, as Firefox for Android does with its tabs.

## Engine: one process, one session per profile

Upstream already holds several `Host`s in one pool: `HostManager::openProfile()` takes a name,
`requestProfileClose(name)` closes one, `TNullAppFrontend::setActiveProfileTab()` moves the front and raises
`sysProfileFocusChangeEvent`, and most `THeadlessSession` signals carry `const QString& profile`. What stops it is the
session:

- `THeadlessSession::prepareStart()` ("One pool a process") refuses a start while `HostManager::self()` exists, so a
  second session cannot open anything.
- Every call (`submit()`, `runLua()`, `setScreenSize()`, `renderMap()`, `pointer()`, ...) acts on the session's one
  `mpHost`, which 188 lines of `src/headless/*.cpp` use.
- `shutdown()` and `shutdownAsync()` close the whole pool, and `~THeadlessSession()` deletes it.

The plan is one `THeadlessSession` per profile, all sharing the process's pool. Each session keeps its one `mpHost`
and its API. The upstream change (Mudlet/Mudlet, `src/headless/THeadlessSession.{h,cpp}` and `src/HostManager.h`):

1. `prepareStart()` joins an existing pool when `Options::configDir` matches the one it was settled with, returns
   `invalidConfigDir` otherwise, and refuses only while the pool is closing.
2. `mpPool` becomes a `std::shared_ptr<HostManager>` held by each session, with a file-static `std::weak_ptr`; the
   pool goes with the last session.
3. `HostManager::setProfileOpenedHook()` replaces the hook it held, so it becomes an add/remove pair, or one
   dispatcher that routes by `Host*`.
4. `shutdown()`, `shutdownAsync()` and `~THeadlessSession()`: a session that is not the last closes only its own
   profile (`requestProfileClose()`, then waits for its save). `StartResult::poolClosing` applies only to the last.
5. A save asked while another session's save runs joins it; `save()` already writes every profile in the pool.
6. `setApplicationActive()` keeps its state in the pool, so `sysApplicationFocusChangeEvent` is raised once, not once
   a session. `setWindowMinimized()` stays per session: each relays its own profile's `attentionRequested`.
7. Optional: `setInFront()`. Until then Lua `setActiveProfile("<name>")` through `runLua()` does the same.

What this costs: each extra profile adds its Lua state, mudlet-lua, console and map, estimated at 15-25 MB PSS; the emulator
smoke test measures it ([`architecture.md`](architecture.md), "Memory per profile"). As on the desktop, `raiseGlobalEvent()` and synced modules work between profiles, a busy
script stalls every profile, and a kill takes them all; the restore brings them all back.

**Rejected: one engine process per profile** (`EngineService1..N`, each with its own `:engineN` process). It needs no
upstream change and isolates a crash to one game, but:

- Each game costs 45-65 MB PSS more (the libmudlet engine is 80 MB idle and 98 MB after a flood,
  [`c-device.md`](decisions/hosting-results/c-device.md); only read-only `.so` pages are shared), and a Qt start of
  165-237 ms.
- N is fixed in the manifest, and each process needs a foreground notification of its own.
- N processes write one config dir (`Mudlet.ini`, `profile-order.json`), which needs cross-process locks.
- `raiseGlobalEvent()` and module sync don't cross processes.

With a socket per profile (below), the UI does not depend on which layout serves it. The stand-in engine ran several
profiles before libmudlet did, so the protocol and the UI landed and were tested first.

## Protocol (version 15)

Version 15, after the text boxes' 14. **A socket per profile, not profile ids on frames:** flow control is per
connection (4 MiB high water), so on one connection a flooding background game would put the front game into lag.
A socket per profile keeps the ring, the replay on attach, the lag handling and `EngineLink` as they are, per profile.

- **Control socket**, `engine.sock` as now. It carries `hello`, `ping`, `listProfiles`, `saveProfile`,
  `deleteProfile`, `setAppState`, the engine-wide statuses (`enginePid`, `platform`, `appState`, the heartbeat,
  `save`) and the messages below.
- **Profile sockets**, `engine-p<slot>.sock` beside it. `slot` is the lowest free number from 1, which keeps the path
  well under `sun_path`'s 108 bytes whatever the profile's name. Each speaks today's per-profile set unchanged
  (`connect`, `send`, `line`, `status`, `notify`, `windows`, `map`, ...). Its `hello` reply adds `profile`, and
  `instance` is `<pid>-<ms>-<slot>`, so a slot reused for another profile replays from scratch. The same
  `UserAccessOption` and `SO_PEERCRED` checks apply.

New on the control socket:

| Message | Dir | Fields | Meaning |
|---|---|---|---|
| `openProfile` | UI→E | `name`, `password`?, `connect`?, `alongside`?, `id`? | With `alongside`, starts the profile beside the others; one already open stays as it is. Answered by `openProfiles`, or `error` `openProfile: at most 4 open`. Without it, the others close first, as in version 14. |
| `closeProfile` | UI→E | `name`, `id`? | Saves and closes that profile, with its `sysExitEvent`; its socket closes. Answered by `openProfiles` or `error`. |
| `setFront` | UI→E | `name` | The profile the player is looking at: raises `sysProfileFocusChangeEvent`, as a desktop tab change does. |
| `markSeen` | UI→E | `name`, `seq` | The player has seen that profile's lines up to `seq`: clears `attention`, and `unread` counts from there. |
| `openProfiles` | E→UI | `profiles`: `name`, `slot`, `socket`, `state`, `connection`, `lastSeq`, `unread`, `attention`, `front`; `re`? | Every open profile, in the order opened. `state` is `starting`, `loaded`, `closing` or `restoring`. `attention` is set by `alert()`, the bell or new data while minimized, until seen. Sent on attach and on every change (line counts coalesced to every 500 ms), and as the answer to `openProfile` and `closeProfile`. |

The profile opened last becomes the front; when the front closes, the next one to the right takes over, else the one to
the left. `unread` lives in the engine because the engine sees every line while the UI is detached.

**Compatibility.** The app keeps working unchanged until U1. `hello` gains `profileSockets`. A UI that leaves it out
gets version 14's behaviour on the control socket: the front profile's lines, statuses and windows, and its profile
requests go to the front profile. With `profileSockets` true, the control socket carries only the control traffic, and a profile request sent
there gets `error`, as a control request sent on a profile socket does.

## The switcher

- **The profile button**, the game pill, sits at the start of the quick bar's row, above the keyboard; the separate
  connection bar is gone. It shows the front profile's name, its connection state as an icon whose shape differs per
  state, and a chip with the number of open games, with a dot when another game has unread lines or wants attention.
  Reconnect and Disconnect, which act on the front profile, are in its sheet; while the game is not connected, one
  chip beside the pill offers what the state needs (Connect, Reconnect, Reconnect now, Try again, or Stop while it
  connects).
- **Tap** opens a bottom sheet with a row per open profile: name, state, an unread badge ("99+" past 99), a bell for
  `attention` and a close button. "Open another game" at the foot goes to the profile list, where Connect sends
  `openProfile` with `alongside` while a game is open. At the cap it says "Close a game to open another (at most 4)". It is 4: the emulator
  measures about 10 MB per profile after the first ([`architecture.md`](architecture.md), "Memory per profile"); raise
  it once that is measured on a phone with real games.
- **Swipe** on the button moves its label with the finger and cross-slides the console. Past 40% of the button's width,
  or on a fling, it commits with a haptic tick; at either end it rubber-bands, with no wrap-around. It mirrors in
  right-to-left layouts; a drag from the system's back-gesture inset belongs to the system. A hardware keyboard gets
  Ctrl+Tab and Ctrl+Shift+Tab.
- **TalkBack.** The button reads "Aardwolf, connected, game 2 of 3, 5 new lines in other games", with the custom
  actions "Next game: Achaea" and "Previous game: Aardwolf", so the swipe is never the only way. After a switch a
  polite live region says "Achaea, connected" and focus goes to the input line. New lines are read only for the front
  profile; a background `announce()` is read politely with the profile's name first, and a background `attention`
  once as "New activity in Achaea".
- **Back** on the game screen goes to the profile list, which gains an "Open games" section; it never walks the games.
  Turn on `android:enableOnBackInvokedCallback` for the predictive preview.
- **Closing a game** asks first when it is connected ("Close Achaea? This disconnects from the game and saves the
  profile."), then sends `closeProfile`. Disconnect leaves the profile open with its scrollback and scripts. With the
  last game closed, the engine leaves the foreground until something opens.
- **Per-profile state:** `EngineClient` becomes a registry of `ProfileSession`s by name, each with its `EngineLink`,
  lines, statuses, history, input draft, scroll position, script windows and map. While the Activity is started the
  UI attaches to every open profile's socket, so switching is instant. Text size and the other settings stay global.

## Interactions

- **The map.** Each profile's `map` and `appMap` come on its own socket and belong to its `ProfileSession`. Only the
  front profile is asked to render, so background games draw nothing. Whether the map is in front is per screen: after
  a switch it stays in front, with the new game's map, or its offer to download one where it has none.
- **App state.** `setAppState` stays on the control socket. `active` false raises `sysApplicationFocusChangeEvent`
  once in every profile and saves them all; `minimized` goes to every session, so "notify on new data while minimized"
  shows as `attention`.
- **Saving.** One `save()` writes every profile of the pool; status `save` on the control socket names any profile not
  saved.
- **The restore.** `SessionStore` (`engine-session.json`) keeps every open profile, with its address, TLS and whether
  its connection is wanted, and the front. A restore reopens the front first, then each of the others 2 s after the one
  before it has loaded, behind the front. The limit of
  three restores in 10 minutes stays engine-wide. As now, a restored profile has no password.
- **Notifications.** A tell gets its profile as `setSubText` and `setGroup()`, with a summary per profile, and its
  tap makes that profile the front and shows the game screen. The foreground notification reads "Keeping 3 games
  connected" with a line per game and its connection, and with one game looks as it did with one profile.
  `ControlBridge` gives `AndroidNotifier::showProfiles` the open profiles as they change, for the stand-in and libmudlet
  alike.

## Pull request plan

Each follows the repo's rules: a protocol change in one commit across `docs/protocol.md`, the bridge, `Protocol.kt`
and `protocol_client.py`, with `ProtocolSpecTest` green.

| PR | Repo | What | Needs |
|---|---|---|---|
| M0 | android | This document | - |
| UP1 | Mudlet | Several `THeadlessSession`s share one pool: items 1-6 above, with `headless.md` and tests | - |
| P1 | android | Protocol 15: the control and profile sockets and the messages above; the bridge split into a control bridge and a bridge per profile; the stand-in runs several profiles, libmudlet refuses a second | M0 |
| U1 | android | `EngineClient` becomes a registry of `ProfileSession`s; `FakeEngine` serves several sockets. With one game nothing visible changes | P1 |
| U2 | android | The profile button and its sheet, "Open another game", closing, TalkBack, "Open games", predictive back | U1 |
| U3 | android | The swipe, its slide, rubber band and haptic, right-to-left, Ctrl+Tab | U2 |
| U4 | android | Unread and attention badges, `markSeen`, background speech | U2 |
| N1 | android | Notifications per profile: `showProfiles`, grouped tells that open their game, leaving the foreground with no game open | P1 |
| E1 | android | libmudlet with several profiles: a session each sharing the pool, the restore of the list, `setFront`, app state for the pool; host tests with two games and `raiseGlobalEvent()`; memory per profile measured to set the cap | UP1 in the pinned revision; P1 |

```
M0 ── P1 ─┬─ U1 ── U2 ─┬─ U3
          │            └─ U4
          ├─ N1
UP1 ──────┴──────────────────── E1
```

Everything but E1 is checked in CI against the stand-in and `FakeEngine`. What only a phone shows (the swipe's feel,
TalkBack's speech, memory with three real games) goes on the on-device checklist in the PR that adds it.
