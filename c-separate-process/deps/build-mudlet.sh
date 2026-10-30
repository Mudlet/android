#!/bin/bash
# Cross-compiles libmudlet (mudlet_core + mudlet_headless) for Android from a Mudlet checkout, against the
# prefix deps/build-deps.sh made. Apply deps/mudlet-android.patch to the checkout first.
# Usage: ANDROID_ABI=x86_64 MUDLET_SOURCE_DIR=~/Mudlet deps/build-mudlet.sh  ->  build-mudlet-<abi>
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
abi=${ANDROID_ABI:-arm64-v8a}
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/28.2.13676358}
src=${MUDLET_SOURCE_DIR:?set MUDLET_SOURCE_DIR to a Mudlet checkout}
prefix="$here/build-deps-$abi/prefix"
build="$here/build-mudlet-$abi"

if [ ! -f "$build/build.ninja" ]; then
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH= \
    WITH_UPDATER=NO WITH_3DMAPPER=NO WITH_FONTS=NO WITH_MCPSERVER=NO WITH_HEADLESS=YES WITH_BUNDLED_LUA=YES \
    "$qt/android_${abi//-/_}/bin/qt-cmake" -S "$src" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DQT_HOST_PATH="$qt/gcc_64" -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" -DANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
        -DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_FIND_ROOT_PATH="$prefix" -DBoost_DIR="$prefix/lib/cmake/Boost" \
        -DBUILD_TESTING=OFF -DPCRE2_USE_STATIC_LIBS=ON -DUSE_SANITIZER= \
        -DQt6LinguistTools_DIR="$qt/gcc_64/lib/cmake/Qt6LinguistTools"
fi
${NINJA:-ninja} -C "$build" -j "${JOBS:-4}" mudlet_headless
echo "libmudlet for $abi in $build"
