# Architecture

Mudlet for Android runs in two processes. The UI process is Kotlin and Jetpack Compose and loads no Qt. The engine
process (`:engine`) runs Qt and the engine (libmudlet, or the stand-in for fast tests) in a foreground service. They
talk over a local socket with the protocol in [`protocol.md`](protocol.md). Why it is built this way is in
[`decisions/hosting.md`](decisions/hosting.md).

This began as prototype C's report. Its measurements are at the end; its emulator results are in
[`decisions/hosting-results/c-device.md`](decisions/hosting-results/c-device.md).

The design's main cost is that every engine-to-UI interaction is an asynchronous message: no synchronous getters, no
shared objects. Mudlet's UI code calls into `Host`/`TConsole` directly everywhere, so that is real redesign work
(see "What building Mudlet on it costs").

Its main risk is starting a `QGuiApplication` inside a `QtServiceBase`, which Qt does not support: Qt pairs services
only with `QAndroidService`. Two fixes, found in prototype A, make it work on the emulator (see "Qt internals relied
on"):
- The engine releases Qt's private service-setup semaphore itself. Without this, `Service.onCreate()` never returns
  and the engine ANRs.
- `main()` waits for a "service ready" call before it creates `QGuiApplication`. Without this, Qt's
  `ApplicationHidden` state would stop the engine's timers and sockets.

Both rely on Qt private API (`Qt6::CorePrivate`), which ties the build to one exact Qt version.

## Source layout

| Where | What |
|---|---|
| `app/` (`:app`) | The UI process: `MudletApp`, and the self-test screen (`ui/MainActivity.kt`) with its model of the engine (`ui/EngineClient.kt`) |
| `engine-service/` (`:engine-service`) | The engine process's Kotlin side: `EngineService` (a `QtServiceBase`), its notifications, `EngineNative` (the JNI handshake). Its manifest declares the service and the permissions. It also carries the Qt libraries and jars that `scripts/build-apk.sh` stages into `engine-service/qtdeploy/` |
| `protocol/` (`:protocol`) | Plain Kotlin/JVM with no `android.*`: the frame codec, message types and `EngineLink`, the reconnecting client. Its tests run on the JVM, against the desktop engine too |
| `native/enginehost/` | `libengine`'s `main()`, `SocketBridge` (the protocol's engine side), `MudletEngine` (libmudlet through `THeadlessSession`), and the Android glue: `AndroidServiceSetup` (the only private Qt API, so a rename in Qt fails the build) and `AndroidNotifier` |
| `native/api/` | `IEngine` and `IFrontend`, between the host and an engine |
| `native/standin/` | The stand-in engine for fast tests: minimal telnet, ANSI runs, Lua 5.1.5 (vendored in `native/third_party/lua-5.1.5`, tarball sha256 `2640fc56…695333`), a font check and a heartbeat |
| `native/tests/` | The fake game server, the Python protocol client, and the host tests of both engines |

## How it is wired
```
UI process (org.mudlet.mudlet)              :engine process (org.mudlet.mudlet:engine)
Kotlin + Compose, no Qt                     QtServiceBase + libengine.so (Qt Core/Gui/Network)

:app       MainActivity -> EngineClient     :engine-service    EngineService : QtServiceBase
:protocol  EngineLink (reader thread)       native/enginehost  QLocalServer (Qt's main thread)
              <======== LocalSocket ========>                  SocketBridge : IFrontend -> the engine
              frames: u32 BE length + JSON                       (MudletEngine, or the stand-in)
                                                               AndroidNotifier -> EngineNotifications.postTell (JNI)
```

Startup:
1. The UI calls `startForegroundService`. `MudletApp.onCreate` runs in the new
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
7. The UI's `EngineLink` retries the connection every 50 ms for 2 s, then backs
   off from 100 ms to 2 s, until the socket appears.

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
- In `:engine-service`'s manifest: `<service android:name=".EngineService" android:process=":engine"
  android:exported="false" android:foregroundServiceType="specialUse">`
- A `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining the long-lived MUD
  connection.
