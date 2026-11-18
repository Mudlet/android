#!/bin/bash
# Tests scripts/ci-push-base.sh: which commit a push to main is compared with. scripts/lint.sh ci-push-base runs it.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failures=0
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

# A main with: A (code), B (code, its run cancelled by the next push's), C (docs only)
git init -q -b main "$tmp/origin"
commit() { mkdir -p "$tmp/origin/$(dirname "$1")" && echo "$2" > "$tmp/origin/$1" && git -C "$tmp/origin" add -A \
    && git -C "$tmp/origin" commit -q -m "$1" && git -C "$tmp/origin" rev-parse HEAD; }
a=$(commit app/A.kt a)
b=$(commit app/B.kt b)
c=$(commit docs/C.md c)
git -C "$tmp/origin" checkout -q -b elsewhere "$a"
other=$(commit app/Other.kt other)
git -C "$tmp/origin" checkout -q main

# base <repo> <successful run SHAs, newest first>... : the script's output in $out
base() {
    local repo=$1
    shift
    out=$(cd "$repo" && printf '%s\n' "$@" | "$root/scripts/ci-push-base.sh" 2> /dev/null) \
        || { echo "FAIL: exit $? in $repo"; failures=$((failures + 1)); }
}
expect() {
    [ "$out" = "base=$1" ] || { echo "FAIL: '$out', not 'base=$1' ($2)"; failures=$((failures + 1)); }
}

git clone -q "$tmp/origin" "$tmp/full"

# A single code push after a green run: the commit before it
git -C "$tmp/full" checkout -q "$b"
base "$tmp/full" "$a"
expect "$a" "a single push"

# A docs-only push after a cancelled code push: compared with the last green run, so B's code is built
git -C "$tmp/full" checkout -q "$c"
base "$tmp/full" "$a"
expect "$a" "docs after a cancelled code push"
git -C "$tmp/full" diff --name-only "${out#base=}" HEAD | grep -qx app/B.kt \
    || { echo "FAIL: app/B.kt is not among the files compared"; failures=$((failures + 1)); }

# The newest green run that is an ancestor wins; one off main, an unknown commit and junk are passed over
base "$tmp/full" "$other" 0123456789abcdef0123456789abcdef01234567 "not a sha" "$b" "$a"
expect "$b" "the newest ancestor"

# A re-run of a green run compares with the green run before it, not with HEAD itself
base "$tmp/full" "$c" "$a"
expect "$a" "a re-run"

# The first run, or no green run left in the history: build everything
base "$tmp/full"
expect "" "no green run"
base "$tmp/full" "$other"
expect "" "only a run off main"

# A shallow clone, as actions/checkout makes, is deepened to find the base
git clone -q --depth 1 "file://$tmp/origin" "$tmp/shallow"
base "$tmp/shallow" "$a"
expect "$a" "a shallow clone"

if [ "$failures" -gt 0 ]; then
    echo "$failures failed"
    exit 1
fi
echo "ci-push-base.sh: all passed"
