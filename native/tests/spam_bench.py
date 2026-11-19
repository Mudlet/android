#!/usr/bin/env python3
"""What a game's spam costs the engine host with libmudlet as the engine, through the socket protocol: a fight's
coloured lines and GA prompts from a fake game at a set rate, with and without a set of triggers, a flood as fast as
the game can send it, and a flood past the most lines the main console keeps, so that it trims.

Prints one `METRIC <name> <value>` per line, as Mudlet's PipelineBenchmark does, for the CI job "Engine benchmark"
(.github/workflows/perf.yml) to set beside it. A line's delay is from the game sending it to this client parsing its
`line` frame; the engine's CPU comes from /proc, so this runs on Linux only. The client is Python, slower than the
app's: at a flood's rate it falls behind, and the engine sends it a `gap` (docs/protocol.md) rather than wait.

usage: spam_bench.py <path-to-engine-binary> [--quick]
"""
import json
import os
import random
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from protocol_client import Client  # noqa: E402

IAC, GA = 255, 249
TICK_HZ = os.sysconf("SC_CLK_TCK")
WORDS = ("You slash a goblin with your longsword dealing critical damage The orc parries and ripostes grazing your "
         "arm Gandalf casts fireball at the troll It screams in agony Bob tells you to flee").split()
# Shaped like a fighter's triggers: captures, a line coloured, a prompt read, most matching nothing
TRIGGERS = [r"^You (\w+) (?:a|an|the) (\w+)", r"(\w+) tells you", r"\bfireball\b", r"critical damage",
            r"^H:(\d+)/(\d+) M:(\d+)/(\d+)", r"^The (\w+) parries", r"screams in agony", r"^(\w+) casts (\w+) at",
            r"You are stunned", r"You have been slain", r"^\[(\w+)\]: (.+)", r"tumbles? (\w+)", r"(\d+) gold",
            r"^Exits: (.+)", r"is DEAD!", r"You feel (\w+)", r"flees? (\w+)", r"^(\w+) arrives", r"grazing your (\w+)",
            r"^You (?:dodge|parry|block)"] * 2
COLOURED = r"critical damage"
# What the fake game was asked and sent, for a spam that never ends
GAME = {"commands": [], "sent": 0}


def combat_line(rng, k):
    if k % 6 == 5:
        return f"\x1b[32mH:{rng.randrange(2400)}/2400\x1b[0m M:{rng.randrange(1800)}/1800 [eb] ".encode(), True
    words = " ".join(rng.choice(WORDS) for _ in range(6 + rng.randrange(12)))
    return f"\x1b[1;3{k % 8}m{words[:12]}\x1b[0m{words[12:]} \x1b[33m[{k}]\x1b[0m".encode(), False


def serve(listener):
    """One game at a time: `spam <lines> <lines per second, 0 for flat out>` sends a fight, then SPAM DONE."""
    while True:
        conn, _ = listener.accept()
        threading.Thread(target=game, args=(conn,), daemon=True).start()


def game(conn):
    rng = random.Random(9011)
    conn.sendall(b"Welcome to the arena\r\n")
    buf = b""
    while True:
        data = conn.recv(4096)
        if not data:
            return
        buf += data
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            words = line.strip().split()
            GAME["commands"].append(line[-40:])
            if len(words) == 3 and words[0] == b"spam":
                spam(conn, rng, int(words[1]), int(words[2]))


