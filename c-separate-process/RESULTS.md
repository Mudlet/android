# C: on-device results (emulator)

Run on 2026-10-30, following the REPORT's on-device checklist.

- **Device and setup:** same emulator, servers and FORTIFY fix as `a-inprocess-service/RESULTS.md`. The emulator is Android 16, x86_64.
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
| 6 | Rotate | partial | Same pids, still connected. **But:** no reattach happened (attaches stayed 1), and columns stayed 87 in landscape, so the engine never got the new width. |
| 7 | Kill UI, reopen after 35 s | pass | WARM. `same engine, delta replay`, attaches 2 (172 ms). Heartbeat ticks 4, still connected. |
| 8 | Kill UI process, reopen | pass | Engine 9879 survived. Cold reopen 571 ms; new UI 10457 attached; game still connected and scrollback replayed. |
| 9 | Kill engine process | pass | New engine 10531. `engine losses 1 (loss->attach 700 ms)`. Game disconnected, as expected.<br>**UI issue:** the UI kept showing the old instance's counters (Lines 57, heartbeat 5). |
| 9b | `kill <enginepid>` from `run-as`, 3 trials | pass, ~700 ms | loss->attach 701 ms every time.<br>**Timing breakdown:**<br>- ActivityManager restarts `:engine` 300-310 ms after the death (`Start proc ... for service EngineService`).<br>- The restarted Qt is up 89-90 ms after Application.onCreate.<br>- The engine is ready at about 395 ms, but EngineLink's backoff (EngineLink.kt:95-104) retries at 100, 200 and 400 ms. The attach therefore lands at 100+200+400 = 700 ms.<br>Linear or capped-low backoff would get close to the ~400 ms floor. The host's ~110 ms had no AM restart delay. |
| 10 | Stop engine, Start engine | pass | **Stop:**<br>- logcat `onDestroy: QtServiceBase terminates Qt and calls System.exit(0) on this process`, `main() returned 0`, Zygote `Process 10646 exited cleanly (0)`.<br>- `ps -A`: only the UI (10457) left. The UI reads `FGS: not running`, `engine not reachable (attempt 7 ...)`.<br>**Start:** new engine 10830. The attach took up to 2 s, since the backoff is capped at 2000 ms while the engine is down. |
| 11 | Screen off 30+ min, then force-idle | pass (emulator) | Screen off 20:51:27-21:28:33 (37 min) with the battery unplugged. Light Doze reached IDLE at about t+20 min. Then `deviceidle force-idle` (deep IDLE) for 6 min, then `unforce`. The push server sent a tell every 300 s, 7 in total, the last one during forced deep idle. The emulator's logcat shows each tell's notification at the same constant offset from the server's send time, so none was delayed. The freezer never froze an `org.mudlet` process.<br>Afterwards: `Heartbeat ticks 80, largest gap 30180 ms`. `Lines 31, Lua onLine 31, notifications posted 7`. `game: connected`. Same engine 11308 and UI 11558; reopening gave `same engine, delta replay`. |
| 12 | Memory (`dumpsys meminfo`) | recorded | TOTAL PSS:<br>- engine, android QPA: 59.3 MB (Native Heap 10.5 MB)<br>- engine, offscreen QPA: 49.6 MB (Native Heap 4.4 MB)<br>- UI process (no Qt): 57.5 MB |
| - | Recents swipe | pass | Task removed. UI (10457) and engine (11308) survived. Relaunch: WARM 217 ms, `same engine, delta replay` in 62 ms, still connected. |
| - | `am kill` + launcher | pass | `am kill` killed only the cached UI process. The FGS engine 11308 survived. Launcher: COLD 599 ms; the new UI attached to engine 11308; game still connected. |

## UI issues found

- **Tells counter:** "notifications posted 2 (UI saw 0)" after an engine restart; the UI's count is per UI process.
- **Hello time:** "UI start->hello -1 ms" in a UI process started while the engine was already up.
- **ANSI black:** colour 0 is invisible on the black pane, as in A and B.
