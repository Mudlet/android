# Mudlet for Android: hosting prototypes

Groundwork for a native Mudlet Android app: a Kotlin + Jetpack Compose interface on top of
the libmudlet core (`mudlet_core`, [Mudlet#9011](https://github.com/Mudlet/Mudlet/issues/9011)).
Qt does none of the drawing; it only runs the engine. These three prototypes try three ways
of hosting a Qt 6 C++ engine under a Compose UI, to find the soundest foundation.

All three build the same small stand-in engine (spec in [`BRIEF.md`](BRIEF.md)):
- minimal telnet;
- ANSI colour runs;
- a statically linked Lua 5.1 trigger that posts a notification;
- a font-metrics check through `QGuiApplication`;
- a heartbeat timer.

That way the only difference between them is how Qt is hosted and how the UI talks to it.
Each APK opens a self-test screen that shows these live.

| Directory | Approach |
|-----------|----------|
| [`a-inprocess-service/`](a-inprocess-service) | Engine in an in-process foreground service extending Qt's `QtServiceBase`; JNI both ways |
| [`b-quickview/`](b-quickview) | Qt started through Qt's supported embedding path (`QtQuickView`, Qt Gradle Plugin), the view created with the service as its context |
| [`c-separate-process/`](c-separate-process) | Engine in a `QtServiceBase` service in its own `:engine` process; the UI process loads no Qt and talks to it over a local socket |

Each directory has a `REPORT.md` covering:
- measurements;
- the Qt internals the approach relies on;
- expected lifecycle behaviour;
- an on-device checklist;
- problems hit along the way.

## Status

All three build for arm64-v8a with Qt 6.12.0, NDK 28.2 and compileSdk/targetSdk 36. All
pass the 16 KB page alignment checks and their desktop engine tests. **None has run on a
device yet.** The builds ran in a container without an emulator, so every lifecycle
statement in the reports comes from reading Qt's sources, not from running them.

| | A: in-process | B: QtQuickView | C: separate process |
|---|---|---|---|
| Starts Qt using | Two `QtAndroidPrivate` calls (private API) | Public API only | The same private calls as A |
| Engine survives the UI closing | Expected | Only with the view created inside the service and never attached | Yes; an engine crash can't take the UI down |
| Stopping Qt | Ends the whole process (`System.exit(0)`) | Not restartable in-process | Ends only the engine process |
| Download size (release) | ~8.8 MB, measured | ~14 MB, estimated; Quick + QML add 5.7 MB | ~7-9 MB, estimated |

Two findings apply to anything built on `QtServiceBase`:

- **A `QGuiApplication` hangs inside `QtServiceBase` unless helped.** `QtNative.startApplication()`
  waits on a semaphore that only `QAndroidService` (a `QCoreApplication`) releases; see
  `androidjnimain.cpp` and `qjnihelpers.cpp` in Qt 6.12.0. A and C release it with
  `QtAndroidPrivate::acuqireServiceSetup()` and `setOnBindListener()`, which ties them to
  that exact Qt version. Embedded mode (B) skips the wait.
- **Startup order matters.** If `QtServiceBase` reports `ApplicationHidden` after the platform
  integration exists, Qt's event dispatcher stops servicing timers and sockets without any
  error. A and C construct `QGuiApplication` only after `onCreate()` has returned.

C's split matches libmudlet's rule that every frontend call must be possible over a socket,
and it is the first piece of the proxy and relay options for staying connected. An untried
combination is C's separate process with B's public embedded start-up in the engine process.

## Trying them on a phone

Each prototype has its own package name, so all three install side by side. Build an APK with
the commands in its `REPORT.md`, `adb install` it, then follow that report's on-device
checklist. The main things to watch:

- startup finishes without an ANR;
- the heartbeat's largest gap stays near 30 s;
- the game connection is still alive after 30 minutes with the screen off;
- what "Kill UI" and "Stop engine" do.

## Building

`BRIEF.md` describes the machine these were written on (`/opt/android-toolchain/...`). The build
files now default to `~/Programs/Qt/6.12.0` and `~/Android/Sdk`, and build arm64-v8a; override with
`-PqtRoot=...` / `-PandroidAbi=x86_64` (A, C's Gradle), `qtPath` in `b-quickview/android/gradle.properties`,
or `QT_ROOT` / `ANDROID_ABI` / `ANDROID_SDK_ROOT` (A's `build-native.sh`, C's `build-apk.sh`). You need:

- Qt 6.12.0 for `gcc_64` (host) and `android_arm64_v8a`, with qtwebsockets;
- an Android SDK with NDK 28.2.13676358, build-tools 37.0.0 and platform android-36;
- JDK 21 and Gradle 8.14.

Each prototype vendors Lua 5.1.5 (MIT, see its `COPYRIGHT`).
