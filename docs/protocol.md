# Engine socket protocol

How the UI process talks to the engine process, as implemented by:

- the engine: [`native/enginehost/SocketBridge.cpp`](../native/enginehost/SocketBridge.cpp) (C++, Qt);
- the app: [`protocol/`](../protocol/src/main/kotlin/org/mudlet/android/protocol) (`Protocol.kt` and `EngineLink.kt`);
- the host tests' client: [`native/tests/protocol_client.py`](../native/tests/protocol_client.py).

`ProtocolSpecTest` (in `:protocol`) checks that the message tables below, the C++ engine and the Kotlin client name
the same messages and fields, and that all of them, and the Python client, use the same protocol version. Change
this file in the same commit as the code.

The current version is **5**.

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
- One UI at a time. A new connection replaces the current one, which the engine closes.
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
| `hello` | `v`, `client`, `instance`?, `lastSeq`? | Must be the first frame. `v` is the protocol version, `client` names the client (the app sends `android-compose`; the engine ignores it). `instance` and `lastSeq` are what the UI already has: the engine instance it last attached to and the last line `seq` it holds. The app sends both or neither. |
| `ping` | `id` | Asks for a `pong` with the same `id`. |
| `connect` | `host`, `port`, `tls`? | Connects the engine to a game server. `port` defaults to 23. `tls` true encrypts the connection, false does not; absent, the profile's own choice stands. libmudlet takes `tls` only when this `connect` starts the profile, and stores it with the profile, as Mudlet's connection dialog does: while the profile runs, a `tls` other than the one it started with gets status `connection` `error: ...`. The stand-in answers `tls` true the same way, as it has no TLS. A `tls` that is not a boolean gets `error`. |
| `send` | `text` | A command line. libmudlet takes it as typed input (`THeadlessSession::submit()`); the stand-in sends it to the game as it is. |
| `sendHidden` | `text` | What the player typed while `hiddenInput` holds, as a password: straight to the game, with its line breaks taken out, and no aliases, echo or history (`submitHiddenInput()`). A failure is status `sendError`, which never quotes the text. Neither engine logs it. |
| `dismissHidden` | | The player closed the masked line without sending, as Esc on Mudlet's empty hidden-input box (`dismissHiddenInput()`): `hiddenInput` false follows, and `send` is the way to the game again. libmudlet hides input again once the game answers the next line sent, as Mudlet does. Refused with status `sendError` while input is not hidden. |
| `complete` | `text`, `fromHistory`?, `id`? | Completes the input line `text`, answered by status `completion`: the whole completed line, or empty for none. `fromHistory` true completes it from the history, as the up arrow does in Mudlet with "Highlight history" on: the newest line that starts with `text`. Otherwise, as Tab, it completes the last word from the words the game sent lately and those scripts suggested. A `complete` whose `text` is the line the last one answered goes on to the next word or older line. libmudlet runs `setCommandLineText()`, then `completeCommandLine()` or `completeCommandLineFromHistory()`; nothing completes while input is hidden. The stand-in completes from the lines `send` sent, both ways. A `fromHistory` that is not a boolean gets `error`. |
| `disconnect` | | Closes the game connection. |
| `runLua` | `code`, `id` | Runs Lua in the engine. The result comes back as status `luaResult` with `re` set to `id`. |
| `setScreenSize` | `columns`, `rows` | The UI's console in characters of the font it draws in, each a whole number from 1 to 65535 (else `error`): the UI does the measuring. A game that asks (telnet NAWS) is told it. libmudlet gives it to `setScreenSize()` now and to each profile as it loads, so the profile's scripts read it as the main window's size; as in Mudlet, a width below 40 counts as 40, the game is told no more than the profile's wrap width, and of a change once the size has held for 0.3 s. Until the UI sends one, a game is told 90 by 25. The stand-in tells the game at once, as it was given. |
| `getLink` | `link`, `id`? | Asks what the main console's link with that id offers now, answered by `link`, or by `error` `getLink: unknown` for an id the console no longer holds, one from a line of a profile opened before the one running, or with no profile open. `link` is the id a run carries, a whole number from 1 (else `error`). The stand-in has no links, and answers `error` `getLink: not supported`. |
| `followLink` | `link`, `item`?, `id`? | Follows the link as a tap does, or picks `item`, counted from 0, from `link`'s `items`, as its menu does (`followLink()`): a spoiler not yet revealed is only revealed, else its command or function runs. Nothing answers a link that ran or was revealed; `linksChanged` follows what that changed of it. Otherwise `error` answers with `followLink:` and the name of the `LinkResult`: `disabled` (a disabled link, or a menu's greyed-out item), `concealed`, `noSuchItem`, `unknown` (as for `getLink`), or `failed` or `refused` followed by a colon and why. A `link` that is not a whole number from 1, or an `item` that is not one from 0, gets `error`. The stand-in answers `error` `followLink: not supported`. |
| `listProfiles` | `id`? | Asks for `profiles`. |
| `saveProfile` | `name`, `host`, `port`, `tls`, `login`, `autoReconnect`, `id`? | Creates the profile `name`, or updates the one of that name, ignoring case; an update keeps the name it was created with. All the fields are needed: `host` not empty, `port` from 1 to 65535, `tls` and `autoReconnect` booleans, `login` a string, which may be empty. A new `name` must be one Mudlet can make a folder of: only the letters A to Z, the digits, spaces and `. _ ( ) - # &`, no space at either end, and neither `.` nor `..` in it. A running profile read its details when it started, so a change takes effect at its next start. |
| `deleteProfile` | `name`, `id`? | Deletes the profile, with everything Mudlet keeps for it. Refused while it is running, being opened or closed, or waited for by a restore. |
| `openProfile` | `name`, `password`?, `connect`?, `id`? | Starts the profile `name`. One already running is first closed and saved, as Mudlet closes a profile. `password` is for this run only: Mudlet's auto-login sends the profile's `login` and then this password once it connects, and nothing stores it. `connect`, true if absent, connects the profile to its own address. Opening the profile that is running leaves it running, with the password it was started with, and connects it if `connect` and it is not connected. While a profile is being opened, `connect`, `disconnect` and `runLua` are for it. |

