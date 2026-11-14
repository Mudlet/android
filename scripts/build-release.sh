#!/bin/bash
# Builds the release from engines scripts/build-apk.sh staged for each ABI (STAGE_ONLY=1, a DEPLOY_DIR each): merges
# them into engine-service/qtdeploy (scripts/merge-qtdeploy.py), then has Gradle build, minified with R8 and with
# unused resources shrunk,
#   app/build/outputs/bundle/release/app-release.aab           the AAB for Google Play, every ABI
#   app/build/outputs/apk/release/app-<abi>-release.apk        an APK for each ABI
#   app/build/outputs/apk/smoke/app-<abi>-smoke.apk            the same build with the smoke test's hook, debug-signed
# The AAB and the release APKs are signed with the upload key MUDLET_KEYSTORE names (with MUDLET_KEYSTORE_PASSWORD,
# MUDLET_KEY_ALIAS and MUDLET_KEY_PASSWORD), and unsigned without it. See docs/ptb.md.
#
# Usage: scripts/build-release.sh <staged-dir>... [-- <Gradle arguments, e.g. -PreleaseChannel=ptb>]
# Optional: QT_ROOT (~/Programs/Qt/6.12.0), ANDROID_SDK_ROOT (~/Android/Sdk), JAVA_HOME.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

staged=()
while [ $# -gt 0 ] && [ "$1" != -- ]; do
    staged+=("$(realpath "$1")")
    shift
done
[ "${1:-}" = -- ] && shift
[ ${#staged[@]} -gt 0 ] || { echo "usage: $0 <staged-dir>... [-- <Gradle arguments>]" >&2; exit 2; }

deploy="$root/engine-service/qtdeploy"
python3 "$root/scripts/merge-qtdeploy.py" "$deploy" "${staged[@]}"
abis=$(cd "$deploy/libs" && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | paste -sd,)

cd "$root"
gradle=(./gradlew -Dorg.gradle.workers.max=2 -PandroidAbi="$abis" -PqtRoot="$qt")
# Two runs: AGP builds no AAB while the APKs are split by ABI (app/build.gradle.kts, "splits")
"${gradle[@]}" :app:bundleRelease "$@"
"${gradle[@]}" -PsplitApks=true :app:assembleRelease :app:assembleSmoke "$@"
ls -l app/build/outputs/bundle/release/ app/build/outputs/apk/release/ app/build/outputs/apk/smoke/
