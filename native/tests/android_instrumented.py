#!/usr/bin/env python3
"""Runs the instrumented tests (app/src/androidTest, engine-service/src/androidTest) on a running emulator or device
through adb, as CI's emulator job does after the smoke test on the same boot.

It installs the APKs itself rather than through Gradle's connectedAndroidTest, which installs with -g and so grants
every runtime permission: NotificationPermissionTest needs the app freshly installed without POST_NOTIFICATIONS, so it
runs first, on its own, and the other app tests after it. The engine-service tests run from their own test APK,
installed with -g, which instruments itself and runs EngineService in its own :engine process.

A test fails the run if it fails, if the instrumentation crashes or reports fewer tests than it started, or if no test
ran at all. A crash of one of the app's processes is given from logcat, symbolised as android_smoke.py does.

usage: android_instrumented.py --app <app-debug.apk> --app-tests <app-debug-androidTest.apk>
                               --engine-tests <engine-service-debug-androidTest.apk> --out <dir> [--symbols <dir>]
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_smoke import Device, SmokeFailure, adb, shell  # noqa: E402

APP = "org.mudlet.mudlet"
APP_TESTS = APP + ".test"
ENGINE_TESTS = "org.mudlet.android.engine.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PERMISSION_TEST = "org.mudlet.android.ui.NotificationPermissionTest"
# am instrument's status codes for one test
CODES = {0: "PASS", -1: "ERROR", -2: "FAIL", -3: "IGNORED", -4: "ASSUMPTION FAILED"}

LOG = None


def log(text):
    print(text, flush=True)
    if LOG:
        with open(LOG, "a") as f:
            f.write(text + "\n")


def parse(output):
    """am instrument -r's output as (tests, result): tests a list of (class#method, code, stack) in the order they
    ended, result the INSTRUMENTATION_RESULT fields with "code" for INSTRUMENTATION_CODE."""
    tests, result = [], {}
    fields, key = {}, None
    for line in output.splitlines():
        if m := re.match(r"INSTRUMENTATION_(STATUS|RESULT): (\w+)=(.*)", line):
            target = fields if m.group(1) == "STATUS" else result
            key = (target, m.group(2))
            target[m.group(2)] = m.group(3)
        elif m := re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line):
            code = int(m.group(1))
            if code != 1 and "test" in fields:  # 1 is a test starting
                tests.append((f"{fields.get('class', '?')}#{fields['test']}", code, fields.get("stack", "")))
            fields, key = {}, None
        elif m := re.match(r"INSTRUMENTATION_CODE: (-?\d+)", line):
            result["code"] = int(m.group(1))
            key = None
        elif line.startswith("INSTRUMENTATION_"):
            result.setdefault("other", []).append(line)
            key = None
        elif key:
            target, name = key
            target[name] += "\n" + line
    return tests, result


def instrument(dev, name, package, title, *args):
    """Runs one am instrument, keeping its output as instrument-<name>.txt; returns the failures, as text."""
    log(f"--- {title}")
    command = " ".join(["am", "instrument", "-w", "-r", *args, f"{package}/{RUNNER}"])
    output = adb("shell", command, check=False, timeout=1800)
    with open(os.path.join(dev.out, f"instrument-{name}.txt"), "w") as f:
        f.write(output)
    tests, result = parse(output)
    failures = []
    for name, code, stack in tests:
        log(f"{CODES.get(code, code)} {name}")
        if code not in (0, -3):
            failures.append(f"{name}: {stack.strip()}")
    summary = result.get("stream", "").strip().splitlines()
    if result.get("code") != -1 or "shortMsg" in result or result.get("other"):
        failures.append(f"{title}: the instrumentation did not finish: {result}")
    elif not tests:
        failures.append(f"{title}: no test ran")
    if summary:
        log(summary[-1])
    crash = dev.crash()
    if crash:
        failures.append(f"{title}: a process crashed:\n{crash}")
    return failures


def install(apk, grant=False):
    adb("install", "-r", *(["-g"] if grant else []), apk, timeout=300)


def run(dev, args):
    for package in (APP_TESTS, APP, ENGINE_TESTS):
        adb("uninstall", package, check=False)
    # The UI detaches from the engine when its Activity stops, and Compose tests need a screen
    for command in ("input keyevent KEYCODE_WAKEUP", "svc power stayon true", "wm dismiss-keyguard",
                    "settings put system screen_off_timeout 1800000"):
        shell(command, check=False)
    dev.start_logcat()
    failures = []
    install(args.app)
    install(args.app_tests)
    failures += instrument(dev, "app-permission", APP_TESTS, "the app, freshly installed: the notification permission",
                           "-e", "class", PERMISSION_TEST)
    failures += instrument(dev, "app", APP_TESTS, "the app's other tests", "-e", "notClass", PERMISSION_TEST)
    install(args.engine_tests, grant=True)
    failures += instrument(dev, "engine-service", ENGINE_TESTS, "engine-service: EngineService on the device")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--app", required=True)
    parser.add_argument("--app-tests", required=True)
    parser.add_argument("--engine-tests", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--symbols", help="directory with the unstripped engine library, for ndk-stack")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    global LOG
    LOG = os.path.join(args.out, "instrumented.log")
    open(LOG, "w").close()
    dev = Device(args.out, os.path.abspath(args.app), args.symbols)
    dev.logcat_path = os.path.join(args.out, "logcat-instrumented.txt")
    try:
        failures = run(dev, args)
    except (SmokeFailure, subprocess.TimeoutExpired) as e:
        failures = [str(e)]
    finally:
        try:
            dev.screenshot("instrumented-screenshot.png")
        except OSError as e:
            log(f"no screenshot: {e}")
        dev.stop_logcat()
    if failures:
        log("\n" + "\n\n".join(f"FAIL {f}" for f in failures))
        if os.environ.get("GITHUB_ACTIONS"):
            text = "\n\n".join(failures)[:4000] + "\n(the instrumentation output and logcat are in the emulator artifact)"
            text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Instrumented tests::{text}", flush=True)
        sys.exit(1)
    log("\nall passed")


if __name__ == "__main__":
    main()
