# Approach A: in-process QtServiceBase foreground service

## Verdict
The APK builds, is 16 KB aligned, and wires a Compose Activity to a Qt engine running in the same process inside a `QtServiceBase` foreground service. Nothing was run on a device. Out of the box, a `QGuiApplication` cannot start inside `QtServiceBase`: `QtServiceBase.onCreate()` blocks Android's main thread on a semaphore that only `QAndroidService` (a `QCoreApplication`) releases, so a GUI app hangs there until Android reports an ANR. That is almost certainly the "it hung" report. The prototype gets past it by calling two private `QtAndroidPrivate` functions at the top of `main()`, and it has to order startup carefully so Qt does not stop its own timers and sockets. It is the leanest of the three designs: one process, direct JNI and no IPC. Its biggest risk is how much undocumented Qt behaviour it depends on (the service-setup semaphore, the app-state ordering, `System.exit(0)` in `onDestroy`, Qt running only once per process), and all of it has to be checked again on every Qt upgrade. A native crash also takes the UI down with it.

## Status
| Check | Result |
|-------|--------|
| Engine desktop build and fake-server test | **pass**: `ctest --test-dir build-desktop` runs 17 checks against `desktop/fake_server.py`. They cover IAC stripping across TCP segments, DO/WILL refusal (the server checks the bytes it gets back), `\r` dropping, prompts on GA and after 500 ms of silence, SGR runs including colour carried across lines, bright and 256-colour, Lua `onLine` + `notify`, `runLua` results and errors, the font check (offscreen: DejaVu Sans Mono, M = 8 px, 135 columns at 1080 px) and the heartbeat. A deliberate telnet bug made the test fail, so it is not passing vacuously. |
| APK builds (`assembleDebug`) | **yes**: `gradle --no-daemon assembleDebug` (Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01). `assembleRelease` with R8 also builds. |
| APK path | `/home/user/android-prototypes/a-inprocess-service/app/build/outputs/apk/debug/app-debug.apk` (release, unsigned: `app/build/outputs/apk/release/app-release-unsigned.apk`) |
| Clean build time | 2 min 22 s for `assembleDebug` with `build-android/` and `app/build` deleted and the Gradle cache warm, on a machine at load average about 10 (three agents building). The native part (CMake configure, 46 ninja steps at `-j2`, androiddeployqt) takes about 16 s of that. The first build ever took 4 min 55 s, mostly downloading. After touching `Engine.cpp`: 40 s. No-op: 28 s (mostly `--no-daemon` JVM start-up and configuration). |
| APK size | Debug 46.8 MB, of which 25.7 MB is unminified debug dex. Release with R8: 23.2 MB on disk, about **8.8 MB to download** (gzip -9 of the APK; Play compresses the stored `.so` files). `lib/arm64-v8a` uncompressed: **20.4 MB** (16 files, stored uncompressed, `extractNativeLibs=false`). |
| Largest native libraries | libQt6Gui 8.15 MB, libQt6Core 6.50 MB, libQt6Network 1.87 MB, libc++_shared 1.25 MB, qtforandroid platform plugin 0.74 MB, libQt6Svg 0.61 MB (pulled in by the svg icon/image plugins), qjpeg 0.38 MB, **libmudletproto 0.30 MB** (engine + Lua + bridge, stripped; 2.9 MB unstripped), openssl TLS backend 0.30 MB, qoffscreen 0.10 MB |
| 16 KB page alignment | `zipalign -c -P 16 -v 4`: "Verification successful", every `.so` OK. `llvm-readelf -lW`: every LOAD segment in all 16 `.so` files is aligned to `0x4000`. |