- The `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions.
- Qt's `android.app.lib_name=engine`, `android.app.arguments` and
  `android.app.background_running=true` meta-data on the service.

Security: the socket is a filesystem socket inside the app's private directory.
The server uses `QLocalServer::UserAccessOption` and checks the peer uid with
`SO_PEERCRED` against `getuid()`, so other apps cannot connect.

### Protocol
The socket protocol is specified in [`protocol.md`](protocol.md): frames of a 4-byte big-endian length and a UTF-8
JSON object; requests from the UI; lines, the tail, statuses and notifications from the engine; a 2000-line ring
replayed on attach; and flow control so that the engine never blocks on the UI.

This matters on Android because the cached-app freezer can freeze a
backgrounded UI process mid-read. Verified on the host:
- With a stalled reader, the engine kept running at 42-60 MB RSS. When the
  reader resumed it received 1999 lines and one `gap`.

## libmudlet as the engine
Built with `LIBMUDLET_BUILD_DIR`, the engine is `MudletEngine` (`native/enginehost/MudletEngine.*`):
one profile in a `THeadlessSession` behind the same socket protocol. It uses only that class's
public API, from Mudlet's `development` with no patch: it includes no Mudlet header but
`THeadlessSession.h` (which brings `TDebug.h` and `utils.h`) and sets none of Mudlet's
defines. [`c-device.md`](decisions/hosting-results/c-device.md) has the emulator run of the earlier build, which read Mudlet's console model
and needed a patch.

**Build.** The same scripts as CI's libmudlet job:
1. `scripts/build-mudlet-deps.sh <prefix>` builds the third-party libraries for Android.
2. `scripts/build-libmudlet.sh <mudlet> <build> <prefix>` builds `mudlet_core`, `mudlet_headless`
   and the runner, then writes `<build>/libmudlet.cmake`.
3. `LIBMUDLET_BUILD_DIR=<build> scripts/build-apk.sh` builds the APK.

Mudlet installs and exports nothing to link against. Everything `mudlet_headless` needs (the
core, Lua, communi, the Lua C modules, QtKeychain, PCRE2 and the rest) appears only on the
runner's link line. `scripts/export-libmudlet.py` reads that line from CMake's file API and
writes it as the imported target `libmudlet::headless`. Its Qt libraries become `Qt6::` targets,
so `androiddeployqt` deploys them and their plugins as usual. Upstream now covers what the old
`deps/` scripts, the patch, `LuaModules.c` and the `mudlet-lua` assets did. On Android,
`mudlet_core` carries Mudlet's Lua files and unpacks them into the app's cache. It also carries
the seven Lua C modules (`USE_BUILTIN_MUDLET_LUA`, `USE_BUNDLED_LUA_MODULES`). QtKeychain
keeps the profiles' passwords in the KeyStore.

**What the engine maps:**

| THeadlessSession | Socket protocol |
|---|---|
| `lineShown`: each line once its triggers ran, gagged ones left out | `line`. The last one is held as the `tail` until the next line or text that does not continue it, so an `echo()` after `deleteLine()` or a command typed at a prompt (`continuesLine`) still lands on its end |
| `localTextStyled`: `echo()`, Mudlet's messages, the command echo | `line` for each piece a line feed ends, `tail` for the rest. The line feed it repeats after `lineShown` ended its line is dropped, as `docs/headless.md` says |
| `mainConsoleCleared` | `clear`. The ring is emptied, and a UI that attaches later is sent `clear` too |
| `notificationRequested` (`showNotification()`) | `notify`, posted by the engine process as the stand-in's are |
| `connecting`, `connected`, `disconnected` | status `connection` |
| `outputLine`, `localTextStyled`, `errorConsoleText` | counters, and status `luaError` |
| `profileLoaded`, `startFinished` | status `profile`, `profileLoadMs`, `startMs` |

The tail is sent once per pass of the event loop, so a flood sends its lines and a single tail.

**Lifecycle.**
- The first `connect` or `runLua` calls `startAsync()` with `Options::configDir` set to
  `<files>/mudlet` (`MUDLET_CONFIG_DIR` overrides it on the desktop).
- Requests that arrive during the start are queued, and run once `startFinished` reports
  `started`.
- `start()` can create a profile only from a game's address, so Lua sent before the first
  connect of a new profile gets `no profile`.
- An engine started after the last one was killed loads that one's profile again, and connects it
  if the connection was wanted (see "Restoring the session" below).
- When the engine quits, it calls `shutdownAsync(true)` and waits up to 5 s for the profile's
  sysExitEvent and save.
- On the desktop, SIGTERM quits the engine the same way.
- On Android this depends on QtServiceBase ending the event loop when the service is destroyed.
  That has not been tried on a device.

**Tests.** `native/tests/test_mudlet_engine.py` runs the host build of this engine against
`native/tests/fake_telnet.py` through the socket protocol. CI builds libmudlet for the host with the
Android options (`scripts/build-libmudlet-desktop.sh`) to run it. It checks:
- the banner's colours and styles, and the prompt as the tail;
- a gag, a trigger's `echo()`, and an `echo()` after `deleteLine()`;
- `showNotification()`, `echo()` and `cecho()`;
- `clearWindow()`, and the replay after a clear;
- a 3000-line flood that arrives whole and in order;
- Lua errors;
- the profile's save on quit.

`native/tests/test_mudlet_restore.py` checks the restore below the same way, killing the engine with SIGKILL.

**Gaps:**
- Game text that no line feed or go-ahead has ended, such as a prompt without GA, is not shown
  until the line ends. `lineShown` sends only finished lines, and `THeadlessSession` has no
  signal for the line under way. The earlier build read it from the console model.
- A change to a line already sent isn't sent again (`lineShown` sends each line once). Links
  are marked by id, but the UI can't follow them yet (`link()`, `followLink()`, `linksChanged`).
- The main window's size (`setScreenSize()`) is not passed on, so the game is told 90 by 25 if
  it asks.
- No bundled fonts, no TLS (the APK ships no OpenSSL).
- On a device, Lua 5.1 must be built with `-U_FORTIFY_SOURCE`, as the stand-in's is
  (`native/standin/CMakeLists.txt`). Otherwise bionic's FORTIFY check aborts the engine in
  `luaL_openlibs`. Mudlet does not do this yet; a separate change carries it in this
  repository's libmudlet build. Neither CI job runs the engine on Android.

### Restoring the session
Android kills `:engine` when it needs the memory, and the user can kill it from the self-test. The engine is started
again within about half a second while the UI is shown (the UI starts it, and the system may too), but a new engine
knew nothing of the profile, so the player was silently left disconnected. Now the engine keeps what it needs to
start the profile again on disk, and a new engine restores it. Only the engine with libmudlet does this; the
stand-in does not.

**What is kept.** `native/enginehost/SessionStore.*` keeps one JSON file, `<files>/engine-session.json`
(`MUDLET_ENGINE_STATE`; the app names it in `EngineSession.kt`). It holds:
- the profile, while one runs, which is the only `THeadlessSession::Options` field the engine sets besides the
  connection's (`configDir` comes from the app's files, as at any start);
- the host and port the UI last connected it to, passed again as `Options::host` and `port` (an empty host leaves
  the address stored in the profile);
- whether the connection is wanted;
- when each restore within the last 10 minutes started.

The file is rewritten whenever one of these changes, through `QSaveFile`, so a kill part-way through leaves the last
whole file.

**When the connection is wanted:**
- It is, from a connect from the UI, or once the profile is connected for any reason.
- A disconnect from the UI ends it.
- A connection the game closed or refused ends it too, unless the profile reconnects automatically. That is
  Mudlet's "Reconnect automatically" in the connection dialog, which the engine reads from the profile's own
  `autoreconnect` file. So a restore reconnects exactly when Mudlet itself would still be trying to.

**When the engine restores.** At start-up, a file that names a profile means the engine before it was killed:
- An orderly stop deletes the file first: the engine's destructor, run when Qt's event loop ends (SIGTERM on the
  desktop, the service's destruction on Android).
- The app also writes a stop marker, `<files>/engine-stopped` (`MUDLET_ENGINE_STOPPED`), from
  `EngineService.onDestroy` and from the UI's Stop engine. A process kill never runs `onDestroy`. The next engine
  takes the marker and restores nothing, so a stop is never undone, even if `System.exit(0)` ends the process before
  the destructor runs, or the engine was not running when the user stopped it.
- A profile that a script closed, or that failed to load, is forgotten.

**How it restores.** The profile is loaded with `startAsync()` from the event loop, once the engine listens, and
connected if that was wanted. It does not wait for a UI. Status `restore`
([`protocol.md`](protocol.md), "The restore status") says what happened, and the engine keeps the status, so a UI
that attaches later is told too. The UI shows it as a marker line.

**Not hammering a game.**
- Each restore counts itself in the file before it starts, so one that kills the engine still counts.
- The first restore within 10 minutes connects at once, the second after 10 s, the third after 30 s.
- A start that finds three restores within 10 minutes restores nothing (`gaveUp`) and forgets the profile. A crash
  loop therefore calls a game at most three more times. The profile comes back when the user starts it again.
- A failed reconnect is not retried by the engine. Mudlet retries it on its own, with its own back-off from 5 s,
  only for a profile that reconnects automatically.

**Not covered:**
- A script's `disconnect()` counts as the game closing the connection, so a profile that reconnects automatically
  would be reconnected after a kill.
- A game a script connected to with `connectToServer()` is reconnected at the UI's last address, or the profile's.
- The engine cannot tell the user's force-stop, or an app update, from a kill, so it restores the profile when the
  app next starts it.
- The engine's 2000-line scrollback is not kept; the UI keeps its own lines.

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
  - At the top of `main()`, `native/enginehost/AndroidServiceSetup.cpp` calls
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
    libraries, plugins and jars into `engine-service/qtdeploy/` without building.
  - `scripts/build-apk.sh` then deletes the generated `build.gradle`,
    `settings.gradle` and `AndroidManifest.xml`, and `:engine-service`'s Gradle build
    consumes `qtdeploy/libs` and `qtdeploy/res`, which reach the APK through `:app`.
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
  - The service is `START_NOT_STICKY`. On the emulator the system started
    `:engine` again about 300 ms after the kill all the same
    ([`c-device.md`](decisions/hosting-results/c-device.md), 9b).
  - The new engine has a new `instance`. The UI keeps its old lines and adds a
    "new engine instance" marker. The engine's scrollback is gone.
  - The engine with libmudlet loads the profile again and reconnects it, and the
    UI shows that as marker lines ("Restoring the session" above). The
    stand-in's game connection is gone.
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
  On the phone, run `adb shell dumpsys meminfo org.mudlet.mudlet` and
  `... org.mudlet.mudlet:engine`.

## What building Mudlet on it costs
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

## Native libraries: loaded from the APK, or extracted
By default the APK stores its `.so` files uncompressed and 16 KB aligned, and Android loads them
from the APK in place (`useLegacyPackaging = false`, AGP's default for minSdk 23 and up).
`scripts/build-apk.sh -PextractNativeLibs=true` turns on `useLegacyPackaging`: the libraries are
compressed in the APK and the installer extracts them to the app's `nativeLibraryDir`. Qt starts
either way, as Qt's `QtLoader` loads from `nativeLibraryDir` whenever that folder holds libraries.

Extraction matters only for a Lua C module that `require()` is to find on `package.cpath` as
`lib<name>.so`. Lua's `require()` opens each candidate path with `fopen()`, which cannot read a
file inside the APK, so such a module loads only when extracted. With Mudlet/Mudlet#12909, Mudlet
then puts `<nativeLibraryDir>/lib?.so` on `package.cpath`. A module the app hands to
`THeadlessSession::preloadLuaModule()` needs no extraction, and neither do Mudlet's own C modules
(lfs, lpeg, rex_pcre2 and the rest), which `mudlet_core` carries on Android and preloads.

What extraction costs, measured on CI's debug APK of prototype C with the stand-in engine (Prototype
APKs run 43482451881, commit 0dd127f). The "extracted" column is a repack of that APK with the same 16
libraries deflated at zlib level 6, not an APK AGP built; the `APK (extracted libs)` CI job builds
that variant on every run and reports its real sizes. MB here is 10^6 bytes, as in
the CI summary, so the libraries' 20,419,784 bytes are 20.4 MB (19.5 MiB).

| | Loaded from the APK (default) | Extracted |
|---|---|---|
| `lib/arm64-v8a` in the APK | 20.4 MB, stored | 7.6 MB, deflated |
| APK file | 48.3 MB | 35.4 MB (-27%) |
| On the device: APK plus extracted libraries | 48.3 MB | 55.8 MB (+7.5 MB, +16%) |

The extra space on the device is the compressed copy that stays in the APK, here 37% of the
libraries' size, so it grows with libmudlet's engine. A store download is compressed in transit
either way, so its size should change little; that was not measured. The default stays off while
the APK ships no Lua C module that `require()` must find on `package.cpath`.

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
   `FGS: foreground=true process=org.mudlet.mudlet:engine`, the "Engine running"
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
   - With the stand-in, the game connection is gone, as expected.
   - With libmudlet, expect the marker lines "profile restored, reconnecting to
     ..." and "Reconnected to ...", and the game's output again. Note how long
     after the kill it reconnects.
   - Kill it twice more within 10 minutes: the second restore waits 10 s and the
     third 30 s before connecting, and a fourth kill brings back no profile ("The
     engine keeps stopping").
   - Kill it with `kill <enginepid>` from `run-as` while another app is in
     front. Note whether the system starts the engine again on its own, and
     whether it then reconnects with no UI shown.
10. Press "Stop engine". The `:engine` process and its notification should
    disappear, and the UI stays alive.
    - Check with `adb shell ps -A | grep mudlet`.
    - Then press "Start engine". With libmudlet, no profile comes back and the
      game is not called.
    - Repeat with the notification's Stop action.
11. Leave the phone with the screen off for 30 minutes or more, connected to the
    game.
    - Afterwards, check the heartbeat's largest gap and whether the game is
      still connected.
    - Also check whether tells arrived as notifications while the screen was off.
12. Measure memory with `adb shell dumpsys meminfo org.mudlet.mudlet:engine`, and
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
- **An Android module's unit tests compile against `android.jar`**, which lacks
  `UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, `ProcessHandle` and
  `Redirect.DISCARD`. The JVM test now lives in `:protocol`, a plain JVM module,
  so it uses the JDK's Unix sockets directly.
