#!/bin/bash
# Builds the engine .so with Qt's Android CMake, stages it with androiddeployqt --aux-mode,
# then builds the Kotlin/Compose APK with Gradle. Bounded parallelism: the machine is shared.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
qt=/opt/android-toolchain/qt/6.12.0
export ANDROID_SDK_ROOT=/opt/android-toolchain/sdk
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
build="$here/build-android"
deploy="$here/android/qtdeploy"

if [ ! -f "$build/build.ninja" ]; then
    "$qt/android_arm64_v8a/bin/qt-cmake" -S "$here" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DQT_HOST_PATH="$qt/gcc_64" -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" -DANDROID_NDK_ROOT="$ANDROID_NDK_ROOT"
fi
ninja -C "$build" -j2 engine

rm -rf "$deploy"
mkdir -p "$deploy/libs/arm64-v8a"
cp "$build/enginehost/libengine_arm64-v8a.so" "$deploy/libs/arm64-v8a/"
"$qt/gcc_64/bin/androiddeployqt" --input "$build/enginehost/android-engine-deployment-settings.json" \
    --output "$deploy" --aux-mode --android-platform android-36 2>&1 | grep -v -i locale || true
# aux mode also writes a Gradle project and manifest template we do not use
rm -f "$deploy/build.gradle" "$deploy/settings.gradle" "$deploy/AndroidManifest.xml"

cd "$here/android"
gradle --no-daemon -Dorg.gradle.workers.max=2 :app:assembleDebug "$@"
ls -l "$here/android/app/build/outputs/apk/debug/"
