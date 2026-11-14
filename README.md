# Mudlet for Android

A native Android app for [Mudlet](https://www.mudlet.org): a Kotlin + Jetpack Compose interface on top of the
libmudlet core (`mudlet_core`, [Mudlet#9011](https://github.com/Mudlet/Mudlet/issues/9011)). Qt does none of the
drawing; it only runs the engine.

The app runs in two processes:

- the **UI process**: Compose, no Qt;
- the **engine process** (`:engine`): Qt and the engine in a `specialUse` foreground service, so the game connection
  and its scripts keep running while the app is in the background.

They talk over a local socket ([`docs/protocol.md`](docs/protocol.md)). The engine is libmudlet, through Mudlet's
`THeadlessSession`, or a small stand-in engine for fast tests. Why it is built this way, and the three prototypes the
choice came from, are in [`docs/decisions/hosting.md`](docs/decisions/hosting.md).

For now the app opens a self-test screen: connect to a game, see its coloured output, send commands and Lua, and,
behind "Debug", the engine's start times, heartbeat, process ids and buttons that stop or kill either process.

## Layout

| Directory | What |
|---|---|
| [`app/`](app) | `:app`, the UI process: Compose, the self-test screen |
| [`engine-service/`](engine-service) | `:engine-service`, an Android library: `EngineService` in the `:engine` process, its notifications, the Qt glue, and the Qt libraries staged for the APK |
| [`protocol/`](protocol) | `:protocol`, plain Kotlin/JVM: the socket protocol's wire format and `EngineLink`, the client that reconnects to the engine, and their tests |
| [`native/`](native) | C++: the engine host (`enginehost/`: `main()`, the socket bridge, libmudlet's adapter, the Android glue), the engine interface (`api/`), the stand-in engine (`standin/`), and the host tests (`tests/`) |
| [`scripts/`](scripts) | Building the APK, and libmudlet and its dependencies for Android and the host |
| [`docs/`](docs) | [`architecture.md`](docs/architecture.md), [`protocol.md`](docs/protocol.md), [`decisions/`](docs/decisions) |

Versions for every Gradle module are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The package
names are `org.mudlet.android.*`; the application id is `org.mudlet.mudlet`, as Mudlet's macOS bundle identifier.

## Building

You need:

- Qt 6.12.0 for `gcc_64` (host) and `android_arm64_v8a`;
- an Android SDK with NDK 28.2.13676358, build-tools 37.0.0 and platform android-36;
- JDK 21, Gradle 8.14 (the build uses AGP 8.13.2 and Kotlin 2.3.21), CMake and Ninja;
- Python 3 for the host tests.

The build files default to `~/Programs/Qt/6.12.0` and `~/Android/Sdk`, and build arm64-v8a. Override them with
`QT_ROOT`, `ANDROID_SDK_ROOT` and `ANDROID_ABI` (`x86_64` for the emulator).

### The APK with the stand-in engine

```sh
scripts/build-apk.sh
```

This builds `native/` with Qt's Android CMake into `native/build-android-<abi>`, stages the engine and its Qt
libraries into `engine-service/qtdeploy` with `androiddeployqt --aux-mode`, then runs Gradle's
`:app:assembleDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle arguments go after the script's
name: `-PextractNativeLibs=true` has the installer extract the native libraries (see "Native libraries" in
[`docs/architecture.md`](docs/architecture.md)).

Gradle on its own (`gradle :app:assembleDebug`, or an IDE) needs `engine-service/qtdeploy` staged by the script
first, and `-PqtRoot=...` unless `qtRoot` in `gradle.properties` fits.

### The APK with libmudlet

Mudlet installs nothing to link against, so first build it from a checkout of Mudlet's `development`:

```sh
scripts/build-mudlet-deps.sh deps/prefix                # its third-party libraries, for Android
scripts/build-libmudlet.sh <mudlet> build deps/prefix   # mudlet_core, mudlet_headless and the runner
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh
```

`build-libmudlet.sh` writes `build/libmudlet.cmake`, which the engine links (see "libmudlet as the engine" in
[`docs/architecture.md`](docs/architecture.md)). Each script's header lists its options. On a device, libmudlet's
Lua 5.1 needs `-U_FORTIFY_SOURCE`, which Mudlet does not set yet.

### Tests

```sh
# The stand-in engine on the host, against a fake game server
cmake -S native -B native/build-desktop -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64
cmake --build native/build-desktop --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_engine.py native/build-desktop/enginehost/engine

# :protocol's tests, with the app's Kotlin client against that engine
QT_QPA_PLATFORM=offscreen native/tests/run_jvm_client_test.sh -PqtRoot=$QT_ROOT

# libmudlet on the host (the system's libraries; see the script's header), then the real engine against the fake server
scripts/build-libmudlet-desktop.sh <mudlet> build-desktop
cmake -S native -B native/build-desktop-mudlet -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64 \
    -DLIBMUDLET_BUILD_DIR=$PWD/build-desktop
cmake --build native/build-desktop-mudlet --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_engine.py native/build-desktop-mudlet/enginehost/engine
```

`gradle :protocol:test` alone runs the protocol tests without an engine; the one against the desktop engine is then
skipped. `ProtocolSpecTest` among them checks that [`docs/protocol.md`](docs/protocol.md), the C++ engine and the
Kotlin client agree.

The on-device checklist is in [`docs/architecture.md`](docs/architecture.md).

## CI

GitHub Actions runs two workflows, each on pushes to `main` and pull requests that touch their files, and on demand:

- **APKs** (`.github/workflows/apks.yml`)
  - runs the stand-in engine's desktop test, and `:protocol`'s tests with the Kotlin client against that engine;
  - builds the debug APK with the stand-in engine, and again with its libraries extracted at install;
  - checks that each APK holds Qt and the engine, that its libraries are 16 KB aligned (their offsets in the APK and
    their load segments), and that they are stored or compressed as `extractNativeLibs` says;
  - uploads both APKs, and gives each one's size, and its size on the device, in the job summary.
- **libmudlet for Android** (`.github/workflows/libmudlet.yml`) checks out Mudlet's `development` branch (or any ref
  given to a manual run).
  - It cross-builds libmudlet's dependencies for arm64-v8a with
    [`scripts/build-mudlet-deps.sh`](scripts/build-mudlet-deps.sh) (pinned versions and checksums), then
    `mudlet_core`, `mudlet_headless` and the `mudlet-headless` runner with
    [`scripts/build-libmudlet.sh`](scripts/build-libmudlet.sh). The job summary gives the Mudlet commit it built and
    the library sizes, stripped and not.
  - It builds the APK with libmudlet as the engine, linked through the `libmudlet::headless` target that
    [`scripts/export-libmudlet.py`](scripts/export-libmudlet.py) writes from the runner's link line, puts it through
    the same checks as above, checks that the engine carries Mudlet's Lua, its Lua C modules and QtKeychain's KeyStore
    backend, and uploads it.
  - A second job builds libmudlet for the host with the same options
    ([`scripts/build-libmudlet-desktop.sh`](scripts/build-libmudlet-desktop.sh)) and runs the real engine against the
    fake game server through the socket protocol (`native/tests/test_mudlet_engine.py`).
  - It fails if the libraries use desktop-only APIs or carry the build-type splash screen images, if the runner lacks
    Mudlet's built-in Lua files, Lua C modules or QtKeychain's Android backend (which keeps profiles' passwords in the
    KeyStore), or if the engine does not build, link or pass its test.
  - It also runs every night, so a change on Mudlet's side that breaks the Android build shows up; a nightly that
    fails or times out opens or updates an issue here. GitHub pauses the nightly after 60 days without activity in
    this repository; re-enable it from the Actions tab. A red check on a pull request can come from Mudlet's side too.

Both install the versions under [Building](#building) with `.github/actions/android-toolchain` (Qt through a pinned
aqtinstall). Qt, the NDK, the dependencies, Gradle and ccache are cached between runs.

## Licences

The stand-in engine vendors Lua 5.1.5 (MIT, see [`native/third_party/lua-5.1.5/COPYRIGHT`](native/third_party/lua-5.1.5/COPYRIGHT)).