## How it is wired
**Packaging.** `app/build.gradle.kts` registers a `buildNative` Exec task before `preBuild` that runs `scripts/build-native.sh`:
1. `qt-cmake` configures `native/` (Qt's Android toolchain, NDK 28, `ANDROID_PLATFORM=android-28`).
2. `ninja -j2 mudletproto_prepare_apk_dir` builds `libmudletproto_arm64-v8a.so` (a `qt_add_executable`) and copies it to `android-build/libs/arm64-v8a`.
3. `androiddeployqt --aux-mode` copies the Qt libraries and plugins and writes `res/values/libs.xml`.
4. The script stages `libs/`, `res/` and `assets/` into `build-android/stage`.

The app module adds the stage as `jniLibs`, `res` and `assets`, and also adds `$qtAndroidDir/src/android/java/res` and `Qt6Android.jar`, `Qt6AndroidNetwork.jar` and `Qt6AndroidNetworkInformationBackend.jar` as file dependencies. Gradle builds the Compose app as a normal module. androiddeployqt never runs Gradle, and the Qt Gradle Plugin is not used. The Qt template's `QtActivity`/`QtApplication` are not used either.

**Startup sequence:**
1. `ProtoApp.onCreate()` records `elapsedRealtime`, creates the "Game connection" and "Tells" channels, and starts a process-wide `MainScope` collector of `NativeBridge.events`.
2. `MainActivity.onCreate()` asks for POST_NOTIFICATIONS (first creation only), then calls `startForegroundService()` and `bindService(BIND_AUTO_CREATE)`. It never touches Qt or JNI.
3. `MudletEngineService.onCreate()`, on the main thread:
   - calls `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` first, so a slow library load cannot miss the 5 s deadline;
   - then calls `super.onCreate()` (`QtServiceBase`), which:
     - calls `QtNative.setService(this)`;
     - runs `QtServiceLoader` (reads `<service>` meta-data and `libs.xml`, sets env, and `System.load()`s c++_shared, Qt6Core, Gui, Network, Svg, the qtforandroid plugin, then `libmudletproto`; **our `JNI_OnLoad` runs here** and `RegisterNatives` binds `NativeBridge`);
     - calls `QtNative.startApplication()`, which runs `initAndroidQpaPlugin` synchronously on the Java thread `QtThread`, posts `startQtNativeApplication` there, and then **blocks the main thread in `waitForServiceSetup()`**.
4. On Qt's thread, Qt `dlsym`s and calls our `main()`. It first calls `QtAndroidPrivate::acuqireServiceSetup(0); setOnBindListener(&nullListener)`, which releases the semaphore. It then waits (up to 10 s) on a C++ condition variable.
5. The main thread returns from `startApplication()`, `QtServiceBase` calls `setApplicationState(1 = Hidden)` and returns, and our `onCreate()` calls `NativeBridge.nativeServiceReady()`.
6. `main()` constructs `QGuiApplication` (android QPA, with the service as context), creates `JniFrontend` + `Engine`, reports timings, pid, tid, dpr and Qt version, calls `engine.start()` (font check and 30 s heartbeat), publishes the engine pointer to the bridge (replaying any calls queued before it existed), and calls `app.exec()`.

**Threads.** The Android main thread runs Compose, the service callbacks and the flow collector. Qt's thread is the Java `QtThread`, attached to the JVM as "QtMainThread". Everything in Qt and the engine runs there: `QGuiApplication`, the event loop, `QTcpSocket`, the timers and Lua. Qt's Java side also starts its own helpers.

**Kotlin to C++.** The Activity calls methods on the bound `MudletEngineService`, which call `@JvmStatic external` functions on `NativeBridge` (registered in `JNI_OnLoad`, no `Java_` symbol names). The C++ side converts the arguments to `QString` and does `QMetaObject::invokeMethod(engine, lambda, Qt::QueuedConnection)` under a mutex. Before the engine exists, calls are buffered. Nothing blocks the caller.

**C++ to Kotlin.** `JniFrontend` (`IFrontend`) calls the static `NativeBridge.onLine(String, int[] runs, boolean)`, `onNotify` and `onStatus` from Qt's thread. They `tryEmit` into a `MutableSharedFlow` (extra buffer 8192, and drops are counted and shown on screen). `EngineRepository` collects it on `Dispatchers.Main` into `StateFlow`s that Compose renders with `collectAsStateWithLifecycle`. `notify()` posts to the "Tells" channel.

**Foreground service declaration.**
- The `<service android:foregroundServiceType="specialUse" android:exported="false">` element carries `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>`.
- The Qt meta-data goes **on the `<service>` element**: `android.app.lib_name=mudletproto`, `android.app.arguments=""` and `android.app.background_running=true`. Without the last one, QtLoader sets `QT_BLOCK_EVENT_LOOPS_WHEN_SUSPENDED=1`.
- Permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, INTERNET and ACCESS_NETWORK_STATE.
- `onStartCommand` returns `START_NOT_STICKY`.
- `onBind` returns a local `Binder`, overriding `QtServiceBase.onBind`, which would ask Qt's native listener and get `null`.

## Qt internals relied on
Each item is something Qt does not document as public API for this use, and would need re-checking on a Qt upgrade.
1. **`org.qtproject.qt.android.QtServiceBase`** from `Qt6Android.jar`. `bindings.QtService` is shipped only as source in `src/android/java/src` (it is not in the jar), so the service extends `QtServiceBase` directly. Qt documents services only together with `QAndroidService` (a `QCoreApplication`). Pairing one with a `QGuiApplication` is unsupported.
2. **The service-setup semaphore.** `QtNative.startApplication()` calls `waitForServiceSetup()` whenever the context is a `QtServiceBase`, and only `QAndroidService`'s constructor and an `exec()` timer release it. We call `QtAndroidPrivate::acuqireServiceSetup()` (sic) and `setOnBindListener()` from `QtCore/private/qjnihelpers_p.h` via `Qt6::CorePrivate`, which prints Qt's "tied to this specific Qt build" warning. This breaks if Qt renames them or changes the locker logic. Symptom: an ANR in `Service.onCreate`.
3. **Application-state ordering.** `QtServiceBase.onCreate()` sets `Qt::ApplicationHidden`. If that reaches `updateApplicationState()` after the platform integration exists, `androidjnimain.cpp` calls `QAndroidEventDispatcherStopper::goingToStop(true)`, and the event dispatcher then **excludes timers and socket notifiers**. The engine would silently stop working. Before the integration exists, the state is only stored as pending and later applied with no `goingToStop`. `main()` therefore waits for `nativeServiceReady()`, sent after `super.onCreate()` returns, before it constructs `QGuiApplication`. This depends on the pending-state code path. The screen shows `appState` (expect 1) and the heartbeat so the failure would be visible.
4. **Meta-data keys** `android.app.lib_name`, `android.app.arguments` and `android.app.background_running`, read by `QtLoader` through `getServiceInfo(new ComponentName(ctx, ctx.getClass()), GET_META_DATA)`. They must therefore be on the concrete service class's `<service>` element.
5. **`libs.xml`** resource names `qt_libs`, `bundled_libs`, `load_local_libs`, `use_local_qt_libs`, `bundle_local_qt_libs` and `system_libs_prefix`, plus the `lib<name>_<abi>.so` naming that QtLoader appends `_arm64-v8a` to. These are produced by `androiddeployqt --aux-mode`. The tool is public, but using its output in a foreign Gradle module is not a documented flow.
6. The CMake target **`mudletproto_prepare_apk_dir`**, an internal target that Qt's `qt_add_executable` generates and that copies the `.so` where androiddeployqt expects it. The `<target>-deployment-settings.json` file name is also internal.
7. **`main()` is `dlsym`ed from the main library and run on `QtThread`.** After `main()` returns, `startQtNativeApplication` calls `stopSelf()` on the service and then C `exit(ret)`, unless `QT_ANDROID_NO_EXIT_CALL` is set.
8. **`QtServiceBase.onDestroy()`** calls `terminateQtNativeApplication()` (quit, wait for `main()` to return, tear down) and then `System.exit(0)`. We skip `super.onDestroy()` for "Stop engine". Android does not require the call for services; this is verified only by reading the `android.app.Service` contract.
9. **`QtServiceBase.onCreate()` returns early when `QtNative.getStateDetails().isStarted`**, logging "A QtService tried to start in the same process as an initiated QtActivity…". We rely on this to re-attach a new service instance to a Qt that is still running.
10. **Package-private `QtNative` members**: `getStateDetails().isStarted`/`.state`, `setService()` and `isServiceValid()`. They are reached from `app/src/main/java/org/qtproject/qt/android/QtProtoAccess.java`, which sits in Qt's package so that a rename breaks the build, not the app.
11. **`g_jService`** in `qjnihelpers.cpp` is a global ref taken once when libQt6Core loads. After a service re-creation, C++ still points at the first, destroyed `Service` object. Context calls generally still work, but it is stale. `QtNative.setService()` only refreshes the Java side.
12. Qt's Java resources (`src/android/java/res`) and the R8 keep rule `-keep class org.qtproject.qt.android.** { *; }`: Qt's C++ finds its Java classes by name.

## Lifecycle
- **Activity finished ("Kill UI") or recreated (rotation)**, *expected*: `onDestroy` unbinds. The service stays started and in the foreground, so Qt and the connection carry on. `EngineRepository` lives in the process, so a new Activity shows the history and current counters, and the pids and Qt start time stay the same. Swiping the task from recents calls `onTaskRemoved` (recorded as `svc.taskRemoved`). The service should keep running because it is in the foreground. Some OEMs kill the process anyway.
- **Service stopped**, *expected*. There are two buttons:
  - **"Stop engine"** unbinds, calls `stopService()`, disconnects in `onDestroy()` and skips `super.onDestroy()`. Qt keeps running idle in the process. "Start engine" creates a new service instance whose `QtServiceBase.onCreate()` logs the "detached from Qt" warning and returns; we call `QtNative.setService(this)` and keep using the same engine.
  - **"Stop + exit Qt"** calls `super.onDestroy()`: `QCoreApplication::quit()`, the main thread waits for `main()` to return, then `System.exit(0)`. The Qt thread may race it with C `exit()`. The **whole process ends, Activity included**. Android may then restart the Activity in a new process, which would start Qt again.
  - Qt itself cannot be stopped and restarted within one process (Qt's own comment, QTBUG-130614); only one Qt app per process is supported. That is why "stop" either keeps Qt or kills the process.
- **Process killed and restarted**, *expected*: `START_NOT_STICKY`, so nothing comes back on its own. The next launch is a cold start: Application, Activity, service, then Qt loaded and started again, with the connection lost. If `START_STICKY` were used, the service would come back with Qt but no game connection.
- **Screen off for a long time**, *expected*: the foreground service keeps the process at foreground-service priority and exempt from Doze's network block, but holds no wakelock. When the CPU suspends, Qt's timers do not fire; incoming packets may still wake it. The heartbeat's max gap shows which: about 30 000 ms means timers kept running, much more means the CPU slept. Mudlet would probably need a partial wakelock while connected, or tolerance for late timers.

Nothing in this section is verified; there was no device or emulator.

## Cost to build Mudlet on it
- **Engine swap.** `Engine` becomes `mudlet_core` (`Host`, triggers, Lua, the mapper model). Each frontend interface (TConsoleFrontend, TAppFrontend, …) is implemented by a C++ class in `native/` that calls `NativeBridge` statics, as `JniFrontend` does here. Every Kotlin-to-core call is a queued lambda, so APIs must be asynchronous. Any synchronous query from Kotlin would need `BlockingQueuedConnection`, which deadlocks if the Qt thread ever calls back into the main thread synchronously. `mudlet_core` must not create or show widgets: in a `QtServiceBase` app the android QPA reports no rendering context (`isValidAndroidContextForRendering()` needs an Activity), so `QWindow`/OpenGL/`QtView` are out. Offscreen raster (`QImage`, `QPainter`, `QTextLayout`) should work. Anything Qt-rendered (e.g. a Qt map widget) cannot be shown in Compose in this mode. `QtView` embedding needs Qt's separate "embedded" mode (`QtServiceEmbeddedDelegate`), which is a different start-up path. If `mudlet_core` links QtWidgets, `QApplication` (a `QGuiApplication` subclass) should start the same way.
- **Build.** All of Mudlet's C/C++ dependencies (Lua, PCRE2, yajl, hunspell, libzip, qtkeychain, …) have to be cross-built in the same CMake tree; that cost is the same for every approach. The CMake/Gradle split is simple: one Exec task and a staging directory. But Gradle sees the native build as a black box: incremental native rebuilds rely on ninja, and the up-to-date check is coarse. Android Studio does not know the C++ sources, so native debugging means attaching LLDB manually with `build-android/` symbols. Moving to AGP `externalNativeBuild { cmake }` with `qt.toolchain.cmake` would fix the IDE side, but androiddeployqt would still be needed for Qt's libraries and `libs.xml`.
- **Daily development.** A C++-only change takes about 40 s to a new APK here. Desktop builds of `mudlet_core` stay the fast inner loop, as `desktop/` shows. A crash in `mudlet_core` or Lua kills the UI as well, which is the main downside against approach C.
- **Startup.** Qt's library loading (about 20 MB of `.so`) and the wait for `main()` both run on the Android main thread inside `Service.onCreate()`, so they can delay the first frame. `svc.superOnCreateMs` measures this on the device.

## Size of the prototype
- Kotlin: 493 lines (5 files). Java: 29 (`QtProtoAccess`).
- C++ engine: 587 (engine/). C++ Android bridge (`main.cpp`, `JniBridge`): 335.
- Desktop test: 168 C++ + 56 Python.
- Build files (Gradle `.kts`, properties, manifest, ProGuard, 3 CMakeLists, `build-native.sh`): 278.
- Vendored Lua 5.1.5 sources are not counted. The tarball's sha256 is `2640fc56…5333`.

## On-device checklist
Install with `adb install -r app-debug.apk`, then watch `adb logcat -s MudletProto MudletEngineService "Qt JAVA" QtLoader Qt QtCore AndroidRuntime ActivityManager`.
1. **Start.** The app opens with no ANR, and the first status line says `started`, with `main()` and `QGuiApplication` times and `superOnCreate` duration. An ANR in `Service.onCreate` means the semaphore release (internal 2) failed; a 10 s pause means `qt.serviceReadyWait` timed out.
2. **Qt on the device.** Check `qpa=android`, `appState=1`, and dpr. In the font line, note the family Qt resolved, the 'M' advance and the columns. Columns are computed from physical view pixels and Qt's logical-pixel advance, so compare the result with dpr.
3. **Connection.** Connect to aardmud.org:23. Expect coloured output and prompts (yellow). Type commands, and `lua return lineCount`. Check that the lines and Lua onLine counters match, and that dropped is 0.
4. **Tells.** `lua onLine("Bob tells you hi")`, or a real tell, should post a "Tells" notification once the permission is granted.
5. **Foreground service.** `adb shell dumpsys activity services org.mudlet.proto.a`: expect `isForeground=true`, `foregroundServiceType=0x40000000`, and the persistent notification visible. The screen should show `fgsType 0x40000000`. ui, svc and qt pids should all be equal.
6. **Timers keep running.** After about 2 minutes, heartbeat ticks should be increasing and the max gap about 30000. If ticks stay at 0, or Connect never completes, internal 3 has bitten.
7. **Screen off.** Lock the screen for 30+ minutes while connected. Note the max gap and whether the connection survived.
8. **Kill UI.** Press "Kill UI", wait, then reopen. Output should have continued and the pids should be unchanged. Also try rotating.
9. **Stop and restart.** "Stop engine", then "Start engine": logcat should show Qt's "detached from Qt" warning, the screen `reattached to running Qt`, and Connect should still work.
10. **Stop and exit.** "Stop + exit Qt": the process should exit (`main() returned` in logcat). Check for a native crash or tombstone at exit, and whether Android restarts the Activity.
11. **Recents.** Swipe the app away from recents while connected. Check whether the service survives on this OEM.

## Problems hit
- **Build-tools in the shared SDK.** AGP 8.13 defaults to build-tools 35.0.0 and **silently installed it into `/opt/android-toolchain/sdk/build-tools/35.0.0`** on my first build (18:02:33 UTC). I then pinned `buildToolsVersion = "37.0.0"`. I left the directory in place, because the other agents' concurrent builds may now be using it. A `36.0.0` directory appeared a minute later from another build. Both can be removed once all builds are done.
- **Maven Central rate limit.** `repo.maven.apache.org` answered **429 Too Many Requests** during plugin resolution, with three agents downloading at once. Fix: Google's Central mirror `https://maven-central.storage-download.googleapis.com/maven2/` is listed before `mavenCentral()` in `settings.gradle.kts`.
- **Missing `CorePrivate` target.** `Qt6::CorePrivate` was "not found" until `CorePrivate` was added to `find_package(Qt6 COMPONENTS …)`.
- **androiddeployqt could not find the app binary.** It reported "Cannot find application binary in build dir …/android-build//libs/arm64-v8a/libmudletproto_arm64-v8a.so" when only `mudletproto` was built. Build `mudletproto_prepare_apk_dir` instead.
- **`bindings.QtService` is not in `Qt6Android.jar`.** Extend `QtServiceBase`, or add `$qtAndroidDir/src/android/java/src` to the source dirs as Qt's template does.
- **No local Qt Java sources.** The Qt kit has no Java sources for `QtServiceBase`/`QtNative`/`QtLoader`. The sandbox refused to run a downloaded decompiler. I used `javap -c` on the jar, plus the matching sources from the `qt/qtbase` GitHub mirror at tag `v6.12.0` (`androidjnimain.cpp`, `qjnihelpers.cpp`, `QtNative.java`, `QtLoader.java`, the QPA plugin files). The two agree.
- **Lua build warning.** Lua 5.1.5 gives one `-Wempty-body` warning in `lauxlib.c`, which is harmless.
- **How to build.** There is no Gradle wrapper; run `gradle --no-daemon assembleDebug` with `/opt/gradle` 8.14.3. `gradle.properties` caps workers at 2, the heap at 1.5 GB, and runs the Kotlin compiler in-process.
