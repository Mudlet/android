#!/bin/bash
# Builds the debug APK: the engine .so (native/) with Qt's Android CMake, staged for :engine-service with
# androiddeployqt --aux-mode together with OpenSSL for Qt's TLS backend, then the app with Gradle. Bounded
# parallelism: the machine is shared.
#
# Usage: scripts/build-apk.sh [Gradle arguments, e.g. -PextractNativeLibs=true]
# Optional: QT_ROOT (~/Programs/Qt/6.12.0), ANDROID_ABI (arm64-v8a), ANDROID_SDK_ROOT (~/Android/Sdk), JAVA_HOME,
# LIBMUDLET_BUILD_DIR (a Mudlet build tree from scripts/build-libmudlet.sh: the engine is then libmudlet),
# OPENSSL_PREFIX (a prefix from scripts/build-openssl.sh, default native/openssl-<abi>, built there if missing).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
abi=${ANDROID_ABI:-arm64-v8a}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
build="$root/native/build-android-$abi"
deploy="$root/engine-service/qtdeploy"
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

cd "$root"
./gradlew -Dorg.gradle.workers.max=2 -PandroidAbi="$abi" -PqtRoot="$qt" :app:assembleDebug "$@"
ls -l "$root/app/build/outputs/apk/debug/"
