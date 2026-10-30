# Approach B: Qt's supported embedding path (QtQuickView plus the Qt Gradle Plugin)

## Verdict
It builds, and it uses only public Qt API to start Qt: the Compose app creates a `QtQuickView`, `loadContent()` makes `QtEmbeddedLoader` load the Qt app's libraries, and `main()` runs on Qt's own thread with a normal `QGuiApplication`. But in the documented setup, where the view lives in an Activity, Qt's lifetime is the Activity's lifetime. Finishing the Activity terminates Qt and the engine (`QtEmbeddedDelegate.onActivityDestroyed`), and starting Qt again in the same process is not something Qt supports. For a MUD client that must keep a connection while the UI is gone, that is the biggest risk. The workaround in this prototype is to construct the `QtQuickView` with the foreground Service as its Context and never attach it. That routes Qt through `QtServiceEmbeddedDelegate`, so Qt lives exactly as long as the Service. It is still only public API, but it is an unusual use of it, and it breaks the brief's rule that "the service does not host Qt". Quick and QML are also required, because no `QtView` subclass works without them. They cost about 5.7 MB of download and 16.6 MB of native code on the device.

## Status
| Check | Result |
|---|---|
| Engine desktop build and fake-server test | **pass** (ctest 1/1). It checks telnet replies, an IAC split across packets, GA and 500 ms silence prompts, SGR runs (bold to bright, 256-colour, truecolor gives -1, colour carried to the next line), Lua lineCount, notify on "tells you", font and columns, and the heartbeat gap. |
| assembleDebug | **yes** |
| APK path | `/home/user/android-prototypes/b-quickview/android/app/build/outputs/apk/debug/app-debug.apk` |
| Clean build time | **2 min 9 s** (`rm -rf app/build build .kotlin && ./gradlew --no-daemon assembleDebug`, ninja -j2, workers=2). Gradle and Maven caches were warm; the C++ and both Gradle builds started from nothing. A no-op build takes 18 s. A one-line engine edit takes 48 s, because it re-runs qt-cmake, ninja, androiddeployqt and the inner Gradle. |
| APK size | **65.5 MB file** (65,514,117 B, debug, not minified; repackaging moves it by about 0.3 MB of zip padding). **Download estimate about 22.9 MB** with every entry deflated: lib 13.3 MB, dex 9.4 MB, other 0.2 MB. **Uncompressed lib/arm64-v8a: 37,001,408 B (35.3 MiB), 38 libraries**, stored uncompressed. The dex is 27.6 MB only because the debug Compose build has no R8. |
| Largest native libs | Qt6Gui 8.15 MB, Qt6Quick 7.56, Qt6Core 6.50, Qt6Qml 5.75, Qt6Network 1.87, c++_shared 1.25, Qt6QmlModels 1.18, qtforandroid 0.74, Qt6Svg 0.61, Qt6OpenGL 0.52, qmldbg_preview 0.44, qjpeg 0.38, libmudengine 0.31 (engine, Lua and bridge together) |
| 16 KB alignment | **pass.** `zipalign -c -P 16 -v 4` reports "Verification successful". `llvm-readelf -lW` shows every LOAD segment of all 38 .so files at 0x4000. |

### What Quick and QML add (measured)
I built the same qtapp with `-DMUDENGINE_WITH_QUICK=OFF` (Core, Gui and Network only, same engine). Both AARs are Release builds from androiddeployqt.

| | Quick (what B ships) | Core+Gui+Network only | Difference |
|---|---|---|---|
| Native libs, uncompressed | 36,991,312 B (37 .so) | 20,382,504 B (15 .so) | **+16.6 MB** |
| Native libs, deflated | 13,333,079 B | 7,623,092 B | **+5.7 MB** |
| Whole AAR | 13.56 MB | 7.84 MB | +5.7 MB |

The extra libraries:
- Quick, Qml, QmlModels, QmlMeta, QmlWorkerScript and OpenGL: 15.27 MB uncompressed, 5.25 MB deflated.
- 12 qmltooling `qmldbg_*` plugins, shipped even in Release and removable: 1.28 MB uncompressed, 0.43 MB deflated.
- 4 QML import plugins: 49 KB.
- Qt6AndroidQuick.jar: 10 KB.

Both variants also deploy Svg, svgicon, qjpeg, qgif, qico, qsvg, offscreen and networkinformation (about 1.9 MB) that nobody asked for.

