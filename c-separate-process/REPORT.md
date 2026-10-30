# Approach C: separate engine process

## Verdict
The split builds cleanly and its protocol side is proven on the host. The app's
own Kotlin socket code talks to the real engine at about 50-110 µs per round
trip. It delivers 50k lines/s without loss, and it reconnects on its own about
110 ms after the engine is killed. Keeping Qt out of the UI process removes a
whole class of problems:
- Qt's `System.exit(0)` on service stop no longer takes the UI down.
- Qt never touches the Activity.
- A frozen or killed UI cannot stall the engine.

The cost is that every engine-to-UI interaction has to become an asynchronous
message: no synchronous getters, no shared objects. Mudlet's UI code calls into
`Host`/`TConsole` directly everywhere, so that is real redesign work.

The biggest risk is starting a `QGuiApplication` inside a `QtServiceBase`, which
Qt does not support. Qt pairs services only with `QAndroidService`. Two fixes
from prototype A are ported, but neither has been tried on a phone:
- The engine releases Qt's private service-setup semaphore itself. Without
  this, `Service.onCreate()` never returns and the engine ANRs.
- `main()` waits for a "service ready" call before it creates
  `QGuiApplication`. Without this, Qt's `ApplicationHidden` state would stop
  the engine's timers and sockets.

Both rely on Qt private API (`Qt6::CorePrivate`), which ties the build to one
exact Qt version.

## Status
| Check | Result |
|-------|--------|
| Engine desktop build and fake-server test | **pass** (rerun after the startup fixes). `tests/test_engine.py`: 0 failures (`tests/results-desktop.txt`). The JVM test of the app's Kotlin client against the same engine also passes (`tests/results-jvm.txt`). |
| APK builds (`assembleDebug`) | **yes** |
| APK path | `/home/user/android-prototypes/c-separate-process/android/app/build/outputs/apk/debug/app-debug.apk` |
| Clean build time | **1 min 27 s** wall time with Gradle dependencies cached: 20 s CMake configure plus native build (44 steps, `ninja -j2`) and aux-mode deploy, then 1 min 7 s Gradle. The machine was shared with two other agents' builds. The first build, which downloaded dependencies, took 4 min 58 s. An incremental Kotlin-only rebuild takes 33 s. |
| APK size (download estimate and uncompressed `lib/arm64-v8a` total) | Debug APK 48.6 MB, of which 27 MB is unminified debug dex (Compose and Kotlin). `lib/arm64-v8a`: 16 files, **19.5 MB** uncompressed, stored uncompressed (`useLegacyPackaging=false`). The whole APK xz-compresses to 11.8 MB (libs 5.0 MB, dex 6.6 MB). A minified release build would be about **6-7 MB to download** (estimate: libs plus about 1 MB of R8-shrunk dex). |
| Largest native libraries | `libQt6Gui` 7.8 MB, `libQt6Core` 6.2 MB, `libQt6Network` 1.8 MB, `libc++_shared` 1.2 MB after AGP strips it (8.8 MB unstripped), `qtforandroid` plugin 0.7 MB, `libQt6Svg` 0.6 MB, `libengine` 0.3 MB (the engine plus Lua 5.1.5) |
| 16 KB page alignment (`zipalign -c -P 16 -v 4`, and `readelf -lW` LOAD alignment of each .so) | **pass**. `zipalign` reports "Verification successful", with every `lib/arm64-v8a/*.so` marked OK. All 15 deployed `.so` have every LOAD segment at `0x4000`. |

Measured from the app's Kotlin code (`EngineLink`, `FrameReader`/`FrameWriter`)
against the desktop engine, `tests/run_jvm_client_test.sh`, three runs:

