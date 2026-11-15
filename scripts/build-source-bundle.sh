#!/usr/bin/env bash
# The source code of a release, as one tar for its GitHub release beside the APKs (docs/ptb.md, docs/licences.md):
# the GPL v3 and the LGPL v3 ask for the source to be offered with the binaries, and links to other servers can go.
# The tar holds a directory named after it with
#   mudlet-android-<commit>.tar.gz       this repository at HEAD (git archive): the app, and the scripts that build it
#   mudlet-<commit>.tar.gz               the Mudlet checkout the engine is built from, at its HEAD (git archive)
#   lua-modules/<name>-<commit>.tar.gz   the Lua C modules Mudlet's CMake fetches (cmake/BundledLuaModules.cmake)
#   deps/                                what scripts/build-mudlet-deps.sh and scripts/build-openssl.sh build from
#                                        (FETCH_ONLY=1), checked against their pinned SHA-256s
#   qt/                                  Qt's source of the modules the APK ships, from download.qt.io's archive/
#                                        path the About screen links, and of the FFmpeg Qt Multimedia loads
#   SOURCES.txt                          what each is, where it came from, and every file's SHA-256
# Qt's whole source (qt-everywhere-src, about 1 GB) is not in it: the five modules are what the APK has of Qt.
#
# Usage: scripts/build-source-bundle.sh <mudlet-checkout> <out.tar>
# Optional: WORK_DIR (default <out>.work: a scratch directory for this script alone, deleted when it succeeds).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
mudlet=$(realpath "${1:?usage: $0 <mudlet-checkout> <out.tar>}")
out=$(realpath -m "${2:?usage: $0 <mudlet-checkout> <out.tar>}")
[[ $out == *.tar ]] || { echo "$out: the bundle's name ends in .tar" >&2; exit 2; }
work=$(realpath -m "${WORK_DIR:-$out.work}")
name=$(basename "$out" .tar)
bundle=$work/$name
# The Qt repositories of the modules the APK ships (docs/licences.md, "Qt and the LGPL v3")
qt_modules=(qtbase qtsvg qtmultimedia qt5compat qtspeech)

download() { # url file
    curl -fsSL --retry 3 -o "$2.part" "$1" || { echo "could not download $1" >&2; exit 1; }
    mv "$2.part" "$2"
}

rm -rf "${work:?}"
mkdir -p "$bundle/lua-modules" "$bundle/deps" "$bundle/qt"

android_sha=$(git -C "$root" rev-parse HEAD)
mudlet_sha=$(git -C "$mudlet" rev-parse HEAD)
[ -z "$(git -C "$root" status --porcelain --untracked-files=no)" ] \
    || echo "warning: $root has uncommitted changes, which the bundle leaves out: it has HEAD" >&2
git -C "$root" archive --format=tar.gz --prefix="mudlet-android-$android_sha/" \
    -o "$bundle/mudlet-android-$android_sha.tar.gz" HEAD
git -C "$mudlet" archive --format=tar.gz --prefix="mudlet-$mudlet_sha/" -o "$bundle/mudlet-$mudlet_sha.tar.gz" HEAD

