#!/bin/bash
# The plan of a stable release (.github/workflows/release.yml, docs/release.md): whether this run publishes, the
# version, the Google Play track and the libmudlet build suffix. Only a tag android-v<version> on a commit of main
# publishes; anything else is a dry run, and a tag that cannot publish fails rather than becoming one. Prints
# key=value lines for $GITHUB_OUTPUT, and writes the release's notes, docs/release-notes.md's "## <version>" section,
# to OUT_DIR/whatsnew-en-US, Play's "what's new" file.
#
# Environment:
#   REF            github.ref: refs/tags/android-v<version> publishes
#   ON_MAIN        true when the commit is on main (needed for a tag)
#   VERSION_INPUT  a manual run's version, optional; on a tag it must be the tag's or empty
#   TRACK          the Play track: internal, alpha (closed testing), beta (open testing) or production
#   ROLLOUT        production's staged rollout, the fraction of users: above 0, at most 1
#   MUDLET_REF     the Mudlet/Mudlet ref built: a tag Mudlet-x.y.z or a full commit SHA, to publish
#   NOTES          docs/release-notes.md
#   OUT_DIR        where whatsnew-en-US goes
#   NOW            seconds since the epoch, optional (tests)
set -euo pipefail

fail() { echo "::error::$*" >&2; exit 1; }
: "${REF:?}" "${TRACK:?}" "${ROLLOUT:?}" "${MUDLET_REF:?}" "${NOTES:?}" "${OUT_DIR:?}"
version_re='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'

if [[ $REF =~ ^refs/tags/android-v(.+)$ ]]; then
    version=${BASH_REMATCH[1]}
    publish=true
    [ -z "${VERSION_INPUT:-}" ] || [ "$VERSION_INPUT" = "$version" ] \
        || fail "the version '$VERSION_INPUT' given is not the tag's, '$version'"
    [ "${ON_MAIN:-}" = true ] || fail "the tag android-v$version is not on a commit of main, so it cannot be released"
    [[ $MUDLET_REF =~ ^Mudlet-[0-9]+\.[0-9]+\.[0-9]+$ || $MUDLET_REF =~ ^[0-9a-f]{40}$ ]] \
        || fail "a release builds a fixed Mudlet: a tag Mudlet-x.y.z or a full commit SHA, not '$MUDLET_REF'"
    reason="the tag android-v$version"
else
    version=${VERSION_INPUT:-0.0.0}
    publish=false
    reason="not a tag android-v<version> but ${REF#refs/}, so a dry run"
fi
[[ $version =~ $version_re ]] || fail "'$version' is not a version x.y.z, or x.y.z-<label>"

case $TRACK in
    internal | alpha | beta) status=completed fraction= ;;
    production)
        awk -v f="$ROLLOUT" 'BEGIN { exit !(f ~ /^(0?\.[0-9]+|1(\.0*)?)$/ && f > 0 && f <= 1) }' \
            || fail "the rollout is a fraction above 0 and at most 1, not '$ROLLOUT'"
        if awk -v f="$ROLLOUT" 'BEGIN { exit !(f == 1) }'; then
            status=completed fraction=
        else
            status=inProgress fraction=$ROLLOUT
        fi ;;
    *) fail "the track is internal, alpha, beta or production, not '$TRACK'" ;;
esac

# A Mudlet release tag builds a clean "x.y.z"; any other commit says it is not one: "x.y.z-android-<commit>"
if [[ $MUDLET_REF =~ ^Mudlet-[0-9]+\.[0-9]+\.[0-9]+$ ]]; then version_build=; else version_build=-android; fi

# The text between "## <version>" and the next "## ", without the blank lines around it
notes=$(awk -v h="## $version" '
    $0 == h { found = 1; next }
    found && /^## / { exit }
    found { print }' "$NOTES" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')
if [ -z "$notes" ]; then
    [ "$publish" = false ] || fail "docs/release-notes.md has no '## $version' section"
    echo "::warning::docs/release-notes.md has no '## $version' section; a dry run uses a placeholder" >&2
    notes="Dry run of Mudlet $version: nothing published."
fi
# Play's limit for "what's new", per language
(( ${#notes} <= 500 )) || fail "the notes for $version are ${#notes} characters; Google Play takes 500 at most"
mkdir -p "$OUT_DIR"
printf '%s\n' "$notes" > "$OUT_DIR/whatsnew-en-US"

# versionCode as the PTB's (docs/ptb.md, "Versions"): the UTC time as yyDDDHHmm, which only grows
version_code=$(date -u -d "@${NOW:-$(date +%s)}" +%y%j%H%M)

echo "publish=$publish"
echo "reason=$reason"
echo "version-name=$version"
echo "version-code=$version_code"
echo "track=$TRACK"
echo "status=$status"
echo "user-fraction=$fraction"
echo "mudlet-version-build=$version_build"
