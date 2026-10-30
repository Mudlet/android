# B: on-device results (emulator)

Run on 2026-10-30, following the REPORT's on-device checklist.

- **Device and setup:** same emulator, servers and FORTIFY fix as `a-inprocess-service/RESULTS.md`. The emulator is Android 16, x86_64. B was built with `-PandroidAbi=x86_64`.
- **Logging:** B logs nothing under `MudProtoB` or `QtEmbeddedDelegate`, because the app has no `Log` calls. The brief's filter therefore only shows `Qt` and system lines.

## Build changes

- **ninja wrapper:** `CMAKE_MAKE_PROGRAM` was an absolute path (`/home/user/android-prototypes/...`), and CMake can't use a relative one: `'../tools/ninja-j2' '--version' failed`. The wrapper is now `tools/ninja-j2/ninja` and is wired through `QtBuild { ninjaPath = rootProject.file("../tools/ninja-j2").absolutePath }`. The plugin takes a directory and appends `/ninja`.
- **gradle.properties:** `qtPath`, `QT_HOST_PATH` and `ANDROID_NDK_ROOT` point at `~/Programs/Qt/6.12.0` and `~/Android/Sdk`. A new `androidAbi` property selects the ABI.

## Checklist

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | Start-up (ACTIVITY) | pass, but no QML Ready | `TotalTime` 701 ms. `Qt started: 501 ms after Application.onCreate (QML -)`. `Font: Droid Sans Mono, 'M' advance 8px @14px, columns 129 (width 1038px)`. `UI pid 8615`, Qt tid 8632. `QGuiApplication after 388 ms in main()`. `Service: foreground (specialUse)`.<br>**QML Ready:** the status callback never fired; the status kept showing "QML -". |
| 2 | Connect, colour, Lua, tells | pass | **aardmud:** connected. Qt started in 531 ms on that run.<br>**Fake server:** `lines=58 lua=58 notify=3`, colours rendered. The 3 notifications are the Bob and Alice tells plus `/lua notify("x","y")`, all on channel `tells`.<br>**UI issue:** ANSI black is invisible, as in A. |
| 3a | Rotate | pass | Only a resize (configChanges). Same pid, still connected. |
| 3b | Recreate Activity | engine pass, UI fail | **Engine:** stayed alive; the heartbeat kept ticking.<br>**Status label:** stuck at `Qt: starting (QtQuickView with MainActivity context)`.<br>**Leak:** Qt registered a receiver against the Activity:<br>`IntentReceiverLeaked: Activity org.mudlet.proto.b.MainActivity has leaked IntentReceiver ...QtNetwork$ProxyReceiver` |
| 3c | Kill UI, reopen | as expected | `main() returned 0`. The process and FGS remain, but the heartbeat stopped and the status showed a stale "connected". |
| 3d | Start engine after Kill UI | **fail: process abort** | `JNI DETECTED ERROR IN APPLICATION: IsInstanceOf received NULL jclass` from `void org.qtproject.qt.android.QtNative.runPendingCppRunnables()`, then SIGABRT in `libQt6Core` frames. Once `main()` has returned, Qt can't start again in the same process. |
| 3e | Stop engine | as expected | `main() returned 0`; the service stopped; the process stays. |
| 4 | SERVICE mode | **fail: crash on every cold start (3/3)** | "Mudlet proto B keeps stopping" (stack below). Every other SERVICE-mode check is blocked. |
| 5 | Screen off 30+ min, then force-idle (ACTIVITY mode) | pass (emulator) | Screen off 20:51:27-21:28:33 (37 min) with the battery unplugged. Light Doze reached IDLE at about t+20 min. Then `deviceidle force-idle` (deep IDLE) for 6 min, then `unforce`. The push server sent a tell every 300 s, 7 in total, the last one during forced deep idle. The emulator's logcat shows each tell's notification at the same constant offset from the server's send time, so none was delayed. The freezer never froze an `org.mudlet` process.<br>Afterwards: `ticks=94 lastGapMs=29988 maxGapMs=30087`. `lines=8 lua=8 notify=7, notifications posted 7, dropped 0`. `connected 10.0.2.2:4001`, same pid 9770, wake lock off. |
| 6 | `am kill` with FGS | not killed | The FGS proc state protects the process. |
| 7 | Storage | 62.6 MB installed (`du` of the code path, x86_64 debug APK), against 45.0 MB for A and 46.3 MB for C. | |

The SERVICE-mode crash:

```
java.lang.ClassCastException: org.mudlet.proto.b.EngineService cannot be cast to android.app.Activity
    at org.qtproject.qt.android.QtDisplayManager.getDisplay(QtDisplayManager.java:128)
    at org.qtproject.qt.android.QtDisplayManager.updateRefreshRate(QtDisplayManager.java:70)
    at org.qtproject.qt.android.QtServiceEmbeddedDelegate.lambda$onNativePluginIntegrationReadyChanged$0(QtServiceEmbeddedDelegate.java:45)
```

This is a Qt 6.12.0 bug. `javap -c` on `Qt6Android.jar` shows that `QtDisplayManager.getDisplay(Context)` starts with an unconditional `checkcast android/app/Activity`. So the public embedded API with a Service context can't run in 6.12.0, whatever the app does. After the test, ACTIVITY mode was restored by editing `shared_prefs/proto.xml` through `run-as`.

## Not run

- **Wake-lock comparison:** the emulator doesn't suspend the CPU, so with and without the wake lock behave alike here.
- **"Home for 5 min":** covered by the screen-off run.
- **SERVICE-mode Kill UI, recents and Exit process:** blocked by the crash.
