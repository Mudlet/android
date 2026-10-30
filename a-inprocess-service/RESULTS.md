# A: on-device results (emulator)

Run on 2026-10-30, following the REPORT's on-device checklist.

- **Device:** Android emulator AVD `mudlet-proto`, image `android-36.1/google_apis_playstore/x86_64`, 4 cores, 4 GB RAM, 1080x2400 at 420 dpi.
  - Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005`.
- **Build:** x86_64 debug, `-PandroidAbi=x86_64`, Qt 6.12.0 from `~/Programs/Qt/6.12.0`. Installed with `adb install -r -g`, so notifications were granted.
- **Game servers:**
  - aardmud.org:23. It sends no ANSI colour before login.
  - `c-separate-process/tests/fake_telnet.py` on the host (10.0.2.2:4000), for colour, floods and tells.
  - A push server that sends a tell every 300 s (10.0.2.2:4001), for the screen-off run.

## Fix needed before anything ran

All three prototypes aborted at start-up with `FORTIFY: strchr: prevented read past end of buffer`:

```
#  luaH_getstr / traversetable (lgc.c:167) <- luaC_step <- luaL_openlibs <- Engine::initLua (Engine.cpp:262)
```

- **Cause:** Lua 5.1's `getstr(ts)` is `(const char*)((ts) + 1)`. Clang's `pass_object_size` sees a zero-byte object behind the header, and bionic's `__strchr_chk` aborts.
- **Scope:** It doesn't depend on the ABI, so arm64 phones would hit it too.
- **Fix:** `lua51` is built with `-U_FORTIFY_SOURCE` (engine/CMakeLists.txt).

## Checklist

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | Start, no ANR | pass | `TotalTime` 666-735 ms over several cold starts. Status: `Qt: main() +256 ms, QGuiApplication +522 ms after Application.onCreate; svc.onCreate +79 ms, super.onCreate took 177 ms; started`. No ANR, and no 10 s `serviceReadyWait` pause. |
| 2 | Qt on the device | pass | `Qt 6.12.0 qpa=android appState=1 dpr=1`. `Font: Roboto (asked Roboto), 'M'=12 px @14px, columns=86`. Rotated: 196 columns. |
| 3 | Connection | pass (no colour from aardmud) | **aardmud:** 24 lines, Lua onLine 24, prompt shown in yellow, dropped 0. aardmud sends no SGR before login: 0 escape codes in 1863 bytes.<br>**Fake server, `flood 50` and `tell`:** 82 lines, 82 onLine, colours rendered.<br>**`lua return lineCount`:** 25, matching the counter.<br>**UI issue:** ANSI colour 0 (black) is invisible on the black output pane. |
| 4 | Tells | pass | `lua onLine("Bob tells you hi")` and the fake server's "Alice tells you 'ping'" each posted a notification on channel `tells`. |
| 5 | Foreground service | pass | `fgsType 0x40000000 (specialUse=0x40000000)`, `pids ui=6382 svc=6382 qt=6382 (Qt tid 6409)`, persistent notification visible. |
| 6 | Timers keep running | pass | 7 ticks in the first ~3.5 min, `max gap 30027 ms`, with `appState=1` (Hidden). Known finding 2, the `nativeServiceReady` wait before `QGuiApplication`, holds: the dispatcher was not stopped. |
| 7 | Screen off 30+ min, then force-idle | pass (emulator) | Screen off 20:51:27-21:28:33 (37 min) with the battery unplugged. Light Doze reached IDLE at about t+20 min. Then `deviceidle force-idle` (deep IDLE) for 6 min, then `unforce`. The push server sent a tell every 300 s, 7 in total, the last one during forced deep idle. The emulator's logcat shows each tell's notification at the same constant offset from the server's send time, so none was delayed. The freezer never froze an `org.mudlet` process.<br>Afterwards: `Heartbeat ticks 105, max gap 30132 ms`. `Lines 8, Lua onLine 8, notify 7, tells posted 7, dropped 0`. `connected 10.0.2.2:4001`, same pid 8549. |
| 8a | Kill UI | pass | pid unchanged and FGS kept. Reopened after 35 s: WARM, 125 ms. Heartbeat ticks 12, still connected, and output kept arriving.<br>**Minor:** the Host field reset to `aardmud.org`, because the UI state isn't saved. |
| 8b | Rotation | pass | Same pid. Columns 86 -> 196 -> 86. Still connected, heartbeat continuing. |
| 9 | Stop engine, Start engine | pass | **Stop:** logcat `onDestroy: Qt left running in process 6382`; the status read `Service destroyed ... bound no`; the game disconnected.<br>**Start:** the status read `reattached to running Qt`, and Connect then worked (106 lines). |
| 10 | Stop + exit Qt | pass | **Order of events:**<br>1. `onDestroy: handing over to QtServiceBase (System.exit follows)`<br>2. `main() returned 0`<br>3. Zygote `Process 6382 exited cleanly (0)`<br>No new tombstone; tombstones 00-02 are the FORTIFY aborts from before the fix. The Activity was not restarted.<br>**Harmless warning:** `IllegalArgumentException: Receiver not registered: QtNetwork$ProxyReceiver`. |
| 11 | Recents swipe | pass | Task removed (`dumpsys activity recents`: 0 tasks). Process 6382 and the FGS survived. |
| - | `am kill` with FGS | not killed | `am kill org.mudlet.proto.a` left pid 8395 running (FGS proc state). |
| - | force-stop, then launcher | pass | Clean cold start, `QGuiApplication +494 ms`. |
| - | `kill -9` from `run-as` | pass | The process died and was not restarted within 12 s. The FGS was gone with it. The next launcher start was a clean cold start, `QGuiApplication +518 ms`. |

## Notes

- The emulator does not truly suspend the CPU when the screen is off, so screen-off and Doze results are weaker evidence than a phone's. The heartbeat gap shows whether Qt's dispatcher kept running. It does not show what a real phone's suspend does to a timer with no wake lock.
- A and C use the same tag, `MudletEngineService`. With both installed, the brief's logcat filter for A also catches C's lines, so filter by pid.
