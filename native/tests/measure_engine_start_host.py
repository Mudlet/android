#!/usr/bin/env python3
"""Measure only (perf-engine-measure branch): the host engine's own start and profile load, the same MUDLET_T stamps as
measure_engine_start.py reads on Android, for desktop's figure beside the phone's. Rounds: new (an empty config, so the
default packages install), then saved (the profile the last new round saved at SIGTERM).

usage: measure_engine_start_host.py <engine binary> [rounds]
"""
import os
import re
import signal
import statistics
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
from protocol_client import Client  # noqa: E402

ENGINE = os.path.abspath(sys.argv[1])
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 5
MARK = re.compile(r"MUDLET_T (\S+)(?: (\S+))? (\d+\.\d+)")


def boot_ms():
    return time.clock_gettime(time.CLOCK_BOOTTIME) * 1000


def one_round(tmp, port, kind, index, base):
    home = os.path.join(tmp, "home")
    log = os.path.join(tmp, f"engine-{kind}{index}.log")
    sock = os.path.join(tmp, "engine.sock")
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", MUDLET_CONFIG_DIR=os.path.join(tmp, "config"),
               MUDLET_PROFILE="Bench", HOME=home, XDG_CONFIG_HOME=os.path.join(home, ".config"),
               XDG_DATA_HOME=os.path.join(home, ".local/share"), XDG_CACHE_HOME=os.path.join(home, ".cache"))
    if base:
        env["MUDLET_MEASURE_BASE"] = "1"
    kind += "-base" if base else ""
    t0 = boot_ms()
    proc = subprocess.Popen([ENGINE, "--socket", sock, "--heartbeat-ms", "1000"], env=env, stdout=subprocess.DEVNULL,
                            stderr=open(log, "wb"))
    deadline = time.monotonic() + 30
    while True:
        try:
            c = Client(sock)
            break
        except (FileNotFoundError, ConnectionRefusedError):
            if time.monotonic() > deadline or proc.poll() is not None:
                raise RuntimeError(f"engine did not listen; see {log}")
            time.sleep(0.005)
    listening = boot_ms()
    asked = boot_ms()
    c.send({"t": "connect", "host": "127.0.0.1", "port": port})
    c.wait_for(lambda m: m["t"] in ("line", "tail") and "bright blue 256" in m.get("text", ""), timeout=60)
    shown = boot_ms()
    time.sleep(1)
    proc.send_signal(signal.SIGTERM)
    proc.wait(30)
    marks = {"script.spawn": 0.0, "script.connected": listening - t0, "script.connectAsked": asked - t0,
             "script.bannerSeen": shown - t0}
    with open(log, errors="replace") as f:
        for line in f:
            m = MARK.search(line)
            if m:
                what = m.group(1) + (" " + m.group(2) if m.group(2) else "")
                key, n = what, 2
                while key in marks:
                    key, n = f"{what}#{n}", n + 1
                marks[key] = float(m.group(3)) - t0
    for k, v in sorted(marks.items(), key=lambda kv: kv[1]):
        print(f"HOSTMARK {kind}{index} {v:9.1f} {k}", flush=True)
    return marks


def main():
    port = fake_telnet.start_in_thread()
    rounds = {}
    for i in range(ROUNDS):
        for base in (False, True):
            tmp = tempfile.mkdtemp(prefix="mudlet-measure-")
            suffix = "-base" if base else ""
            rounds.setdefault("new" + suffix, []).append(one_round(tmp, port, "new", i, base))
            rounds.setdefault("saved" + suffix, []).append(one_round(tmp, port, "saved", i, base))
    for kind, all_marks in rounds.items():
        keys = sorted({k for m in all_marks for k in m}, key=lambda k: statistics.median([m[k] for m in all_marks if k in m]))
        print(f"HOSTSUMMARY {kind} ({len(all_marks)} rounds): ms after spawn, median [min-max]")
        for k in keys:
            vals = [m[k] for m in all_marks if k in m]
            print(f"HOSTSUMMARY {kind} {statistics.median(vals):8.1f} [{min(vals):8.1f}-{max(vals):8.1f}] n={len(vals)} {k}")


if __name__ == "__main__":
    main()