## How it is wired
Startup:
1. `Application.onCreate` records t0 and creates the notification channels.
2. `MainActivity.onCreate` starts `EngineService` (FGS, specialUse).
3. The Qt view is created:
   - ACTIVITY mode: `QtQuickView(activity)` in a 1x1 FrameLayout, drawn underneath the Compose UI by an AndroidView.
   - SERVICE mode: `EngineService.onCreate` creates `QtQuickView(service)` and never attaches it.
4. `view.loadContent(Hidden())`, the generated QtQuickViewContent, makes `QtEmbeddedLoader` (a singleton) load the Qt libraries and then `mudengine_arm64-v8a`.
5. `JNI_OnLoad` runs on the UI thread, so FindClass sees the app class loader. It caches `org/mudlet/proto/b/EngineBridge` and registers 7 natives with RegisterNatives.
6. The delegate calls `QtNative.startApplication`. `main()` runs on `qtMainLoopThread`: QGuiApplication, AndroidFrontend, Engine, exec().

Calls between Kotlin and C++:
- **Threads.** The engine (QTcpSocket, timers, Lua) runs on Qt's thread and Compose on the main thread. The UI shows both TIDs.
- **Kotlin to C++.** Static natives (connect, send, disconnect, runLua, setViewWidthPx, quit, engineAlive) look up the `Engine*` under a std::mutex and call `QMetaObject::invokeMethod(engine, lambda, Qt::QueuedConnection)`. They return false when there is no engine. The Kotlin wrappers catch UnsatisfiedLinkError.
- **C++ to Kotlin.** `AndroidFrontend : IFrontend` calls `EngineBridge.onLine(String, int[] runs, boolean)`, `onNotify` and `onStatus` from Qt's thread. Text crosses as UTF-16 and runs as flattened triples. Kotlin builds an AnnotatedString and posts it to the main Handler. Counters are coalesced at 200 ms.

**Why JNI and not the QML property/signal API.** The QML API only works after the QQuickView root exists, which needs `addView` and so an attached view. It dies with the view, and it passes only boxed primitives and Strings (no int[] runs). In SERVICE mode the view is never attached, so the QML API would never work there. JNI works from `JNI_OnLoad` onward, does not depend on the view, and is what Mudlet needs anyway. The QML status listener is used only to show the QML load status.

**Foreground service.** `foregroundServiceType="specialUse"`, a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property, `ServiceCompat.startForeground(...SPECIAL_USE)`, a persistent "engine" notification, START_NOT_STICKY, and an optional PARTIAL_WAKE_LOCK switch.

**Two host modes.** The mode is kept in SharedPreferences and fixed per process.
- ACTIVITY is the documented path.
- SERVICE is the retained-holder variant. It is a deliberate deviation from "service does not host Qt": no Qt code runs in the Service class, but Qt's lifetime is the Service's.
- The Application context is ruled out. `QtEmbeddedViewInterfaceFactory.create()` returns null for a Context that is neither Activity nor Service (QtView then calls methods on the null), and `QtLoader` throws IllegalArgumentException (QtLoader.java:69-82).

**Build.** AGP 9.2.1, Compose compiler plugin 2.3.10 and `org.qtproject.qt.gradleplugin` 1.4.
- `QtBuildTask` runs qt-cmake on `qtapp/` with `QT_ANDROID_GENERATE_JAVA_QTQUICKVIEW_CONTENTS=ON`, then `ninja aar`.
- That runs androiddeployqt `--build-aar`, which runs a second, inner Gradle build.
- The plugin adds `mudengine.aar` (jni libs, Qt6Android.jar, Qt6AndroidQuick.jar and the generated `org.mudlet.proto.b.qt.mudengine.MudEngine.Hidden`) to the app.
- qtapp pulls in `../engine` with add_subdirectory. The engine has no Android includes.

