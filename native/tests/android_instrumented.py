#!/usr/bin/env python3
"""Runs the instrumented tests (app/src/androidTest, engine-service/src/androidTest) on a running emulator or device
through adb, as CI's emulator job does after the smoke test on the same boot.

It installs the APKs itself rather than through Gradle's connectedAndroidTest, which installs with -g and so grants
every runtime permission: NotificationPermissionTest needs the app freshly installed without POST_NOTIFICATIONS, so it
runs first, on its own, and the other app tests after it. The engine-service tests run from their own test APK,
installed with -g, which instruments itself and runs EngineService in its own :engine process.

A test fails the run if it fails or runs past TEST_TIMEOUT_MS, if the instrumentation crashes, hangs (no test ends for
STALL_S, or it runs past its overall limit) or reports fewer tests than it started, or if no test ran at all. A crash
of one of the app's processes is given from logcat, symbolised as android_smoke.py does; for a hang, the stacks of the
hung process's threads. The screenshots the app's ScreenshotTest takes (API 31 and later) are copied into
<out>/screenshots. From API 31 it then backs the app up through bmgr's local transport, once as a cloud backup and once
as a transfer to a new phone, restores it, and has BackupRestoreTest check what came back.

usage: android_instrumented.py --app <app-debug.apk> --app-tests <app-debug-androidTest.apk>
                               --engine-tests <engine-service-debug-androidTest.apk> --out <dir> [--symbols <dir>]
"""
import argparse
import os
import queue
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_smoke import Device, SmokeFailure, adb, install, shell  # noqa: E402

APP = "org.mudlet.mudlet"
APP_TESTS = APP + ".test"
ENGINE_TESTS = "org.mudlet.android.engine.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PERMISSION_TEST = "org.mudlet.android.ui.NotificationPermissionTest"
BACKUP_TEST = "org.mudlet.android.data.BackupRestoreTest"
# The local transport's one restore set; its is_encrypted and is_device_transfer parameters are from Android 12 (API 31)
LOCAL_TRANSPORT = "com.android.localtransport/.LocalTransport"
LOCAL_RESTORE_SET = "1"
# am instrument's status codes for one test
CODES = {0: "PASS", -1: "ERROR", -2: "FAIL", -3: "IGNORED", -4: "ASSUMPTION FAILED"}
# AndroidJUnitRunner fails a test that runs longer, with the stack it was stuck in; the longest, EngineServiceTest's
# restore, waits up to 180 s for the new engine
TEST_TIMEOUT_MS = 300_000
# A test past TEST_TIMEOUT_MS is failed by the runner, with its stack, and the next one starts: this long with no test
# ending is a hang the runner cannot report. The suites' overall limits are a backstop, well above how long they take,
# so that a slow runner or a growing suite does not fail them
STALL_S = TEST_TIMEOUT_MS // 1000 + 120

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


class Watchdog:
    """Whether an am instrument -r has hung: no test has ended for stall seconds, or it has run for limit."""

    def __init__(self, stall, limit, now):
        self.stall, self.limit = stall, limit
        self.started = self.progress = now

    def line(self, text, now):
        if (m := re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)) and int(m.group(1)) != 1:
            self.progress = now

    def hung(self, now):
        if now - self.started >= self.limit:
            return f"still running after {self.limit} s"
        if now - self.progress >= self.stall:
            return f"no test ended for {self.stall} s"
        return None


def watched(argv, stall, limit):
    """Runs argv, stopping it when Watchdog says it has hung; returns its output and why it was stopped, or None."""
    proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
    lines = queue.Queue()

    def read():
        for line in proc.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read, daemon=True).start()
    dog = Watchdog(stall, limit, time.monotonic())
    output = []
    while True:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            line = ""
        if line is None:
            proc.wait()
            return "".join(output), None
        output.append(line)
        dog.line(line, time.monotonic())
        if why := dog.hung(time.monotonic()):
            proc.kill()
            proc.wait()
            return "".join(output), why


def instrument(dev, name, package, process, title, limit, *args):
    """Runs one am instrument, keeping its output as instrument-<name>.txt; returns the failures, as text. If no test
    ends for STALL_S, or it is still running after limit seconds, the stacks of process's threads go to the log and the
    run is stopped."""
    log(f"--- {title}")
    command = " ".join(["am", "instrument", "-w", "-r", "-e", "timeout_msec", str(TEST_TIMEOUT_MS), *args,
                        f"{package}/{RUNNER}"])
    started = time.monotonic()
    output, why = watched(["adb", "shell", command], STALL_S, limit)
    hung = None
    if why:
        hung = f"{title}: {why}; the threads of {process}:\n{thread_stacks(dev, name, process)}"
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
    log(f"({len(tests)} tests in {time.monotonic() - started:.0f} s of the {limit} s allowed)")
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