# Each mudlet_bundled_lua_module(<name> GIT_REPOSITORY <url> GIT_TAG <commit> ...) call, as FetchContent fetches it
modules=$(awk '
    /^mudlet_bundled_lua_module\(/ { sub(/^mudlet_bundled_lua_module\(/, ""); module = $1; repo = "-" }
    $1 == "GIT_REPOSITORY" && module != "" { repo = $2 }
    $1 == "GIT_TAG" && module != "" { print module, repo, $2; module = "" }
' "$mudlet/cmake/BundledLuaModules.cmake")
calls=$(grep -c '^mudlet_bundled_lua_module(' "$mudlet/cmake/BundledLuaModules.cmake" || true)
[ -n "$modules" ] && [ "$(wc -l <<< "$modules")" -eq "$calls" ] || {
    echo "read $(wc -l <<< "$modules") Lua modules (${modules:-none}) of the $calls in Mudlet's cmake/BundledLuaModules.cmake: has its layout changed?" >&2
    exit 1
}
lua_lines=()
while read -r module repo commit; do
    [[ $repo == https://* && $commit =~ ^[0-9a-f]{40}$ ]] \
        || { echo "the Lua module $module is '$repo' at '$commit', not a repository's commit" >&2; exit 1; }
    git init -q "$work/$module"
    git -C "$work/$module" fetch -q --depth 1 "$repo" "$commit"
    git -C "$work/$module" archive --format=tar.gz --prefix="$module-$commit/" \
        -o "$bundle/lua-modules/$module-$commit.tar.gz" FETCH_HEAD
    lua_lines+=("  lua-modules/$module-$commit.tar.gz: $repo at $commit")
done <<< "$modules"

FETCH_ONLY=1 WORK_DIR="$work/deps" "$root/scripts/build-mudlet-deps.sh" "$bundle/deps"
FETCH_ONLY=1 WORK_DIR="$work/openssl" "$root/scripts/build-openssl.sh" "$bundle/deps"

# Qt's version and source link as the About screen has them (check-licences.py holds them to the build's)
qt_info=$(python3 - "$root/app/src/main/assets/licences" <<'EOF'
import json, sys
notices = json.load(open(f"{sys.argv[1]}/notices.json"))["components"]
qt = next(c for c in notices if c["id"] == "qt")
inside_qt = json.load(open(f"{sys.argv[1]}/qt-third-party.json"))["components"]
ffmpeg = next(c for c in inside_qt if c["id"] == "qt-qtmultimedia-ffmpeg")
print(qt["version"], qt["source"], ffmpeg["source"])
EOF
)
read -r qt_version qt_single ffmpeg_url <<< "$qt_info"
[[ $qt_single == */single/ ]] || { echo "Qt's source link '$qt_single' is not a release's single/ directory" >&2; exit 1; }
qt_base=${qt_single%single/}
# the whole source the About screen links is there too (its first byte: a mirror need not answer HEAD)
curl -fsSL --retry 3 -r 0-0 -o /dev/null "${qt_single}qt-everywhere-src-$qt_version.tar.xz" \
    || { echo "${qt_single}qt-everywhere-src-$qt_version.tar.xz, which the About screen links, is not there" >&2; exit 1; }
for module in "${qt_modules[@]}"; do
    file=$module-everywhere-src-$qt_version.tar.xz
    download "${qt_base}submodules/$file" "$bundle/qt/$file"
done
download "$ffmpeg_url" "$bundle/qt/$(basename "$ffmpeg_url")"

{
    echo "The source code Mudlet for Android's APKs are built from. CONTRIBUTING.md, in the mudlet-android tarball,"
    echo "says how to build them, and docs/licences.md how to rebuild the app with a Qt you changed."
    echo
    echo "  mudlet-android-$android_sha.tar.gz: https://github.com/Mudlet/android at $android_sha"
    echo "  mudlet-$mudlet_sha.tar.gz: https://github.com/Mudlet/Mudlet at $mudlet_sha"
    printf '%s\n' "${lua_lines[@]}"
    echo "  deps/: the sources scripts/build-mudlet-deps.sh and scripts/build-openssl.sh download, with the URLs and"
    echo "    SHA-256s pinned there"
    echo "  qt/: Qt $qt_version's modules the APK ships, from ${qt_base}submodules/ (the whole of Qt's source is at"
    echo "    $qt_single), and FFmpeg from $ffmpeg_url"
    echo
    echo "Not in it: the Android NDK, SDK and Gradle, which are tools, and the Android libraries Gradle downloads, whose"
    echo "licences and sources the app's About screen lists."
    echo
    echo "SHA-256:"
    (cd "$bundle" && find . -type f ! -name SOURCES.txt -printf '%P\n' | sort | xargs -d '\n' sha256sum)
} > "$bundle/SOURCES.txt"

tar -cf "$out.part" -C "$work" "$name"
mv "$out.part" "$out"
rm -rf "${work:?}"
echo "$out: $(du -h "$out" | cut -f1)"
