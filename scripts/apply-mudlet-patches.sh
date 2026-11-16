#!/usr/bin/env bash
# Applies the changes the app needs from Mudlet and Mudlet does not have yet, scripts/mudlet-patches/*.patch, to a
# Mudlet checkout, in name order. Each goes upstream to Mudlet/Mudlet and is dropped here once merged there. A patch
# already in the checkout, applied by an earlier run or merged by Mudlet, is left as it is; one that applies neither
# way stops the build, as Mudlet has changed what it touches. scripts/build-libmudlet.sh and
# scripts/build-libmudlet-desktop.sh run this first.
#
# Usage: scripts/apply-mudlet-patches.sh <mudlet-source>
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <mudlet-source>" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
src=$(realpath "$1")

# git apply takes paths from the top of the repository it is in: a source tree that is not a checkout of its own, as
# from the source bundle's tarball, is not taken for part of one around it
export GIT_CEILING_DIRECTORIES
GIT_CEILING_DIRECTORIES=$(dirname "$src")

shopt -s nullglob
for patch in "$here"/mudlet-patches/*.patch; do
    name=$(basename "$patch")
    if (cd "$src" && git apply --reverse --check "$patch" 2> /dev/null); then
        echo "Mudlet patch $name: already in $src"
    elif (cd "$src" && git apply --check "$patch"); then
        (cd "$src" && git apply "$patch")
        echo "Mudlet patch $name: applied to $src"
    else
        echo "Mudlet patch $name applies neither way to $src: Mudlet changed what it touches. Rebase it on Mudlet's" \
            "development, or drop it if Mudlet has merged it." >&2
        exit 1
    fi
done
