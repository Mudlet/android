# Engine socket protocol

How the UI process talks to the engine process, as implemented by:

- the engine: [`native/enginehost/SocketBridge.cpp`](../native/enginehost/SocketBridge.cpp) (C++, Qt);
- the app: [`protocol/`](../protocol/src/main/kotlin/org/mudlet/android/protocol) (`Protocol.kt` and `EngineLink.kt`);
- the host tests' client: [`native/tests/protocol_client.py`](../native/tests/protocol_client.py).

`ProtocolSpecTest` (in `:protocol`) checks that the message tables below, the C++ engine and the Kotlin client name
the same messages and fields, and that all of them, and the Python client, use the same protocol version. Change
this file in the same commit as the code.

The current version is **1**.

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
message, in backquotes, with `?` after an optional one.

### UI to engine

| Message | Fields | Meaning |
|---|---|---|
| `hello` | `v`, `client`, `instance`?, `lastSeq`? | Must be the first frame. `v` is the protocol version, `client` names the client (the app sends `android-compose`; the engine ignores it). `instance` and `lastSeq` are what the UI already has: the engine instance it last attached to and the last line `seq` it holds. The app sends both or neither. |
| `ping` | `id` | Asks for a `pong` with the same `id`. |
| `connect` | `host`, `port` | Connects the engine to a game server. `port` defaults to 23. |
| `send` | `text` | A command line. libmudlet takes it as typed input (`THeadlessSession::submit()`); the stand-in sends it to the game as it is. |
| `disconnect` | | Closes the game connection. |
| `runLua` | `code`, `id` | Runs Lua in the engine. The result comes back as status `luaResult` with `re` set to `id`. |
| `setViewWidthPx` | `px` | The width of the UI's output view in pixels, from which the engine works out its columns (status `columns`). |

Any request may carry `id`. The engine copies it, as it came, into the `re` of every `status` it sends while
handling that request, and into `pong`. Statuses that come later, such as `connection` once a connect succeeds,
carry no `re`.

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
| `error` | `message` | The engine could not handle a frame. After a bad `hello` it then closes the connection. |

### Status keys

`status` is how the engine reports everything that is not a line. The keys are not part of the protocol's version:
a UI shows what it knows and ignores the rest. Now in use:

- **Both engines:** `connection` (`connecting` or `connecting <host>:<port>`, `connected`, `disconnected`, `error: ...`), `luaResult`,
  `luaError`, `sendError`, `columns`, `viewWidthPx`, `fontFamily`, `advanceM`, `platform`, `linesReceived`,
  `promptsReceived`, `heartbeatTicks`, `heartbeatLastGapMs`, `heartbeatMaxGapMs`.
- **The engine host (`main.cpp`):** `enginePid`, `socketPath`, `appState`, `qtStartMsAfterAppOnCreate`,
  `qtStartMsAfterServiceOnCreate`, and on Android `serviceReadyWait` (`ok` or `timeout`).
- **The stand-in:** `luaOnLineCalls`, `notificationsPosted`, `fontError`.
- **libmudlet (`MudletEngine`):** `engineKind` (`libmudlet`), `configDir`, `profile`, `profileLoadMs`, `startMs`,
  `localLines`, `errorConsoleLines`.

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

`link` is libmudlet's id of the link the run is part of, 0 for none. Nothing can follow a link over the protocol yet.

## Attaching: hello, instance and lastSeq

The engine keeps the last 2000 lines in a ring buffer, its current tail, and the last value of every status key. When
a UI says `hello`, the engine sends, in this order:

1. its own `hello`;
2. every status, each as a `status` without `re`, in no particular order;
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
- Once the queue drains below 256 KiB, the engine sends every status again, then the lines the UI has not had
  (with `clear` and `gap` as on attach, but without `replay`), then the tail.
- Answers are always written, even to a lagging UI: `pong`, and a `status` that carries `re`.

A `notify` sent while the UI lags is not sent again, but the engine has already posted the notification on Android.
`synced.seq` is the newest line when greeting even if lag cut the replay short; the rest follows once the UI drains,
and `EngineLink` then tracks `lastSeq` from those lines.

## Versioning and compatibility

As implemented in version 1:

- The UI sends its version in `hello` `v`, and the engine sends its own in its `hello`.
- The engine accepts only a `hello` whose `v` equals its own. Otherwise it sends
  `error` (`expected hello with v=1`) and closes the connection. A first frame that is not `hello` is refused the
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
