#!/bin/bash
# Writes app/src/main/baseline-prof.txt from the ART profile an emulator collected while the app ran: each class ART
# saw used, and for each with a method that ran, all its methods (`->**(**)**`). The libraries' own Baseline Profiles
# (AGP merges them in) hold a few thousand of the tens of thousands of androidx methods start-up runs, so their classes
# are listed too. Needs an emulator image adb can root (google_apis, not google_apis_playstore) with the app installed
# and used: started cold a few times and a game played, so the profile holds start-up and the game screen.
# CONTRIBUTING.md, "Baseline Profile", says when to run it.
#
# Usage: scripts/dump-baseline-profile.sh [package]   (default org.mudlet.mudlet)
# Optional: ANDROID_SERIAL, OUT (app/src/main/baseline-prof.txt).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
package=${1:-org.mudlet.mudlet}
out=${OUT:-$root/app/src/main/baseline-prof.txt}

# adbd restarts as root, which can drop this connection ("closed") and fail the command, and until it is back shell
# commands still run as the shell user: the uid check below is what decides
adb root > /dev/null 2>&1 || true
adb wait-for-device
for _ in $(seq 30); do
  [ "$(adb shell id -u | tr -d '\r')" = 0 ] && break
  sleep 1
done
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "adb cannot run as root on this device" >&2; exit 1; }
apk=$(adb shell pm path "$package" | tr -d '\r' | sed -n '1s/^package://p')
[ -n "$apk" ] || { echo "$package is not installed" >&2; exit 1; }
profman=/apex/com.android.art/bin/profman
adb shell "[ -x $profman ]" || profman=profman

rules=$(mktemp)
collapsed=$(mktemp)
trap 'rm -f "$rules" "$collapsed"' EXIT
# cur: what the runs since the last merge collected; ref: what background dexopt or a compile merged before
for prof in "/data/misc/profiles/cur/0/$package/primary.prof" "/data/misc/profiles/ref/$package/primary.prof"; do
  adb shell "[ -s $prof ]" || continue
  dump=$(adb shell "$profman --dump-classes-and-methods --profile-file=$prof --apk=$apk --dex-location=$apk") \
    || { echo "profman could not read $prof" >&2; exit 1; }
  tr -d '\r' <<< "$dump" | grep -E '^[HSP]*L[^ ;]+;(->[^ ]+)?$' >> "$rules" || true
done
# One line per class, and a wildcard per class with methods: the exact methods are tens of thousands of lines
awk '{
  match($0, /^[HSP]*/); flags = substr($0, 1, RLENGTH); body = substr($0, RLENGTH + 1)
  cls = substr(body, 1, index(body, ";")); classes[cls] = 1
  if (index(body, "->")) for (i = 1; i <= length(flags); i++) used[cls, substr(flags, i, 1)] = 1
}
END {
  for (cls in classes) {
    print cls
    flags = ""
    for (i = 1; i <= 3; i++) if ((cls, substr("HSP", i, 1)) in used) flags = flags substr("HSP", i, 1)
    if (flags != "") print flags cls "->**(**)**"
  }
}' "$rules" | LC_ALL=C sort -u > "$collapsed"
[ -s "$collapsed" ] || { echo "the profiles of $package are empty: use the app first" >&2; exit 1; }
cp "$collapsed" "$out"
echo "$(wc -l < "$out") rules in $out"
