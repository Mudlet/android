# Mudlet for Android

A Kotlin + Compose UI process (no Qt) and Mudlet's engine in a Qt foreground service in the `:engine` process,
talking over a local socket. `CONTRIBUTING.md` has the full commands, the pinned versions and the ground rules;
`docs/architecture.md` and `docs/protocol.md` the design. `CLAUDE.md`, `AGENTS.md` and
`.github/copilot-instructions.md` link to this file; its paths are from the repository's top.

## Commands

Set `QT_ROOT` (with `gcc_64` and `android_arm64_v8a`), `ANDROID_SDK_ROOT`/`ANDROID_HOME` and `JAVA_HOME` (21) first.
Gradle is always `./gradlew`.

```sh
LIBMUDLET_BUILD_DIR=build scripts/build-apk.sh         # the APK, after build-mudlet-deps.sh and build-libmudlet.sh
./gradlew :protocol:test                               # JVM tests, no engine
# the engine on the host (needs Mudlet's toolchain), its host tests, and the Kotlin client against it
scripts/build-libmudlet-desktop.sh ../mudlet build-desktop
cmake -S native -B native/build-desktop-mudlet -G Ninja -DCMAKE_PREFIX_PATH=$QT_ROOT/gcc_64 -DLIBMUDLET_BUILD_DIR=$PWD/build-desktop
cmake --build native/build-desktop-mudlet --target engine
QT_QPA_PLATFORM=offscreen python3 native/tests/test_mudlet_engine.py native/build-desktop-mudlet/enginehost/engine
QT_QPA_PLATFORM=offscreen native/tests/run_jvm_client_test.sh -PqtRoot=$QT_ROOT
scripts/check-apk.sh                                   # the APK checks, after build-apk.sh
scripts/build-release.sh <staged-dir>... -- -PreleaseChannel=ptb   # AAB + APKs, after STAGE_ONLY=1 build-apk.sh per ABI
scripts/lint.sh                                        # ktlint, clang-format, ShellCheck, actionlint, strings, conflict markers, vendored, games
./gradlew -PqtRoot=$QT_ROOT :app:lintDebug :engine-service:lintDebug   # Android lint, after build-apk.sh
./gradlew ktlintFormat; FIX=1 scripts/lint.sh clang-format             # fix formatting
```

The restore test (`test_mudlet_restore.py`), the emulator smoke test and the instrumented tests (`src/androidTest`,
run by `native/tests/android_instrumented.py`, not `connectedAndroidTest`) are in CONTRIBUTING.md's "Test". CI (`ci.yml`) runs only what the changed files need; "CI result" is its one result.

## Skills

Procedures too long for this file are in `.agents/skills/<name>/SKILL.md` (Claude Code reads them through the
`.claude/skills` link). Read the one for the task before starting it:

- `run-on-emulator`: the smoke and instrumented tests on an emulator or through CI's emulator jobs, crash symbols,
  screenshots
- `bump-qt`: moving to a new Qt, and with it the NDK, SDK levels or AGP

## Rules

- The machine may be shared: keep Gradle's two workers (`gradle.properties`), and pass `JOBS=2` to the libmudlet
  and dependency scripts, which otherwise use every core.
- Before a commit: `scripts/lint.sh`, and the tests for what changed. Formatting-only changes go in a commit of
  their own.
- A new test must fail without the fix: run it on the base first. The emulator and host tests can pass vacuously when
  the setup is wrong (no engine, a skipped `EngineLinkJvmTest`), so a green run proves nothing until it has been red.
- A protocol change updates `docs/protocol.md`, `SocketBridge` or `ControlBridge`, `Protocol.kt` and `native/tests/protocol_client.py`
  together; `ProtocolSpecTest` checks them.
- Docs change with the code they describe. Keep a doc's facts true: run what it tells others to run.
- Do not bump Qt, the NDK, SDK levels, AGP, Kotlin or Gradle on their own; see "Pinned versions" in CONTRIBUTING.md.
- Changes Mudlet needs go upstream to Mudlet/Mudlet and are carried in `scripts/` until merged there.
- Mudlet desktop is the reference for anything a script or a game can see. Close a gap upstream (carried in
  `scripts/mudlet-patches/` until merged) or in the app's renderer; never add an Android-only Lua function or event.
  A deliberate difference goes in `docs/divergences.md`, with why matching desktop would be worse.
