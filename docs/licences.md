# Licences

The app's About screen (the button in the profile list's top bar) shows the app's version, its engine, where the
source code of both is, and the licence of everything the APK bundles, with each licence's full text. This page says
where that list comes from, what keeps it true, and how the APK meets the LGPL for Qt.

## What the list is made of

Everything is in the APK's `assets/licences/`, from [`app/src/main/assets/licences`](../app/src/main/assets/licences):

| File | What | Written by |
|---|---|---|
| `notices.json` | Mudlet, Qt, the native libraries (OpenSSL, libc++, the dependencies `scripts/build-mudlet-deps.sh` builds, the Lua C modules, fonts and Lua files Mudlet builds in), the icons; which component each `.so` in the APK belongs to (`libraries`); how a Gradle POM's licence name reads as SPDX (`pomLicences`) | hand |
| `qt-third-party.json` | The code from others inside the Qt modules the APK ships, from Qt's own `qt_attribution.json` files | `scripts/qt-attributions.py` |
| `android.json` | Every module of the Gradle runtime classpath, with the licence its POM (or a parent POM) declares | `:app`'s `generate<Variant>Licences` task, at build time |
| `texts/<id>.txt` | The text of each licence, named by its SPDX identifier, from Qt's `LICENSES/` or the SPDX list | hand, `qt-attributions.py` |

A component's `licence` is an SPDX expression; the screen shows the text of each identifier in it, plus `texts`
(the GPL v3 that the LGPL v3 is written on top of, for Qt). `engines` says which APKs carry a component: the
stand-in engine's (`standin`) or libmudlet's (`mudlet`). `scripts/build-apk.sh` writes which engine an APK carries,
with Mudlet's version and the commit it was built from, into `:engine-service`'s `res/values/engine_info.xml`.

## What keeps it true

- **Every APK CI builds** goes through `scripts/check-apk.sh`, which runs `scripts/check-licences.py --apk`: each
  `lib/<abi>/*.so` must be claimed by a component of that APK's engine, the APK must carry the licence files as they
  are in the repository, and `android.json` with a text for each of its licences.
- **The repository** (`scripts/check-licences.py` alone, also part of the above): every licence named has its text and
  every text is used; the versions listed for OpenSSL, Qt, the NDK, the stand-in's Lua and each dependency
  `build-mudlet-deps.sh` builds are the ones the scripts pin, so a bump that forgets the list fails.
- **Mudlet's checkout** (`--mudlet-source`, in the libmudlet job, so also nightly against Mudlet's `development`):
  each Lua C module in Mudlet's `cmake/BundledLuaModules.cmake`, each font directory in `mudlet_fonts_common.qrc`,
  each file in `src/mudlet-lua/lua/3rdparty` and the `3rdparty/` libraries the core builds must have a component.
- **The Gradle task** fails the build on a POM licence that `pomLicences` does not map, or a module with no POM licence.
- `LicencesTest` (`app/src/test`, the APK job) checks the data parses for both engines; `AboutScreenTest`
  (`app/src/androidTest`, the emulator jobs) opens the screen and a licence through TalkBack's actions, with the
  accessibility checks on.

When a check fails: add the component, or the library to `libraries`, or the POM's licence name to `pomLicences`,
or the version, as the error says. After a Qt bump, regenerate the Qt attributions from that Qt's sources:

```sh
for m in qtbase qtsvg qtmultimedia qt5compat qtspeech; do
    git clone --depth 1 --branch v6.12.0 --filter=blob:none --sparse https://github.com/qt/$m ../$m
    git -C ../$m sparse-checkout set --no-cone '/**/qt_attribution.json' '/LICENSES/'
done
scripts/qt-attributions.py 6.12.0 ../qtbase=standin,mudlet ../qtsvg=mudlet ../qtmultimedia=mudlet \
    ../qt5compat=mudlet ../qtspeech=mudlet
```

## Mudlet and the GPL

Mudlet is under the GPL version 2 or later. The APK combines it with Qt (LGPL v3) and OpenSSL (Apache 2.0), which
are compatible with version 3 of the GPL but not with version 2 alone, so the app as a whole is distributed under
GPL v3, as Mudlet's own desktop builds are. The About screen links the exact Mudlet commit the engine was built from
and the commit of this repository the APK was built from, whose scripts are how the APK is built.

## Qt and the LGPL v3

What the APK ships, as checked in the APKs CI builds:

- **Shared libraries only.** Qt's modules (`libQt6*_<abi>.so`) and plugins (`libplugins_*_<abi>.so`) are separate
  `.so` files that the engine loads dynamically: Core, Gui and Network for the stand-in engine, and with libmudlet
  also Concurrent, Core5Compat, Multimedia, OpenGL, Svg and TextToSpeech. They are the Qt Company's prebuilt,
  unmodified binaries of Qt 6.12.0 (installed by `aqtinstall`). Nothing of Qt is linked statically (QtKeychain,
  which is, is not Qt and is BSD licensed).
- **FFmpeg** (libmudlet APK only): Qt Multimedia's FFmpeg backend brings Qt's own build of FFmpeg 9.0.1, LGPL v2.1
  or later without its GPL parts, as `libavcodec.so`, `libavformat.so`, `libavutil.so`, `libswresample.so` and
  `libswscale.so`, loaded dynamically too. The About screen links its source release; Qt's build scripts for it are
  in Qt's `qt5` repository (`coin/provisioning`).
- **Qt's Java code** (`Qt6Android*.jar`, which `androiddeployqt` stages) is compiled into the app's dex and, in a
  release, minified by R8 with the rest of the app. Replacing it means rebuilding the APK, which the next section
  covers.
- **Notice:** the About screen says Qt is used under the LGPL v3, and carries the LGPL and the GPL v3 it builds on.
- **Source:** the About screen links Qt 6.12.0's source on download.qt.io (Qt's own server: the binaries are built
  from that release unchanged), and the app's and Mudlet's source, which is everything needed to rebuild the APK.

### Rebuilding the app with your own Qt

The LGPL lets you change Qt and run the app with your changed Qt. As the whole app is free software, the way to do
that is to rebuild it:

1. Build Qt 6.12.0 from your changed source, for the host and for Android, into one directory laid out as the
   installer lays it out (`gcc_64/` and `android_arm64_v8a/`). For Android, configure with
   `-platform android-clang -android-ndk <NDK 28.2.13676358> -android-sdk <SDK> -android-abis arm64-v8a
   -qt-host-path <your host Qt> -submodules qtbase,qtsvg,qtmultimedia,qt5compat,qtspeech`, then
   `cmake --build .` and `cmake --install .`. Keep to 6.12.0's private API, which
   `native/enginehost/AndroidServiceSetup.cpp` relies on ([`architecture.md`](architecture.md), "Qt internals
   relied on").
2. Set `QT_ROOT` to that directory and build as [`CONTRIBUTING.md`](../CONTRIBUTING.md) says ("Build"), with
   `scripts/build-mudlet-deps.sh`, `scripts/build-libmudlet.sh` and `scripts/build-apk.sh`.
3. Install the APK. It is signed with your key, not the project's, so uninstall the app from Google Play first.

GPL v3's "Installation Information" applies to software conveyed with a device sold or leased; the app is not, and
Android installs a self-built, self-signed APK anyway.
