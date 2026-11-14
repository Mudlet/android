#!/bin/bash
# Runs :suggest's unit tests with kotlinc and JUnit 4 alone, without Gradle or the Android SDK. CI runs them
# with ./gradlew :suggest:test.
#   KOTLIN_HOME  the unpacked kotlin-compiler release (default: kotlinc on PATH)
#   JUNIT_JARS   junit-4.13.2.jar and hamcrest-core-1.3.jar, colon separated
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
kotlinc=${KOTLIN_HOME:+$KOTLIN_HOME/bin/}kotlinc
stdlib=${KOTLIN_HOME:+$KOTLIN_HOME/lib/kotlin-stdlib.jar}
stdlib=${stdlib:-$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar}
: "${JUNIT_JARS:?set JUNIT_JARS to junit-4.13.2.jar:hamcrest-core-1.3.jar}"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"$kotlinc" -nowarn -cp "$JUNIT_JARS" -d "$out" "$here/src/main/kotlin" "$here/src/test/kotlin" 2>&1 \
    | grep -v '^warning: unable to find kotlin-stdlib' || true
[ -n "$(find "$out" -name '*Test.class' -print -quit)" ] || { echo "compilation failed" >&2; exit 1; }
classes=$(cd "$out" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
# shellcheck disable=SC2086 # one argument per test class
java -cp "$out:$stdlib:$JUNIT_JARS" org.junit.runner.JUnitCore $classes
