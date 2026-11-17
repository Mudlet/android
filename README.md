# Mudlet for Android

A native Android app for [Mudlet](https://www.mudlet.org): a Kotlin + Jetpack Compose interface on top of the
libmudlet core (`mudlet_core`, [Mudlet#9011](https://github.com/Mudlet/Mudlet/issues/9011)). Qt does none of the
drawing; it only runs the engine.

The app runs in two processes:

- the **UI process**: Compose, no Qt;
- the **engine process** (`:engine`): Qt and the engine in a `specialUse` foreground service, so the game connection
  and its scripts keep running while the app is in the background.

They talk over a local socket ([`docs/protocol.md`](docs/protocol.md)). The engine is libmudlet, through Mudlet's
`THeadlessSession`. Why it is built this way, and the three prototypes the choice came from, are in
[`docs/decisions/hosting.md`](docs/decisions/hosting.md).

For now the app opens on a placeholder for the profile list, with a placeholder for the game screen behind it. Debug
builds also have a self-test screen there: connect to a game, see its coloured output, send commands and Lua, and,
behind "Debug", the engine's start times, heartbeat, process ids and buttons that stop or kill either process.

## Layout

| Directory | What |
|---|---|
| [`app/`](app) | `:app`, the UI process: Compose, the app's screens and, in debug builds, the self-test |
| [`engine-service/`](engine-service) | `:engine-service`, an Android library: `EngineService` in the `:engine` process, its notifications, the Qt glue, and the Qt libraries staged for the APK |
| [`protocol/`](protocol) | `:protocol`, plain Kotlin/JVM: the socket protocol's wire format and `EngineLink`, the client that reconnects to the engine, the script windows' state and layout, what a label draws, and their tests |
| [`native/`](native) | C++: the engine host (`enginehost/`: `main()`, the socket bridge, libmudlet's adapter, the Android glue), the engine interface (`api/`), and the host tests (`tests/`) |
| [`scripts/`](scripts) | Building the APK, and libmudlet and its dependencies for Android and the host |
| [`docs/`](docs) | [`architecture.md`](docs/architecture.md), [`protocol.md`](docs/protocol.md), [`ptb.md`](docs/ptb.md), [`translations.md`](docs/translations.md), [`store-listing.md`](docs/store-listing.md), [`privacy-policy.md`](docs/privacy-policy.md), [`decisions/`](docs/decisions), [`roadmap.md`](docs/roadmap.md), [`plan.md`](docs/plan.md), [`phase-f.md`](docs/phase-f.md), [`multi-profile.md`](docs/multi-profile.md) |

Versions for every Gradle module are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The package
names are `org.mudlet.android.*`; the application id is `org.mudlet.mudlet`, as Mudlet's macOS bundle identifier.

## Building

You need:

- Qt 6.12.0 for `gcc_64` (host) and `android_arm64_v8a`;
- an Android SDK with NDK 28.2.13676358, build-tools 37.0.0 and platform android-36;
- JDK 21, CMake and Ninja (Gradle is the wrapper's, `./gradlew`: 8.14.3, with AGP 8.13.2 and Kotlin 2.3.21);
- Perl and make for OpenSSL's build;
- Python 3 and the `openssl` command for the host tests.

The build files default to `~/Programs/Qt/6.12.0` and `~/Android/Sdk`, and build arm64-v8a. Override them with
`QT_ROOT`, `ANDROID_SDK_ROOT` and `ANDROID_ABI` (`x86_64` for the emulator).

### The APK

The one APK to install is the one with libmudlet as its engine. Mudlet installs nothing to link against, so first
build it from a checkout of Mudlet's `development`:

```sh
scripts/build-mudlet-deps.sh deps/prefix                # its third-party libraries, for Android
scripts/build-libmudlet.sh <mudlet> build deps/prefix   # mudlet_core, mudlet_headless and the runner
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh
```

`build-libmudlet.sh` writes `build/libmudlet.cmake`, which the engine links (see "libmudlet as the engine" in
[`docs/architecture.md`](docs/architecture.md)). Each script's header lists its options. On a device, libmudlet's
Lua 5.1 needs `-U_FORTIFY_SOURCE`, which Mudlet does not set yet; until it does, `build-libmudlet.sh` adds it to
the checkout (see "Lua 5.1 FORTIFY fix" under [CI](#ci)).

`build-apk.sh` builds `native/` with Qt's Android CMake into `native/build-android-<abi>-mudlet`, stages the engine
and its Qt libraries into `engine-service/qtdeploy` with `androiddeployqt --aux-mode`, then runs Gradle's
`:app:assembleDebug`. The engine's APK carries OpenSSL for Qt's TLS backend: the first run builds it into
`native/openssl-<abi>` with [`scripts/build-openssl.sh`](scripts/build-openssl.sh), or `OPENSSL_PREFIX` names a
prefix that script made (see "TLS" in [`docs/architecture.md`](docs/architecture.md)). The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle arguments go after the script's
name: `-PextractNativeLibs=true` has the installer extract the native libraries (see "Native libraries" in
[`docs/architecture.md`](docs/architecture.md)).

`build-apk.sh` needs `LIBMUDLET_BUILD_DIR`: there is no APK, and so no Android lint or app JVM tests, without
Mudlet's build.

Gradle on its own (`./gradlew :app:assembleDebug`, or an IDE) needs `engine-service/qtdeploy` staged by the script
first, and `-PqtRoot=...` unless `qtRoot` in `gradle.properties` fits.

### The release

The release is an AAB for Google Play with arm64-v8a and x86_64, and an APK for each, minified with R8:
`STAGE_ONLY=1 scripts/build-apk.sh` stages each ABI's engine from its libmudlet build, and
[`scripts/build-release.sh`](scripts/build-release.sh) merges them and builds it, signed with the upload key
`MUDLET_KEYSTORE` names. Nightly public test builds of it go to Google Play's open testing track and a GitHub
pre-release: [`docs/ptb.md`](docs/ptb.md).

### Tests

```sh
# libmudlet on the host (the system's libraries; see the script's header), then the engine against a fake game
# server, in the clear and over TLS; the only engine there is, so these need Mudlet's toolchain
scripts/build-libmudlet-desktop.sh <mudlet> build-desktop
cmake -S native -B native/build-desktop-mudlet -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64 \
    -DLIBMUDLET_BUILD_DIR=$PWD/build-desktop
cmake --build native/build-desktop-mudlet --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_engine.py native/build-desktop-mudlet/enginehost/engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_restore.py native/build-desktop-mudlet/enginehost/engine

# :protocol's tests, with the app's Kotlin client against that engine
QT_QPA_PLATFORM=offscreen native/tests/run_jvm_client_test.sh -PqtRoot=$QT_ROOT

# The debug APK with libmudlet, built with ANDROID_ABI=x86_64, on a running emulator (adb on the PATH)
python3 native/tests/android_smoke.py --apk app/build/outputs/apk/debug/app-debug.apk --out smoke-test \
    --symbols native/build-android-x86_64-mudlet/enginehost

# The instrumented tests on it: build the test APKs with the APK, then run them (they reinstall the app)
ANDROID_ABI=x86_64 LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh :app:assembleDebugAndroidTest \
    :engine-service:assembleDebugAndroidTest
python3 native/tests/android_instrumented.py --app app/build/outputs/apk/debug/app-debug.apk \
    --app-tests app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --engine-tests engine-service/build/outputs/apk/androidTest/debug/engine-service-debug-androidTest.apk \
    --out instrumented-test --symbols native/build-android-x86_64-mudlet/enginehost
```

`./gradlew :protocol:test` alone runs the protocol tests without an engine; the one against the desktop engine is then
skipped, but fails on CI (`CI=true`), where a skip would pass with nothing tested. `ProtocolSpecTest` among them checks that [`docs/protocol.md`](docs/protocol.md), the C++ engine and the
Kotlin client agree.

`scripts/check-apk.sh` runs CI's APK checks on the APK `build-apk.sh` made. The on-device checklist is in
[`docs/architecture.md`](docs/architecture.md). Every command to build, test and lint, and the rules the code keeps,
are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## CI

One workflow, **CI** (`.github/workflows/ci.yml`), runs on pull requests, pushes to `main`, every night and on demand.
Its first job sorts the changed files with a path filter and calls only the workflows they need, down to the job; the
nightly runs the libmudlet jobs, and a manual run everything. **PTB**, below, is a workflow of its own. Its last job,
**CI result**, passes when every job the changes called for passed and every other one was skipped, so it is the one
check to require: a workflow a path filter left out never reports, and a skipped job counts as passed. A newer push to a
pull request cancels its older run.

| Changed | Runs |
|---|---|
| Only docs (`*.md`, `docs/` but `docs/protocol.md`) | CI's own two short jobs and the conflict-marker check, nothing else |
| Kotlin in `app/`, `engine-service/`, `protocol/` or `suggest/` | ktlint; the arm64 APK with the app's JVM tests and Android lint, and the emulator jobs, both with libmudlet from its cache; for `protocol/` also the real engine on the host, which runs its tests; for `app/` and `engine-service/` the strings check |
| `native/`, the Gradle build, the build scripts, `.github/actions/` | the jobs below that build from them |
| A workflow | what that workflow runs; `ci.yml` itself, everything |

The workflows it calls:

- **libmudlet for Android** (`.github/workflows/libmudlet.yml`) checks out Mudlet's `development` branch (or any ref
  given to a manual run).
  - It cross-builds libmudlet's dependencies for arm64-v8a with
    [`scripts/build-mudlet-deps.sh`](scripts/build-mudlet-deps.sh) (pinned versions and checksums), then
    `mudlet_core`, `mudlet_headless` and the `mudlet-headless` runner with
    [`scripts/build-libmudlet.sh`](scripts/build-libmudlet.sh) (`.github/actions/libmudlet`). The job summary gives
    the Mudlet commit it built, the library sizes, stripped and not, and whose Lua 5.1 FORTIFY fix the build used.
    A build of the same Mudlet commit with the same scripts, toolchain and FORTIFY choice is taken from the cache
    instead, so a pull request that changes only the app does not build Mudlet again.
  - Lua 5.1 FORTIFY fix: built with the NDK's `_FORTIFY_SOURCE=2`, Mudlet's Lua 5.1 aborts in `luaL_openlibs`
    ("FORTIFY: strchr: prevented read past end of buffer"). Until Mudlet's `3rdparty/lua51/CMakeLists.txt`
    passes `-U_FORTIFY_SOURCE` for Android itself ([Mudlet/Mudlet#12914](https://github.com/Mudlet/Mudlet/pull/12914)),
    the script appends the same `"SHELL:-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=1"` option for Android to that file in
    the Mudlet checkout and says so in its log. A local checkout keeps that change.
  - Its **libmudlet** job first runs `:suggest`'s tests, which need no Mudlet.
  - It builds OpenSSL for Android with [`scripts/build-openssl.sh`](scripts/build-openssl.sh) (a pinned release and
    its checksum), cached under the script's hash by `.github/actions/android-openssl`, then the arm64-v8a debug APK
    with libmudlet as the engine, linked through the `libmudlet::headless` target that
    [`scripts/export-libmudlet.py`](scripts/export-libmudlet.py) writes from the runner's link line. It runs on every
    change to the app, so every pull request that changes it has an APK to install.
  - It runs the app's JVM tests, and Android lint on `:app` and `:engine-service`, where a warning fails (`lint.xml`
    says what is off and why).
  - It checks that the APK holds Qt and the engine, and OpenSSL as Qt's TLS backend loads it (`libcrypto_3.so` and
    `libssl_3.so`, their names, the libraries they need, OpenSSL 3), that its libraries are 16 KB aligned (their
    offsets in the APK and their load segments), that they are stored or compressed as `extractNativeLibs` says, and
    that the launcher icon is an adaptive icon with a monochrome layer ([`scripts/check-apk.sh`](scripts/check-apk.sh)),
    and that the APK, its libraries and its dex keep to their size budget
    ([`scripts/check-apk-size.sh`](scripts/check-apk-size.sh), CONTRIBUTING.md's "APK size budget"); and that the
    engine carries Mudlet's Lua, its Lua C modules and QtKeychain's KeyStore backend.
  - It uploads the APK as `mudlet-libmudlet-debug-apk`, the one APK CI makes to install on a phone, and gives its
    size, its size on the device, and OpenSSL's share, in the job summary.
  - **desktop-engine** builds libmudlet for the host with the same options
    ([`scripts/build-libmudlet-desktop.sh`](scripts/build-libmudlet-desktop.sh)) and runs the real engine against the
    fake game server through the socket protocol (`native/tests/test_mudlet_engine.py`), in the clear and over TLS. It
    also kills that engine mid-session and checks that the next one restores the profile
    (`native/tests/test_mudlet_restore.py`), then runs `:protocol`'s tests, with the app's Kotlin client against it.
  - **emulator-build** builds the dependencies, libmudlet, the APK and the two test APKs for x86_64; then one
    **emulator** job for each of API 28 (Android 9, the app's minSdk) and API 36 (Android 16) boots an emulator (KVM,
    [android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) at a pinned commit) and runs
    on it, one after the other:
    - [`native/tests/android_smoke.py`](native/tests/android_smoke.py): the engine service in the foreground in
      `:engine` with Qt up, a profile on the fake game server (so Mudlet's Lua runs), coloured lines in the UI, a Lua
      trigger and its notification, and the UI attaching to a new engine after `:engine` is killed;
    - [`native/tests/android_instrumented.py`](native/tests/android_instrumented.py), the instrumented tests in
      `app/src/androidTest` and `engine-service/src/androidTest`: the engine service in its own process in the
      foreground, Qt's handshake, a stop from the notification that the next start does not undo, a killed engine that
      comes back with its profile and connection, a TLS connect through OpenSSL; the self-test screen's lines, runs and
      colours from a fake engine, its reattach after losing the engine, the way between the app's screens with
      TalkBack's controls only, the Accessibility Test Framework's checks (API 36), the notification permission's
      dialog, and the screens in the pseudo-locales en-XA and ar-XB with no text cut off and ar-XB mirrored (API 36).

    Both run whatever the other did, and the job fails if either failed. It uploads logcat, screenshots, the UI's
    state and each instrumentation's output, and gives the steps in the job summary. These are the only jobs that
    run the engine on Android, so the only ones that catch a crash there; a manual run with `lua_fortify_fix` off
    shows them failing on the FORTIFY abort in `luaL_openlibs`. API 28 runs on every pull request that runs these at
    all: the code that differs by API level (`ServiceCompat.startForeground`, the notification permission from 33,
    the foreground service type from 34) is in the same files whose changes start them.
  - It fails if the libraries use desktop-only APIs or carry the build-type splash screen images, if Lua 5.1's
    collector (`lgc.c`) references a fortified function such as `__strchr_chk` (as the call that aborts does) or its
    string library (`lstrlib.c`) references none (FORTIFY is then off altogether), if the runner lacks Mudlet's
    built-in Lua files, Lua C modules or QtKeychain's Android backend (which keeps profiles' passwords in the
    KeyStore), if the engine does not build, link or pass its test, or if a test on an emulator fails.
- **Checks** (`.github/workflows/checks.yml`) runs [`scripts/lint.sh`](scripts/lint.sh): ktlint on the Kotlin,
  clang-format on `native/` (Mudlet's `.clang-format`), ShellCheck on the scripts, actionlint on the workflows, and
  the strings check on `app/` and `engine-service/` (no text written into the Kotlin, a translator's comment on each
  string: [`docs/translations.md`](docs/translations.md)), the conflict-marker check on every text file, and the
  vendored check (Lua as released, each Mudlet patch's `Upstream:` line), and the games check (the profile list's
  games as `scripts/sync-mudlet-games.py` makes them), as eight short jobs, each when a file it checks changed.

Every night CI runs the libmudlet jobs against Mudlet's latest `development` (the other jobs build only from this
repository, which pushes to `main` already check), so a change on Mudlet's side that breaks the Android build shows up;
a nightly that fails or times out opens or updates an issue here. GitHub pauses the nightly after 60 days without
activity in this repository; re-enable it from the Actions tab. A red check on a pull request can come from Mudlet's
side too. A manual run of CI (Actions, CI, "Run workflow") runs everything on any branch, with any Mudlet ref;
`libmudlet.yml` can also be run on its own, with a choice of its jobs.

**PTB** (`.github/workflows/ptb.yml`), outside CI, runs every night and on pull requests that change the app's code
or how the release is built, whose minified size it checks against its budget. It builds the release from Mudlet's
`development` for arm64-v8a and x86_64, as the separate app Mudlet PTB (`org.mudlet.mudlet.ptb`), runs its minified
x86_64 build through the emulator smoke test, and from `main` uploads it to Google Play's open testing track and
replaces the `ptb` GitHub pre-release, whose APKs come with the source code they are built from. It skips a night
when neither Mudlet nor this repository changed, and publishes nothing without its secrets or off `main`. See
[`docs/ptb.md`](docs/ptb.md).

Dependabot proposes updates for the Gradle dependencies, the actions and the linters weekly; Qt, the NDK and the
versions tied to them are bumped by hand (see "Pinned versions" in [`CONTRIBUTING.md`](CONTRIBUTING.md)).

The libmudlet jobs install the versions under [Building](#building) with `.github/actions/android-toolchain` (Qt
through a pinned aqtinstall). Qt, the NDK, the dependencies, OpenSSL, Mudlet's Android builds, Gradle, ccache and the
emulators' system images are cached between runs.

## Licence

This repository's code is free software under the GNU General Public License, version 3 or later
(`GPL-3.0-or-later`); the full text is in [`LICENSE`](LICENSE). The APK as a whole, with Mudlet, Qt and OpenSSL, is
distributed under version 3 of the GPL.

The app's About screen lists the licence of everything the APK bundles, with the texts: Mudlet (GPL v2 or later), Qt
(LGPL v3), OpenSSL 3 (Apache 2.0), Lua 5.1.5 (MIT), Mudlet's dependencies and the
Android libraries. Where the list comes from, the checks that keep it complete, and how to rebuild the app with your
own Qt are in [`docs/licences.md`](docs/licences.md).
The launcher icon is made from Mudlet's logo, by Thorsten Wilms, from Mudlet/Mudlet (GPL-2.0-or-later); see
[`docs/launcher-icon.md`](docs/launcher-icon.md).