## Qt internals relied on
Starting Qt itself uses only public API: QtQuickView, loadContent, the generated content class, setStatusChangeListener, and the plugin properties qtPath, qtProjectPath and qtExtraCMakeArguments. The rest depends on undocumented behaviour:
1. **`android.app.background_running` meta-data**, read by `QtLoader.isBackgroundRunningBlocked()` (QtLoader.java:129-134). Without it `QT_BLOCK_EVENT_LOOPS_WHEN_SUSPENDED=1`, and Qt's thread blocks in onStop. With it, no dispatcher registers with the stopper (qandroideventdispatcher.cpp:13). It is set on both the Activity and the Service. If the key is renamed, the engine stalls in the background.
2. **The factory chooses the delegate by `instanceof`** on the Context (QtEmbeddedViewInterfaceFactory.java:16-24). SERVICE mode depends on this.
3. **Qt starts in loadContent, not on attach** (QtView.java:139-160). If a future Qt defers starting until attach, SERVICE mode never starts.
4. **QtServiceEmbeddedDelegate terminates only when the last attached view is removed** (QtServiceEmbeddedDelegate.java:68-92). That never happens for a view that is never attached, so the app quits Qt itself in `EngineService.onDestroy`.
5. **onActivityDestroyed terminates unless isChangingConfigurations()** (QtEmbeddedDelegate.java:70-81).
6. **QtEmbeddedLoader sets QT_ANDROID_NO_EXIT_CALL** (QtEmbeddedLoader.java:19), so `main()` returning does not call exit() (androidjnimain.cpp:511).
7. **startApplication returns early once isStarted** (QtNative.java:359-369), and each delegate starts once (`m_membersInitialized`). A second view attaches to the Qt that is already running.
8. **The main lib `mudengine_arm64-v8a` is loaded by System.loadLibrary on the UI thread.** JNI_OnLoad and RegisterNatives depend on this.
9. **The JNIEnv on Qt's thread is attached by Qt.**
10. **Plugin 1.4 behaviour:**
    - It runs ninja with no -j, so `QtBuild.ninjaPath` points at the `tools/ninja-j2/` directory (the plugin appends `/ninja`). This was an absolute CMAKE_MAKE_PROGRAM before; see RESULTS.md.
    - The NDK version comes from Core.json (27.2), so ANDROID_NDK_ROOT must be passed.
    - qtProjectPath is resolved against the root project.
    - The task's only inputs are qtProjectPath.
11. **The inner androiddeployqt Gradle template asks for -Xmx3200m and parallel=true.** It can only be bounded through GRADLE_OPTS.

## Lifecycle
Everything here is **expected, from reading the Qt 6.12.0 sources**. Nothing ran on a device. Only the desktop engine test is verified.

**Rotation.**
- ACTIVITY mode: Qt's configChanges set is in the manifest, so there is no recreation.
- A forced `recreate()` has isChangingConfigurations true, so terminate is skipped. The new view gets a new delegate, which calls setActivity(new), and startApplication returns early. **The engine survives.** The old delegate, the first Activity and the QtEmbeddedLoader singleton's Context leak.
- SERVICE mode: not affected.

**Activity finished.**
- ACTIVITY mode, **engine dies**:
  1. onStop sets Suspended, and stopAll() marks the stopper stopped.
  2. onDestroy calls terminateQtNativeApplication, which calls quit() (androidjnimain.cpp:568-573).
  3. The UI thread then blocks in sem_wait until `main()` returns (line 576), an ANR risk during a long Lua call.
  4. QT_ANDROID_NO_EXIT_CALL keeps the process and the FGS alive, with no engine.
- Restarting in the same process is **fragile**: `setStarted(false)` and a second terminate can sit queued in BackgroundActionsTracker, so isStarted stays stale. Treat it as unsupported and restart the process instead.
- SERVICE mode: **the engine survives**, and the new Activity reattaches to EngineBridge state.

**Service stopped.**
- ACTIVITY mode: Qt keeps running while the Activity exists.
- SERVICE mode: onDestroy calls nativeQuit and `main()` returns. Qt's Java side keeps a stale service reference.
- Recommendation: exit the process.

**Background, onStop.** With background_running=true nothing blocks: timers and sockets keep running. Without it, onPause excludes timers and sockets (qandroideventdispatcher.cpp:52-54) and onStop parks the thread. SERVICE mode never sees Activity state, because `isServiceValid()` keeps Qt active.

**Process killed.** START_NOT_STICKY, so it is a cold restart on the next launch. START_STICKY plus SERVICE mode could bring the engine back with no UI, which ACTIVITY mode cannot.

**Screen off.**
- The FGS keeps the process at foreground priority and the socket open.
- Qt timers stall during CPU deep sleep and Doze unless a wake lock is held, so the heartbeat gap grows. Doze also cuts the network.
- A wake-lock switch is provided for comparison.

