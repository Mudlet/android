# Contributing

How to build, test and check the app, where things are, and the rules the code keeps. What the app is and how it is
built is in [`README.md`](README.md) and [`docs/architecture.md`](docs/architecture.md).

## Where things are

| Where | What |
|---|---|
| `app/` | `:app`, the UI process: Kotlin and Compose, no Qt |
| `engine-service/` | `:engine-service`: `EngineService` in the `:engine` process, and the Qt libraries `scripts/build-apk.sh` stages into `engine-service/qtdeploy/` |
| `protocol/` | `:protocol`, plain Kotlin/JVM: the socket protocol and `EngineLink`, with the JVM tests |
| `native/` | C++: the engine host (`enginehost/`), the engine interface (`api/`), the stand-in engine (`standin/`), the host tests (`tests/`); `third_party/` is vendored Lua 5.1.5 |
| `scripts/` | Builds (APK, OpenSSL, libmudlet and its dependencies), `check-apk.sh` and `lint.sh` |
| `docs/` | [`architecture.md`](docs/architecture.md), [`protocol.md`](docs/protocol.md), [`decisions/`](docs/decisions), [`foundation-checklist.md`](docs/foundation-checklist.md) |
| `.github/` | The workflows (`apks.yml`, `libmudlet.yml`, `checks.yml`), the actions they share, Dependabot |
| `gradle/libs.versions.toml` | Every Gradle module's versions, and the SDK, NDK and build-tools levels |

## Setting up

