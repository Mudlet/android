#!/usr/bin/env python3
"""Runs the instrumented tests (app/src/androidTest, engine-service/src/androidTest) on a running emulator or device
through adb, as CI's emulator job does after the smoke test on the same boot.

It installs the APKs itself rather than through Gradle's connectedAndroidTest, which installs with -g and so grants
every runtime permission: NotificationPermissionTest needs the app freshly installed without POST_NOTIFICATIONS, so it
runs first, on its own, and the other app tests after it. The engine-service tests run from their own test APK,
installed with -g, which instruments itself and runs EngineService in its own :engine process.

A test fails the run if it fails or runs past TEST_TIMEOUT_MS, if the instrumentation crashes, hangs or reports fewer
tests than it started, or if no test ran at all. A crash of one of the app's processes is given from logcat,
symbolised as android_smoke.py does; for a hang, the stacks of the hung process's threads. The screenshots the app's
ScreenshotTest takes (API 31 and later) are copied into <out>/screenshots.

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
# AndroidJUnitRunner fails a test that runs longer, with the stack it was stuck in; the longest, EngineServiceTest's
# restore, waits up to 180 s for the new engine
TEST_TIMEOUT_MS = 300_000

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


def instrument(dev, name, package, process, title, timeout, *args):
    """Runs one am instrument, keeping its output as instrument-<name>.txt; returns the failures, as text. If it is
    still running after timeout seconds, the stacks of process's threads go to the log and the run is stopped."""
    log(f"--- {title}")
    command = " ".join(["am", "instrument", "-w", "-r", "-e", "timeout_msec", str(TEST_TIMEOUT_MS), *args,
                        f"{package}/{RUNNER}"])
    hung = None
    try:
        output = adb("shell", command, check=False, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        output = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else e.stdout or ""
        hung = f"{title}: still running after {timeout} s; the threads of {process}:\n{thread_stacks(dev, name, process)}"
        shell(f"am force-stop {process.split(':')[0]}", check=False)
    with open(os.path.join(dev.out, f"instrument-{name}.txt"), "w") as f:
        f.write(output)
    tests, result = parse(output)
    failures = []
    for test, code, stack in tests:
        log(f"{CODES.get(code, code)} {test}")
        if code not in (0, -3):
            failures.append(f"{test}: {stack.strip()}")
    summary = result.get("stream", "").strip().splitlines()
    if hung:
        started = re.findall(r"INSTRUMENTATION_STATUS: test=(\S+)", output)
        failures.append(hung + (f"\n(the last test to start: {started[-1]})" if started else ""))
    elif result.get("code") != -1 or "shortMsg" in result or result.get("other"):
        failures.append(f"{title}: the instrumentation did not finish: {result}")
    elif not tests:
        failures.append(f"{title}: no test ran")
    if summary:
        log(summary[-1])
    crash = dev.crash()
    if crash:
        failures.append(f"{title}: a process crashed:\n{crash}")
    return failures


def thread_stacks(dev, name, process):
    """The Java stacks of process's threads that run the app's or the tests' code, the rest in stacks-<name>.txt."""
    pid = shell(f"pidof {process}", check=False).strip()
    if not pid:
        return f"{process} is not running"
    # debuggerd needs root to dump another app's process; the emulator's google_apis images have su
    dump = ""
    for command in (f"debuggerd -j {pid}", f"su 0 debuggerd -j {pid}"):
        try:
            dump = adb("shell", f"{command} 2>&1", check=False, timeout=60)
        except subprocess.TimeoutExpired:
            continue
        if '"main"' in dump:
            break
    else:
        return f"debuggerd could not dump {process} (pid {pid}): {dump.strip()[:300]}"
    with open(os.path.join(dev.out, f"stacks-{name}.txt"), "w") as f:
        f.write(dump)
    threads = [t for t in re.split(r"\n(?=\")", dump) if t.startswith('"main"') or "org.mudlet" in t]
    return "\n".join(threads)[:6000]


def pull_screenshots(dev):
    """Copies the PNGs ScreenshotTest saved in the app's files (files/screenshots) into <out>/screenshots."""
    listing = shell(f"run-as {APP} ls files/screenshots 2>&1", check=False)
    names = [n for n in listing.split() if n.endswith(".png")]
    if not names:
        # None below API 31, which ScreenshotTest skips; on a later release, ScreenshotTest's own result says why
        log(f"screenshots: none ({listing.strip()[:200]})")
        return
    out = os.path.join(dev.out, "screenshots")
    os.makedirs(out, exist_ok=True)
    copied = []
    for name in names:
        path = os.path.join(out, name)
        with open(path, "wb") as f:
            done = subprocess.run(["adb", "exec-out", "run-as", APP, "cat", f"files/screenshots/{name}"], stdout=f,
                                  stderr=subprocess.PIPE, timeout=60)
        if done.returncode == 0:
            copied.append(name)
        else:
            os.remove(path)
            log(f"screenshot {name} not copied: {done.stderr.decode(errors='replace').strip()}")
    log(f"screenshots: {', '.join(sorted(copied))}")


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
    failures += instrument(dev, "app-permission", APP_TESTS, APP, "the app, freshly installed: the notification permission",
                           480, "-e", "class", PERMISSION_TEST)
    failures += instrument(dev, "app", APP_TESTS, APP, "the app's other tests", 720, "-e", "class", "org.mudlet.android.ui.ScriptMapTest,org.mudlet.android.ui.ScreenshotTest")
    pull_screenshots(dev)
    install(args.engine_tests, grant=True)
    failures += instrument(dev, "engine-service", ENGINE_TESTS, ENGINE_TESTS, "engine-service: EngineService on the device",
                           1320)
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
        except (OSError, subprocess.SubprocessError) as e:
            log(f"no screenshot: {e}")
        dev.stop_logcat()
    if failures:
        log("\n" + "\n\n".join(f"FAIL {f}" for f in failures))
        if os.environ.get("GITHUB_ACTIONS"):
            text = "\n\n".join(failures)[:4000] + "\n(the instrumentation output and logcat are in the job's artifact)"
            text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Instrumented tests::{text}", flush=True)
        sys.exit(1)
    log("\nall passed")


if __name__ == "__main__":
    main()
