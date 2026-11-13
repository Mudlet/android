#!/usr/bin/env bash
# Cross-compiles libmudlet (mudlet_core, mudlet_headless and the mudlet-headless runner) for Android from a
# Mudlet checkout, against the prefix scripts/build-mudlet-deps.sh made, then reports the library sizes and
# fails if they use desktop-only APIs or the runner lacks the built-in Lua files and modules.
#
# Usage: ANDROID_NDK_ROOT=... QT_ROOT=... scripts/build-libmudlet.sh <mudlet-source> <build-dir> <deps-prefix>
# Optional: QT_ANDROID / QT_HOST_PATH instead of QT_ROOT, ANDROID_ABI (arm64-v8a), ANDROID_API (28), JOBS (nproc),
# BUILD_TYPE (Release), HEADLESS_RUNNER=0 to build only the libraries, SIZE_REPORT (<build-dir>/size-report.md).
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: $0 <mudlet-source> <build-dir> <deps-prefix>" >&2; exit 2; }
src=$(realpath "$1")
build=$(realpath -m "$2")
prefix=$(realpath "$3")
abi=${ANDROID_ABI:-arm64-v8a}
api=${ANDROID_API:-28}
ndk=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
qt_android=${QT_ANDROID:-${QT_ROOT:?set QT_ROOT, or QT_ANDROID and QT_HOST_PATH}/android_${abi//-/_}}
qt_host=${QT_HOST_PATH:-${QT_ROOT:?set QT_ROOT, or QT_ANDROID and QT_HOST_PATH}/gcc_64}
jobs=${JOBS:-$(nproc)}
report=${SIZE_REPORT:-$build/size-report.md}

# Mudlet's CMake leaves out the desktop-only parts for Android by itself; USE_GUI and BUILD_TESTING are named
# so that a change of those defaults does not quietly turn this into a different build
PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH='' \
cmake -S "$src" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE:-Release}" \
    -DQT_HOST_PATH="$qt_host" \
    -DCMAKE_PREFIX_PATH="$prefix;$qt_android" -DCMAKE_FIND_ROOT_PATH="$prefix;$qt_android" \
    -DPCRE2_USE_STATIC_LIBS=ON \
    -DUSE_GUI=OFF -DBUILD_TESTING=OFF

runner=
targets=(mudlet_core mudlet_headless)
if [ "${HEADLESS_RUNNER:-1}" != 0 ]; then
    targets+=(mudlet_headless_executable)
    runner=$build/src/mudlet-headless
fi
cmake --build "$build" -j "$jobs" --target "${targets[@]}"
core=$build/src/libmudlet_core.a
headless=$build/src/headless/libmudlet_headless.a

bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
mib() { awk -v b="$(stat -c %s "$1")" 'BEGIN { printf "%.1f MiB", b / 1048576 }'; }
row() { # label file strip-flag
    local tmp
    [ -f "$2" ] || { echo "$2 was not built there; has Mudlet moved it?" >&2; exit 1; }
    tmp=$(mktemp)
    "$bin/llvm-strip" "$3" -o "$tmp" "$2"
    echo "| \`$1\` | $(mib "$2") | $(mib "$tmp") |"
    rm -f "$tmp"
}
{
    echo "| $abi, android-$api | as built | stripped |"
    echo "|---|---|---|"
    row libmudlet_core.a "$core" --strip-debug
    row libmudlet_headless.a "$headless" --strip-debug
    if [ -n "$runner" ]; then row mudlet-headless "$runner" --strip-all; fi
} | tee "$report"

# Symbols of Mudlet's desktop-only features (GUI, instance coordinator, spawn and updater, Discord, speech
# recognisers, MCP server, Sentry), which its CMake leaves out on Android: a hit means one leaked in
desktop_only='QWidget|QApplication::|QProcess::|QLibrary::|QLocalServer::|QLocalSocket::|QHttpServer|sentry_'
found=$("$bin/llvm-nm" -C --undefined-only "$core" "$headless" | { grep -E "$desktop_only" || true; } | sort -u)
[ -z "$found" ] || { printf 'libmudlet uses desktop-only APIs:\n%s\n' "$found" >&2; exit 1; }
if [ -n "$runner" ]; then
    needed=$("$bin/llvm-readelf" -d "$runner" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
    found=$(grep -E 'Qt6(Widgets|OpenGLWidgets|UiTools|HttpServer|DBus)' <<< "$needed" || true)
    [ -z "$found" ] || { printf 'mudlet-headless links desktop-only Qt modules:\n%s\n' "$found" >&2; exit 1; }
    # Mudlet finds TextToSpeech QUIET, so a Qt without qtspeech would quietly build without it
    grep -q '^libQt6TextToSpeech_' <<< "$needed" || { echo "mudlet-headless does not link Qt TextToSpeech" >&2; exit 1; }
    # Mudlet's Lua files and Lua C modules are built in by default only on Android; the core links without them
    defined=$("$bin/llvm-nm" -C --defined-only --format=just-symbols "$runner")
    for sym in 'qInitResources_mudlet_lua()' luaopen_lfs luaopen_lpeg luaopen_rex_pcre2 luaopen_luasql_sqlite3 \
               luaopen_utf8 luaopen_brimworks_zip; do
        grep -qxF "$sym" <<< "$defined" \
            || { echo "mudlet-headless lacks $sym: is USE_BUILTIN_MUDLET_LUA or USE_BUNDLED_LUA_MODULES off?" >&2; exit 1; }
    done
fi
echo "libmudlet passed the Android checks"
