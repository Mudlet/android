# On-device results: comparison

All three prototypes were run on 2026-10-30 against their REPORT checklists. Per-prototype tables are in `a-inprocess-service/RESULTS.md`, `b-quickview/RESULTS.md` and `c-separate-process/RESULTS.md`.

- **Device:** Android 16 emulator (API 36.1, x86_64, google_apis_playstore), x86_64 debug builds, Qt 6.12.0.
- **Caveat:** an emulator doesn't suspend its CPU with the screen off, so the Doze and screen-off rows show whether Qt's event loop keeps running. They don't show what a phone's deep sleep does. Repeat row "Screen off" on a phone before relying on it.

## Fix all three needed

Every prototype aborted in `luaL_openlibs` with `FORTIFY: strchr: prevented read past end of buffer`. The cause is Lua 5.1's `getstr()` pointer arithmetic under clang's object-size checks. It doesn't depend on the ABI, so phones would hit it too. Fixed with `-U_FORTIFY_SOURCE` on the vendored `lua51` target in each engine's CMakeLists.txt.

## Side by side

| Check | A: in-process QtServiceBase | B: QtQuickView embedding | C: `:engine` QtServiceBase |
|-------|----------------------------|--------------------------|----------------------------|
| Cold start (`am start -W` TotalTime) | 666-735 ms | 701 ms | 629 ms |
| Qt start | `QGuiApplication` +522 ms after Application.onCreate | 501 ms after Application.onCreate | 142 ms after Application.onCreate (:engine). Restarts: 82-90 ms |
| Font, 'M' at 14 px, columns | Roboto, 12 px, 86 | Droid Sans Mono, 8 px, 129 | Roboto, 12 px, 87 (offscreen QPA: no fonts) |
| appState / heartbeat | 1 (Hidden), max gap 30027 ms | running, max gap 30087 ms | 1 (Hidden), max gap 30495 ms |
| aardmud.org:23 | pass | pass | pass |
| Colour (fake server) | pass | pass | pass |
| Lua onLine = lines | pass | pass | pass |
| Tells notification | pass | pass | pass |
| Screen off 30+ min + force-idle | pass: max gap 30132 ms, 7/7 tells | pass (ACTIVITY mode): max gap 30087 ms, 7/7 tells | pass: max gap 30180 ms, 7/7 tells |
| Rotation | pass | pass (resize only) | pass on re-run: reattach in 83-89 ms, columns 87 -> 197 -> 87 |
| Kill UI | pass, same pid | Qt's `main()` returns and the engine is dead | pass, delta replay |
| Kill UI process | n/a (one process) | n/a | pass, engine survives |
| Recents swipe | pass | not run separately | pass |
| Stop engine | Qt left running; Start reattaches | `main()` returns | only `:engine` exits (`System.exit(0)`); the UI reports it |
| Restart Qt after it stopped | yes (reattach, or Stop + exit then relaunch) | **no**: JNI abort in `QtNative.runPendingCppRunnables` | yes, new process. Kill to attach: 500-607 ms after the retry fix (was 701 ms); the engine itself listens ~460 ms after the kill |
| Engine without an Activity | yes | **no**: SERVICE mode crashes 3/3 with `ClassCastException` in `QtDisplayManager.getDisplay` | yes |
| `am kill` with FGS | survives | survives | UI killed, engine survives |
| Memory (PSS) | - | - | engine 59.3 MB, UI 57.5 MB |
| Installed size (x86_64 debug) | 45.0 MB | 62.6 MB | 46.3 MB |

## Recommendation for C: start Qt the A way

**Decided:** Mudlet for Android uses C's layout with A's Qt start-up; the reasons are in the README's Decision section.

The device evidence supports keeping C's engine on A's start path: `QtServiceBase` plus the private `QtAndroidPrivate::acuqireServiceSetup(0)` / `setOnBindListener()` release. Don't move it to B's public embedded API.

1. **The public path doesn't work without an Activity in Qt 6.12.0.** C's `:engine` process never has an Activity, so B's route would have to be `QtServiceEmbeddedDelegate` with a Service context. On the device that crashes on every start: `QtDisplayManager.getDisplay(Context)` does an unconditional `checkcast android/app/Activity` (QtDisplayManager.java:128, confirmed with `javap`). No app-side change avoids it.
2. **The public path can't restart Qt in a process.** After `main()` returns, B's "Start engine" aborts the process (`JNI DETECTED ERROR ... IsInstanceOf received NULL jclass`). C restarts by replacing the process, so this would matter less there. Still, B offers nothing A's way lacks.
3. **A's way works in a service-only process.**
   - C reaches `Qt up` in 142 ms cold and 82-90 ms on restart.
   - The `service-ready` handshake is ok, and `appState` is 1 (Hidden), yet timers and sockets keep running. The heartbeat's max gap stays about 30 s, and through 37 minutes of screen-off and forced deep idle the max gap was 30180 ms, with all 7 tells delivered. So known finding 2's mitigation, the pending-state ordering, holds on a device.
   - Known finding 1, the semaphore release, gave no ANR in any cold start or restart of A or C.
4. **The cost is a private-API dependency.**
   - `acuqireServiceSetup` and `setOnBindListener` are in `qjnihelpers_p.h`, and Qt warns that the code is "tied to this specific Qt build".
   - **Mitigations:** pin the Qt version; keep the `QtProtoAccess` shim so a rename fails the build; keep the `appState`/heartbeat self-check on screen.
   - Report the `QtDisplayManager` cast to Qt. If a later Qt fixes it, B's `QtServiceEmbeddedDelegate` route becomes worth re-testing as the public alternative.

Smaller items for C, from its RESULTS:

- Fixed after the first run: EngineLink retries every 50 ms for 2 s before backing off, and Start engine wakes it, so kill to attach is ~500 ms and Start to attach ~250 ms. The UI drops a lost engine's counters. ANSI black is drawn dark grey.
- Rotation was not a bug: the first run's emulator never rotated, and a re-run sends the new width.
- Offscreen QPA saves about 10 MB PSS but has no fonts, so the metrics are wrong.
