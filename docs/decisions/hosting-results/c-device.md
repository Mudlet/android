# C: on-device results (emulator)

Run on 2026-10-30, following the on-device checklist now in [`../../architecture.md`](../../architecture.md). This is
a record of prototype C as it was then: its files were under `c-separate-process/` (see [`37d2cc3`](https://github.com/Mudlet/android/tree/37d2cc3)), its
package was `org.mudlet.proto.c`, and `./build-apk.sh` is now `scripts/build-apk.sh`.
[`c-desktop-test.txt`](c-desktop-test.txt) and [`c-jvm-test.txt`](c-jvm-test.txt) are its host test runs.

- **Device and setup:** same emulator, servers and FORTIFY fix as A's
  [`RESULTS.md`](https://github.com/Mudlet/android/blob/37d2cc3/a-inprocess-service/RESULTS.md). The emulator is Android 16, x86_64.
- **Build:** `ANDROID_ABI=x86_64 ./build-apk.sh`. The script and Gradle now take `QT_ROOT`/`qtRoot` and `ANDROID_ABI`/`androidAbi`.

## Checklist

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 2 | Qt starts in a service-only process | pass | `TotalTime` 629 ms, no ANR, ui=9859 engine=9879.<br>Status: `Qt up 142 ms after Application.onCreate (:engine), 136 ms after Service.onCreate; UI start->hello 289 ms`.<br>Font line: `Font Roboto, 'M' 12 px @14px, columns 87 (1048 px), QPA android`.<br>`Qt appState 1 (Hidden), service-ready handshake ok`. |
| 2b | Heartbeat with appState Hidden | pass | Ticks kept rising, largest gap 30495 ms. The `ApplicationHidden` mitigation works here, as in A. |
| 2c | QPA offscreen | pass, but no fonts | `Qt up 82 ms`, `QPA offscreen`, `Qt appState 2`. Qt warns that it no longer ships fonts.<br>Font metrics are fallbacks: an empty family, 'M' 14 px, 74 columns.<br>The game path worked: flood 30 plus a tell gave 37 lines, Lua 37 and 2 tells notifications. |
| 3 | Foreground service | pass | `FGS (type specialUse): foreground=true pid=9879 process=org.mudlet.proto.c:engine`. The two pids differ. |
| 4 | aardmud.org:23 | pass | 23 lines, Lua onLine 23, connected. No colour before login, as in A.<br>**Fake server:** 57 lines, 1 prompt, Lua onLine 57, colours rendered. Two tells notifications (Bob and Alice) on channel `tells`. `lua return lineCount` = 57. |
| 5 | Ping x300 | pass | n=280, median 102 µs, p99 208 µs, max 1316 µs. |
| 6 | Rotate | pass (re-run) | First run: attaches stayed 1 and columns stayed 87, because the emulator never actually rotated. Re-run with `user_rotation` 1 then 0 and auto-rotate off: the Activity is recreated, the link reattaches to the same engine (`same engine, delta replay`, 89 and 83 ms), and the engine gets the new width: columns 87 -> 197 (2368 px) -> 87. Same pids, still connected. |
| 7 | Kill UI, reopen after 35 s | pass | WARM. `same engine, delta replay`, attaches 2 (172 ms). Heartbeat ticks 4, still connected. |
| 8 | Kill UI process, reopen | pass | Engine 9879 survived. Cold reopen 571 ms; new UI 10457 attached; game still connected and scrollback replayed. |
| 9 | Triggers | Temp, regex (with captures), permanent, and `send()` from a trigger all fire. |
| What the UI shows | Lines come from the main console model once their triggers have run, so `deleteLine()` gags, `replace()`, `selectString()` + `fg`/`bg`/`setUnderline`, and a trigger's `echo()` all show as in Mudlet. ANSI, 256-colour and Mudlet's own coloured messages keep their colours. A line Mudlet wrapped at 100 columns arrives whole. The open bottom line (a prompt) is sent as a provisional "tail". |
| Kill engine process | pass | New engine 10531. `engine losses 1 (loss->attach 700 ms)`. Game disconnected, as expected.<br>**UI issue (fixed):** the UI kept showing the old instance's counters (Lines 57, heartbeat 5). The UI now drops the engine's status when the link is lost or a new instance attaches; the new engine resends its own after hello. Re-run: Lines 22 -> 0 after the kill. |
| 9b | `kill <enginepid>` from `run-as`, 3 trials | pass, ~700 ms | loss->attach 701 ms every time.<br>**Timing breakdown:**<br>- ActivityManager restarts `:engine` 300-310 ms after the death (`Start proc ... for service EngineService`).<br>- The restarted Qt is up 89-90 ms after Application.onCreate.<br>- The engine is ready at about 395 ms, but EngineLink's backoff (EngineLink.kt:95-104) retries at 100, 200 and 400 ms. The attach therefore lands at 100+200+400 = 700 ms.<br>Linear or capped-low backoff would get close to the ~400 ms floor. The host's ~110 ms had no AM restart delay.<br>**Fixed:** EngineLink now retries every 50 ms for the first 2 s, then backs off 100 ms -> 2 s. Re-run, 3 trials: loss->attach 607, 500 and 504 ms. In the 504 ms trial the engine died at 54.786, AM started the new process at 55.043 and it listened at 55.248 (462 ms), so the attach lands within one 50 ms poll of the floor. JVM test on the host: 112 -> 53 ms. |
| 10 | Stop engine, Start engine | pass | **Stop:**<br>- logcat `onDestroy: QtServiceBase terminates Qt and calls System.exit(0) on this process`, `main() returned 0`, Zygote `Process 10646 exited cleanly (0)`.<br>- `ps -A`: only the UI (10457) left. The UI reads `FGS: not running`, `engine not reachable (attempt 7 ...)`.<br>**Start:** new engine 10830. The attach took up to 2 s, since the backoff is capped at 2000 ms while the engine is down.<br>**Fixed:** Start engine now wakes the link and restarts its fast retry phase. Re-run after 10 s stopped (attempt 48): `UI start->hello 253 ms`. |
| 11 | Screen off 30+ min, then force-idle | pass (emulator) | Screen off 20:51:27-21:28:33 (37 min) with the battery unplugged. Light Doze reached IDLE at about t+20 min. Then `deviceidle force-idle` (deep IDLE) for 6 min, then `unforce`. The push server sent a tell every 300 s, 7 in total, the last one during forced deep idle. The emulator's logcat shows each tell's notification at the same constant offset from the server's send time, so none was delayed. The freezer never froze an `org.mudlet` process.<br>Afterwards: `Heartbeat ticks 80, largest gap 30180 ms`. `Lines 31, Lua onLine 31, notifications posted 7`. `game: connected`. Same engine 11308 and UI 11558; reopening gave `same engine, delta replay`. |
| 12 | Memory (`dumpsys meminfo`) | recorded | TOTAL PSS:<br>- engine, android QPA: 59.3 MB (Native Heap 10.5 MB)<br>- engine, offscreen QPA: 49.6 MB (Native Heap 4.4 MB)<br>- UI process (no Qt): 57.5 MB |
| - | Recents swipe | pass | Task removed. UI (10457) and engine (11308) survived. Relaunch: WARM 217 ms, `same engine, delta replay` in 62 ms, still connected. |
| - | `am kill` + launcher | pass | `am kill` killed only the cached UI process. The FGS engine 11308 survived. Launcher: COLD 599 ms; the new UI attached to engine 11308; game still connected. |

## UI issues found

All three are fixed:

- **Tells counter:** "notifications posted 2 (UI saw 0)" after an engine restart; the UI's count is per UI process. It now resets with the engine's counters when a new instance attaches.
- **Hello time:** "UI start->hello -1 ms" in a UI process started while the engine was already up. It now reads "- (engine was already up)".
- **ANSI black:** colour 0 was invisible on the black pane, as in A and B. C now draws it as dark grey (#5C5C5C).

## libmudlet as the engine (2026-10-30)

C's engine process ran libmudlet in place of the stand-in. `MudletEngine` drives one
`THeadlessSession` behind the same socket protocol.

This run used a build that has since been replaced: the engine now builds from Mudlet's
`development` with no patch, through `THeadlessSession`'s public API only, and the `deps/`
scripts and patch below are gone. See "libmudlet as the engine" in [`architecture.md`](../../architecture.md).

**Build:**
- Mudlet: the tip of the libmudlet PR stack (Mudlet/Mudlet#12005, d2eb6cb2f4) plus
  `deps/mudlet-android.patch` (since removed).
- Steps:
  1. `deps/build-deps.sh`
  2. `MUDLET_SOURCE_DIR=... deps/build-mudlet.sh`
  3. `MUDLET_SOURCE_DIR=... MUDLET_BUILD_DIR=build-mudlet-<abi> ./build-apk.sh`
- The patch:
  - builds the bundled qtkeychain;
  - skips DBus on Android;
  - adds `-U_FORTIFY_SOURCE` to the bundled Lua, the same FORTIFY abort as the stand-in;
  - looks for `mudlet-lua` in the app's files dir;
  - adds a weak `mudlet_preload_lua_modules()` hook;
  - adds a `linesRemoved` signal to the console model's notifier, for deleted and trimmed lines.
- Mudlet builds with ASan by default on every non-Windows platform. Android needs `-DUSE_SANITIZER=`.
- Lua C modules are compiled into the engine and put in `package.preload`: lfs, lpeg, rex_pcre2,
  lua-utf8, yajl, brimworks.zip and luasql.sqlite3, plus sqlite and yajl themselves.
  They are Mudlet's own modules, linked statically. Users still can't load native modules.
- `mudlet-lua` and `translations/lua` ship as assets: 65 files. The engine copies them out
  when their stamp changes: 151 ms the first time, nothing after that.

**Results on the emulator:**

| Check | Result |
|---|---|
| Engine start | Qt up 165-237 ms after Application.onCreate, as with the stand-in |
| Profile start | `THeadlessSession::start()` with the profile loaded: 55-79 ms. The first start also installs Mudlet's default packages. |
| aardmud.org:23 | Connected; 24 lines including the login banner and "What be thy name". Error console empty. |
| Lua modules | `lfs.currentdir()`, `utf8.len`, `rex.match`, `yajl.to_string`, `zip`, `luasql.sqlite3` and `lpeg.version` all work. `db:create` + `db:add` + `db:fetch` gives 1 row. |
| Fake server | Connected through `connectToServer()`. ANSI, UTF-8, GA prompts (2) and the `tells you` line all worked. Mudlet's GMCP `Core.Hello` reaches the server. |
| Flood 20,000 lines | All 20,036 lines arrived; 20,000 were `FLOOD DONE` in under 2.5 s, including the UI polling. Read from the console model: all in order across the buffer's trims, none skipped, engine 96 MB PSS after. |
| Triggers | Temp, regex (with captures), permanent, and `send()` from a trigger all fire. |
| What the UI shows | Lines come from the main console model once their triggers have run, so `deleteLine()` gags, `replace()`, `selectString()` + `fg`/`bg`/`setUnderline`, and a trigger's `echo()` all show as in Mudlet. ANSI, 256-colour and Mudlet's own coloured messages keep their colours. A line Mudlet wrapped at 100 columns arrives whole. The open bottom line (a prompt) is sent as a provisional "tail". |
| Kill engine process | The UI reattached in 600 ms, and the Lua files were left as they were. The profile isn't restarted or reconnected. |
| Memory (TOTAL PSS) | Engine: 80 MB idle, 98 MB after the flood. Stand-in engine: 59 MB. UI process: 56 MB. |
| APK (debug, x86_64) | 112 MB. The engine `.so` is 139 MB unstripped. Multimedia pulls in FFmpeg (~20 MB). |

**Gaps:**
- `THeadlessSession::outputLine` fires before triggers run and carries no formatting, so it
  can't drive a UI. The engine reads the console model instead, which needs Mudlet's defines
  and headers at build time, plus the `linesRemoved` signal from the patch.
- A line already sent isn't updated: a later restyle or `deleteLine()` of an earlier line
  doesn't reach the UI. Links aren't marked.
- Mudlet's own notifications don't exist yet: the stand-in's Lua `notify()` has no Mudlet counterpart.
- No profile restart after the engine is killed.
- No bundled fonts (`WITH_FONTS=NO`), so Geyser falls back from Ubuntu: debug lines only.
- No TLS: `qt.network.ssl: No functional TLS backend`, since the APK ships no OpenSSL.
- The test server was naive: it took Mudlet's GMCP subnegotiation as part of the command
  line. `tests/fake_telnet.py` now strips subnegotiation.
