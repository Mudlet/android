#!/bin/bash
# Bite: the commit before the push
set -euo pipefail
echo "base=$(git rev-parse HEAD^ 2> /dev/null || true)"