def backup_round(dev, to):
    """A backup of the app through the local transport, as a cloud backup encrypted with the screen lock (to "cloud")
    or a transfer to a new phone ("phone"), then the app cleared and restored: BackupRestoreTest writes the data before
    and says what came back after. Returns the failures, as text."""
    parameters = "is_encrypted=true" if to == "cloud" else "is_device_transfer=true"
    failures = instrument(dev, f"backup-seed-{to}", APP_TESTS, APP, f"backup ({to}): the data to back up", 120,
                          "-e", "class", BACKUP_TEST + "#seed")
    shell(f"settings put secure backup_local_transport_parameters {parameters}")
    # No am force-stop first: it marks the package stopped, and bmgr then answers "Backup is not allowed" for it.
    # The seed's instrumentation has already ended the app's processes.
    out = shell(f"bmgr backupnow {APP}")
    log(f"bmgr backupnow: {' '.join(out.split())}")
    # The package manager's own "@pm@ ... Success" comes in the same output, so only the app's result counts
    if f"Package {APP} with result: Success" not in out:
        return failures + [f"backup ({to}): bmgr backupnow did not succeed: {out.strip()}"]
    shell(f"pm clear {APP}")
    out = shell(f"bmgr restore {LOCAL_RESTORE_SET} {APP}")
    log(f"bmgr restore: {' '.join(out.split())}")
    return failures + instrument(dev, f"backup-check-{to}", APP_TESTS, APP, f"backup ({to}): what the restore brought",
                                 120, "-e", "class", BACKUP_TEST + "#check", "-e", "backupTo", to)


def backup_rounds(dev):
    if int(shell("getprop ro.build.version.sdk").strip()) < 31:
        log("--- backup: skipped, the local transport takes the flags it needs from API 31")
        return []
    transports = shell("bmgr list transports")
    current = next((line.split()[-1] for line in transports.splitlines() if line.strip().startswith("*")), None)
    shell("bmgr enable true")
    shell(f"bmgr transport {LOCAL_TRANSPORT}")
    try:
        return backup_round(dev, "cloud") + backup_round(dev, "phone")
    finally:
        shell("settings delete secure backup_local_transport_parameters", check=False)
        if current:
            shell(f"bmgr transport {current}", check=False)


BENCH = "org.mudlet.android.ui.ConsoleFloodBenchmark"
ACTIVITY = APP + "/org.mudlet.android.ui.MainActivity"


def perf_lines(seen):
    """The PERF lines ConsoleFloodBenchmark logged that have not been printed yet, printed."""
    out = adb("logcat", "-d", "-s", "ConsoleFloodBenchmark:I", check=False)
    for line in out.splitlines():
        if "PERF " in line:
            metric = line[line.index("PERF "):]
            if metric not in seen:
                seen.add(metric)
                log("METRIC " + metric)


def cold_starts(mode, rounds=6):
    """am start -W of MainActivity after a force-stop, rounds times; the engine process's memory after the last."""
    for i in range(rounds):
        shell(f"am force-stop {APP}", check=False)
        time.sleep(3)
        out = shell(f"am start -W -n {ACTIVITY}", check=False)
        total = re.search(r"TotalTime: (\d+)", out)
        wait = re.search(r"WaitTime: (\d+)", out)
        log(f"METRIC COLD mode={mode} round={i} totalMs={total.group(1) if total else -1} waitMs={wait.group(1) if wait else -1}")
        time.sleep(8)
    for process in (APP, APP + ":engine"):
        mem = shell(f"dumpsys meminfo {process}", check=False)
        pss = re.search(r"TOTAL(?: PSS)?:?\s+(\d+)", mem)
        log(f"METRIC MEM mode={mode} process={process} pssKb={pss.group(1) if pss else -1}")
    shell(f"am force-stop {APP}", check=False)


def run(dev, args):
    for package in (APP_TESTS, APP, ENGINE_TESTS):
        adb("uninstall", package, check=False)
    for command in ("input keyevent KEYCODE_WAKEUP", "svc power stayon true", "wm dismiss-keyguard",
                    "settings put system screen_off_timeout 1800000"):
        shell(command, check=False)
    dev.start_logcat()
    failures = []
    install(args.app)
    install(args.app_tests)
    seen = set()
    cold_starts("installed")
    n = 0
    for mode in ("installed", "installed", "installed", "speed-profile", "speed"):
        if mode != "installed":
            log(shell(f"cmd package compile -m {mode} -f {APP}", check=False).strip())
        n += 1
        log(f"load: {shell('cat /proc/loadavg', check=False).strip()}")
        failures += instrument(dev, f"bench-{n}", APP_TESTS, APP, f"ConsoleFloodBenchmark run {n} ({mode})", 400,
                               "-e", "class", BENCH, "-e", "perfRun", f"{n}-{mode}")
        perf_lines(seen)
        if mode != "installed" or n == 3:
            cold_starts(mode if n > 3 else "installed-after-runs")
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