def spam(conn, rng, n, rate):
    # 5 ms ticks when paced; each line carries when it was sent, for the client's delay
    per_tick = max(1, rate // 200) if rate else 256
    start = time.perf_counter()
    k = 0
    while k < n:
        chunk = bytearray()
        for _ in range(min(per_tick, n - k)):
            text, prompt = combat_line(rng, k)
            chunk += text + b" ~" + str(time.monotonic_ns()).encode()
            chunk += bytes([IAC, GA]) if prompt else b"\r\n"
            k += 1
        conn.sendall(chunk)
        GAME["sent"] = k
        if rate:
            delay = start + k / rate - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
    conn.sendall(b"\r\nSPAM DONE\r\n")


def cpu_seconds(pid):
    with open(f"/proc/{pid}/stat") as f:
        fields = f.read().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / TICK_HZ


def memory_kb(pid, key):
    with open(f"/proc/{pid}/status") as f:
        return next(int(line.split()[1]) for line in f if line.startswith(key + ":"))


class Engine:
    def __init__(self, binary, extra_env=None):
        self.dir = tempfile.mkdtemp(prefix="mudlet-spam-bench-")
        home = os.path.join(self.dir, "home")
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen", MUDLET_CONFIG_DIR=os.path.join(self.dir, "config"),
                   MUDLET_PROFILE="Spam Bench", HOME=home, XDG_CONFIG_HOME=os.path.join(home, ".config"),
                   XDG_DATA_HOME=os.path.join(home, ".local/share"), XDG_CACHE_HOME=os.path.join(home, ".cache"),
                   **(extra_env or {}))
        sock = os.path.join(self.dir, "engine.sock")
        self.proc = subprocess.Popen([binary, "--socket", sock, "--heartbeat-ms", "1000"], env=env,
                                     stdout=subprocess.DEVNULL, stderr=open(os.path.join(self.dir, "engine.log"), "ab"))
        deadline = time.monotonic() + 20
        while True:
            try:
                self.client = Client(sock)
                break
            except (FileNotFoundError, ConnectionRefusedError):
                if time.monotonic() > deadline or self.proc.poll() is not None:
                    raise RuntimeError(f"engine did not listen; see {self.dir}/engine.log")
                time.sleep(0.02)
        self.client.wait_for(lambda m: m["t"] == "synced", timeout=20)

    def connect(self, port):
        self.client.send({"t": "connect", "host": "127.0.0.1", "port": port})
        self.wait_text("Welcome to the arena", 60)

    def lua(self, code):
        self.client.send({"t": "runLua", "id": 1, "code": code})
        result = self.client.wait_for(lambda m: m["t"] == "status" and m["key"] == "luaResult", timeout=30)
        if result["value"] != "ran":
            raise RuntimeError(f"the engine did not run the bench's Lua: {result['value']}")

    def wait_text(self, text, timeout):
        self.client.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"] == text, timeout=timeout)

    def spam(self, lines, rate):
        """Delays in microseconds of the lines read, and the lines the engine skipped as gaps."""
        delays, skipped, frames, texts = [], 0, {}, []
        cpu = cpu_seconds(self.proc.pid)
        start = time.perf_counter()
        self.client.send({"t": "send", "text": f"spam {lines} {rate}"})
        allowed = max(120, lines / 500)
        deadline = time.monotonic() + allowed
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError(f"SPAM DONE did not come within {allowed:.0f} s{self.seen(frames, texts)}")
            raw = self.client.next_raw(30)
            if raw is None:
                raise TimeoutError(f"the spam stopped{self.seen(frames, texts)}")
            now = time.monotonic_ns()
            if b'"t":"line"' not in raw and b'"t":"gap"' not in raw:
                kind = raw[raw.find(b'"t":"') + 5:][:20].split(b'"', 1)[0].decode("utf-8", "replace")
                frames[kind] = frames.get(kind, 0) + 1
                if kind in ("tail", "status", "error"):
                    texts = (texts + [raw[:200].decode("utf-8", "replace")])[-6:]
                continue
            m = json.loads(raw)
            frames[m["t"]] = frames.get(m["t"], 0) + 1
            if m["t"] == "line":
                texts = (texts + [m["text"][:120]])[-6:]
            if m["t"] == "gap":
                skipped += m["skipped"]
            elif m["text"] == "SPAM DONE":
                break
            # A prompt is a line only once the next one starts, so its delay is the game's, not the engine's
            elif " ~" in m["text"] and not m.get("replay") and not m["prompt"]:
                delays.append((now - int(m["text"].rsplit(" ~", 1)[1])) / 1000)
        return dict(seconds=time.perf_counter() - start, cpu=cpu_seconds(self.proc.pid) - cpu, delays=delays,
                    skipped=skipped)

    def seen(self, frames, texts):
        with open(os.path.join(self.dir, "engine.log"), "rb") as f:
            log = f.read()[-3000:].decode("utf-8", "replace")
        return (f"\nframes: {frames}\nlast: {texts}\ngame asked {GAME['commands'][-3:]}, sent {GAME['sent']} lines"
                f"\nengine running: {self.proc.poll() is None}\nengine.log ends:\n{log}")

    def stop(self):
        self.client.close()
        self.proc.terminate()
        try:
            self.proc.wait(20)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def metric(name, value):
    print(f"METRIC {name} {value:.1f}" if isinstance(value, float) else f"METRIC {name} {value}", flush=True)