| | Result |
|---|---|
| ping round trip (Kotlin, then Unix socket, then engine, then back) | median 45-114 µs, p99 268-397 µs |
| paced flood 25k and 50k lines/s | every line delivered |
| paced flood 100k and 200k lines/s | 53-67% and 18-20% delivered; the rest is reported in `gap` messages, and nothing is lost silently |
| engine SIGKILL, until the UI sees EOF | 6-16 ms |
| engine SIGKILL, until reattached to a restarted engine | 108-112 ms. This includes the test spawning a new engine (about 40 ms) and the 100 ms reconnect backoff. |

From the Python client in `tests/test_engine.py`:

| | Result |
|---|---|
| ping | median 83 µs, p99 211 µs |
| `runLua` round trip | median 106 µs, p99 226 µs |
| send, game echo, line back (includes the telnet hop) | median 160 µs, p99 401 µs |
| engine CPU per line | 4.05 µs with a UI attached, 2.65 µs with none |
| engine alone, no UI attached | 908k lines/s through telnet, ANSI parsing and Lua `onLine` |
| wire size | about 146 B per coloured line |
| full replay of the 2000-line ring on attach | 44 ms |

## How it is wired
```
UI process (org.mudlet.proto.c)                 :engine process (org.mudlet.proto.c:engine)
Kotlin + Compose, no Qt                         QtServiceBase + libengine.so (Qt Core/Gui/Network)
MainActivity -> EngineClient (object)           EngineService : QtServiceBase
   EngineLink reader thread  <== LocalSocket ==>   QLocalServer (main Qt thread)
   (frames: u32 BE length + UTF-8 JSON)            SocketBridge : IFrontend -> Engine (telnet, ANSI, Lua)
                                                   AndroidNotifier -> EngineNotifications.postTell (JNI)
```

Startup:
1. The UI calls `startForegroundService`. `ProtoApp.onCreate` runs in the new
   `:engine` process and creates the notification channels.
2. `EngineService.onCreate` uses `Os.setenv` to set the socket path, the
   timestamps and `QT_QPA_PLATFORM`.
3. It calls `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` before
   Qt loads, so a slow library load cannot miss the start-foreground deadline.
4. It calls `super.onCreate()`. That is QtServiceBase, which loads the Qt
   libraries listed in the generated `libs.xml` and starts `libengine`'s
   `main()` on Qt's thread. It then blocks the service's main thread in
   `QtNative.waitForServiceSetup()`.
5. On Qt's thread, `main()` first releases that wait
   (`AndroidServiceSetup::releaseQtServiceSetup()`), so `super.onCreate()` can
   return.
6. `EngineService` then calls `EngineNative.nativeServiceReady()`, a JNI
   function bound by name in `libengine`. `main()` waits up to 10 s for this
   call. Only after it does `main()` create `QGuiApplication`, the `Engine` and
   the `SocketBridge`, and call `listen()` on `noBackupFilesDir/engine.sock`.
7. The UI's `EngineLink` retries the connection with backoff from 100 ms to 2 s
   until the socket appears.

Threads:
- The engine is single-threaded on Qt's main thread: socket, telnet, Lua and
  timers.
- In the UI, one daemon reader thread per link decodes frames. Lines and
  statuses are batched and flushed to Compose state on the main thread every
  16 ms.
- Writes from the UI go out on the caller's thread through a synchronized
  `FrameWriter`. Each one is a small frame.

Crossing the boundary:
- UI to engine: requests (below).
- Engine to UI: pushed lines, statuses and notifies.
- There are no calls in either direction. A reply is a `status` frame carrying
  `re`, the request id.
- Inside `:engine`, the only JNI calls are:
  - Java to native: `nativeServiceReady`, once at startup.
  - Native to Java: `QJniObject::callStaticMethod` for `postTell`.

Foreground service declaration:
- `<service android:name=".engine.EngineService" android:process=":engine" android:exported="false"
  android:foregroundServiceType="specialUse">`
- A `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining the long-lived MUD
  connection.
- The `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions.
- Qt's `android.app.lib_name=engine`, `android.app.arguments` and
  `android.app.background_running=true` meta-data on the service.

