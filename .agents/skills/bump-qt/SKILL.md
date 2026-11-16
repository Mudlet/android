---
name: bump-qt
description: >-
  Read before moving the app to a new Qt version, or the NDK, SDK levels or AGP that move with it. Gives every place
  the version is pinned, the private Qt API to re-check, the licence data to regenerate and what CI must show.
license: GPL-3.0-or-later
---

## When to use

A Qt bump, or an NDK, SDK level or AGP change, which Qt's Android requirements tie to it. One pull request moves them
all together ("Pinned versions" in CONTRIBUTING.md). Which Qt to move to is `docs/foundation-checklist.md`'s "Qt pin
policy": 6.12's patch releases as they come, a new minor release only for a reason it names.

## 1. Read the new Qt's Android requirements

Its supported NDK, minimum and target SDK, and the AGP and Gradle its templates use. Qt's minimum SDK is the app's
minSdk (`libs.versions.toml`). AGP 9 needs Gradle 9, so leaving AGP 8.13 means moving the wrapper too:

```sh
./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>
# the sum from https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256
```

## 2. Change every pinned place

The table in CONTRIBUTING.md's "Pinned versions" is the list; update its versions and the "Setting up" text with it.
Then find what the table misses:

```sh
git grep -n '6\.12\.0'          # the old Qt; likewise the old NDK (28.2.13676358), platform and build-tools
```

Expect at least: `.github/actions/android-toolchain/action.yml` (`qt-version`, `ndk-version`, `platform`,
`build-tools`, `aqtinstall-version`), `scripts/build-apk.sh` (default `QT_ROOT`, `ANDROID_NDK_ROOT`,
`--android-platform`), `gradle.properties` (`qtRoot`), `gradle/libs.versions.toml`, `scripts/check-apk.sh`,
`native/enginehost/CMakeLists.txt`, the `ANDROID_API` default of `build-libmudlet.sh`, `build-mudlet-deps.sh` and
`build-openssl.sh` for a minSdk change, `libmudlet.yml`'s host Qt step (aqtinstall), README.md, and the docs that name
the version (`docs/architecture.md`, `docs/licences.md`, and for a new minor release the pin policy in
`docs/foundation-checklist.md`). `docs/decisions/` is history: leave it.

## 3. Re-check the private Qt API

`native/enginehost/AndroidServiceSetup.cpp` uses Qt's private API, and `EngineService` relies on `QtServiceBase`'s
order of calls (`docs/architecture.md`, "Qt internals relied on", has each assumption). With the new Qt installed, and
`scripts/build-apk.sh` run once so that `engine-service/qtdeploy/` holds its jars:

```sh
grep -rn 'acuqireServiceSetup\|setOnBindListener' \
    "$QT_ROOT"/android_arm64_v8a/include/QtCore/*/QtCore/private/qjnihelpers_p.h
javap -c -classpath engine-service/qtdeploy/libs/Qt6Android.jar org.qtproject.qt.android.QtServiceBase
```

Both names must print: `grep` prints nothing, and fails quietly, when Qt has renamed them.

`acuqireServiceSetup` is Qt's own spelling: do not correct it. In the `javap` output, check what "Qt internals relied
on" lists: `onCreate` starts Qt in the same order, `onBind` returns `QtNative.onBind`, and `onDestroy` still ends in
`System.exit(0)`. Read the new Qt's `androidjnimain.cpp` and `qandroidextras.cpp` for the service-setup semaphore's
locker logic. A change there shows only on a device: an ANR in `Service.onCreate`, or an engine whose timers and
sockets stop.

`androiddeployqt --aux-mode`'s output layout and the `libs.xml` keys `scripts/build-apk.sh` relies on are Qt's
internals too: `scripts/build-apk.sh` and `scripts/check-apk.sh` show whether they still hold.

## 4. Regenerate the licence data

`scripts/check-licences.py` fails while `app/src/main/assets/licences/qt-third-party.json` names another Qt version.
Rerun `scripts/qt-attributions.py` on source checkouts of the new Qt's modules, as its header and `docs/licences.md`
say, and update Qt's version and source link in `app/src/main/assets/licences/notices.json`.

## 5. CI

The pull request changes `.github/actions/`, so it runs every build and emulator job. The caches are keyed on the Qt
and NDK versions, so the first run rebuilds them and takes much longer. The emulator jobs are the only test of the
engine on Android: the bump is done when both `emulator (API 28)` and `emulator (API 36)` pass. If the APKs grow, raise
the budgets as CONTRIBUTING.md's "APK size budget" says, naming the Qt bump as the reason.