## Cost to build Mudlet on it
- **Engine swap.** Swap in mudlet_core, and implement TConsoleFrontend and TAppFrontend the way AndroidFrontend implements IFrontend. The JNI bridge grows with the number of frontend calls, so a table-driven bridge would be worth it. If mudlet_core needs Widgets, that adds about 8-10 MB.
- **Fixed size tax.** Quick, QML and OpenGL add about 5.7 MB download and 16.6 MB on device, used only to start Qt.
- **ACTIVITY mode runs a real 1x1 QQuickView render loop.**
- **Lifecycle.** SERVICE mode is required in practice, and it depends on internals 2-4, so re-check them on every Qt upgrade.
- **Daily development.**
  - Two Gradle builds, one CMake build, and the plugin glue with an AAR.
  - A one-line C++ edit costs 48 s; a clean build costs 2 min for the stand-in and much longer for mudlet_core.
  - Native debugging goes through a lib packaged in an AAR.
  - The plugin is young, and its NDK and ninja handling had to be overridden.
  - Upside: the Kotlin side is plain AGP and Compose, and the engine still builds and tests on the desktop.

## Size of the prototype
| Part | Lines |
|---|---|
| Kotlin (8 files) | 678 |
| Engine C++ and desktop (vendored Lua not counted) | 889 |
| qtapp C++ and JNI bridge, plus Hidden.qml | 268 |
| Python fake server and runner | 98 |
| Build files | about 253 |

## On-device checklist
1. **Start-up.**
   - Status shows Qt running, Qt start ms, QML Ready (ACTIVITY mode only), the font and 'M' advance, columns, and PIDs and TIDs (same PID, different TIDs).
   - The engine notification is present.
   - `dumpsys activity services org.mudlet.proto.b` shows `types=0x40000000`.
2. **Connect to aardmud.org 23.**
   - Coloured output appears.
   - `/lua return lineCount` works.
   - `/lua notify("x","y")` posts a Tells notification.
3. **ACTIVITY mode.**
   - Rotate: only a resize.
   - "Recreate Activity": engineAlive stays true.
   - Home for 5 min: on return maxGapMs is about 30000 and lines kept arriving.
   - "Kill UI", then reopen: expect "main() returned". Try "Start engine" and capture `logcat -s Qt QtEmbeddedDelegate MudProtoB`.
4. **"Use other mode + exit", reopen: SERVICE mode.**
   - QML stays blank.
   - Kill UI or swipe from recents, reopen: still connected, with the backlog present.
   - "Stop engine" ends Qt, and "Exit process" kills everything.
5. **Screen off for 30 min or more**, once without and once with the wake lock. Compare heartbeat ticks, lastGapMs and maxGapMs, and check the socket. Optionally run `dumpsys deviceidle force-idle`.
6. **`am kill` in the background.** Nothing restarts, and a cold start reports a new Qt start time.
7. **Storage.** Compare the app's storage size with the 37 MB native and 27.6 MB dex.

## Problems hit
- **QT_HOST_PATH required.** Pass `-DQT_HOST_PATH=.../gcc_64`.
- **NDK 27.2 vs 28.2.** Pass `-DANDROID_NDK_ROOT` explicitly.
- **The plugin runs ninja without -j.** Set `QtBuild { ninjaPath = .../tools/ninja-j2 }`, a directory holding the `ninja` wrapper.
- **The inner Gradle asks for -Xmx3200m and parallel.** Bound it with GRADLE_OPTS.
- **Maven Central 429.** Add the GCS mirror to settings and use `tools/mirror-init.gradle` for the inner build. Its repository must be named "gcsMirror", because the name "maven" already exists.
- **qtProjectPath is relative to the root project.** Use `../qtapp`.
- **Namespace clash in the manifest merger.** Use `QT_ANDROID_PACKAGE_NAME=org.mudlet.proto.b.qt`.
- **Qt's AAR adds WRITE_EXTERNAL_STORAGE.** Remove it with `tools:node="remove"`.
- **Stale native code shipped silently (verified).** QtBuildTask ignored edits in `../engine`. Fixed by adding `../engine/src` and `../engine/CMakeLists.txt` as task inputs in `app/build.gradle.kts`, and verified that an engine edit now rebuilds.
- **Compose BOM 2026.09 needs compileSdk 37.** Use 2026.03.00.
- **Harmless noise.** "Could NOT find Qt6TaskTree" during configure, and the Lua lauxlib "empty body" warning.