Any request may carry `id`. The engine copies it, as it came, into the `re` of every `status` it sends while
handling that request, of the `profiles` or `error` that answers it, and into `pong`. Statuses that come later, such
as `connection` once a connect succeeds, carry no `re`.

Each profile request is answered with `profiles`, or with `error` whose `message` is the request's name, a colon and
why it was refused: a bad name or field, a profile that does not exist, or one that is in use. An `openProfile`
answered with `profiles` has begun: status `profile` tells how the close and the start go. In libmudlet a `connect`
before any `openProfile` starts the profile `MUDLET_PROFILE` names (`Android` in the app), making it if it does not
exist, as in version 1; after one, it connects the profile opened. The stand-in keeps its profiles in
`profiles.json` beside its socket, has no login, so a password goes nowhere, and connects a `connect` to the address
it names whatever profile is open.

### Engine to UI

| Message | Fields | Meaning |
|---|---|---|
| `hello` | `v`, `pid`, `instance` | The answer to the UI's `hello`. `pid` is the engine process's id. `instance` is an opaque string that differs for each run of the engine (now `<pid>-<ms since the epoch>`); its sequence numbers start again at 1. |
| `line` | `seq`, `text`, `runs`, `prompt`, `replay`? | A finished line, which never changes. `seq` counts lines from 1 for each instance, by one. `prompt` is true for a prompt (a line ended by a telnet go-ahead or end-of-record, or the stand-in's 500 ms of silence). `replay` is true on lines sent from the ring buffer in answer to `hello`. |
| `tail` | `text`, `runs`, `prompt` | The console's bottom line, which may still change or be ended. It replaces the previous tail. Empty `text` means none. Only libmudlet sends tails; the stand-in sends its prompts as lines. |
| `clear` | | The console was emptied: the UI drops its lines and the tail. |
| `status` | `key`, `value`, `re`? | A named value of the engine's state. The engine keeps the last value of each key. `re` is the `id` of the request being handled. |
| `notify` | `title`, `text` | A notification the engine's Lua asked for (the stand-in's `notify()`, libmudlet's `showNotification()`). The engine process posts it to Android itself; the UI only learns of it. |
| `pong` | `id` | The answer to `ping`. `id` is absent if the ping had none. |
| `gap` | `skipped` | `skipped` lines fell out of the ring buffer before the UI could get them. They come just before the next line. |
| `synced` | `seq` | Ends the answer to `hello`: the newest line's `seq` (0 for none) when the engine greeted the UI. |
| `error` | `message`, `re`? | The engine could not handle a frame. After a bad `hello` it then closes the connection. |
| `profiles` | `profiles`: `name`, `host`, `port`, `tls`, `login`, `autoReconnect`; `re`? | Every profile, the last opened or created first, then any the engine has not opened, by name. Each is an object with the fields `saveProfile` takes; there is no password in it. |
| `hiddenInput` | `wanted` | The game hides what the player types, as for a password (`wanted` true), or has stopped: the UI masks its input line and sends what is typed there with `sendHidden`. libmudlet sends it from `hiddenInputWanted`, which a disconnect or the profile closing also ends, and never while the profile shows passwords as they are typed; the stand-in from the game's telnet `WILL ECHO` and `WONT ECHO`. Sent on each change, and to each UI that attaches. |
| `history` | `lines` | The input line's history, newest first, each line once, as Mudlet's up arrow walks it (`commandHistory()`): the lines `send` sent, as their aliases left them, and those the profile's earlier sessions kept, but nothing sent while input was hidden. Sent to each UI that attaches, and by libmudlet when a profile has loaded. In between, the UI adds the lines it sends itself; the next attach corrects what it guessed. The stand-in's is the lines `send` sent since it started. |
| `announce` | `text`, `assertive` | What a script's `announce()` asks screen readers to say, as plain text (libmudlet's `announcementRequested`, which also carries Mudlet's own, as when a game's link is hidden or revealed). `assertive` is true for the `importantall` and `importantmostrecent` styles, which interrupt what is being said; otherwise it waits its turn. Not kept for a UI that attaches later. The stand-in's Lua has `announce()` too. |
| `link` | `link`, `tooltip`, `menu`, `menuTitle`, `spoiler`, `revealed`, `disabled`, `concealed`, `visited`, `selected`, `fg`?, `bg`?, `styles`?, `items`: `label`, `enabled`, `separator`; `re`? | The answer to `getLink`, from `THeadlessSession::link()`. `tooltip` is plain text, empty for none. A tap follows the first of `items`; where `menu` is true, a long press lists them all, under `menuTitle` if it is not empty. An item has a `label`, is greyed out where `enabled` is false, and is a line between items, with no label, where `separator` is true. `spoiler` is a link a tap first reveals, `revealed` once it has been. A `disabled` link runs nothing; a `concealed` one the game's visibility settings hide, which the UI shows as blanks no tap reaches. `visited` it has been followed; `selected` it is the one chosen in its selection group. `fg`, `bg` (colours as in runs) and `styles` (attribute bits) are how it is painted now, absent where the run's own apply; `styles` replaces only the run's bold, italic, underline, overline and strike-out. |
| `linksChanged` | `ids` | What `link` says of these links of the main console may have changed (libmudlet's `linksChanged`): one was followed, revealed, chosen or deselected in its group, concealed or revealed by the game, expired, or let go of with the lines holding it, after which `getLink` answers `unknown`. Empty `ids` means any link may have changed: the engine sends that to a UI that attaches, or catches up after lagging, when it missed a change. Only libmudlet sends it. |
| `commandLine` | `text` | A script set the whole input line, as `printCmdLine()`, `appendCmdLine()` and `clearCmdLine()` do, or Mudlet emptied it after a `send` (`commandLineTextRequested`): the UI replaces what is on its line. Not kept for a UI that attaches later. Only libmudlet sends it. |

### Status keys

`status` is how the engine reports everything that is not a line. The keys are not part of the protocol's version:
a UI shows what it knows and ignores the rest. Now in use:

- **Both engines:** `connection` (`connecting` or `connecting <host>:<port>`, `connected`, `disconnected`, `error: ...`), `luaResult`,
  `luaError`, `sendError`, `completion`, `fontFamily`, `advanceM`, `platform`, `linesReceived`,
  `promptsReceived`, `heartbeatTicks`, `heartbeatLastGapMs`, `heartbeatMaxGapMs`, `profile` (`<name> loaded`;
  libmudlet also sends `<name> (not started)`, `<name> starting`, `<name> closing`, `<name> closed`,
  `<name> closed without its save: ...`, `<name> not closed: ...` and `start failed ...`).
- **The engine host (`main.cpp`):** `enginePid`, `socketPath`, `appState`, `qtStartMsAfterAppOnCreate`,
  `qtStartMsAfterServiceOnCreate`, and on Android `serviceReadyWait` (`ok` or `timeout`).
- **The stand-in:** `luaOnLineCalls`, `notificationsPosted`, `fontError`.
- **libmudlet (`MudletEngine`):** `engineKind` (`libmudlet`), `configDir`, `profileLoadMs`, `startMs`,
  `localLines`, `errorConsoleLines`, `restore`, and `consoleColors`: `<fg> <bg>`, each `#rrggbb`, the colours of the
  console where the game asks for none (the runs' -1), from `consoleForeground()` and `consoleBackground()` when a
  profile loads, and on each change (`consoleColorsChanged`: a script's `setBackgroundColor()` on the main console,
  or a package or save with colours of its own).

### The restore status

An engine started after the one before it was killed brings back the profile that one ran, and its game connection if
that was wanted ([`architecture.md`](architecture.md), "Restoring the session"). Status `restore` says how that goes.
It is never sent by an engine that has nothing to restore, as after an orderly stop. Its value is a phase, then a
space and what the phase is about, if anything; `<target>` is `<host>:<port>`, or the profile's name when the profile's
own address is used. The app shows each new value as a marker line (`EngineRestore.describe`), and `ProtocolSpecTest`
checks that this table, `MudletEngine.cpp` and `EngineRestore.PHASES` name the same phases.

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
| `gaveUp <restores> <minutes>` | The engine was restored `<restores>` times within `<minutes>` and died each time: this start restores nothing, and forgets the profile. |

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
- 0-15, the ANSI palette (the stand-in);
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
those of the profiles before it; a UI drops the links of lines from an earlier instance, as it does their statuses.

## Attaching: hello, instance and lastSeq

The engine keeps the last 2000 lines in a ring buffer, its current tail, and the last value of every status key. When
a UI says `hello`, the engine sends, in this order:

1. its own `hello`;
2. every status, each as a `status` without `re`, in no particular order, then `hiddenInput` and `history`, and
   `linksChanged` with empty `ids` if a change of a link went to no UI since the last attach;
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
  `linksChanged`, as on attach), then the lines the UI has not had
  (with `clear` and `gap` as on attach, but without `replay`), then the tail.
- Answers are always written, even to a lagging UI: `pong`, `profiles`, `link`, `error`, and a `status` that carries `re`.

A `notify` sent while the UI lags is not sent again, but the engine has already posted the notification on Android;
nor is a `commandLine` or an `announce`.
`synced.seq` is the newest line when greeting even if lag cut the replay short; the rest follows once the UI drains,
and `EngineLink` then tracks `lastSeq` from those lines.

## Versioning and compatibility

As implemented in version 5, which added the link requests (`getLink`, `followLink`) and messages (`link`,
`linksChanged`); version 4 replaced `setViewWidthPx` (and its statuses `columns` and `viewWidthPx`) with
`setScreenSize` and added `announce`; version 3 added the input line's requests (`sendHidden`, `dismissHidden`,
`complete`) and messages (`hiddenInput`, `history`, `commandLine`); version 2 added the profile requests and
`profiles`:

- The UI sends its version in `hello` `v`, and the engine sends its own in its `hello`.
- The engine accepts only a `hello` whose `v` equals its own. Otherwise it sends
  `error` (`expected hello with v=5`) and closes the connection. A first frame that is not `hello` is refused the
  same way.
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
