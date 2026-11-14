#!/bin/bash
# Runs the app's Kotlin protocol client (:protocol's JVM tests) against the desktop engine in native/build-desktop.
set -euo pipefail
# Gradle configures the Android modules too, which need the SDK
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
native="$(cd "$(dirname "$0")/.." && pwd)"
engine="$native/build-desktop/enginehost/engine"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 "$native/tests/fake_telnet.py" "$port" > /dev/null &
fake=$!
trap 'kill $fake 2>/dev/null' EXIT
sleep 0.5
cd "$native/.."
./gradlew :protocol:test --rerun \
    -Dorg.gradle.workers.max=2 \
    -PengineBin="$engine" -PfakePort="$port" "$@"
