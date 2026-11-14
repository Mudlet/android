# Decision: hosting the engine

**Status:** decided, 2026-10-30. Mudlet for Android uses prototype C's layout with prototype A's Qt start-up.
The app in this repository is built on it.

## Context

Mudlet for Android puts a Kotlin + Jetpack Compose interface on top of the libmudlet core (`mudlet_core`,
[Mudlet#9011](https://github.com/Mudlet/Mudlet/issues/9011)). Qt does none of the drawing; it only runs the engine.
Three prototypes tried three ways of hosting a Qt 6 C++ engine under a Compose UI. Each built the same small stand-in
engine, so the only difference between them was how Qt is hosted and how the UI talks to it:

- minimal telnet;
- ANSI colour runs;
- a statically linked Lua 5.1 trigger that posts a notification;
- a font-metrics check through `QGuiApplication`;
- a heartbeat timer.

| Prototype | Approach |
|-----------|----------|
| A, [`a-inprocess-service/`](https://github.com/Mudlet/android/tree/37d2cc3/a-inprocess-service) | Engine in an in-process foreground service extending Qt's `QtServiceBase`; JNI both ways |
| B, [`b-quickview/`](https://github.com/Mudlet/android/tree/37d2cc3/b-quickview) | Qt started through Qt's supported embedding path (`QtQuickView`, Qt Gradle Plugin), the view created with the service as its context |
| C, [`c-separate-process/`](https://github.com/Mudlet/android/tree/37d2cc3/c-separate-process) | Engine in a `QtServiceBase` service in its own `:engine` process; the UI process loads no Qt and talks to it over a local socket |

The prototypes' code, their brief ([`BRIEF.md`](https://github.com/Mudlet/android/blob/37d2cc3/BRIEF.md)) and each one's `REPORT.md` and `RESULTS.md` are in
commit [`37d2cc3`](https://github.com/Mudlet/android/tree/37d2cc3), the last commit that has them. C became the app: its code is now `app/`,
`engine-service/`, `protocol/` and `native/`, its report [`../architecture.md`](../architecture.md), and its results
[`hosting-results/`](hosting-results).

## Decision

The app is built on C's layout:
- a Compose UI process that loads no Qt;
- Qt and the engine in a `specialUse` foreground service in its own `:engine` process;
- a local socket between them ([`../protocol.md`](../protocol.md)).

The engine process starts Qt the way A does: `QtServiceBase`, plus the two private
`QtAndroidPrivate` calls, with `QGuiApplication` constructed only after `onCreate()` returns.
It uses the `android` QPA; `offscreen` saves ~10 MB but has no fonts.

Why C over A:
- **Smaller protected footprint in the background.** Only the engine (~59 MB PSS on the
  emulator) runs at foreground-service priority. Android can freeze or drop the UI process
  while Mudlet is backgrounded. A keeps the whole app, Compose included, resident at that priority.
- **It fits libmudlet.** Every frontend call must already work over a socket, so the
  socket protocol is that boundary rather than an extra JNI layer. It also leads into the
  proxy and relay options for staying connected.

Crash isolation is not a reason. Native Lua modules aren't allowed on Android, so the engine
dying realistically means Android killing it. The UI then reattaches to the restarted engine
in ~500 ms.

Why not B: Qt 6.12.0's public embedded path can't run without an Activity (a
`ClassCastException` in `QtDisplayManager.getDisplay`), and Qt can't be restarted in the
same process.

Installed size is not a deciding factor.

## Consequences and open items

- The private Qt API means pinning the Qt version and keeping a shim, so a rename fails the build.
- Report the `QtDisplayManager` cast to Qt.
- Repeat the screen-off run on a phone, since an emulator doesn't suspend its CPU.
- Measure A's and C's memory and survival under memory pressure on a phone.
- An untried combination: C's separate process with B's public embedded start-up in the engine process.

Two findings apply to anything built on `QtServiceBase`:

- **A `QGuiApplication` hangs inside `QtServiceBase` unless helped.** `QtNative.startApplication()`
  waits on a semaphore that only `QAndroidService` (a `QCoreApplication`) releases; see
  `androidjnimain.cpp` and `qjnihelpers.cpp` in Qt 6.12.0. A and C release it with
  `QtAndroidPrivate::acuqireServiceSetup()` and `setOnBindListener()`, which ties them to
  that exact Qt version. Embedded mode (B) skips the wait.
- **Startup order matters.** If `QtServiceBase` reports `ApplicationHidden` after the platform
  integration exists, Qt's event dispatcher stops servicing timers and sockets without any
  error. A and C construct `QGuiApplication` only after `onCreate()` has returned.

## Before the emulator run

All three built for arm64-v8a and x86_64 with Qt 6.12.0, NDK 28.2 and compileSdk/targetSdk 36, and passed the 16 KB
page alignment checks and their desktop engine tests. This table is from before the emulator run, and some of its
lifecycle statements came from reading Qt's sources.

| | A: in-process | B: QtQuickView | C: separate process |
|---|---|---|---|
| Starts Qt using | Two `QtAndroidPrivate` calls (private API) | Public API only | The same private calls as A |
| Engine survives the UI closing | Expected | Only with the view created inside the service and never attached | Yes; an engine crash can't take the UI down |
| Stopping Qt | Ends the whole process (`System.exit(0)`) | Not restartable in-process | Ends only the engine process |
| Download size (release) | ~8.8 MB, measured | ~14 MB, estimated; Quick + QML add 5.7 MB | ~7-9 MB, estimated |

## On-device results: comparison

All three prototypes were run on 2026-10-30 against their REPORT checklists. The per-prototype tables are in each
prototype's `RESULTS.md` at [`37d2cc3`](https://github.com/Mudlet/android/tree/37d2cc3); C's is also in
[`hosting-results/c-device.md`](hosting-results/c-device.md).

- **Device:** Android 16 emulator (API 36.1, x86_64, google_apis_playstore), x86_64 debug builds, Qt 6.12.0.
- **Caveat:** an emulator doesn't suspend its CPU with the screen off, so the Doze and screen-off rows show whether Qt's event loop keeps running. They don't show what a phone's deep sleep does. Repeat row "Screen off" on a phone before relying on it.

### Fix all three needed

Every prototype aborted in `luaL_openlibs` with `FORTIFY: strchr: prevented read past end of buffer`. The cause is Lua 5.1's `getstr()` pointer arithmetic under clang's object-size checks. It doesn't depend on the ABI, so phones would hit it too. Fixed with `-U_FORTIFY_SOURCE` on the vendored `lua51` target in each engine's CMakeLists.txt.

### Side by side

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

### Recommendation for C: start Qt the A way

The device evidence supports keeping C's engine on A's start path: `QtServiceBase` plus the private `QtAndroidPrivate::acuqireServiceSetup(0)` / `setOnBindListener()` release. Don't move it to B's public embedded API.

1. **The public path doesn't work without an Activity in Qt 6.12.0.** C's `:engine` process never has an Activity, so B's route would have to be `QtServiceEmbeddedDelegate` with a Service context. On the device that crashes on every start: `QtDisplayManager.getDisplay(Context)` does an unconditional `checkcast android/app/Activity` (QtDisplayManager.java:128, confirmed with `javap`). No app-side change avoids it.
2. **The public path can't restart Qt in a process.** After `main()` returns, B's "Start engine" aborts the process (`JNI DETECTED ERROR ... IsInstanceOf received NULL jclass`). C restarts by replacing the process, so this would matter less there. Still, B offers nothing A's way lacks.
3. **A's way works in a service-only process.**
   - C reaches `Qt up` in 142 ms cold and 82-90 ms on restart.
   - The `service-ready` handshake is ok, and `appState` is 1 (Hidden), yet timers and sockets keep running. The heartbeat's max gap stays about 30 s, and through 37 minutes of screen-off and forced deep idle the max gap was 30180 ms, with all 7 tells delivered. So known finding 2's mitigation, the pending-state ordering, holds on a device.
   - Known finding 1, the semaphore release, gave no ANR in any cold start or restart of A or C.
4. **The cost is a private-API dependency.**
   - `acuqireServiceSetup` and `setOnBindListener` are in `qjnihelpers_p.h`, and Qt warns that the code is "tied to this specific Qt build".
   - **Mitigations:** pin the Qt version; keep the private calls in one shim so a rename fails the build (A's `QtProtoAccess.java`; in the app, `native/enginehost/AndroidServiceSetup.cpp`); keep the `appState`/heartbeat self-check on screen.
   - Report the `QtDisplayManager` cast to Qt. If a later Qt fixes it, B's `QtServiceEmbeddedDelegate` route becomes worth re-testing as the public alternative.

Smaller items for C, from its results ([`hosting-results/c-device.md`](hosting-results/c-device.md)):

- Fixed after the first run: EngineLink retries every 50 ms for 2 s before backing off, and Start engine wakes it, so kill to attach is ~500 ms and Start to attach ~250 ms. The UI drops a lost engine's counters. ANSI black is drawn dark grey.
- Rotation was not a bug: the first run's emulator never rotated, and a re-run sends the new width.
- Offscreen QPA saves about 10 MB PSS but has no fonts, so the metrics are wrong.
