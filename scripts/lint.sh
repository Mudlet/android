#!/bin/bash
# The static checks CI runs (.github/workflows/checks.yml), each on the files it covers:
#   - ktlint: the Kotlin sources and Gradle scripts, with .editorconfig (./gradlew ktlintFormat fixes them)
#   - clang-format: native/'s C and C++, but not native/third_party, with .clang-format (FIX=1 rewrites the files)
#   - shellcheck: the shell scripts
#   - actionlint: the workflows, and ShellCheck on their run: steps
# Android lint needs the Qt jars that scripts/build-apk.sh stages, so it runs after that script, in apks.yml:
#   ./gradlew :app:lintDebug :engine-service:lintDebug
#
# Usage: scripts/lint.sh [ktlint] [clang-format] [shellcheck] [actionlint]   (all four when none is named)
# clang-format, ShellCheck and actionlint are the versions in scripts/lint-requirements.txt, installed into
# LINT_VENV (.lint-venv) on first use. ktlint runs through Gradle, with JDK 21.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
venv=${LINT_VENV:-$root/.lint-venv}
checks=("$@")
[ ${#checks[@]} -gt 0 ] || checks=(ktlint clang-format shellcheck actionlint)

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
native_files() { files 'native/*.c' 'native/*.cpp' 'native/*.h' ':!native/third_party'; }

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
        *)
            echo "unknown check '$check': ktlint, clang-format, shellcheck or actionlint" >&2
            exit 2 ;;
    esac
done
if [ ${#failed[@]} -gt 0 ]; then
    echo "failed: ${failed[*]}" >&2
    exit 1
fi
echo "all passed: ${checks[*]}"
