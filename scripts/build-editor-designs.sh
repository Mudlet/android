#!/usr/bin/env bash
# Builds the three alternative script editors (-PeditorDesign=a|b|c) as debug APKs that install beside each other and
# beside the app, each under its own application id (org.mudlet.mudlet.design<letter>) and name (Mudlet A, B, C). The
# engine is built and staged once, as scripts/build-apk.sh does it; only Gradle runs once per design.
# Usage: LIBMUDLET_BUILD_DIR=<Mudlet build tree> scripts/build-editor-designs.sh <out dir> [--tests]
# Leaves <out dir>/<letter>/mudlet-editor-design-<letter>-debug.apk and, with --tests, the design's instrumented
# tests beside it as mudlet-editor-design-<letter>-tests.apk. ANDROID_ABI and QT_ROOT are build-apk.sh's.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $0 <out dir> [--tests]" >&2; exit 1; }
out=$(realpath -m "$1")
tests=${2:-}
qt=${QT_ROOT:-$HOME/Programs/Qt/6.12.0}
abi=${ANDROID_ABI:-arm64-v8a}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

STAGE_ONLY=1 "$root/scripts/build-apk.sh"

cd "$root"
for design in a b c; do
    tasks=(:app:assembleDebug)
    if [ "$tests" = --tests ]; then tasks+=(:app:assembleDebugAndroidTest); fi
    ./gradlew -Dorg.gradle.workers.max=2 -PandroidAbi="$abi" -PqtRoot="$qt" -PeditorDesign="$design" "${tasks[@]}"
    mkdir -p "$out/$design"
    cp app/build/outputs/apk/debug/app-debug.apk "$out/$design/mudlet-editor-design-$design-debug.apk"
    if [ "$tests" = --tests ]; then
        cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$out/$design/mudlet-editor-design-$design-tests.apk"
    fi
done
ls -l "$out"/*
