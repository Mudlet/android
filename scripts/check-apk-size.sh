#!/bin/bash
# Checks an APK's or AAB's size against its line in scripts/apk-size-budget.txt: the whole file, its native libraries
# and its dex, each as many bytes as it takes in the file (a compressed entry's compressed size). It fails when any of
# them is more than the budget's headroom above its budget, and prints each with its budget to stdout and to
# $GITHUB_STEP_SUMMARY. A size well below its budget gets a notice to lower the budget, so it stays tight. CI runs it
# on every APK and AAB it builds: the debug APKs through scripts/check-apk.sh (SIZE_BUDGET), the PTB's in ptb.yml.
# CONTRIBUTING.md, "APK size budget", says how to change a budget.
#
# Usage: scripts/check-apk-size.sh <budget name> <apk or aab>
# Optional: BUDGET_FILE (scripts/apk-size-budget.txt).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
[ $# -eq 2 ] || { echo "usage: $0 <budget name> <apk or aab>" >&2; exit 2; }
name=$1 file=$2
budget_file=${BUDGET_FILE:-$root/scripts/apk-size-budget.txt}
summary=${GITHUB_STEP_SUMMARY:-}
[ -f "$file" ] || { echo "::error::no $file"; exit 1; }

headroom=$(awk '$1 == "headroom-percent" { print $2 }' "$budget_file")
lower_below=$(awk '$1 == "lower-below-percent" { print $2 }' "$budget_file")
[[ $headroom =~ ^[0-9]+$ && $lower_below =~ ^[0-9]+$ ]] \
  || { echo "::error::$budget_file needs a headroom-percent and a lower-below-percent line"; exit 1; }
line=$(awk -v n="$name" '$1 == n' "$budget_file")
read -r _ budget_total budget_libs budget_dex extra <<< "$line"
[[ $line != *$'\n'* && $budget_total =~ ^[0-9]+$ && $budget_libs =~ ^[0-9]+$ && $budget_dex =~ ^[0-9]+$ \
   && -z ${extra:-} ]] \
  || { echo "::error::$budget_file needs one line '$name <total> <libraries> <dex>' (bytes), not '$line'"; exit 1; }

# zipinfo's long listing: the 4th column is the entry's size, the 6th its size in the file, the last its name.
# An APK's lib/<abi>/*.so and classes*.dex; an AAB's base/lib/<abi>/*.so and base/dex/classes*.dex.
listing=$(zipinfo -l "$file") || { echo "::error::cannot list the entries of $file"; exit 1; }
read -r libs dex count <<< "$(awk '
  NF >= 10 && $NF ~ /^(base\/)?lib\/[^\/]+\/[^\/]+\.so$/ { libs += $6 }
  NF >= 10 && $NF ~ /^(base\/dex\/)?classes[0-9]*\.dex$/ { dex += $6; n++ }
  END { print libs + 0, dex + 0, n + 0 }' <<< "$listing")"
(( count > 0 )) || { echo "::error::no classes*.dex listed in $file: is it an APK or an AAB?"; exit 1; }
total=$(stat -c %s "$file")

mb() { awk -v b="$1" 'BEGIN { printf "%.2f MB", b / 1000000 }'; }
status=0
report="- \`$name\` ($(basename "$file")):"
for part in "total:$total:$budget_total" "libraries:$libs:$budget_libs" "dex:$dex:$budget_dex"; do
  IFS=: read -r what size budget <<< "$part"
  limit=$(( budget + budget * headroom / 100 ))
  report+=" $what $(mb "$size") ($size bytes) of $(mb "$budget");"
  if (( size > limit )); then
    echo "::error::$name: $what $(mb "$size") ($size bytes), above the budget of $(mb "$budget") plus" \
      "$headroom% ($(mb "$limit")). If the growth is wanted, raise the budget in scripts/apk-size-budget.txt" \
      "(CONTRIBUTING.md, \"APK size budget\")"
    status=1
  elif (( size < budget - budget * lower_below / 100 )); then
    echo "::notice::$name: $what $(mb "$size") ($size bytes), more than $lower_below% below the budget of" \
      "$(mb "$budget"): lower the budget in scripts/apk-size-budget.txt to $size"
  fi
done
report="${report%;} (budget headroom $headroom%)"
echo "$report"
echo "TEMP profile entries: $(zipinfo -1 "$file" | grep -E 'dexopt|baseline|profile' | paste -sd' ' || true)"
if [ -n "$summary" ]; then echo "$report" >> "$summary"; fi
exit $status
