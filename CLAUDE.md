# Mudlet for Android

A Kotlin + Compose UI process (no Qt) and Mudlet's engine in a Qt foreground service in the `:engine` process,
talking over a local socket. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the full commands, the pinned versions and the
ground rules; [`docs/architecture.md`](docs/architecture.md) and [`docs/protocol.md`](docs/protocol.md) the design.

## Commands

Set `QT_ROOT` (with `gcc_64` and `android_arm64_v8a`), `ANDROID_SDK_ROOT`/`ANDROID_HOME` and `JAVA_HOME` (21) first.
Gradle is always `./gradlew`.

```sh
scripts/build-apk.sh                                   # stand-in engine APK
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh         # libmudlet APK, after build-mudlet-deps.sh and build-libmudlet.sh
./gradlew :protocol:test                               # JVM tests, no engine
cmake -S native -B native/build-desktop -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64
cmake --build native/build-desktop --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_engine.py native/build-desktop/enginehost/engine
QT_QPA_PLATFORM=offscreen native/tests/run_jvm_client_test.sh -PqtRoot=$QT_ROOT
scripts/check-apk.sh                                   # the APK checks, after build-apk.sh
scripts/lint.sh                                        # ktlint, clang-format, ShellCheck, actionlint
./gradlew -PqtRoot=$QT_ROOT :app:lintDebug :engine-service:lintDebug   # Android lint, after build-apk.sh
./gradlew ktlintFormat; FIX=1 scripts/lint.sh clang-format             # fix formatting
```

The real engine's host tests (`test_mudlet_engine.py`, `test_mudlet_restore.py`) and the emulator smoke test are in
CONTRIBUTING.md's "Test".

## Rules

- The machine may be shared: keep Gradle's two workers (`gradle.properties`), and pass `JOBS=2` to the libmudlet
  and dependency scripts, which otherwise use every core.
- Before a commit: `scripts/lint.sh`, and the tests for what changed. Formatting-only changes go in a commit of
  their own.
- A protocol change updates `docs/protocol.md`, `SocketBridge`, `Protocol.kt` and `native/tests/protocol_client.py`
  together; `ProtocolSpecTest` checks them.
- Docs change with the code they describe. Keep a doc's facts true: run what it tells others to run.
- Do not bump Qt, the NDK, SDK levels, AGP, Kotlin or Gradle on their own; see "Pinned versions" in CONTRIBUTING.md.
- Changes Mudlet needs go upstream to Mudlet/Mudlet and are carried in `scripts/` until merged there.
- Never commit build output (`.gitignore` lists it: `native/build*`, `engine-service/qtdeploy/`, `deps/`, ...).