- **The JVM test runner needs `ANDROID_HOME`**, as Gradle configures the Android
  modules too; `native/tests/run_jvm_client_test.sh` sets it.
- **`pkill -f test_engine.py` from a shell whose own command line contains that
  string kills the shell.** Use the pid.
- **`/usr/bin/time` is not installed.** Use the bash `time` builtin.

## Measurements (prototype C, October 2026)

From prototype C's report, on the host, before it became this app; the paths are the app's.

| Check | Result |
|-------|--------|
| Engine desktop build and fake-server test | **pass** (rerun after the startup fixes). `native/tests/test_engine.py`: 0 failures ([`c-desktop-test.txt`](decisions/hosting-results/c-desktop-test.txt)). The JVM test of the app's Kotlin client against the same engine also passes ([`c-jvm-test.txt`](decisions/hosting-results/c-jvm-test.txt)). |
| Real engine (libmudlet from Mudlet's `development`) on the host | **pass**. `native/tests/test_mudlet_engine.py` against the fake server; CI's libmudlet workflow runs it, and builds and checks the APK with this engine. See "libmudlet as the engine". |
| APK builds (`assembleDebug`) | **yes** |
| Clean build time | **1 min 27 s** wall time with Gradle dependencies cached: 20 s CMake configure plus native build (44 steps, `ninja -j2`) and aux-mode deploy, then 1 min 7 s Gradle. The machine was shared with two other agents' builds. The first build, which downloaded dependencies, took 4 min 58 s. An incremental Kotlin-only rebuild takes 33 s. |
| APK size (download estimate and uncompressed `lib/arm64-v8a` total) | Debug APK 48.6 MB, of which 27 MB is unminified debug dex (Compose and Kotlin). `lib/arm64-v8a`: 16 files, **19.5 MB** uncompressed, stored uncompressed (`useLegacyPackaging=false`). The whole APK xz-compresses to 11.8 MB (libs 5.0 MB, dex 6.6 MB). A minified release build would be about **6-7 MB to download** (estimate: libs plus about 1 MB of R8-shrunk dex). |
| Largest native libraries | `libQt6Gui` 7.8 MB, `libQt6Core` 6.2 MB, `libQt6Network` 1.8 MB, `libc++_shared` 1.2 MB after AGP strips it (8.8 MB unstripped), `qtforandroid` plugin 0.7 MB, `libQt6Svg` 0.6 MB, `libengine` 0.3 MB (the engine plus Lua 5.1.5) |
| 16 KB page alignment (`zipalign -c -P 16 -v 4`, and `readelf -lW` LOAD alignment of each .so) | **pass**. `zipalign` reports "Verification successful", with every `lib/arm64-v8a/*.so` marked OK. All 15 deployed `.so` have every LOAD segment at `0x4000`. |

Measured from the app's Kotlin code (`EngineLink`, `FrameReader`/`FrameWriter`)
against the desktop engine, `native/tests/run_jvm_client_test.sh`, three runs:

| | Result |
|---|---|
| ping round trip (Kotlin, then Unix socket, then engine, then back) | median 45-114 µs, p99 268-397 µs |
| paced flood 25k and 50k lines/s | every line delivered |
| paced flood 100k and 200k lines/s | 53-67% and 18-20% delivered; the rest is reported in `gap` messages, and nothing is lost silently |
| engine SIGKILL, until the UI sees EOF | 6-16 ms |
| engine SIGKILL, until reattached to a restarted engine | 108-112 ms. This includes the test spawning a new engine (about 40 ms) and the 100 ms reconnect backoff. |

From the Python client in `native/tests/test_engine.py`:

| | Result |
|---|---|
| ping | median 83 µs, p99 211 µs |
| `runLua` round trip | median 106 µs, p99 226 µs |
| send, game echo, line back (includes the telnet hop) | median 160 µs, p99 401 µs |
| engine CPU per line | 4.05 µs with a UI attached, 2.65 µs with none |
| engine alone, no UI attached | 908k lines/s through telnet, ANSI parsing and Lua `onLine` |
| wire size | about 146 B per coloured line |
| full replay of the 2000-line ring on attach | 44 ms |
