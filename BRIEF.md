# Mudlet on Android: hosting prototypes

Three prototypes, one per way of running a Qt 6 C++ engine under a native
Kotlin + Jetpack Compose interface. The goal is to find out which approach is the
soundest foundation for a Mudlet Android app built on `mudlet_core` (libmudlet).
Each prototype builds the **same stand-in engine** (spec below), so the difference
between them is only how Qt is hosted and how the UI talks to it.

| Dir | Approach |
|-----|----------|
| `a-inprocess-service/` | Engine in an in-process foreground service extending Qt's `QtServiceBase`; Compose Activity binds to it; JNI in both directions |
| `b-quickview/` | Qt started through Qt's supported embedding path (`QtQuickView`, via the Qt Gradle Plugin / Qt Tools), with a 1x1 or hidden view; engine runs in that Qt app; a plain foreground `Service` keeps the process alive |
| `c-separate-process/` | Engine in a `QtServiceBase` foreground service in its own process (`android:process=":engine"`); the UI process has no Qt at all and talks to it over a local socket with a small framed protocol |

## Toolchain (shared, read-only, do not modify)

- `/opt/android-toolchain/qt/6.12.0/gcc_64` - host Qt (androiddeployqt, qmlimportscanner)
- `/opt/android-toolchain/qt/6.12.0/android_arm64_v8a` - Qt for Android, includes qtwebsockets
- `/opt/android-toolchain/sdk` - Android SDK; NDK, platform and build-tools versions in `/opt/android-toolchain/versions.txt`
- JDK 21 and Gradle on PATH (`/opt/gradle`); network through the session proxy (Maven Central, Google Maven, lua.org, download.qt.io work)
- Installed: NDK 28.2.13676358, build-tools 37.0.0, platforms;android-36 (android-37 is not offered). Use `ANDROID_SDK_ROOT=/opt/android-toolchain/sdk`.
- Target arm64-v8a only. minSdk 28 (Qt's minimum), compileSdk and targetSdk 36. Qt 6.12's default targetSdk is 37: override it.
- No `/dev/kvm`: there is no usable emulator. Do not try to run one. Runtime behaviour is checked later on a real phone using the self-test screen below.

The machine has 4 cores and 15 GB RAM shared by three agents building at once. Keep
builds bounded: `ninja -j2`, Gradle with `--no-daemon`, `org.gradle.workers.max=2`,
`org.gradle.jvmargs=-Xmx1536m`. Stay inside your own directory; the Gradle cache in
`~/.gradle` is shared and safe to use concurrently.

## Stand-in engine (identical in all three)

C++20, Qt Core + Gui + Network, Lua 5.1.5 compiled statically from the lua.org
tarball (vendor it into your dir). One `Engine : QObject` living on Qt's thread:

- `connectTo(host, port)`, `send(line)`, `disconnectFromGame()`, `runLua(code)`, `setViewWidthPx(int)`
- Minimal telnet: strip IAC sequences, answer every DO/WILL with WONT/DONT; split
  into lines on `\n` (drop `\r`); a line without `\n` that is followed by GA/EOR or
  500 ms of silence is emitted as a prompt.
- ANSI SGR parse into runs: `{start, length, fg}` with fg as 0-15 or -1 for default;
  the line text has escapes removed.
- Lua: a default script defines `onLine(text)`. It counts lines and, when a line
  matches `tells you`, calls the C++-provided `notify(title, text)`.
- Font check at startup: `QGuiApplication` must exist; report
  `QFontMetrics(QFontDatabase::systemFont(QFontDatabase::FixedFont)).horizontalAdvance('M')`
  at 14px, the font family Qt resolved, and the columns that fit `setViewWidthPx`.
- Heartbeat: a 30 s `QTimer` records the wall-clock gap since the previous tick, so
  the self-test can show whether timers ran while the screen was off.

The engine talks to its host through one interface (`IFrontend`) with `line(text,
runs, isPrompt)`, `notify(title, text)` and `status(key, value)`. Each approach
implements `IFrontend` in its own bridge (JNI, or the socket protocol). Keep the
engine in its own directory with no Android or JNI includes, so it also builds for
desktop: add a desktop CMake target using `/opt/android-toolchain/qt/6.12.0/gcc_64`
and a host-side test that runs it against a small local fake telnet server (a Python
or C++ script you write) to prove the engine logic.

## The APK's self-test screen (Compose)

The screen shows, live:

- When Qt started (ms after Application.onCreate), font family, 'M' advance, columns
- A host/port field (default `aardmud.org` 23), Connect/Disconnect, scrolling coloured output, a command field
- Lines received, Lua `onLine` calls, notifications posted
- Heartbeat ticks with the largest gap seen
- Foreground service state (type `specialUse` with a subtype property, plus a persistent notification), process ids
- A "Kill UI" button (finish the Activity) and a "Stop engine" button. Note what happens to Qt in each case.

Request POST_NOTIFICATIONS at runtime. `notify()` posts to a "Tells" notification channel.

## What to measure and write in `REPORT.md`

Use `REPORT_TEMPLATE.md`. Be factual: say what you verified by building or
running, and what you only expect to happen on a phone. If something does not
work, record the exact error and what you tried. A failed approach with a clear
reason is a useful result.

## Ground rules

- Work only in your own subdirectory. Make it a git repo and commit as you go; do not push anywhere.
- Don't install software system-wide. Don't change `/opt/android-toolchain`.
- Don't message the overseeing session or stream progress. Write everything into
  `REPORT.md`. Your final reply is at most 100 words: built yes/no, APK path, one-line verdict.
