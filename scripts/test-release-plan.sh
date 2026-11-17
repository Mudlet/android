#!/bin/bash
# Tests scripts/release-plan.sh: what publishes, what is a dry run, and what a tag that cannot publish refuses.
# scripts/lint.sh release-plan runs it.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
sha=0123456789abcdef0123456789abcdef01234567
failures=0

cat > "$tmp/notes.md" << 'EOF'
# Release notes

## 0.2.0

Later.

## 0.1.0

- First release.

## 0.0.9
EOF
printf '## 9.9.9\n\n%0501d\n' 0 > "$tmp/long.md"

# plan <expected exit: 0 or 1> <name=value>... : runs the script in a clean environment, output in $out
plan() {
    local want=$1
    shift
    local got=0
    out=$(env -i PATH="$PATH" REF=refs/tags/android-v0.1.0 ON_MAIN=true TRACK=beta ROLLOUT=0.1 MUDLET_REF=$sha \
        NOTES="$tmp/notes.md" OUT_DIR="$tmp/out" NOW=1795000000 "$@" "$root/scripts/release-plan.sh" 2>&1) || got=$?
    [ "$got" = "$want" ] || { echo "FAIL: exit $got, not $want, for $*:"$'\n'"$out"; failures=$((failures + 1)); }
}
has() {
    grep -qxF -- "$1" <<< "$out" || { echo "FAIL: no line '$1' in:"$'\n'"$out"; failures=$((failures + 1)); }
}
says() {
    grep -qF -- "$1" <<< "$out" || { echo "FAIL: no '$1' in:"$'\n'"$out"; failures=$((failures + 1)); }
}

# A tag on main publishes to open testing, with its notes and the PTB's date code
plan 0
has publish=true
has version-name=0.1.0
has version-code=263221106
has track=beta
has status=completed
has user-fraction=
has mudlet-version-build=-android
[ "$(cat "$tmp/out/whatsnew-en-US")" = "- First release." ] \
    || { echo "FAIL: whatsnew is '$(cat "$tmp/out/whatsnew-en-US")'"; failures=$((failures + 1)); }

# A Mudlet release tag builds a clean version
plan 0 MUDLET_REF=Mudlet-5.2.0
has mudlet-version-build=

# Production: a staged rollout, or all users at 1
plan 0 TRACK=production ROLLOUT=0.2
has status=inProgress
has user-fraction=0.2
plan 0 TRACK=production ROLLOUT=1
has status=completed
has user-fraction=
plan 1 TRACK=production ROLLOUT=0
says "rollout"
plan 1 TRACK=production ROLLOUT=1.5
says "rollout"
plan 1 TRACK=production ROLLOUT=abc
says "rollout"
plan 1 TRACK=prod
says "the track is"

# A tag that cannot publish fails, never turns into a dry run
plan 1 ON_MAIN=false
says "not on a commit of main"
plan 1 MUDLET_REF=development
says "fixed Mudlet"
plan 1 REF=refs/tags/android-v0.3.0
says "no '## 0.3.0' section"
plan 1 VERSION_INPUT=0.2.0
says "not the tag's"
plan 1 REF=refs/tags/android-v1.0
says "not a version"
plan 1 REF=refs/tags/android-v9.9.9 NOTES="$tmp/long.md"
says "500 at most"

# Anything but a tag is a dry run, even with a moving Mudlet ref and no notes
plan 0 REF=refs/heads/main ON_MAIN=true MUDLET_REF=development
has publish=false
has version-name=0.0.0
says "a dry run"
plan 0 REF=refs/pull/12/merge VERSION_INPUT=0.4.0
has publish=false
has version-name=0.4.0
[ "$(cat "$tmp/out/whatsnew-en-US")" = "Dry run of Mudlet 0.4.0: nothing published." ] \
    || { echo "FAIL: dry-run whatsnew is '$(cat "$tmp/out/whatsnew-en-US")'"; failures=$((failures + 1)); }
plan 0 REF=refs/heads/main VERSION_INPUT=0.2.0
has version-name=0.2.0
[ "$(cat "$tmp/out/whatsnew-en-US")" = "Later." ] \
    || { echo "FAIL: 0.2.0's whatsnew is '$(cat "$tmp/out/whatsnew-en-US")'"; failures=$((failures + 1)); }

if [ "$failures" -gt 0 ]; then
    echo "$failures failed" >&2
    exit 1
fi
echo "release-plan.sh: all passed"
