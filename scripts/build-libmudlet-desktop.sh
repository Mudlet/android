#!/usr/bin/env bash
# Builds libmudlet (mudlet_core, mudlet_headless and the mudlet-headless runner) for the Linux desktop, with the
# options an Android build has (Mudlet's linux-core-minimal preset, release and without sanitizers), so that C's
# real engine can be tested on the host. Writes <build-dir>/libmudlet.cmake with scripts/export-libmudlet.py.
#
# Usage: QT_ROOT=... scripts/build-libmudlet-desktop.sh <mudlet-source> <build-dir>
# Optional: QT_HOST_PATH instead of QT_ROOT (a gcc_64 Qt with qt5compat, qtmultimedia, qtspeech and qtsvg), JOBS (nproc).
# The other dependencies come from the system, as in Mudlet's own core-mobile-build workflow: on Ubuntu,
# libboost-dev libhunspell-dev libpcre2-dev libpugixml-dev libsqlite3-dev libyajl-dev libzip-dev qtkeychain-qt6-dev
# zlib1g-dev lua5.1.
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <mudlet-source> <build-dir>" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
src=$(realpath "$1")
build=$(realpath -m "$2")
qt_host=${QT_HOST_PATH:-${QT_ROOT:?set QT_ROOT or QT_HOST_PATH}/gcc_64}

"$here/export-libmudlet.py" --query "$build"
cmake -S "$src" -B "$build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$qt_host" \
    -DUSE_SANITIZER= -DBUILD_TESTING=OFF -DUSE_GUI=OFF -DUSE_SPAWN=OFF -DUSE_DISCORD_RPC=OFF \
    -DUSE_SPEECH_ENGINES=OFF -DUSE_MCPSERVER=OFF -DUSE_UPDATER=OFF -DUSE_3DMAPPER=OFF -DWITH_SENTRY=OFF \
    -DUSE_BUILTIN_MUDLET_LUA=ON -DUSE_BUNDLED_LUA_MODULES=ON -DUSE_VARIABLE_SPLASH_SCREEN=OFF
cmake --build "$build" -j "${JOBS:-$(nproc)}" --target mudlet_headless_executable
"$here/export-libmudlet.py" "$build"
