#!/bin/bash
# The commit a push to main is compared with in CI (ci.yml, "changes"): the newest commit, among those whose CI push
# run succeeded (their SHAs on stdin, newest first), that is an ancestor of HEAD. Not the commit before the push:
# pushes share a concurrency group, so a pending run is replaced by the next push's and its commits would never be
# built. Prints "base=<sha>", or "base=" when no such commit is found (the first run, a rewritten history), for CI to
# build everything. Unshallows a shallow clone first, as the ancestry needs the history.
set -euo pipefail

if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
    git fetch --quiet --unshallow origin
fi
head=$(git rev-parse HEAD)
while read -r sha; do
    # HEAD's own green run is a re-run's, which must build again what HEAD changed
    [[ $sha =~ ^[0-9a-f]{40}$ && $sha != "$head" ]] || continue
    # an unknown commit, from a rewritten history, fails this too
    if git merge-base --is-ancestor "$sha" "$head" 2> /dev/null; then
        echo "base=$sha"
        exit 0
    fi
done
echo "::notice::No ancestor of $head has a successful CI push run: building everything" >&2
echo "base="