Security: the socket is a filesystem socket inside the app's private directory.
The server uses `QLocalServer::UserAccessOption` and checks the peer uid with
`SO_PEERCRED` against `getuid()`, so other apps cannot connect.

### Protocol (version 1)
A frame is a 4-byte big-endian length followed by a UTF-8 JSON object, at most
16 MiB. Every object has `t`. Unknown `t` gets `error`; a bad hello gets
`error` and the connection is closed.

UI to engine:
- `hello{v, client, instance?, lastSeq?}`
- `ping{id}`
- `connect{host, port}`
- `send{text}`
- `disconnect`
- `runLua{code, id}`
- `setViewWidthPx{px}`

Engine to UI:
- `hello{v, pid, instance}`
- `line{seq, text, runs:[[start,len,fg],...], prompt, replay?}`
- `status{key, value, re?}`
- `notify{title, text}`
- `pong{id}`
- `gap{skipped}`
- `synced{seq}`
- `error{message}`

On hello the engine sends:
1. its own `hello`
2. every current status
3. the ring buffer: the last 2000 lines, or only those after `lastSeq` when
   `instance` matches
4. `synced`

The engine never blocks on the UI:
- When more than 4 MB is queued unsent, the UI is marked lagging. Lines keep
  going into the ring but are not written.
- Below 256 KB the engine catches up from the ring, and sends `gap` for whatever
  fell out of it.
- Replies (`pong`, and statuses carrying `re`) are always written, even to a
  lagging UI.
- A new connection replaces the old one.

This matters on Android because the cached-app freezer can freeze a
backgrounded UI process mid-read. Verified on the host:
- With a stalled reader, the engine kept running at 42-60 MB RSS. When the
  reader resumed it received 1999 lines and one `gap`.

## Qt internals relied on
- **`org.qtproject.qt.android.QtServiceBase`** (from `Qt6Android.jar`).
  - Qt documents extending `QtService`, which is in the bindings sources only and
    not in the jar. I extend `QtServiceBase` directly.
  - The behaviour I depend on, read with `javap`:
    - `onCreate` refuses to start if `QtNative` is already started.
    - It calls `QtNative.setService`, then `QtServiceLoader.loadQtLibraries`,
      then `QtNative.startApplication`.
    - `onBind` returns `QtNative.onBind`.
    - `onDestroy` calls `terminateQtNativeApplication` and then **`System.exit(0)`**.
  - It would break if the class is renamed or made final, or if the order of
    `onCreate` changes, which would invalidate my "start foreground first" call
    before `super.onCreate()`.
  - The separate process is exactly what makes `System.exit(0)` harmless here.
