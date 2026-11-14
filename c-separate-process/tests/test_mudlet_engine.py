#!/usr/bin/env python3
"""Host test of C's engine with libmudlet as the engine (MudletEngine on THeadlessSession), through the socket
protocol, against fake_telnet.py.

Build the engine against a host libmudlet first (scripts/build-libmudlet-desktop.sh, then this directory's CMake
with -DLIBMUDLET_BUILD_DIR=<that build>).

usage: test_mudlet_engine.py <path-to-engine-binary>
"""
import os
import signal
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
from proto import Client  # noqa: E402

ENGINE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build-desktop-mudlet/enginehost/engine")
TMP = tempfile.mkdtemp(prefix="mudlet-engine-test-")
SOCK = os.path.join(TMP, "engine.sock")
CONFIG = os.path.join(TMP, "config")
LOG = os.path.join(TMP, "engine.log")
BOLD = 1
failures = []


def check(cond, what):
    print(("PASS " if cond else "FAIL ") + what, flush=True)
    if not cond:
        failures.append(what)


def start_engine():
    # Mudlet's own files (its Lua, unpacked from the library, and Mudlet.ini outside the config dir) stay in TMP
    home = os.path.join(TMP, "home")
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", MUDLET_CONFIG_DIR=CONFIG, MUDLET_PROFILE="Engine Test",
               HOME=home, XDG_CONFIG_HOME=os.path.join(home, ".config"), XDG_DATA_HOME=os.path.join(home, ".local/share"),
               XDG_CACHE_HOME=os.path.join(home, ".cache"))
    proc = subprocess.Popen([ENGINE, "--socket", SOCK, "--heartbeat-ms", "1000"], env=env,
                            stdout=subprocess.DEVNULL, stderr=open(LOG, "ab"))
    t0 = time.monotonic()
    while time.monotonic() - t0 < 20:
        if proc.poll() is not None:
            raise RuntimeError(f"engine exited with {proc.returncode}; see {LOG}")
        try:
            return proc, Client(SOCK)
        except (FileNotFoundError, ConnectionRefusedError):
            time.sleep(0.02)
    raise RuntimeError("engine did not listen")


def lines_of(msgs):
    return [m for m in msgs if m["t"] == "line"]


def shown(text):
    """A sent line, or the open tail (the console's last line, which a later echo() may still continue)."""
    return lambda m: m["t"] in ("line", "tail") and m["text"] == text


def texts_of(msgs):
    """The lines sent, then the open tail if there is one, as the UI shows them."""
    texts = [m["text"] for m in lines_of(msgs)]
    tails = [m for m in msgs if m["t"] == "tail"]
    if tails and tails[-1]["text"]:
        texts.append(tails[-1]["text"])
    return texts


def wait_status(c, key, pred, msgs, timeout=30):
    return c.wait_for(lambda m: m["t"] == "status" and m["key"] == key and pred(m["value"]), timeout=timeout, collect=msgs)


