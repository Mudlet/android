#!/bin/bash
# Builds the debug APK: the engine .so (native/) with Qt's Android CMake, staged for :engine-service with
# androiddeployqt --aux-mode together with OpenSSL for Qt's TLS backend and the engine's name and version for the
# About screen (res/values/engine_info.xml), then the app with Gradle. Bounded
# parallelism: the machine is shared. With STAGE_ONLY=1 it stops after staging, for scripts/build-release.sh, which
# merges the staged engines of several ABIs into one release.
#
# Usage: scripts/build-apk.sh [Gradle arguments, e.g. -PextractNativeLibs=true]
# Optional: QT_ROOT (~/Programs/Qt/6.12.0), ANDROID_ABI (arm64-v8a), ANDROID_SDK_ROOT (~/Android/Sdk), JAVA_HOME,
# LIBMUDLET_BUILD_DIR (a Mudlet build tree from scripts/build-libmudlet.sh: the engine is then libmudlet),
# OPENSSL_PREFIX (a prefix from scripts/build-openssl.sh, default native/openssl-<abi>, built there if missing),
# DEPLOY_DIR (where the engine is staged, emptied first; engine-service/qtdeploy), STAGE_ONLY=1 (stage, then stop).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
abi=${ANDROID_ABI:-arm64-v8a}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
build="$root/native/build-android-$abi"
deploy=$(realpath -m "${DEPLOY_DIR:-$root/engine-service/qtdeploy}")
openssl=${OPENSSL_PREFIX:-$root/native/openssl-$abi}
mudlet_args=()
if [ -n "${LIBMUDLET_BUILD_DIR:-}" ]; then
    build="$build-mudlet"
    mudlet_args=(-DLIBMUDLET_BUILD_DIR="$(realpath "$LIBMUDLET_BUILD_DIR")")
fi

if [ ! -f "$openssl/lib/libssl_3.so" ] || [ ! -f "$openssl/lib/libcrypto_3.so" ]; then
    ANDROID_ABI=$abi JOBS=${JOBS:-2} "$root/scripts/build-openssl.sh" "$openssl"
fi
# Each run, so that an existing build tree takes OPENSSL_PREFIX too
"$qt/android_${abi//-/_}/bin/qt-cmake" -S "$root/native" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DQT_HOST_PATH="$qt/gcc_64" -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" -DANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
    -DANDROID_OPENSSL_DIR="$(realpath "$openssl/lib")" "${mudlet_args[@]}"
${NINJA:-ninja} -C "$build" -j2 engine

rm -rf "$deploy"
mkdir -p "$deploy/libs/$abi"
cp "$build/enginehost/libengine_$abi.so" "$deploy/libs/$abi/"
"$qt/gcc_64/bin/androiddeployqt" --input "$build/enginehost/android-engine-deployment-settings.json" \
    --output "$deploy" --aux-mode --android-platform android-36 2>&1 | { grep -v -i locale || true; }
# aux mode also writes a Gradle project and manifest template we do not use
rm -f "$deploy/build.gradle" "$deploy/settings.gradle" "$deploy/AndroidManifest.xml"

# Which engine this is, for the app's About screen and its licences (docs/licences.md): for libmudlet, Mudlet's
# version as its CMake set it (APP_VERSION and the cached APP_BUILD) and the commit of the checkout it was built from
engine_kind=standin engine_version='' engine_commit=''
if [ -n "${LIBMUDLET_BUILD_DIR:-}" ]; then
    cache="$(realpath "$LIBMUDLET_BUILD_DIR")/CMakeCache.txt"
    mudlet_src=$(sed -n 's/^CMAKE_HOME_DIRECTORY:INTERNAL=//p' "$cache")
    [ -f "$mudlet_src/CMakeLists.txt" ] || { echo "$cache names no Mudlet source ('$mudlet_src')" >&2; exit 1; }
    app_version=$(sed -n 's/^set(APP_VERSION \(.*\))$/\1/p' "$mudlet_src/CMakeLists.txt")
    [ -n "$app_version" ] || { echo "no APP_VERSION in $mudlet_src/CMakeLists.txt" >&2; exit 1; }
    engine_kind=mudlet
    engine_version="$app_version$(sed -n 's/^APP_BUILD:STRING=//p' "$cache")"
    # empty for a source tree that is not a git checkout: the About screen then names no commit
    engine_commit=$(git -C "$mudlet_src" rev-parse --verify -q HEAD || true)
fi
xml_escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e "s/'/\\\\'/g" <<< "$1"; }
mkdir -p "$deploy/res/values"
cat > "$deploy/res/values/engine_info.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Written by scripts/build-apk.sh. :app's About screen reads these, which lint of :engine-service alone cannot see -->
<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources">
    <string name="engine_kind" translatable="false">$engine_kind</string>
    <string name="engine_version" translatable="false">$(xml_escape "$engine_version")</string>
    <string name="engine_commit" translatable="false">$engine_commit</string>
</resources>
EOF
if [ "${STAGE_ONLY:-0}" = 1 ]; then
    echo "Staged the $abi engine in $deploy"
    exit 0
fi

cd "$root"
./gradlew -Dorg.gradle.workers.max=2 -PandroidAbi="$abi" -PqtRoot="$qt" :app:assembleDebug "$@"
ls -l "$root/app/build/outputs/apk/debug/"
