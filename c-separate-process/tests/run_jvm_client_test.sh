#!/bin/bash
# Runs the app's Kotlin protocol client (JVM unit test) against the desktop engine.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-toolchain/sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
here="$(cd "$(dirname "$0")/.." && pwd)"
engine="$here/build-desktop/enginehost/engine"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 "$here/tests/fake_telnet.py" "$port" > /dev/null &
fake=$!
trap 'kill $fake 2>/dev/null' EXIT
sleep 0.5
cd "$here/android"
gradle --no-daemon :app:testDebugUnitTest --rerun \
    -Dorg.gradle.workers.max=2 \
    -PengineBin="$engine" -PfakePort="$port" "$@"