- **Qt's service-setup semaphore**, which uses private API from
  `QtCore/private/qjnihelpers_p.h` through `Qt6::CorePrivate`. Qt's configure
  prints a warning that the build is now "tied to this specific Qt module
  build version".
  - In Qt 6.12.0, `QtNative.startApplication()` calls `waitForServiceSetup()`
    whenever `QtAndroidPrivate::service().isValid() &&
    QtAndroid::isQtApplication()` is true (androidjnimain.cpp:555-562). That
    holds for any `QtServiceBase`.
  - The semaphore is released only by `setOnBindListener()`, once the locker
    count is zero. Only `QAndroidService` does that (qandroidextras.cpp:551/620),
    so a `QGuiApplication` never releases it, and the QPA plugin makes no
    difference.
  - At the top of `main()`, `enginehost/AndroidServiceSetup.cpp` calls
    `QtAndroidPrivate::acuqireServiceSetup(0)` (Qt's misspelling) and then
    `setOnBindListener()` with a listener that returns null.
  - It breaks if Qt renames these functions or changes the locker logic. The
    symptom would be an ANR in `Service.onCreate` with no engine.
  - The source line numbers above come from prototype A's reading of Qt's
    source. The private header is checked here; the source is not installed.
- **The ordering of application state.**
  - `QtServiceBase.onCreate()` sets `Qt::ApplicationHidden`. If that state
    reaches `updateApplicationState()` after the platform integration exists,
    Qt calls `QAndroidEventDispatcherStopper::goingToStop(true)`. The event
    dispatcher then excludes timers and socket notifiers, and the engine stops
    silently.
  - Before the integration exists, the state is only stored as pending and is
    applied later without `goingToStop`.
  - So `main()` does not create `QGuiApplication` until
    `EngineNative.nativeServiceReady()` arrives, and that is sent after
    `super.onCreate()` returns. This depends on the pending-state code path.
  - The self-test shows `appState` (1 = Hidden) and the service-ready result,
    and the heartbeat and line counters show whether timers and sockets still
    run.
  - `nativeServiceReady` is bound by its JNI name. That relies on `QtLoader`
    loading `libengine` through the app's class loader.
- **`QtLoader` metadata lookup.**
  - It reads `android.app.lib_name`, `android.app.arguments` and
    `android.app.background_running` from `ServiceInfo`, through
    `ComponentName(context, context.getClass())`.
  - So the meta-data must sit on the service that is the concrete subclass.
  - `background_running` sets `QT_BLOCK_EVENT_LOOPS_WHEN_SUSPENDED`. Its meaning
    for a service is not documented.
- **The resource arrays in `libs.xml`:** `qt_libs`, `bundled_libs`,
  `load_local_libs`, `use_local_qt_libs` and `bundle_local_qt_libs`.
  - They are generated by `androiddeployqt --aux-mode`, which copies the
    libraries, plugins and jars into `android/qtdeploy/` without building.
  - `build-apk.sh` then deletes the generated `build.gradle`,
    `settings.gradle` and `AndroidManifest.xml`, and the app's own Gradle build
    consumes `qtdeploy/libs` and `qtdeploy/res`.
  - Both the libs.xml keys and aux-mode's output layout are internal to Qt and
    can change in any release.
- **Qt's `src/android/java/res`** is added as a resource directory, for the
  values QtLoader expects.
- **The `android` QPA plugin in a process with no Activity.** Qt only
  documents `QAndroidService`, which is `QCoreApplication`-based, for services.
  `QGuiApplication` in a service process is **not verified**, even with the two
  fixes above.
  - The fallback is `QT_QPA_PLATFORM=offscreen`: the `qoffscreen` plugin is
    deployed and a button toggles it.
  - The fallback does not help with the semaphore, which is independent of the
    QPA plugin.
- **`QNativeInterface::QAndroidApplication::context()`** returns the Service
  inside the service process. This one is public API.
- **Build versions.** The Qt 6.12 templates expect AGP 9.2.1, but the system
  Gradle here is 8.14.3, so the app uses AGP 8.13.2 and Kotlin 2.3.21. The
  compile and target SDK are set to 36 in both CMake and Gradle, overriding
  Qt's default of 37.

## Lifecycle
- **Activity finished ("Kill UI") or recreated (rotation).**
  - *Verified on the host; expected on the phone.*
  - `onStop` stops the UI's link. Qt is in another process and does not notice
    anything except the socket closing. The game connection stays up (host test:
    "game connection survived the UI detaching").
  - `onStart` restarts the **same** `EngineLink`. Because it sends its
    `instance` and `lastSeq`, the engine replays only the lines the UI missed
    (JVM test: `synced.seq == seqBefore`).
  - A quick `stop()` followed by `start()` used to leave two reader threads
    fighting over the engine. It is fixed with a per-thread generation; the JVM
    test reproduces the bug and fails without the fix.
  - Killing the UI process instead gives a new UI the full 2000-line ring plus a
    `gap` marker, in 44 ms on the host.
- **Service stopped** (the "Stop engine" button, or the notification's Stop action).
  - *Expected.*
  - `stopService` leads to `QtServiceBase.onDestroy`: Qt terminates and then
    `System.exit(0)` ends **only** `:engine`.
  - The UI sees EOF (3-16 ms on the host) and shows "engine connection lost;
    reconnecting". Because `engineWanted` is now false, it does not restart the
    engine; "Start engine" starts a fresh process.
  - Qt cannot be restarted inside the same process, but that never comes up here.
- **Process killed and restarted.**
  - Killing `:engine` ("Kill engine process", or the low-memory killer): the UI
    sees EOF and retries. After 3 failed connects, while an Activity is started
    and the engine is wanted, it calls `startForegroundService`; that is allowed
    because the app is in the foreground.
  - The service is `START_NOT_STICKY`: the system does not resurrect it, because
    a resurrected engine has lost its game connection anyway.
  - The new engine has a new `instance`. The UI keeps its old lines and adds a
    "new engine instance" marker. The game connection and the engine's
    scrollback are gone.
  - Host timings: the restarted engine is reachable 33 ms after it starts, and
    the JVM link reattaches 110 ms after the kill.
  - On a phone, expect a few hundred ms for the process fork and Qt library load.
    The self-test shows "loss->attach ms" and "UI start->hello ms".
  - Killing the UI process does not touch the engine: the notification stays and
    tells are still posted. Host test: with no UI attached, every line still
    reached Lua `onLine`.
- **Screen off for a long time.**
  - *Expected; check the heartbeat on the phone.*
  - `:engine` is a foreground service, so it is not cached or frozen and keeps
    network access. Doze may still delay its timers, and the heartbeat's
    "largest gap" will show it.
  - The UI process is backgrounded and may be frozen. That is harmless, because
    it detached in `onStop` and the engine never waits for it.

### Which notification work is in which process
- **`:engine`:**
  - The foreground-service notification, which must come from the service
    calling `startForeground`.
  - All "Tells" notifications: Lua `notify()` goes through `SocketBridge::notify`
    and `AndroidNotifier` (JNI) to `EngineNotifications.postTell`. They work
    while the UI is dead or frozen.
  - The Stop action is a service `PendingIntent` handled in `onStartCommand`.
  - The counters are sent to the UI as statuses.
- **UI process:**
  - The `POST_NOTIFICATIONS` runtime request, which needs an Activity.
  - The tap target: the notification's content intent opens `MainActivity`.
  - The UI only counts the `notify` frames it sees; it never posts.
- **Both:** `Application.onCreate` runs in both processes, and channel creation
  is idempotent.

### Memory: two-process estimate
- **Measured on the desktop engine:**
  - Idle: RSS 30.5 MB, PSS 15.5 MB.
  - After floods: RSS 43.7 MB, PSS 28.6 MB.
  - Peak while a UI stalls: RSS 60 MB, bounded by the 4 MB high-water mark plus
    the ring.
- **Estimated on Android:** `:engine` is a full ART process forked from zygote,
  plus Qt.
  - Compared with running everything in one process, the extra cost is a second
    ART runtime with its own heap and JIT. That is typically about 8-15 MB PSS
    for a process that runs only a service.
  - The scrollback is held twice, by the UI and by the engine ring, at about
    0.5-1 MB each for 2000 lines.
  - Qt is mapped only in `:engine`, so nothing large is duplicated.
- **Total estimate: 10-20 MB PSS** more than an in-process design. Not measured.
  On the phone, run `adb shell dumpsys meminfo org.mudlet.proto.c` and
  `... org.mudlet.proto.c:engine`.

## Cost to build Mudlet on it
- **The engine side is straightforward.** `libengine.so` would become
  `mudlet_core` plus a socket-backed `TConsoleFrontend`/`TAppFrontend`. The
  bridge is a few hundred lines: a frame codec, a ring buffer and request
  dispatch. It already builds for desktop, so the whole engine-plus-protocol
  stack can be tested headless with no phone, as done here.
- **The UI side costs more.** Everything the UI needs must be pushed as state or
  answered by request id, because there is no synchronous getter such as
  "what is the current prompt".
  - Mudlet's frontends reach into `Host`, `TConsole`, the mapper and the editors
    synchronously.
  - Every one of those becomes a protocol message and a Kotlin model: consoles,
    mini-consoles, labels, gauges, the map, trigger and alias editors, settings,
    and the package manager.
  - Large payloads, such as the map or a long scrollback, need incremental
    messages rather than snapshots.
  - Versioning the protocol (`v` in hello) is required from day one, because the
    two halves ship together but must reattach across an app update's process
    restarts.
  - The upside: these are the same seams a remote, headless or multi-client
    Mudlet would need.
- **Daily development.**
  - Two languages and two build systems: CMake builds `libengine` and
    `androiddeployqt --aux-mode` stages it; then Gradle builds an APK that has
    no Qt Gradle plugin in it. This is easier than embedding Qt's Gradle
    template, because the Android side is a normal Compose project that Android
    Studio understands.
  - Debugging is per process. The engine can be debugged on desktop against the
    fake server, and Kotlin with ordinary tools. Native debugging on the phone
    means attaching lldb to `:engine`.
  - Builds: about 1.5 min clean, and 33 s for a Kotlin-only change on this
    shared machine.
- **Lower lifecycle risk.** Qt's lifecycle assumptions, such as one start per
  process and `System.exit` on destroy, never meet the Activity. A Qt crash
  cannot take down the UI; the UI shows "reconnecting" and restarts the engine.

## Size of the prototype
| Part | Lines |
|---|---|
| C++ engine (`engine/`: telnet, ANSI, Lua, heartbeat, font) | 495 |
| C++ host (`enginehost/`: socket bridge, `main`, JNI notifier, service setup) | 532 |
| Kotlin app (`android/app/src/main`) | 914 |
| Kotlin JVM test | 211 |
| Build files (CMake, Gradle, manifest, `build-apk.sh`) | 227 |
| Host tests (Python fake server, client and tests, shell) | 609 |

Lua 5.1.5 is vendored in `third_party/lua-5.1.5` (tarball sha256 `2640fc56…695333`).

## On-device checklist
1. Install the APK with `adb install -r app-debug.apk`. Open it and allow notifications.
2. **The critical check: does Qt start in a service-only process?**
   - There must be no ANR, and no "isn't responding" dialog, in the first 10 s.
     An ANR in `Service.onCreate` means the semaphore release did not work.
   - The top line should show "Qt up N ms after Application.onCreate (:engine)".
   - The font line should show a real family name (expect Roboto Mono or Droid
     Sans Mono) with QPA `android`.
   - "service-ready handshake" should read `ok`. `timeout` means
     `nativeServiceReady` did not bind; check logcat for `UnsatisfiedLinkError`.
   - Note the `appState` value.
   - Wait 30-60 s. Heartbeat ticks must keep rising, and after Connect, lines
     must arrive. A frozen heartbeat with a live process means Qt's
     `ApplicationHidden` stopped the dispatcher.
   - If the link stays "engine not reachable", collect
     `adb logcat | grep -iE "qt|Mudlet|engine"`.
   - Then press "QPA next start: offscreen", "Stop engine" and "Start engine",
     and see whether the engine comes up with `offscreen`.
3. Check the foreground service. The line should read
   `FGS: foreground=true process=org.mudlet.proto.c:engine`, the "Engine running"
   notification should be visible, and the two process ids should differ.
4. Connect to `aardmud.org:23`. Expect coloured output, a prompt and rising line
   counters.
   - Send `lua return lineCount` and check the result.
   - Have someone send you a tell, or use a MUD that echoes one. A "Tells"
     notification should appear.
5. Press "Ping x300" and note the median and p99. The host gives about 50-110 µs;
   a phone will be slower.
6. Rotate the phone. The link should read "same engine, delta replay", with no
   duplicated lines and no repeated attaches.
7. Press "Kill UI", then reopen from the launcher or the notification. The game
   is still connected, and only the missed lines arrive.
8. Press "Kill UI process" and reopen. The game is still connected, and the
   scrollback is replayed.
9. Press "Kill engine process". Expect "engine connection lost", then an
   automatic restart and "new engine instance"; note the loss-to-attach time.
   The game connection is gone, as expected.
10. Press "Stop engine". The `:engine` process and its notification should
    disappear, and the UI stays alive.
    - Check with `adb shell ps -A | grep mudlet`.
    - Then press "Start engine".
11. Leave the phone with the screen off for 30 minutes or more, connected to the
    game.
    - Afterwards, check the heartbeat's largest gap and whether the game is
      still connected.
    - Also check whether tells arrived as notifications while the screen was off.
12. Measure memory with `adb shell dumpsys meminfo org.mudlet.proto.c:engine`, and
    the same for the UI process.

## Problems hit
- **`QJsonDocument` sorts keys**, so `"t"` is not the first key. A test that
  matched on the frame prefix `{"t":"line"` hung, and the heartbeat statuses
  kept resetting its timeout. Match on the content instead.
- **The first backpressure design dropped any client** above 8 MB queued, so a
  normal flood disconnected the UI. Replaced with lag mode: a ring buffer,
  sequence numbers and a `gap` message. That also fits the Android freezer.
- **Replies were swallowed while the UI lagged.** Replies (`re`, `pong`) are now
  always written.
- **A hello with a huge `lastSeq` poisoned the resend cursor.** `lastSeq` is now
  clamped to `[0, nextSeq - 1]` in `greet()`.
- **The UI reattach replayed the whole ring** because a new `EngineLink` was made
  per `onStart` and sent no `instance`. It also raced a quick stop and start
  (rotation) into two reader threads. Fixed with one link per process and a
  per-thread generation.
  - JDK `SocketChannel.close()` waits for a blocked reader, which hid the race
    on the JVM. The test's transport delays the reader's wake-up after a close,
    so the race shows every time.
- **Maven Central returned HTTP 429** for kotlin-stdlib 2.3.21 while three agents
  built at once. Added Google's Maven Central mirror
  (`maven-central.storage-download.googleapis.com/maven2/`) before
  `mavenCentral()`.
- **The Qt 6.12 templates want AGP 9.2.1**, which needs a newer Gradle than the
  installed 8.14.3. Used AGP 8.13.2 and our own Gradle project, with
  `androiddeployqt --aux-mode` for the Qt parts.
- **The engine would ANR at startup on a phone** (reported by the coordinator,
  from prototype A's on-device analysis). Two problems:
  - Qt's service-setup semaphore is released only by `QAndroidService`.
  - The `ApplicationHidden` state, arriving once the integration exists, stops
    timers and sockets.
  Both A fixes are ported: the semaphore is released at the top of `main()`,
  and `main()` waits for `nativeServiceReady()` before creating
  `QGuiApplication`. Neither failure shows on the desktop build, so the host
  tests cannot catch them.
- **Running `clang-format` without this repo's own `.clang-format`** applies
  LLVM style and rewrites whole files. Use
  `--style=file:/home/user/Mudlet/.clang-format`.
- **`QtService` from Qt's docs is not in `Qt6Android.jar`**; it exists only as
  source. Extend `QtServiceBase`.
- **`QAndroidApplication::context()` needs
  `#include <QtCore/qcoreapplication_platform.h>`.**
- **Unit tests compile against `android.jar`**, which lacks
  `UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, `ProcessHandle` and
  `Redirect.DISCARD`. The JVM test reaches the Unix-socket API by reflection and
  kills the engine with `kill -9`.
- **The JVM test runner needs `ANDROID_HOME`**; `run_jvm_client_test.sh` sets it.
- **`pkill -f test_engine.py` from a shell whose own command line contains that
  string kills the shell.** Use the pid.
- **`/usr/bin/time` is not installed.** Use the bash `time` builtin.
