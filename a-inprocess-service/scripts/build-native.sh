#!/usr/bin/env bash
# Builds libmudletproto for arm64-v8a and stages it with its Qt dependencies for Gradle:
#   build-android/stage/libs/arm64-v8a/*.so    -> app jniLibs
#   build-android/stage/res/values/libs.xml    -> app res (read by Qt's QtLoader)
#   build-android/stage/assets/                -> app assets (Qt asset cache, if any)
# Gradle's buildNative task runs this; it can also be run by hand. Bounded to -j2.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
QT_ANDROID="${QT_ANDROID:-/opt/android-toolchain/qt/6.12.0/android_arm64_v8a}"
QT_HOST="${QT_HOST:-/opt/android-toolchain/qt/6.12.0/gcc_64}"
SDK="${ANDROID_SDK_ROOT:-/opt/android-toolchain/sdk}"
NDK="${ANDROID_NDK_ROOT:-$SDK/ndk/28.2.13676358}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
out="$here/build-android"
stage="$out/stage"

if [ ! -f "$out/build.ninja" ]; then
  "$QT_ANDROID/bin/qt-cmake" -S "$here/native" -B "$out" -G Ninja \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DQT_HOST_PATH="$QT_HOST" \
    -DANDROID_SDK_ROOT="$SDK" -DANDROID_NDK_ROOT="$NDK" \
    -DANDROID_PLATFORM=android-28
fi
ninja -C "$out" -j2 mudletproto_prepare_apk_dir

settings="$out/android-mudletproto-deployment-settings.json"
"$QT_HOST/bin/androiddeployqt" --input "$settings" --output "$out/android-build" \
  --android-platform android-36 --aux-mode

rm -rf "$stage"
mkdir -p "$stage/libs" "$stage/res/values" "$stage/assets"
cp -r "$out/android-build/libs/arm64-v8a" "$stage/libs/"
cp "$out/android-build/res/values/libs.xml" "$stage/res/values/"
if [ -d "$out/android-build/assets" ]; then cp -r "$out/android-build/assets/." "$stage/assets/"; fi
echo "staged: $(ls "$stage/libs/arm64-v8a" | wc -l) libraries"
