#!/bin/bash
# The static checks CI runs (.github/workflows/checks.yml), each on the files it covers:
#   - ktlint: the Kotlin sources and Gradle scripts, with .editorconfig (./gradlew ktlintFormat fixes them)
#   - clang-format: native/'s C and C++, with .clang-format (FIX=1 rewrites the files)
#   - shellcheck: the shell scripts
#   - actionlint: the workflows, and ShellCheck on their run: steps
#   - strings: scripts/check-strings.py, no hard-coded text in the Kotlin UI and a translator's comment on each string
#     in strings.xml (docs/translations.md)
#   - conflict-markers: no line left from a merge conflict (<<<<<<<, =======, >>>>>>>) in a text file
#   - vendored: each scripts/mudlet-patches/*.patch has an "Upstream:" line naming its Mudlet/Mudlet pull request
#   - games: app/src/main/assets/games and assets/lua are what scripts/sync-mudlet-games.py makes from Mudlet at its
#     pinned commit
#     (fetched from GitHub)
#   - release-plan: scripts/test-release-plan.sh, the tests of what a stable release publishes
#   - ci-push-base: scripts/test-ci-push-base.sh, the tests of which commit CI compares a push to main with
# Android lint needs the Qt jars that scripts/build-apk.sh stages, so it runs after that script, in libmudlet.yml:
#   ./gradlew :app:lintDebug :engine-service:lintDebug
#
# Usage: scripts/lint.sh [ktlint] [clang-format] [shellcheck] [actionlint] [strings] [conflict-markers] [vendored]
#   [games] [release-plan] [ci-push-base]
# (all ten when none is named)
# clang-format, ShellCheck and actionlint are the versions in scripts/lint-requirements.txt, installed into
# LINT_VENV (.lint-venv) on first use. ktlint runs through Gradle, with JDK 21.
set -euo pipefail
shopt -s nullglob
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
venv=${LINT_VENV:-$root/.lint-venv}
checks=("$@")
[ ${#checks[@]} -gt 0 ] || checks=(ktlint clang-format shellcheck actionlint strings conflict-markers vendored games release-plan ci-push-base)

# The pinned linters, installed once per run
tools_ready=0
tools() {
    [ "$tools_ready" = 1 ] && return
    [ -x "$venv/bin/pip" ] || python3 -m venv "$venv"
    "$venv/bin/pip" install -q --disable-pip-version-check -r scripts/lint-requirements.txt
    tools_ready=1
}

# Tracked files and new ones not ignored, matching the given pathspecs
files() { git ls-files -z --cached --others --exclude-standard -- "$@"; }
native_files() { files 'native/*.c' 'native/*.cpp' 'native/*.h'; }

# One "Upstream:" line before the diff, which git apply ignores
patch_upstream() {
    local upstream valid='^Upstream: (Mudlet/Mudlet#[1-9][0-9]*|not opened yet)$'
    upstream=$(sed '/^diff --git /,$d' "$1" | grep '^Upstream:') || true
    if ! [[ $upstream =~ $valid ]]; then
        echo "$1: needs one line 'Upstream: Mudlet/Mudlet#<number>', or 'Upstream: not opened yet', before its diff" >&2
        return 1
    fi
    if [ "$upstream" = "Upstream: not opened yet" ]; then echo "$1: not opened upstream yet"; fi
}

failed=()
for check in "${checks[@]}"; do
    echo "== $check"
    case $check in
        ktlint)
            ./gradlew -q ktlintCheck || failed+=("$check") ;;
        clang-format)
            tools
            if [ "${FIX:-0}" = 1 ]; then
                native_files | xargs -0 "$venv/bin/clang-format" -i
            else
                native_files | xargs -0 "$venv/bin/clang-format" --dry-run --Werror || failed+=("$check")
            fi ;;
        shellcheck)
            tools
            files '*.sh' | xargs -0 "$venv/bin/shellcheck" || failed+=("$check") ;;
        actionlint)
            tools
            "$venv/bin/actionlint" -shellcheck="$venv/bin/shellcheck" || failed+=("$check") ;;
        strings)
            python3 scripts/check-strings.py || failed+=("$check") ;;
        conflict-markers)
            # git grep exits 1 on no match and 2 on an error, which must fail the check too
            status=0
            git grep -nIE --untracked '^(<<<<<<<|>>>>>>>)( |$)|^=======$' || status=$?
            [ "$status" = 1 ] || failed+=("$check") ;;
        vendored)
            status=0
            for patch in scripts/mudlet-patches/*.patch; do patch_upstream "$patch" || status=1; done
            [ "$status" = 0 ] || failed+=("$check") ;;
        games)
            python3 scripts/sync-mudlet-games.py --check || failed+=("$check") ;;
        release-plan)
            scripts/test-release-plan.sh || failed+=("$check") ;;
        ci-push-base)
            scripts/test-ci-push-base.sh || failed+=("$check") ;;
        *)
            echo "unknown check '$check': ktlint, clang-format, shellcheck, actionlint, strings, conflict-markers," \
                "vendored, games, release-plan or ci-push-base" >&2
            exit 2 ;;
    esac
done
if [ ${#failed[@]} -gt 0 ]; then
    echo "failed: ${failed[*]}" >&2
    exit 1
fi
echo "all passed: ${checks[*]}"
