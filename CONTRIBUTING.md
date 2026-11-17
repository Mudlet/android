# Contributing

How to build, test and check the app, where things are, the rules the code keeps, and how pull requests are merged.
What the app is and how it is built is in [`README.md`](README.md) and [`docs/architecture.md`](docs/architecture.md).

## Where things are

| Where | What |
|---|---|
| `app/` | `:app`, the UI process: Kotlin and Compose, no Qt |
| `engine-service/` | `:engine-service`: `EngineService` in the `:engine` process, and the Qt libraries `scripts/build-apk.sh` stages into `engine-service/qtdeploy/` |
| `protocol/` | `:protocol`, plain Kotlin/JVM: the socket protocol and `EngineLink`, the script windows' state and layout, what a label draws, with the JVM tests |
| `suggest/` | `:suggest`, plain Kotlin/JVM with no dependencies: the input line's command suggestions and the session that loads, feeds and saves them, with the JVM tests |
| `native/` | C++: the engine host (`enginehost/`), the engine interface (`api/`), the stand-in engine (`standin/`, a test harness of the socket protocol on the host, never the app), the host tests (`tests/`); `third_party/` is vendored Lua 5.1.5, the stand-in's |
| `scripts/` | Builds (APK, release and its source bundle, OpenSSL, libmudlet and its dependencies), `check-apk.sh`, `check-apk-size.sh` with the size budget `apk-size-budget.txt`, `check-licences.py`, `lint.sh` and the strings check it runs, `check-strings.py`, and `launcher-icons.py` |
| `docs/` | [`architecture.md`](docs/architecture.md), [`protocol.md`](docs/protocol.md), [`ptb.md`](docs/ptb.md), [`translations.md`](docs/translations.md), [`launcher-icon.md`](docs/launcher-icon.md), [`store-listing.md`](docs/store-listing.md), [`privacy-policy.md`](docs/privacy-policy.md), [`licences.md`](docs/licences.md), [`decisions/`](docs/decisions), [`foundation-checklist.md`](docs/foundation-checklist.md), [`roadmap.md`](docs/roadmap.md), [`phase-f.md`](docs/phase-f.md), [`multi-profile.md`](docs/multi-profile.md), [`divergences.md`](docs/divergences.md) (where the app differs from Mudlet desktop on purpose), [`ai-instructions.md`](docs/ai-instructions.md) (coding agents' instructions, which `CLAUDE.md` and `AGENTS.md` link to) |
| `.agents/skills/` | Longer procedures for coding agents (running on the emulator, bumping Qt); `.claude/skills` links to it |
| `crowdin.yml` | What Crowdin translates and where the translations go ([`docs/translations.md`](docs/translations.md)) |
| `.github/` | The workflows (`ci.yml`, which calls `checks.yml` and `libmudlet.yml` as the changes need, and `ptb.yml`), the actions they share, Dependabot |
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

`scripts/build-apk.sh` builds with two jobs, and Gradle with two workers (`gradle.properties`); the libmudlet and
dependency scripts use every core unless `JOBS` says otherwise.

## Build

There is one APK to install: the one with libmudlet as its engine, which CI uploads as `mudlet-libmudlet-debug-apk`.

```sh
# The debug APK with libmudlet, from a checkout of Mudlet's development branch: app/build/outputs/apk/debug/app-debug.apk
git clone --depth 1 --branch development https://github.com/Mudlet/Mudlet ../mudlet
scripts/build-mudlet-deps.sh deps/prefix
scripts/build-libmudlet.sh ../mudlet build deps/prefix
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh -PextractNativeLibs=true   # the variant that extracts its libraries at install

# Without Mudlet's build: the stand-in engine packaged, only for Android lint and the app's JVM tests. Not the app
# (no TLS, map, script windows or login; its About screen says so): never install it to play, or hand it out
scripts/build-apk.sh
```

`build-libmudlet.sh` adds the Lua 5.1 FORTIFY fix to the Mudlet checkout (see the README's CI section). For the
emulator, set `ANDROID_ABI=x86_64` for all three scripts; that needs Qt for `android_x86_64`.

```sh
# The release: each ABI's engine staged (add LIBMUDLET_BUILD_DIR for libmudlet), then the AAB, an APK for each ABI
# and the smoke build, minified; signed with MUDLET_KEYSTORE, MUDLET_KEYSTORE_PASSWORD and MUDLET_KEY_ALIAS if set
STAGE_ONLY=1 DEPLOY_DIR=staged/arm64-v8a scripts/build-apk.sh
ANDROID_ABI=x86_64 STAGE_ONLY=1 DEPLOY_DIR=staged/x86_64 scripts/build-apk.sh
scripts/build-release.sh staged/arm64-v8a staged/x86_64 -- -PreleaseChannel=ptb
```

The public test builds, their versions and signing, and what the maintainer sets up for them are in
[`docs/ptb.md`](docs/ptb.md).

## Test

```sh
# :protocol's JVM tests without an engine (EngineLinkJvmTest is then skipped, but fails with CI=true, as on CI)
./gradlew :protocol:test

# :suggest's tests, with Gradle or with kotlinc and JUnit 4 alone (no Android SDK)
./gradlew :suggest:test
KOTLIN_HOME=path/to/kotlinc JUNIT_JARS=junit-4.13.2.jar:hamcrest-core-1.3.jar suggest/run-tests.sh

# The stand-in engine, the protocol's test harness, on the host, then :protocol's tests with the Kotlin client against it
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

# The app's JVM tests (the About screen's licence data), after scripts/build-apk.sh
./gradlew -PqtRoot=$QT_ROOT :app:testDebugUnitTest

# The licence list against the build scripts, and against a Mudlet checkout (docs/licences.md)
scripts/check-licences.py --mudlet-source ../mudlet

# The APK checks CI runs on every APK: Qt, the engine and OpenSSL in it, 16 KB alignment, how its libraries are
# stored, the adaptive launcher icon, a licence for each library and Gradle module in it, and with SIZE_BUDGET its
# size budget (below)
scripts/check-apk.sh                       # after scripts/build-apk.sh
EXTRACTED=true scripts/check-apk.sh        # after scripts/build-apk.sh -PextractNativeLibs=true
SIZE_BUDGET=libmudlet-arm64-v8a scripts/check-apk.sh   # after the libmudlet build
scripts/check-apk-size.sh ptb-aab app/build/outputs/bundle/release/app-release.aab   # after scripts/build-release.sh

# The x86_64 APK with libmudlet on a running emulator (CI's emulator job)
python3 native/tests/android_smoke.py --apk app/build/outputs/apk/debug/app-debug.apk --out smoke-test \
    --symbols native/build-android-x86_64-mudlet/enginehost
# The minified release, as the PTB workflow tests it: its smoke build, after scripts/build-release.sh
python3 native/tests/android_smoke.py --apk app/build/outputs/apk/smoke/app-x86_64-smoke.apk --out smoke-test \
    --package org.mudlet.mudlet.ptb

# The instrumented tests (app/src/androidTest, engine-service/src/androidTest) on it: build the test APKs with the APK, then run them (they reinstall the app)
ANDROID_ABI=x86_64 LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh :app:assembleDebugAndroidTest \
    :engine-service:assembleDebugAndroidTest
python3 native/tests/android_instrumented.py --app app/build/outputs/apk/debug/app-debug.apk \
    --app-tests app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --engine-tests engine-service/build/outputs/apk/androidTest/debug/engine-service-debug-androidTest.apk \
    --out instrumented-test --symbols native/build-android-x86_64-mudlet/enginehost
```

`android_instrumented.py` also copies the screenshots `ScreenshotTest` takes (the profile list, the game screen with
the map behind its text, the settings and the map in front, light and dark, on Android 12 and later) into
`<out>/screenshots/`, which CI's emulator jobs upload with their results: before and after shots for a pull request
that changes how the app looks.

The on-device checklist is in [`docs/architecture.md`](docs/architecture.md#on-device-checklist).

### APK size budget

[`scripts/apk-size-budget.txt`](scripts/apk-size-budget.txt) holds the size of each APK and AAB CI builds, as CI built
them: the whole file, its native libraries and its dex, in bytes as they take up the file. CI fails when any of them
grows more than `headroom-percent` (5%) above its budget, and prints each size beside its budget in the log and the job
summary (`scripts/check-apk-size.sh`, which `check-apk.sh` runs with `SIZE_BUDGET`; `ptb.yml` runs it on the PTB's
APKs and AAB, and publishes a nightly PTB past it). A local build can come out a little different from CI's.

To raise a budget on purpose, in the pull request that makes the APK grow: take the new sizes from the failing job's
log (its line starting with the budget's name), write them into that line of the budget file, and say in the pull
request what grew and why. A size more than `lower-below-percent` (10%) under its budget gets a notice in CI: lower
the budget the same way, so that the next growth is caught from there. The libmudlet and PTB budgets follow Mudlet's
`development` too: when the nightly fails on one because libmudlet grew, raise it in a pull request of its own that
names the Mudlet commits.

## Lint

```sh
scripts/lint.sh                  # ktlint, clang-format, ShellCheck, actionlint, strings, conflict markers and vendored code, as CI's Checks workflow
./gradlew ktlintFormat           # fix the Kotlin
FIX=1 scripts/lint.sh clang-format   # fix native/
./gradlew -PqtRoot=$QT_ROOT :app:lintDebug :engine-service:lintDebug   # Android lint, after scripts/build-apk.sh
```

`scripts/lint.sh` installs clang-format, ShellCheck and actionlint from `scripts/lint-requirements.txt` into
`.lint-venv` on first use. Android lint fails on a warning; `lint.xml` turns off a check only with a reason. Formatting
is Mudlet's `.clang-format` for C++ and ktlint's `intellij_idea` style (`.editorconfig`) for Kotlin. The strings check
(`scripts/check-strings.py`) fails on text written into the Kotlin and on a string in `strings.xml` without a comment
for translators ([`docs/translations.md`](docs/translations.md)). The conflict-marker check fails on a line a merge
conflict left in any text file (`<<<<<<< `, `=======`, `>>>>>>> `). The vendored check fails when a file in
`native/third_party/` differs from the release its `SHA256SUMS` lists, or is not in it, and when a
`scripts/mudlet-patches/*.patch` lacks the `Upstream:` line before its diff: `Upstream: Mudlet/Mudlet#<number>`, or
`Upstream: not opened yet`, which the check prints.

## Ground rules

- **The UI process loads no Qt.** Everything between the UI and the engine is an asynchronous message over the
  socket: no calls, no shared objects, no synchronous getters ([`docs/architecture.md`](docs/architecture.md)).
- **The protocol changes in one commit everywhere:** [`docs/protocol.md`](docs/protocol.md), `SocketBridge` or `ControlBridge` (C++),
  `Protocol.kt` and `native/tests/protocol_client.py`, with the version raised as `protocol.md`'s "Versioning and
  compatibility" says. `ProtocolSpecTest` fails when they disagree.
- **The engine never waits for the UI**: it stops writing to a lagging UI and catches it up from its ring later
  (`protocol.md`, "Flow control").
- **Private Qt API stays in `native/enginehost/AndroidServiceSetup.*`**, which ties the build to one exact Qt version.
- **Every APK carries OpenSSL 3 for Qt's TLS backend, and 16 KB aligned libraries**; CMake refuses an Android build
  without `ANDROID_OPENSSL_DIR`, and `scripts/check-apk.sh` checks both.
- **A fix Mudlet needs goes to Mudlet/Mudlet as a pull request**, and is carried here, in the build scripts, until it
  is merged there (as `build-libmudlet.sh` does with the Lua 5.1 FORTIFY fix, Mudlet/Mudlet#12914).
- **Mudlet desktop is the reference for what scripts and games see**: a gap is closed upstream or in the app's
  renderer, never with an Android-only Lua function or event, and a deliberate difference is recorded in
  [`docs/divergences.md`](docs/divergences.md) with why matching the desktop would be worse.
- **Every text a user sees or hears is in a `strings.xml`**, with a comment for translators right before it; the
  translations come from Crowdin and are not edited here ([`docs/translations.md`](docs/translations.md)).
- **Docs change with the code they describe**, in the same pull request.
- **Build output stays out of git**; `.gitignore` covers what the commands here write.

## Merging

As in Mudlet's own repository ([its CONTRIBUTING.md](https://github.com/Mudlet/Mudlet/blob/development/docs/CONTRIBUTING.md#git-commit-guidelines-for-core-team)),
the preferred order of merging pull requests is:

1. Prefer _squash and merge_ for a clean history and added PR numbers for details of discussion for future comparison.
2. Else _rebase and merge_ if you'd like to keep the history, but know this will not link to the PR in public test
   builds' (PTB) changelogs, etc.
3. Avoid creating a _merge commit_.

Update a branch by rebasing it onto its base, not by merging the base into it, and push it with
`git push --force-with-lease`. A squash leaves one commit per pull request, so its title and description are what
explains the change later: keep them true to what is merged.

Stacked pull requests: once the lower one is squashed into `main`, rebase the next onto `main`, leaving out the lower
one's commits (`git rebase --onto origin/main <the lower branch as it was before the squash> <the next branch>`),
and set its base to `main` if GitHub has not already.

## Pinned versions

These are pinned to the exact version, and some must move together. Dependabot bumps the Gradle libraries, the
actions and the linters, but AGP, Kotlin, Gradle and AndroidX only to a new patch release; the rest is bumped by
hand.

| What | Version | Where |
|---|---|---|
| Qt, host and Android | 6.12.0 | `.github/actions/android-toolchain` (`qt-version`), `scripts/build-apk.sh`'s default `QT_ROOT`, `gradle.properties`' `qtRoot` |
| aqtinstall | 3.3.0 | `android-toolchain` (`aqtinstall-version`), `libmudlet.yml`'s host Qt modules step |
| NDK | 28.2.13676358 | `android-toolchain` (`ndk-version`), `libs.versions.toml` (`ndk`), `scripts/build-apk.sh`, `scripts/check-apk.sh` |
| SDK platform, compile and target SDK | 36 | `android-toolchain` (`platform`), `libs.versions.toml`, `scripts/build-apk.sh` (`--android-platform`), `native/enginehost/CMakeLists.txt` |
| minSdk | 28, Qt 6.12's minimum | `libs.versions.toml`, `native/enginehost/CMakeLists.txt`, the `ANDROID_API` default of the libmudlet scripts |
| build-tools | 37.0.0 | `android-toolchain` (`build-tools`), `libs.versions.toml`, `scripts/check-apk.sh` |
| Gradle, AGP, Kotlin | 8.14.3, 8.13.2, 2.3.21 | `gradle/wrapper/gradle-wrapper.properties`, `libs.versions.toml` |
| AndroidX core, activity, Compose BOM | 1.17.0, 1.11.0, 2025.12.01 | `libs.versions.toml` |
| JDK | 21 | `android-toolchain` (`java-version`), `libs.versions.toml` (`jdk`) |
| OpenSSL; Mudlet's dependencies | 3.5.9; in the script | `scripts/build-openssl.sh`; `scripts/build-mudlet-deps.sh`; each with its sha256s |

AndroidX minor releases can need a newer compile SDK and AGP (core 1.19.1 and the 2026.09.00 Compose BOM need
compileSdk 37 and AGP 9.1), so take them with an SDK level or AGP bump, at the newest versions that build with it.

**Bumping Qt** (and with it the NDK, SDK levels or AGP): read the new Qt's Android requirements (its supported NDK,
minimum and target SDK, and the AGP and Gradle its templates use), change every place in the table in one pull
request, and check that `AndroidServiceSetup.cpp`'s private API still exists in that Qt
([`docs/architecture.md`](docs/architecture.md), "Qt internals relied on"). The pull request runs every build and
emulator job, as it changes `.github/actions/`; the emulator jobs are the only test of the engine on Android. Caches
are keyed on the Qt and NDK versions, so a bump rebuilds them. AGP 9 needs Gradle 9, so leaving AGP 8.13 means moving the wrapper too
(`./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`, the sum from
`https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`).
