#!/usr/bin/env python3
"""Host-side test of the engine process and its socket protocol.

Runs the desktop build of the engine (the same sources as libengine.so on Android)
against fake_telnet.py, checks the engine logic through the protocol, then measures
round-trip latency, line throughput, reconnect/replay and engine-death behaviour.

usage: test_engine.py <path-to-engine-binary>
"""
import faulthandler
import json
import os
import signal
import socket
import statistics
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
from protocol_client import Client, Closed  # noqa: E402

ENGINE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build-desktop/enginehost/engine")
TMP = tempfile.mkdtemp(prefix="engine-test-")
SOCK = os.path.join(TMP, "engine.sock")
failures = []
results = {}


def check(cond, what):
    print(("PASS " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


def rss_kb(pid):
    with open(f"/proc/{pid}/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                return int(line.split()[1])
    return -1


def cpu_ms(pid):
    with open(f"/proc/{pid}/stat") as f:
        fields = f.read().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) * 1000 / os.sysconf("SC_CLK_TCK")


def pss_kb(pid):
    try:
        with open(f"/proc/{pid}/smaps_rollup") as f:
            for line in f:
                if line.startswith("Pss:"):
                    return int(line.split()[1])
    except OSError:
        pass
    return -1


def start_engine():
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    proc = subprocess.Popen([ENGINE, "--socket", SOCK, "--heartbeat-ms", "1000"], env=env,
                            stdout=subprocess.DEVNULL, stderr=open(os.path.join(TMP, "engine.log"), "ab"))
    t0 = time.monotonic()
    while time.monotonic() - t0 < 10:
        try:
            c = Client(SOCK)
            return proc, c, time.monotonic() - t0
        except (FileNotFoundError, ConnectionRefusedError):
            time.sleep(0.01)
    raise RuntimeError("engine did not listen")


def handshake(c):
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    return msgs


def status_map(msgs):
    return {m["key"]: m["value"] for m in msgs if m["t"] == "status"}


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p / 100))]


def latency(name, samples_s):
    us = [s * 1e6 for s in samples_s]
    results[name] = (statistics.median(us), pct(us, 99), max(us))
    print(f"     {name}: n={len(us)} median={statistics.median(us):.0f}us p99={pct(us, 99):.0f}us max={max(us):.0f}us")


