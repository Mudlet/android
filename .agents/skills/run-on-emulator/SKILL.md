---
name: run-on-emulator
description: >-
  Read before running the app, the smoke test or the instrumented tests on an emulator or device, or through CI's
  emulator jobs, and when reading a crash, a hang or the screenshots they leave. Gives the x86_64 build, the two test
  runners, how to symbolise a crash and where the screenshots go.
license: GPL-3.0-or-later
---

## When to use

Any change that the engine on Android could break, a new instrumented test, or a pull request that changes how the app
looks. The emulator jobs are the only test of the real engine on Android.

## Locally: an emulator with KVM

Needs Qt for `android_x86_64` beside `gcc_64`, the SDK of CONTRIBUTING.md's "Setting up", an x86_64 `google_apis`
system image (CI boots API 28, the minSdk, and API 36), and `adb` on the `PATH`. Start the emulator first; both
runners use the one device `adb` sees.

```sh
# libmudlet for x86_64, from a checkout of Mudlet's development (CONTRIBUTING.md, "Build"), then the APK and the
# two test APKs; JOBS=2 on a shared machine
ANDROID_ABI=x86_64 JOBS=2 scripts/build-mudlet-deps.sh deps/prefix
ANDROID_ABI=x86_64 JOBS=2 scripts/build-libmudlet.sh ../mudlet build deps/prefix
ANDROID_ABI=x86_64 LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh :app:assembleDebugAndroidTest \
    :engine-service:assembleDebugAndroidTest

# The smoke test, then the instrumented tests, as CI's emulator job runs them; set ANDROID_NDK_ROOT for --symbols
python3 native/tests/android_smoke.py --apk app/build/outputs/apk/debug/app-debug.apk --out smoke-test \
    --symbols native/build-android-x86_64-mudlet/enginehost
python3 native/tests/android_instrumented.py --app app/build/outputs/apk/debug/app-debug.apk \
    --app-tests app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --engine-tests engine-service/build/outputs/apk/androidTest/debug/engine-service-debug-androidTest.apk \
    --out instrumented-test --symbols native/build-android-x86_64-mudlet/enginehost
```

`deps/prefix` and `build` hold one ABI at a time: use other directories to keep an arm64-v8a build beside it.

One test class, once `android_instrumented.py` has installed the APKs (it uninstalls and reinstalls them at each run):

```sh
adb shell am instrument -w -r -e class org.mudlet.android.ui.ScriptLabelsTest \
    org.mudlet.mudlet.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` exits 0 even when a test fails: read the end of its output for `OK (<n> tests)`, with `n` the number
of tests meant to run.

Do not use `./gradlew connectedAndroidTest`: it installs with `-g`, which grants every permission, and
`NotificationPermissionTest` fails.

## Through CI: no KVM, or no Android toolchain

A pull request runs the emulator jobs when it changes the Kotlin, `native/`, the Gradle build, the APK or Mudlet
scripts, or `.github/actions/` (the "changes" job in `.github/workflows/ci.yml`). To run them alone on a branch,
dispatch `libmudlet.yml` with `android` and `desktop-engine` off and `emulator` on; a manual run of `ci.yml` runs
everything, which costs far more runner time.

Each `emulator (API 28)` and `emulator (API 36)` job uploads `emulator-tests-api<level>`, both runners' `--out`
(`device-tests`), listed below; the job summary gives the steps.

## What the runners leave in `--out`

- `smoke.log`, `instrumented.log`: each step, and the failures with their stacks.
- `logcat.txt`, `logcat-instrumented.txt`; `instrument-<name>.txt`, each instrumentation's raw output.
- `crash-symbolised.txt`: a crash of the app's processes through `ndk-stack`, when `--symbols` and
  `ANDROID_NDK_ROOT` were given. `stacks-<name>.txt`: the threads of a process that hung.
- `screenshot.png`, `before-kill.png`, `instrumented-screenshot.png`, `ui-state.json`, `memory.json`.
- `screenshots/`: what `ScreenshotTest` took (the profile list, the game screen, settings, the map in front, a trigger's
  page, light and dark), on API 31 and later, so only from the API 36 job in CI. These are the before and after shots
  for a pull request that changes how the app looks: take them from the base's run and the branch's.

## Symbolising a crash from a CI run

The engine loads from inside the APK, so logcat names a frame `base.apk (offset ...)`. `ndk-stack` finds it when the
APK sits beside the unstripped library under that name. Download `emulator-apks` from the same run, which keeps the
repository's paths, and in the directory it was unpacked into:

```sh
mkdir symbols
cp app/build/outputs/apk/debug/app-debug.apk symbols/base.apk
cp native/build-android-x86_64-mudlet/enginehost/libengine_x86_64.so symbols/
"$ANDROID_NDK_ROOT/ndk-stack" -sym symbols -i logcat.txt
```

## Pitfalls

- Green is not proof: `android_instrumented.py` fails a run in which no test ran, and an `assumeTrue` that skips, but
  an `@Ignore`d test passes as `IGNORED`, and a test that checks the wrong node passes too. Confirm a new test fails on
  the base.
- The accessibility checks need API 34 and the pseudo-locale test API 33, so in CI they run on API 36 only.
