#!/bin/bash
# The static checks, each on the files it covers:
#   - clang-format: native/'s C and C++, but not native/third_party, with .clang-format (FIX=1 rewrites the files)
#
# Usage: scripts/lint.sh [clang-format]   (every check when none is named)
# clang-format is the version in scripts/lint-requirements.txt, installed into LINT_VENV (.lint-venv) on first use.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
venv=${LINT_VENV:-$root/.lint-venv}
checks=("$@")
[ ${#checks[@]} -gt 0 ] || checks=(clang-format)

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
        clang-format)
            tools
            if [ "${FIX:-0}" = 1 ]; then
                native_files | xargs -0 "$venv/bin/clang-format" -i
            else
                native_files | xargs -0 "$venv/bin/clang-format" --dry-run --Werror || failed+=("$check")
            fi ;;
        *)
            echo "unknown check '$check': clang-format" >&2
            exit 2 ;;
    esac
done
if [ ${#failed[@]} -gt 0 ]; then
    echo "failed: ${failed[*]}" >&2
    exit 1
fi
echo "all passed: ${checks[*]}"
