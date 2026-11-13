#!/bin/bash
# Builds the engine .so with Qt's Android CMake, stages it with androiddeployqt --aux-mode,
# then builds the Kotlin/Compose APK with Gradle. Bounded parallelism: the machine is shared.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
abi=${ANDROID_ABI:-arm64-v8a}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
build="$here/build-android-$abi"
deploy="$here/android/qtdeploy"
# With MUDLET_SOURCE_DIR and MUDLET_BUILD_DIR (see deps/build-mudlet.sh) the engine is libmudlet
mudlet_args=()
if [ -n "${MUDLET_BUILD_DIR:-}" ]; then
    build="$build-mudlet"
    mudlet_args=(-DMUDLET_SOURCE_DIR="$MUDLET_SOURCE_DIR" -DMUDLET_BUILD_DIR="$MUDLET_BUILD_DIR"
        -DMUDLET_DEPS_PREFIX="${MUDLET_DEPS_PREFIX:-$here/build-deps-$abi/prefix}")
fi

if [ ! -f "$build/build.ninja" ]; then
    "$qt/android_${abi//-/_}/bin/qt-cmake" -S "$here" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DQT_HOST_PATH="$qt/gcc_64" -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" -DANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" "${mudlet_args[@]}"
fi
${NINJA:-ninja} -C "$build" -j2 engine

rm -rf "$deploy"
mkdir -p "$deploy/libs/$abi"
cp "$build/enginehost/libengine_$abi.so" "$deploy/libs/$abi/"
"$qt/gcc_64/bin/androiddeployqt" --input "$build/enginehost/android-engine-deployment-settings.json" \
    --output "$deploy" --aux-mode --android-platform android-36 2>&1 | { grep -v -i locale || true; }
# aux mode also writes a Gradle project and manifest template we do not use
rm -f "$deploy/build.gradle" "$deploy/settings.gradle" "$deploy/AndroidManifest.xml"
if [ -n "${MUDLET_BUILD_DIR:-}" ]; then
    # Mudlet loads its Lua from a directory; the engine copies these out when the stamp changes
    mkdir -p "$deploy/assets/mudlet-lua"
    cp -r "$MUDLET_SOURCE_DIR/src/mudlet-lua/lua" "$deploy/assets/mudlet-lua/"
    # Where installers put them, beside the Lua that loads them (loadTranslations() in Other.lua)
    cp -r "$MUDLET_SOURCE_DIR/translations/lua" "$deploy/assets/mudlet-lua/lua/translations"
    (cd "$deploy/assets/mudlet-lua" && find lua -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16 > stamp)
fi

cd "$here/android"
gradle --no-daemon -Dorg.gradle.workers.max=2 -PandroidAbi="$abi" -PqtRoot="$qt" :app:assembleDebug "$@"
ls -l "$here/android/app/build/outputs/apk/debug/"