Qt 6.12.0 for `gcc_64` and `android_arm64_v8a`, an Android SDK with NDK 28.2.13676358, platform android-36 and
build-tools 37.0.0, JDK 21, CMake, Ninja, Python 3, and Perl and make for OpenSSL (the README's "Building" lists them).
Gradle is the wrapper's: always `./gradlew`. The scripts read:

```sh
export QT_ROOT=~/Programs/Qt/6.12.0          # holds gcc_64 and android_arm64_v8a
export ANDROID_SDK_ROOT=~/Android/Sdk ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

Builds run with two jobs and two Gradle workers (`gradle.properties`); raise `JOBS` on a machine of your own.

## Build

```sh
# The debug APK with the stand-in engine: app/build/outputs/apk/debug/app-debug.apk
scripts/build-apk.sh
scripts/build-apk.sh -PextractNativeLibs=true      # the variant that extracts its libraries at install

# The debug APK with libmudlet, from a checkout of Mudlet's development branch
git clone --depth 1 --branch development https://github.com/Mudlet/Mudlet ../mudlet
scripts/build-mudlet-deps.sh deps/prefix
scripts/build-libmudlet.sh ../mudlet build deps/prefix
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh
```

`build-libmudlet.sh` adds the Lua 5.1 FORTIFY fix to the Mudlet checkout (see the README's CI section). For the
emulator, set `ANDROID_ABI=x86_64` for all three scripts; that needs Qt for `android_x86_64`.

## Test

```sh
# :protocol's JVM tests without an engine (EngineLinkJvmTest is then skipped)
./gradlew :protocol:test

# The stand-in engine on the host, then :protocol's tests with the Kotlin client against it
cmake -S native -B native/build-desktop -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64
cmake --build native/build-desktop --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_engine.py native/build-desktop/enginehost/engine
QT_QPA_PLATFORM=offscreen native/tests/run_jvm_client_test.sh -PqtRoot=$QT_ROOT

# The real engine on the host: libmudlet for gcc_64 needs Qt's qt5compat, qtmultimedia, qtspeech and qtsvg there
# too, and the system libraries in the script's header
scripts/build-libmudlet-desktop.sh ../mudlet build-desktop
cmake -S native -B native/build-desktop-mudlet -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64 \
    -DLIBMUDLET_BUILD_DIR=$PWD/build-desktop
cmake --build native/build-desktop-mudlet --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_engine.py native/build-desktop-mudlet/enginehost/engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_restore.py native/build-desktop-mudlet/enginehost/engine

# The APK checks CI runs on every APK: Qt, the engine and OpenSSL in it, 16 KB alignment, how its libraries are stored
scripts/check-apk.sh                       # after scripts/build-apk.sh
EXTRACTED=true scripts/check-apk.sh        # after scripts/build-apk.sh -PextractNativeLibs=true

# The x86_64 APK with libmudlet on a running emulator (CI's emulator job)
python3 native/tests/android_smoke.py --apk app/build/outputs/apk/debug/app-debug.apk --out smoke-test \
    --symbols native/build-android-x86_64-mudlet/enginehost
```

The on-device checklist is in [`docs/architecture.md`](docs/architecture.md#on-device-checklist).

## Lint

```sh
scripts/lint.sh                  # ktlint, clang-format, ShellCheck and actionlint, as CI's Checks workflow
./gradlew ktlintFormat           # fix the Kotlin
FIX=1 scripts/lint.sh clang-format   # fix native/
./gradlew -PqtRoot=$QT_ROOT :app:lintDebug :engine-service:lintDebug   # Android lint, after scripts/build-apk.sh
```

`scripts/lint.sh` installs clang-format, ShellCheck and actionlint from `scripts/lint-requirements.txt` into
`.lint-venv` on first use. Android lint fails on a warning; `lint.xml` turns off a check only with a reason. Formatting
is Mudlet's `.clang-format` for C++ and ktlint's `intellij_idea` style (`.editorconfig`) for Kotlin.

## Ground rules

- **The UI process loads no Qt.** Everything between the UI and the engine is an asynchronous message over the
  socket: no calls, no shared objects, no synchronous getters ([`docs/architecture.md`](docs/architecture.md)).
- **The protocol changes in one commit everywhere:** [`docs/protocol.md`](docs/protocol.md), `SocketBridge` (C++),
  `Protocol.kt` and `native/tests/protocol_client.py`, with the version raised as `protocol.md`'s "Versioning and
  compatibility" says. `ProtocolSpecTest` fails when they disagree.
- **The engine never waits for the UI**: it stops writing to a lagging UI and catches it up from its ring later
  (`protocol.md`, "Flow control").
- **Private Qt API stays in `native/enginehost/AndroidServiceSetup.*`**, which ties the build to one exact Qt version.
- **Every APK carries OpenSSL 3 for Qt's TLS backend, and 16 KB aligned libraries**; CMake refuses an Android build
  without `ANDROID_OPENSSL_DIR`, and `scripts/check-apk.sh` checks both.
- **A fix Mudlet needs goes to Mudlet/Mudlet as a pull request**, and is carried here, in the build scripts, until it
  is merged there (as `build-libmudlet.sh` does with the Lua 5.1 FORTIFY fix, Mudlet/Mudlet#12914).
- **Docs change with the code they describe**, in the same pull request.
- **Build output stays out of git**; `.gitignore` covers what the commands here write.

## Pinned versions

These are pinned to the exact version, and some must move together. Dependabot bumps the Gradle libraries, AGP,
Kotlin and Gradle to their next patch release only, the actions and the linters; the rest is bumped by hand.

| What | Version | Where |
|---|---|---|
| Qt, host and Android | 6.12.0 | `.github/actions/android-toolchain` (`qt-version`), `scripts/build-apk.sh`'s default `QT_ROOT`, `gradle.properties`' `qtRoot` |
| aqtinstall | 3.3.0 | `android-toolchain` (`aqtinstall-version`), `libmudlet.yml`'s host Qt modules step |
| NDK | 28.2.13676358 | `android-toolchain` (`ndk-version`), `libs.versions.toml` (`ndk`), `scripts/build-apk.sh`, `scripts/check-apk.sh` |
| SDK platform, compile and target SDK | 36 | `android-toolchain` (`platform`), `libs.versions.toml`, `scripts/build-apk.sh` (`--android-platform`), `native/enginehost/CMakeLists.txt` |
| minSdk | 28, Qt 6.12's minimum | `libs.versions.toml`, `native/enginehost/CMakeLists.txt`, the `ANDROID_API` default of the libmudlet scripts |
| build-tools | 37.0.0 | `android-toolchain` (`build-tools`), `libs.versions.toml`, `scripts/check-apk.sh` |
| Gradle, AGP, Kotlin | 8.14.3, 8.13.2, 2.3.21 | `gradle/wrapper/gradle-wrapper.properties`, `libs.versions.toml` |
| JDK | 21 | `android-toolchain` (`java-version`), `libs.versions.toml` (`jdk`) |
| OpenSSL; Mudlet's dependencies | 3.5.9; in the script | `scripts/build-openssl.sh`; `scripts/build-mudlet-deps.sh`; each with its sha256s |

**Bumping Qt** (and with it the NDK, SDK levels or AGP): read the new Qt's Android requirements (its supported NDK,
minimum and target SDK, and the AGP and Gradle its templates use), change every place in the table in one pull
request, and check that `AndroidServiceSetup.cpp`'s private API still exists in that Qt
([`docs/architecture.md`](docs/architecture.md), "Qt internals relied on"). Run the libmudlet workflow by hand on the
branch: its emulator job is the only test of the engine on Android. Caches are keyed on the Qt and NDK versions, so a
bump rebuilds them. AGP 9 needs Gradle 9, so leaving AGP 8.13 means moving the wrapper too
(`./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`, the sum from
`https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`).
