#!/usr/bin/env python3
"""Measure only (perf-engine-measure branch): where the time goes from launching the app to the game's first line.

Reads the MUDLET_T stamps (CLOCK_BOOTTIME, ms) the UI, the engine service, the engine host and Mudlet (the measure
patch) log, per round:
  new    - the app's data cleared, so a new profile: Mudlet installs the default packages
  saved  - a saved profile, the app and engine stopped first, connect asked as soon as the UI is attached
  late   - as saved, but connect asked 5 s after the UI attached, as a player who looks at the list first
usage: measure_engine_start.py --apk <app-debug.apk> --out <dir> [--rounds N]
"""
import argparse
import os
import re
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_smoke as s  # noqa: E402
import fake_telnet  # noqa: E402

PROFILE = "Bench"
MARK = re.compile(r"^\s*(\S+(?: \S+)?)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+.*?MUDLET_T (\S+)(?: (\S+))? (\d+\.\d+)\s*$")
START_PROC = re.compile(r"^\s*(\d+\.\d+)\s.*Start proc (\d+):(org\.mudlet\.mudlet(?::engine)?)/")


def log(text):
    s.log(text)


def logcat_since(path, offset):
    with open(path, "rb") as f:
        f.seek(offset)
        return f.read().decode(errors="replace")


def parse(text):
    marks, procs, offsets = [], [], []
    for line in text.splitlines():
        m = MARK.match(line)
        if m:
            what = m.group(4) + (" " + m.group(5) if m.group(5) else "")
            stamp = float(m.group(6))
            marks.append((stamp, int(m.group(2)), what))
            if re.fullmatch(r"\d+\.\d+", m.group(1)):
                offsets.append(stamp - float(m.group(1)) * 1000)
            continue
        m = START_PROC.match(line)
        if m:
            procs.append((float(m.group(1)) * 1000, m.group(3)))
    shift = statistics.median(offsets) if offsets else 0
    for ts, name in procs:
        marks.append((ts + shift, 0, "proc.start " + name))
    return sorted(marks)


def engine_gone(timeout=60):
    deadline = time.monotonic() + timeout
    while s.shell(f"pidof {s.ENGINE_PROCESS}", check=False).strip():
        if time.monotonic() > deadline:
            return False
        time.sleep(0.3)
    return True


def stop_all(dev):
    try:
        dev.command("stop")
    except s.SmokeFailure as e:
        log(f"stop: {e}")
    if not engine_gone():
        log("engine did not stop in 60 s; force-stopping")
    s.shell(f"am force-stop {s.PKG}", check=False)
    time.sleep(1)


def one_round(dev, kind, port, index):
    offset = os.path.getsize(dev.logcat_path)
    s.shell(f"setprop debug.mudlet.base {1 if kind.endswith('-base') else 0}", check=False)
    if kind.startswith("new"):
        s.shell(f"pm clear {s.PKG}", check=False)
        s.shell(f"pm grant {s.PKG} android.permission.POST_NOTIFICATIONS", check=False)
    time.sleep(2)
    began = time.monotonic()
    am = s.shell(f"am start -W -n {s.ACTIVITY}")
    total = re.search(r"TotalTime: (\d+)", am)
    state = dev.wait("the UI attached", lambda st: st["link"].startswith("attached to pid"), 60)
    if kind.startswith("late"):
        time.sleep(5)
    dev.command("connect", host=s.HOST_FROM_EMULATOR, port=port, profile=PROFILE, login=s.LOGIN, password=s.PASSWORD)
    dev.wait("the banner", lambda st: st["profileAnswer"] not in ("waiting", "ok")
             or st["status"].get("profile", "").startswith("start failed")
             or (st["status"].get("profile") == f"{PROFILE} loaded" and s.line_with(st, "bright blue 256 plain")), 180)
    time.sleep(2)
    wall = time.monotonic() - began
    marks = parse(logcat_since(dev.logcat_path, offset))
    t0 = next((t for t, _, w in marks if w.startswith("app.onCreate") and not w.endswith(":engine")), None)
    if t0 is None:
        t0 = marks[0][0] if marks else 0
    text = logcat_since(dev.logcat_path, offset)
    certs = " ".join(re.findall(r"MUDLET_CERTS \w+ \d+", text))
    certs += " " + " | ".join(re.findall(r"MUDLET_(?:FRAMES|GREET) [^\r\n]*", text))
    log(f"ROUND {kind}{index} amTotal={total.group(1) if total else '?'} wall={wall:.1f}s {certs}")
    seen = {}
    for t, pid, what in marks:
        key = what
        n = 2
        while key in seen:
            key = f"{what}#{n}"
            n += 1
        seen[key] = t - t0
        log(f"MARK {kind}{index} {t - t0:9.1f} {what} (pid {pid})")
    return seen


def summarise(rounds):
    by_kind = {}
    for kind, marks in rounds:
        by_kind.setdefault(kind, []).append(marks)
    for kind, all_marks in by_kind.items():
        keys = []
        for marks in all_marks:
            for k in marks:
                if k not in keys:
                    keys.append(k)
        keys.sort(key=lambda k: statistics.median([m[k] for m in all_marks if k in m]))
        log(f"SUMMARY {kind} ({len(all_marks)} rounds): ms after the UI's Application.onCreate, median [min-max]")
        for k in keys:
            vals = [m[k] for m in all_marks if k in m]
            log(f"SUMMARY {kind} {statistics.median(vals):8.1f} [{min(vals):8.1f}-{max(vals):8.1f}] n={len(vals)} {k}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    s.LOG = os.path.join(args.out, "measure.log")
    dev = s.Device(args.out, args.apk, None)
    port = fake_telnet.start_in_thread()
    s.install(args.apk, "-g")
    for command in ("input keyevent KEYCODE_WAKEUP", "svc power stayon true", "wm dismiss-keyguard",
                    "settings put system screen_off_timeout 1800000"):
        s.shell(command, check=False)
    s.adb("logcat", "-c")
    probe = subprocess.run(["adb", "logcat", "-d", "-t", "1", "-v", "threadtime", "-v", "monotonic"], capture_output=True,
                           text=True)
    formats = ["-v", "threadtime", "-v", "monotonic"] if probe.returncode == 0 else ["-v", "threadtime"]
    log(f"logcat formats {formats}: {probe.stdout.strip()[:120]} {probe.stderr.strip()[:120]}")
    dev.logcat = subprocess.Popen(["adb", "logcat", *formats],
                                  stdout=open(dev.logcat_path, "w"), stderr=subprocess.STDOUT)
    time.sleep(1)
    rounds = []
    try:
        for i in range(3):
            for kind in ("new", "new-base"):
                rounds.append((kind, one_round(dev, kind, port, i)))
                s.shell(f"am send-trim-memory {s.ENGINE_PROCESS} RUNNING_LOW", check=False)
                dev.wait("the save", lambda st: st["status"].get("save", "").startswith(("saved", "not saved")), 60)
                stop_all(dev)
        for i in range(args.rounds):
            for kind in ("saved", "saved-base", "late", "late-base"):
                rounds.append((kind, one_round(dev, kind, port, i)))
                stop_all(dev)
    except s.SmokeFailure as e:
        log(f"FAILED {e}")
    finally:
        summarise(rounds)
        dev.stop_logcat()
    return 0


if __name__ == "__main__":
    sys.exit(main())