def main():
    faulthandler.register(signal.SIGUSR1)
    port = fake_telnet.start_in_thread()
    proc, c, startup = start_engine()
    print(f"engine pid {proc.pid}, socket ready after {startup * 1000:.0f} ms (process spawn to accepted connect)")
    results["startup_ms"] = startup * 1000
    results["rss_idle_kb"] = rss_kb(proc.pid)
    results["pss_idle_kb"] = pss_kb(proc.pid)

    # Handshake and initial state
    hs = handshake(c)
    check(hs[0]["t"] == "hello" and hs[0]["v"] == 1 and hs[0]["pid"] == proc.pid, "hello reply carries v=1 and engine pid")
    st = status_map(hs)
    check(st.get("connection") == "disconnected", "initial connection status replayed")
    check(int(st.get("advanceM", "0")) > 0, f"font check: family={st.get('fontFamily')!r} advanceM={st.get('advanceM')} platform={st.get('platform')}")
    results["font"] = (st.get("fontFamily"), st.get("advanceM"))

    # Wrong protocol version is refused
    bad = Client(SOCK, version=99)
    try:
        m = bad.recv(2)
        check(m is not None and m["t"] == "error", "hello with v=99 gets an error frame")
        try:
            gone = bad.recv(2) is None
        except Closed:
            gone = True
        check(gone, "and the connection is closed")
    except Closed:
        check(False, "hello with v=99 gets an error frame")
    # The bad client replaced the good one; reconnect
    c.close()
    c = Client(SOCK)
    handshake(c)

    # Columns
    c.send({"t": "setViewWidthPx", "px": 1080})
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "columns")
    check(int(m["value"]) == 1080 // int(st["advanceM"]), f"columns for 1080px = {m['value']}")

    # The stand-in has no TLS, and says so rather than connecting in the clear
    c.send({"t": "connect", "host": "127.0.0.1", "port": port, "tls": True})
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "connection")
    check(m["value"] == "error: the stand-in engine has no TLS", f"a TLS connect is refused: {m['value']}")
    c.send({"t": "connect", "host": "127.0.0.1", "port": port, "tls": "yes"})
    m = c.wait_for(lambda m: m["t"] in ("error", "status"))
    check(m == {"t": "error", "message": "connect: tls must be true or false"}, f"a tls that is not a boolean is refused: {m}")

    # Connect and parse the banner
    msgs = []
    c.send({"t": "connect", "host": "127.0.0.1", "port": port, "tls": False})
    c.wait_for(lambda m: m["t"] == "line" and m["prompt"], collect=msgs)
    lines = [m for m in msgs if m["t"] == "line"]
    texts = [m["text"] for m in lines]
    check(any(m["t"] == "status" and m["key"] == "connection" and m["value"] == "connected" for m in msgs), "connection status 'connected'")
    check(texts[0] == "Red bold normal green", f"ANSI stripped from text: {texts[0]!r}")
    check(lines[0]["runs"] == [[0, 8, 9], [8, 8, -1], [16, 5, 2]], f"runs for bold red/default/green: {lines[0]['runs']}")
    check(texts[1] == "Héllo wörld ✓", "UTF-8 decoded, CR dropped")
    check(texts[2] == "Bob tells you 'hello there'", "tell line")
    check(texts[3] == "a�b", "IAC IAC kept as a literal 0xFF byte (invalid UTF-8 -> U+FFFD)")
    check(texts[4] == "bright blue 256 plain" and lines[4]["runs"] == [[0, 15, 12], [15, 6, -1]],
          f"38;5;n colour and non-SGR CSI dropped: {lines[4]['runs']}")
    check(lines[-1]["text"] == "HP:100> " and lines[-1]["prompt"], "GA-terminated prompt flagged isPrompt")
    check(all(not m["prompt"] for m in lines[:-1]), "ordinary lines not flagged as prompts")
    notes = [m for m in msgs if m["t"] == "notify"]
    if not notes:
        notes = [c.wait_for(lambda m: m["t"] == "notify")]
    check(notes[0]["title"] == "Tell" and "tells you" in notes[0]["text"], "Lua onLine called notify() for the tell")

    c.send({"t": "send", "text": "negotiation"})
    m = c.wait_for(lambda m: m["t"] == "line" and m["text"].startswith("NEG:"))
    neg = bytes.fromhex(m["text"][5:])
    check(neg == bytes([255, 252, 24, 255, 254, 1, 255, 254, 201]), f"DO->WONT, WILL->DONT replies: {neg.hex()}")

    c.send({"t": "send", "text": "prompt-nogo"})
    t0 = time.monotonic()
    m = c.wait_for(lambda m: m["t"] == "line")
    dt = (time.monotonic() - t0) * 1000
    check(m["text"] == "Silent prompt> " and m["prompt"] and 450 <= dt <= 900, f"un-terminated prompt emitted after silence ({dt:.0f} ms)")

    c.send({"t": "runLua", "code": "return lineCount, 1 + 1", "id": 7})
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "luaResult")
    check(m.get("re") == 7 and m["value"].split("\t")[1] == "2" and int(m["value"].split("\t")[0]) >= 6,
          f"runLua result echoes request id: {m}")
    c.send({"t": "runLua", "code": "error('boom')", "id": 8})
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "luaResult")
    check(m["value"].startswith("error:") and "boom" in m["value"], "runLua error reported")

    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "luaOnLineCalls", timeout=2)
    check(int(m["value"]) >= 6, f"luaOnLineCalls counter = {m['value']}")
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "heartbeatTicks", timeout=3)
    check(int(m["value"]) >= 1, "heartbeat ticks (1 s interval in the test)")

    # Latency
    samples = []
    for i in range(3000):
        t0 = time.perf_counter()
        c.send({"t": "ping", "id": i})
        c.wait_for(lambda m: m["t"] == "pong" and m["id"] == i)
        samples.append(time.perf_counter() - t0)
    latency("ping (UI->engine->UI, protocol only)", samples[200:])

    samples = []
    for i in range(1000):
        t0 = time.perf_counter()
        c.send({"t": "runLua", "code": "return 1", "id": 1000 + i})
        c.wait_for(lambda m: m["t"] == "status" and m.get("re") == 1000 + i)
        samples.append(time.perf_counter() - t0)
    latency("runLua round trip", samples[100:])

    samples = []
    for i in range(1000):
        t0 = time.perf_counter()
        c.send({"t": "send", "text": f"echo {i}"})
        c.wait_for(lambda m: m["t"] == "line" and m["text"] == f"You said: {i}")
        samples.append(time.perf_counter() - t0)
    latency("send -> game echo -> line (incl. telnet hop)", samples[100:])

    # Throughput. The engine never waits for the UI, so a client slower than the
    # engine sees a "gap" and loses lines; count them rather than assume none.
    instance = hs[0]["instance"]

    def drain_flood(decode=True):
        count = skipped = 0
        seq = None
        while True:
            raw = c.next_raw(30)
            if raw is None:
                raise TimeoutError("flood")
            # Qt writes JSON keys in sorted order, so "t" is not first
            if b'"t":"line"' in raw:
                count += 1
                if decode:
                    json.loads(raw)
                if b"FLOOD DONE" in raw:
                    seq = json.loads(raw)["seq"]
                    return count, skipped, seq
            elif b'"t":"gap"' in raw:
                skipped += json.loads(raw)["skipped"]

    # Paced floods: the highest line rate a (Python, JSON-decoding) UI keeps up with
    sustained = 0
    for rate in (10000, 25000, 50000, 100000, 200000):
        n = rate  # one second of traffic
        cpu0 = cpu_ms(proc.pid)
        c.send({"t": "send", "text": f"pace {n} {rate}"})
        count, skipped, last_seq = drain_flood()
        _, t_start, t_end = fake_telnet.FLOOD_TIMES[-1]
        engine_cpu = (cpu_ms(proc.pid) - cpu0) / (t_end - t_start) / 10
        print(f"     paced {rate:>6} lines/s for 1 s: delivered {count}, skipped {skipped}, engine CPU {engine_cpu:.0f}% of a core")
        check(count + skipped == n + 1, f"paced {rate}: every line delivered or counted in a gap")
        results[f"paced_{rate}"] = (count, skipped, round(engine_cpu))
        if skipped == 0:
            sustained = rate
    results["sustained_lines_per_s_python_ui"] = sustained

    # Unpaced flood, then the same with no UI attached: engine CPU per line with and without a UI
    n = 200000
    cpu0 = cpu_ms(proc.pid)
    t0 = time.perf_counter()
    c.send({"t": "send", "text": f"flood {n}"})
    count, skipped, last_seq = drain_flood()
    dt = time.perf_counter() - t0
    attached_cpu_us = (cpu_ms(proc.pid) - cpu0) * 1000 / n
    print(f"     unpaced flood {n}: done in {dt:.2f} s, UI got {count}, skipped {skipped}, engine {attached_cpu_us:.2f} us CPU/line")
    check(count + skipped == n + 1, "unpaced flood: every line delivered or counted in a gap")

    c.send({"t": "runLua", "code": "return lineCount", "id": "lc0"})
    base = int(c.wait_for(lambda m: m["t"] == "status" and m.get("re") == "lc0")["value"])
    floods = len(fake_telnet.FLOOD_TIMES)
    cpu0 = cpu_ms(proc.pid)
    c.send({"t": "send", "text": f"flood {n}"})
    c.close()
    while len(fake_telnet.FLOOD_TIMES) == floods:
        time.sleep(0.01)
    time.sleep(0.3)
    alone_cpu_us = (cpu_ms(proc.pid) - cpu0) * 1000 / n
    c = Client(SOCK, hello=False)
    c.send({"t": "hello", "v": 1, "instance": instance, "lastSeq": 1 << 50})
    msgs = handshake(c)
    last_seq = msgs[-1]["seq"]
    for i in range(1000):
        c.send({"t": "runLua", "code": "return lineCount", "id": f"lc{i + 1}"})
        done = int(c.wait_for(lambda m: m["t"] == "status" and m.get("re") == f"lc{i + 1}", timeout=10)["value"]) - base
        if done >= n + 1:
            break
        time.sleep(0.01)
    _, start, end = fake_telnet.FLOOD_TIMES[-1]
    print(f"     unpaced flood {n} with no UI attached: consumed at {n / (end - start):,.0f} lines/s, {alone_cpu_us:.2f} us CPU/line")
    check(done == n + 1, "every line reached Lua onLine with no UI attached")
    results["engine_cpu_us_per_line"] = {"ui_attached": round(attached_cpu_us, 2), "no_ui": round(alone_cpu_us, 2)}
    results["engine_alone_lines_per_s"] = round(n / (end - start))
    results["rss_after_flood_kb"] = rss_kb(proc.pid)
    results["pss_after_flood_kb"] = pss_kb(proc.pid)

    # Detach / reattach: lines that arrive while no UI is attached are kept in the ring
    c.close()
    time.sleep(0.1)
    c2 = Client(SOCK, hello=False)
    c2.close()  # a client that never says hello must not get data or break anything
    t0 = time.monotonic()
    c = Client(SOCK)
    msgs = handshake(c)
    reattach_ms = (time.monotonic() - t0) * 1000
    replayed = [m for m in msgs if m["t"] == "line"]
    check(len(replayed) == 2000 and all(m.get("replay") for m in replayed) and msgs[-1]["seq"] == last_seq,
          f"fresh UI (no lastSeq) gets the {len(replayed)}-line ring + statuses in {reattach_ms:.0f} ms")
    results["reattach_full_ms"] = reattach_ms
    check(replayed[-1]["text"] == "FLOOD DONE", "replay ends with the last line seen")
    check(any(m["t"] == "gap" for m in msgs), "and a gap marker for what fell out of the ring")
    c.close()
    time.sleep(0.05)
    c = Client(SOCK, hello=False)
    c.send({"t": "hello", "v": 1, "instance": instance, "lastSeq": last_seq - 3})
    msgs = handshake(c)
    replayed = [m["seq"] for m in msgs if m["t"] == "line"]
    check(replayed == [last_seq - 2, last_seq - 1, last_seq], f"UI that sends instance+lastSeq gets only the missing lines {replayed}")
    c.send({"t": "send", "text": "tell"})
    m = c.wait_for(lambda m: m["t"] == "notify")
    check("Alice" in m["text"], "game connection survived the UI detaching")

    # Second client replaces the first
    c3 = Client(SOCK)
    handshake(c3)
    try:
        c.recv(1)
        old_gone = False
    except Closed:
        old_gone = True
    check(old_gone, "a new client replaces (closes) the previous one")
    c = c3

    # Stalled UI (what Android's cached-app freezer does to a backgrounded UI process)
    stalled = Client(SOCK)
    stalled.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    stalled.send({"t": "send", "text": "flood 300000"})
    stall_rss = []
    for _ in range(8):
        time.sleep(0.5)
        stall_rss.append(rss_kb(proc.pid))
    msgs = []
    stalled.wait_for(lambda m: m["t"] == "line" and m["text"] == "FLOOD DONE", timeout=30, collect=msgs)
    gaps = [m["skipped"] for m in msgs if m["t"] == "gap"]
    got = sum(1 for m in msgs if m["t"] == "line")
    check(proc.poll() is None and gaps and got + sum(gaps) >= 300001,
          f"stalled UI: engine kept running (RSS {min(stall_rss) // 1024}-{max(stall_rss) // 1024} MB while stalled),"
          f" UI later got {got} lines + gap of {sum(gaps)}")
    results["rss_stalled_kb"] = max(stall_rss)
    c = stalled

    # Engine death: the client sees EOF at once, then reconnects with backoff when it comes back
    c = Client(SOCK)
    handshake(c)
    t_kill = time.monotonic()
    os.kill(proc.pid, signal.SIGKILL)
    # Frames already sent before the kill (a heartbeat status) may come first
    eof = False
    try:
        while time.monotonic() - t_kill < 5 and c.recv(5 - (time.monotonic() - t_kill)) is not None:
            pass
    except Closed:
        eof = True
    detect_ms = (time.monotonic() - t_kill) * 1000
    check(eof, f"engine SIGKILL seen by the client as EOF after {detect_ms:.1f} ms")
    results["death_detect_ms"] = detect_ms
    proc.wait()
    try:
        Client(SOCK)
        refused = False
    except (ConnectionRefusedError, FileNotFoundError) as e:
        refused = True
        print(f"     connect to the dead engine: {type(e).__name__} (stale socket file left: {os.path.exists(SOCK)})")
    check(refused, "connecting while the engine is dead fails fast")

    t_restart = time.monotonic()
    proc, c, _ = start_engine()
    msgs = handshake(c)
    reconnect_ms = (time.monotonic() - t_restart) * 1000
    st = status_map(msgs)
    check(st.get("connection") == "disconnected" and msgs[0]["instance"] != instance and not any(m["t"] == "line" for m in msgs),
          f"restarted engine reachable after {reconnect_ms:.0f} ms; game connection and scrollback are lost")
    results["restart_reconnect_ms"] = reconnect_ms
    proc.terminate()
    proc.wait()

    print()
    for k, v in results.items():
        print(f"  {k}: {v}")
    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
