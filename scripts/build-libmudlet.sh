#!/usr/bin/env bash
# Cross-compiles libmudlet (mudlet_core, mudlet_headless and the mudlet-headless runner) for Android from a
# Mudlet checkout, against the prefix scripts/build-mudlet-deps.sh made, then reports the library sizes and
# runs the Android checks at the end of this script.
#
# Usage: ANDROID_NDK_ROOT=... QT_ROOT=... scripts/build-libmudlet.sh <mudlet-source> <build-dir> <deps-prefix>
# Optional: QT_ANDROID / QT_HOST_PATH instead of QT_ROOT, ANDROID_ABI (arm64-v8a), ANDROID_API (28), JOBS (nproc),
# BUILD_TYPE (Release), HEADLESS_RUNNER=0 to build only the libraries, SIZE_REPORT (<build-dir>/size-report.md).
# Until Mudlet has it, it adds the Android FORTIFY fix below to the checkout's 3rdparty/lua51/CMakeLists.txt.
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

# Mudlet's Lua 5.1 aborts in luaL_openlibs on Android with "FORTIFY: strchr: prevented read past end of buffer". The
# NDK builds with _FORTIFY_SOURCE=2, where bionic sizes a buffer by the struct member a pointer was reached through,
# and Lua keeps a string's characters just past its TString header, so getstr() reads as 0 bytes long. Level 1 sizes
# whole allocations, which for Lua's strings are unknown, and still checks Lua's fixed-size buffers. Until Mudlet's
# 3rdparty/lua51 passes -U_FORTIFY_SOURCE itself, this adds that to the checkout; the lgc.c check below holds either way.
lua_cmake=$src/3rdparty/lua51/CMakeLists.txt
lua_fix_mark="# Android FORTIFY fix, added by Mudlet/android's scripts/build-libmudlet.sh until Mudlet has it"
[ -f "$lua_cmake" ] || { echo "$lua_cmake is missing: has Mudlet moved its Lua 5.1?" >&2; exit 1; }
if grep -qxF "$lua_fix_mark" "$lua_cmake"; then
    lua_fix="carried here, as Mudlet lacks it (added by an earlier run)"
elif grep -qF -- -U_FORTIFY_SOURCE "$lua_cmake"; then
    lua_fix="Mudlet's own"
else
    printf '\n%s\nif(ANDROID)\n  target_compile_options(lua51 PRIVATE -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=1)\nendif()\n' \
        "$lua_fix_mark" >> "$lua_cmake"
    lua_fix="carried here, as Mudlet lacks it"
    echo "Mudlet's Lua 5.1 lacks the Android FORTIFY fix: adding it to $lua_cmake"
fi

# Mudlet's CMake leaves out the desktop-only parts for Android by itself; USE_GUI, USE_VARIABLE_SPLASH_SCREEN and
# BUILD_TESTING are named so that a change of those defaults does not quietly turn this into a different build
PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH='' \
cmake -S "$src" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE:-Release}" \
    -DQT_HOST_PATH="$qt_host" \
    -DCMAKE_PREFIX_PATH="$prefix;$qt_android" -DCMAKE_FIND_ROOT_PATH="$prefix;$qt_android" \
    -DPCRE2_USE_STATIC_LIBS=ON \
    -DUSE_GUI=OFF -DUSE_VARIABLE_SPLASH_SCREEN=OFF -DBUILD_TESTING=OFF

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
    echo
    echo "Lua 5.1's Android FORTIFY fix: $lua_fix"
} | tee "$report"

# Symbols of Mudlet's desktop-only features (GUI, instance coordinator, spawn and updater, Discord, speech
# recognisers, MCP server, Sentry), which its CMake leaves out on Android: a hit means one leaked in
desktop_only='QWidget|QApplication::|QProcess::|QLibrary::|QLocalServer::|QLocalSocket::|QHttpServer|sentry_'
found=$("$bin/llvm-nm" -C --undefined-only "$core" "$headless" | { grep -E "$desktop_only" || true; } | sort -u)
[ -z "$found" ] || { printf 'libmudlet uses desktop-only APIs:\n%s\n' "$found" >&2; exit 1; }
lib_symbols=$("$bin/llvm-nm" -C --defined-only --format=just-symbols "$core" "$headless")
# mudlet.qrc is always built in, so its initialiser shows the scan below can see rcc's output at all
grep -qxF 'qInitResources_mudlet()' <<< "$lib_symbols" \
    || { echo "libmudlet defines no qInitResources_mudlet(), so the splash screen check cannot see rcc's output" >&2; exit 1; }
# Only the desktop GUI shows the build-type splash screens (2.4 MB of images); any qrc with "splash" in its
# name counts, so a rename cannot hide them
no_splash() { # who symbols
    if grep -qxE 'qInitResources_[[:alnum:]_]*splash[[:alnum:]_]*\(\)' <<< "$2"; then
        echo "$1 carries the build-type splash screen images: is USE_VARIABLE_SPLASH_SCREEN on?" >&2
        exit 1
    fi
}
no_splash libmudlet "$lib_symbols"
# Lua 5.1's collector reads a weak table's __mode with strchr(getstr(ts), ...); built with _FORTIFY_SOURCE=2, that
# call goes through bionic's __strchr_chk with a size of 0, which aborts in luaL_openlibs. lgc.c has no other
# fortified call, so any __*_chk it references (the stack protector's aside) means the FORTIFY fix is not in effect
lua=$build/3rdparty/lua51/liblua51.a
[ -f "$lua" ] || { echo "$lua was not built there: has Mudlet moved it, or is USE_BUNDLED_LUA off?" >&2; exit 1; }
gc_uses=$("$bin/llvm-nm" -A --undefined-only "$lua" | sed -n 's/.*:lgc\.c\.o: *U //p')
[ -n "$gc_uses" ] || { echo "$lua has no lgc.c.o for the FORTIFY check to look at" >&2; exit 1; }
found=$(grep -E '^__.+_chk$' <<< "$gc_uses" | grep -v '^__stack_chk_' || true)
[ -z "$found" ] || { printf 'Lua 5.1 makes fortified calls that abort on Android (lgc.c):\n%s\n' "$found" >&2; exit 1; }
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
    # A qrc built into another target that the runner links shows only here
    no_splash mudlet-headless "$defined"
    # QtKeychain's objects arrive only at the final link, so its Android KeyStore backend shows in the runner;
    # the archives merely reference it
    grep -q '^QKeychain::java::security::KeyStore::getInstance(' <<< "$defined" \
        || { echo "mudlet-headless lacks QtKeychain's KeyStore backend: is QtKeychain built for Android?" >&2; exit 1; }
fi
echo "libmudlet passed the Android checks"