- Every screen and control works with TalkBack alone, takes the app's own labels from `strings.xml`, and passes the
  Accessibility Test Framework checks in the instrumented tests (`ScreenReaderTest`, `TalkBackOrderTest`). A new thing
  scripts draw gets a TalkBack reading (`docs/phase-f.md`, "Touch and TalkBack") and a test of it. Check a layout in
  the pseudo-locales en-XA and ar-XB (`PseudoLocaleTest`).
- Stored names are the player's data: renaming one alone orphans what players already have. Rename one only with a
  migration that runs before the first read, and a test that reads the old name. They are:
  - the application id `org.mudlet.mudlet` (`.ptb` for the PTB);
  - the SharedPreferences `settings` (the keys in `data/Settings.kt`), `engine` (`qpaPlatform`, `lastProfile`, and
    `lastHost:`, `lastPort:` and `lastTls:` before a profile's name) and `ui`;
  - `PasswordStore`'s file `passwords/profiles.json` and KeyStore alias `profile-passwords`;
  - the engine's files: `files/mudlet/` (`profiles/<name>/`, `profile-order.json`, and what Mudlet keeps under the
    organisation and application name `Mudlet` that `MudletEngine.cpp` gives Qt), `engine-session.json` and
    `engine-stopped`;
  - the command suggestions in `files/suggest/`;
  - the notification channels `engine` and `tells`, whose ids Android keeps the player's choices under.
- Each `scripts/mudlet-patches/*.patch` goes to Mudlet/Mudlet as a pull request, which its `Upstream:` line names
  once it is open; `scripts/lint.sh vendored` checks it.
- Comment only a why the code cannot show: a Qt or Android quirk, a workaround, an ordering or threading constraint,
  a file or wire format constraint, or why the obvious alternative is wrong. Never narrate the change ("previously",
  "now", "fixes #12"): that is the commit message's. Keep translators' comments and tool directives, and update them
  with what they describe.
- Squash (or rebase) into `main`, never a merge commit; update branches by rebasing: "Merging" in CONTRIBUTING.md.
- A new call from C++ into Kotlin or Java (JNI) needs an R8 rule in `engine-service/consumer-rules.pro`, or the
  minified release loses it; see `docs/ptb.md`. No signing key or password is ever committed.
- Never commit build output (`.gitignore` lists it: `native/build*`, `engine-service/qtdeploy/`, `deps/`, ...).

## Traps (do not fix)

Each looks wrong and is not. Most fail only on Android, where the emulator jobs are the one test.

- `scripts/build-libmudlet.sh` builds Mudlet's Lua 5.1 with `-U_FORTIFY_SOURCE` until Mudlet/Mudlet#12914 is merged:
  without it Lua aborts in `luaL_openlibs` on Android. The script's own `lgc.c` check and the emulator jobs catch it.
- `native/enginehost/AndroidServiceSetup.*` uses Qt 6.12.0's private API (`acuqireServiceSetup` is Qt's spelling) to
  release the service-setup semaphore; without it the engine ANRs in `Service.onCreate`. Only the emulator jobs catch
  it.
- `EngineService` goes foreground before `super.onCreate()`, and `main()` waits for `nativeServiceReady()` before it
  makes `QGuiApplication`: otherwise the start misses its deadline, or Qt stops the engine's timers and sockets
  silently (`docs/architecture.md`, "Qt internals relied on"). Only the emulator jobs catch it.
- `lint.xml` turns `MissingTranslation` off: a string Crowdin has not translated yet shows in English, as on the
  desktop. On, Android lint would fail every build in which a language lacks a string, from Crowdin's first one on.
- The instrumented tests run through `android_instrumented.py`, not `connectedAndroidTest`, which installs with `-g`
  and so grants every permission: `NotificationPermissionTest` fails then.
- The emulator tests install with `adb install --no-streaming`. Streamed, the API 28 emulator's install now and then
  stops partway through the APK and hangs until the 300 s timeout fails the job.
- `EngineLinkJvmTest`'s transport delays the reader's wake-up after a close on purpose: without it the reattach race
  the test guards never shows on the JVM, and the test passes whether or not the fix is there.
- `settings.gradle.kts` lists Google's mirror of Maven Central before `mavenCentral()`, as Maven Central itself
  answered HTTP 429 to builds running in parallel. Nothing catches its removal until a busy day.
- The emulator job on API 28 (minSdk) runs on every pull request that runs the emulator jobs, not only nightly: the
  code that differs by API level is in the files that start them (`libmudlet.yml`).