def main():
    port = fake_telnet.start_in_thread()
    proc, c = start_engine()
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    status = {m["key"]: m["value"] for m in msgs if m["t"] == "status"}
    check(status.get("engineKind") == "libmudlet", f"the engine is libmudlet: {status.get('engineKind')}")
    check(status.get("configDir") == CONFIG, f"profiles kept in MUDLET_CONFIG_DIR: {status.get('configDir')}")

    # Lua before the profile exists: start() can make a profile only with a game's address
    msgs = []
    c.send({"t": "runLua", "id": 1, "code": "return 1"})
    m = wait_status(c, "luaResult", lambda v: True, msgs)
    check(m["value"] == "no profile", f"Lua with no profile yet is refused: {m['value']}")

    # The banner, as the main console shows it
    msgs = []
    c.send({"t": "connect", "host": "127.0.0.1", "port": port})
    c.wait_for(lambda m: m["t"] == "tail" and m["text"].startswith("HP:100>"), timeout=30, collect=msgs)
    tail = [m for m in msgs if m["t"] == "tail"][-1]
    lines = lines_of(msgs)
    texts = [m["text"] for m in lines]
    statuses = [(m["key"], m["value"]) for m in msgs if m["t"] == "status"]
    check(("profile", "Engine Test loaded") in statuses, "profileLoaded reported")
    check(os.path.isdir(os.path.join(CONFIG, "profiles", "Engine Test")), "the profile was made under MUDLET_CONFIG_DIR")
    check(("connection", "connecting") in statuses and ("connection", "connected") in statuses,
          "connecting, then connected, from the session's signals")
    red = next((m for m in lines if m["text"].startswith("Red bold")), None)
    check(red is not None and red["text"] == "Red bold normal green", f"ANSI line: {red}")
    if red:
        first = red["runs"][0] if red["runs"] else None
        check(first is not None and first[:2] == [0, 8] and first[2] != -1 and len(first) >= 5 and first[4] & BOLD,
              f"bold red run, default runs left out: {red['runs']}")
    check("Héllo wörld ✓" in texts, "UTF-8 line")
    check("Bob tells you 'hello there'" in texts, "tell line")
    check("bright blue 256 plain" in texts, "256-colour line")
    check(tail["prompt"] and tail["text"] == "HP:100> ", f"the GA prompt is the open tail: {tail}")

    # The game's next line ends the prompt, which then comes as a line of its own: the fake server asks Mudlet not to
    # echo commands (WILL ECHO), so nothing is typed onto its end
    msgs = []
    c.send({"t": "send", "text": "echo hi"})
    c.wait_for(shown("You said: hi"), collect=msgs)
    prompt = next((m for m in lines_of(msgs) if m["text"].startswith("HP:100>")), None)
    check(prompt is not None and prompt["prompt"] and prompt["text"] == "HP:100> ", f"the prompt is sent as a line: {prompt}")

    # What the UI shows is the console once the triggers ran
    msgs = []
    c.send({"t": "runLua", "id": 2, "code": "\n".join([
        'tempTrigger("gag me", function() deleteLine() end)',
        'tempTrigger("tells you", function() showNotification("Tell", line) end)',
        'tempTrigger("mark me", function() echo(" [seen]") end)',
        'tempTrigger("swap me", function() deleteLine() echo(" [swapped]") end)',
    ])})
    m = wait_status(c, "luaResult", lambda v: True, msgs)
    check(m["value"] == "ran", f"runLua ran in the profile: {m['value']}")
    msgs = []
    for command in ("tell", "echo gag me", "echo mark me", "echo before", "echo swap me", "echo done"):
        c.send({"t": "send", "text": command})
    c.wait_for(shown("You said: done"), collect=msgs)
    texts = texts_of(msgs)
    check("You said: mark me [seen]" in texts, f"a trigger's echo() is on its line: {texts}")
    check(not any("gag me" in t for t in texts), "a line a trigger deleted is not sent")
    check("You said: before [swapped]" in texts,
          f"an echo() after deleteLine() goes on the end of the line above, still open as the tail: {texts}")
    notes = [m for m in msgs if m["t"] == "notify"]
    check(bool(notes) and notes[0]["title"] == "Tell" and "tells you" in notes[0]["text"],
          f"showNotification() reaches the UI: {notes}")

    # Local text: whole lines, and an open one that the next line ends
    msgs = []
    c.send({"t": "runLua", "id": 3, "code": 'echo("local one\\n") cecho("<red>coloured<reset> two\\n") echo("open")'})
    c.wait_for(shown("open"), collect=msgs)
    lines = [m for m in lines_of(msgs) if m["text"] in ("local one", "coloured two")]
    check([m["text"] for m in lines] == ["local one", "coloured two"], f"echo() lines: {texts_of(msgs)}")
    check(len(lines) == 2 and lines[1]["runs"] and lines[1]["runs"][0][:2] == [0, 8] and lines[1]["runs"][0][2] != -1,
          f"cecho() colours carried: {lines[1]['runs'] if len(lines) == 2 else None}")
    msgs = []
    c.send({"t": "send", "text": "echo closed"})
    c.wait_for(shown("You said: closed"), collect=msgs)
    check("open" in [m["text"] for m in lines_of(msgs)], f"the game's next line ends the open echo(): {texts_of(msgs)}")
    last_seq = lines_of(msgs)[-1]["seq"]

    # The console's clear
    msgs = []
    c.send({"t": "runLua", "id": 4, "code": "clearWindow()"})
    c.wait_for(lambda m: m["t"] == "clear", collect=msgs)
    check(True, "clearWindow() sends clear")
    c.send({"t": "send", "text": "echo after"})
    c.send({"t": "send", "text": "echo next"})
    m = c.wait_for(lambda m: m["t"] == "line" and m["text"] == "You said: after")
    after_seq = m["seq"]

    # A UI that reattaches with lines from before the clear is told of it, and gets only what came after
    c.close()
    probe = Client(SOCK, hello=False)
    probe.send({"t": "hello", "v": 1, "client": "probe"})
    instance = probe.wait_for(lambda m: m["t"] == "hello")["instance"]
    probe.close()
    c = Client(SOCK, hello=False)
    c.send({"t": "hello", "v": 1, "client": "python-test", "instance": instance, "lastSeq": last_seq})
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    kinds = [m["t"] for m in msgs if m["t"] in ("clear", "line", "gap")]
    replayed = lines_of(msgs)
    check(kinds[:1] == ["clear"] and "gap" not in kinds, f"replay after a clear starts with clear, no gap: {kinds}")
    check(all(m["seq"] > last_seq for m in replayed) and any(m["seq"] == after_seq for m in replayed),
          f"replay holds only lines after the clear: {[(m['seq'], m['text']) for m in replayed]}")
    check(texts_of(msgs)[-1:] == ["You said: next"], f"and the open tail: {texts_of(msgs)[-1:]}")

    # A flood: every line, in order
    msgs = []
    c.send({"t": "send", "text": "flood 3000"})
    c.wait_for(shown("FLOOD DONE"), timeout=60, collect=msgs)
    flood = [t for t in texts_of(msgs) if t.startswith("Line ")]
    check(len(flood) == 3000 and all(t.startswith(f"Line {k:07d}") for k, t in enumerate(flood)),
          f"3000 flood lines in order: got {len(flood)}")
    tails = sum(1 for m in msgs if m["t"] == "tail")
    check(tails < 50, f"the tail is synced once a turn, not once a line: {tails} tail frames")

    # Errors from Lua reach the UI as status
    c.send({"t": "runLua", "id": 5, "code": "error('boom')"})
    # The error console prints it over two lines: where, then the message
    m = wait_status(c, "luaError", lambda v: "boom" in v, [], timeout=10)
    check(True, f"a Lua error is reported: {m['value']}")

    # SIGTERM quits the engine as Android's stop would, and the profile is saved (shutdownAsync) on the way out
    current = os.path.join(CONFIG, "profiles", "Engine Test", "current")
    check(not os.path.isdir(current), "nothing saved before the quit")
    proc.send_signal(signal.SIGTERM)
    try:
        code = proc.wait(20)
    except subprocess.TimeoutExpired:
        proc.kill()
        code = "hung"
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    saves = [f for f in os.listdir(current) if f.endswith(".xml")] if os.path.isdir(current) else []
    check(bool(saves), f"the profile was saved at exit: {saves}")
    if failures:
        print(f"\n{len(failures)} FAILED; engine log: {LOG}")
        with open(LOG, errors="replace") as f:
            print(f.read()[-4000:])
        sys.exit(1)
    print("\nall passed")


if __name__ == "__main__":
    main()