def report(prefix, lines, run):
    metric(f"{prefix}_lines_per_sec", lines / run["seconds"])
    metric(f"{prefix}_engine_cpu_us_per_line", run["cpu"] * 1e6 / lines)
    if run["delays"]:
        delays = sorted(run["delays"])
        metric(f"{prefix}_delay_p50_us", statistics.median(delays))
        metric(f"{prefix}_delay_p99_us", delays[len(delays) * 99 // 100])
    metric(f"{prefix}_gap_lines", run["skipped"])


def main():
    binary = os.path.abspath(sys.argv[1])
    quick = "--quick" in sys.argv
    seconds = 2 if quick else 10
    listener = socket.create_server(("127.0.0.1", 0))
    threading.Thread(target=serve, args=(listener,), daemon=True).start()
    port = listener.getsockname()[1]
    metric("cpu_count", os.cpu_count())

    engine = Engine(binary)
    try:
        engine.connect(port)
        for rate in (1000, 5000, 20000):
            report(f"text_{rate}", rate * seconds, engine.spam(rate * seconds, rate))
        code = ";".join(f'tempRegexTrigger([[{p}]], function() local m = matches[2] end)' for p in TRIGGERS)
        code += f';tempRegexTrigger([[{COLOURED}]], function() selectString(matches[1], 1) fg("red") resetFormat() end)'
        engine.lua(code)
        metric("trigger_count", len(TRIGGERS) + 1)
        for rate in (1000, 5000, 20000):
            report(f"combat_{rate}", rate * seconds, engine.spam(rate * seconds, rate))
        flood = 10000 if quick else 100000
        report("flood", flood, engine.spam(flood, 0))
        metric("peak_rss_kb", memory_kb(engine.proc.pid, "VmHWM"))
    finally:
        engine.stop()

    # The main console at its most lines: filling it, then each batch it sheds as more come
    limit = 5000 if quick else 50000
    engine = Engine(binary, {"MUDLET_SCROLLBACK_MAX_LINES": str(limit)})
    try:
        engine.connect(port)
        engine.lua(f'setConsoleBufferSize("main", {limit}, {limit // 5})')
        before = memory_kb(engine.proc.pid, "VmRSS")
        fill = engine.spam(limit, 0)
        metric("limit_lines", limit)
        metric("limit_fill_engine_cpu_us_per_line", fill["cpu"] * 1e6 / limit)
        metric("limit_kept_bytes_per_line", (memory_kb(engine.proc.pid, "VmRSS") - before) * 1024.0 / limit)
        trim = engine.spam(2 * limit, 0)
        metric("limit_trim_engine_cpu_us_per_line", trim["cpu"] * 1e6 / (2 * limit))
        metric("limit_peak_rss_kb", memory_kb(engine.proc.pid, "VmHWM"))
    finally:
        engine.stop()


if __name__ == "__main__":
    main()
