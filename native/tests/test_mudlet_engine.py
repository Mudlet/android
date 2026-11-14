#!/usr/bin/env python3
"""Host test of the engine with libmudlet as the engine (MudletEngine on THeadlessSession), through the socket
protocol, against fake_telnet.py, in the clear and then over TLS, which needs the openssl command for a self-signed
certificate.

Build the engine against a host libmudlet first (scripts/build-libmudlet-desktop.sh, then this directory's CMake
with -DLIBMUDLET_BUILD_DIR=<that build>).

usage: test_mudlet_engine.py <path-to-engine-binary>
"""
import glob
import json
import os
import re
import signal
import ssl
import struct
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
import profile_checks  # noqa: E402
from protocol_client import PROTOCOL_VERSION, Client  # noqa: E402

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


def start_engine(profile="Engine Test"):
    # Mudlet's own files (its Lua, unpacked from the library, and Mudlet.ini outside the config dir) stay in TMP
    home = os.path.join(TMP, "home")
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", MUDLET_CONFIG_DIR=CONFIG, MUDLET_PROFILE=profile,
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


def stop_engine(proc):
    """SIGTERM, as Android's stop would quit the engine; the exit code, or "hung"."""
    proc.send_signal(signal.SIGTERM)
    try:
        return proc.wait(20)
    except subprocess.TimeoutExpired:
        proc.kill()
        return "hung"


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
    c.send({"t": "runLua", "id": 3, "code": 'echo("local one\\n") cecho("<red>coloured<reset> two\\n") '
                                             'echoLink("a link", [[echo("clicked")]], "hint") echo("\\n") echo("open")'})
    c.wait_for(shown("open"), collect=msgs)
    lines = [m for m in lines_of(msgs) if m["text"] in ("local one", "coloured two")]
    check([m["text"] for m in lines] == ["local one", "coloured two"], f"echo() lines: {texts_of(msgs)}")
    check(len(lines) == 2 and lines[1]["runs"] and lines[1]["runs"][0][:2] == [0, 8] and lines[1]["runs"][0][2] != -1,
          f"cecho() colours carried: {lines[1]['runs'] if len(lines) == 2 else None}")
    link = next((m for m in lines_of(msgs) if m["text"] == "a link"), None)
    check(link is not None and any(len(r) == 6 and r[:2] == [0, 6] and r[5] > 0 for r in link["runs"]),
          f"echoLink()'s run carries its link id: {link}")
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
    probe.send({"t": "hello", "v": PROTOCOL_VERSION, "client": "probe"})
    instance = probe.wait_for(lambda m: m["t"] == "hello")["instance"]
    probe.close()
    c = Client(SOCK, hello=False)
    c.send({"t": "hello", "v": PROTOCOL_VERSION, "client": "python-test", "instance": instance, "lastSeq": last_seq})
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

    # A line that neither a line feed nor GA has ended. While the game has sent no GA, Mudlet ends it once the game
    # has been quiet for the profile's network packet timeout (300 ms by default), and lineShown sends it, not as a
    # prompt: here a new connection, to a game that sends no GA
    login_port = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    c.send({"t": "connect", "host": "127.0.0.1", "port": login_port})
    m = c.wait_for(shown("Login: "), timeout=10)
    delay = time.perf_counter() - fake_telnet.SENT["banner"]
    check(not m["prompt"] and 0.2 <= delay < 1.5,
          f"a prompt without GA is shown after 300 ms of quiet, not as a prompt: {delay * 1000:.0f} ms, {m}")

    # Once the game has sent a GA, a line it leaves unfinished is shown only when it ends, as in the desktop GUI
    msgs = []
    first_seen = []

    def until_last_line(m):
        if m["t"] in ("line", "tail") and m["text"].startswith("Press return") and not first_seen:
            first_seen.append(time.perf_counter())
        return shown("last line")(m)

    c.send({"t": "send", "text": "ga-partial"})
    c.wait_for(until_last_line, timeout=10, collect=msgs)
    check(any(m["t"] == "tail" and m["prompt"] and m["text"] == "Ready> " for m in msgs), "the GA prompt is the tail")
    check("Press return: " in texts_of(msgs) and first_seen[0] >= fake_telnet.SENT["line-end"],
          f"after a GA, the unfinished line waits for its end: {texts_of(msgs)}")

    # Errors from Lua reach the UI as status
    c.send({"t": "runLua", "id": 5, "code": "error('boom')"})
    # The error console prints it over two lines: where, then the message
    m = wait_status(c, "luaError", lambda v: "boom" in v, [], timeout=10)
    check(True, f"a Lua error is reported: {m['value']}")

    # SIGTERM quits the engine as Android's stop would, and the profile is saved (shutdownAsync) on the way out
    current = os.path.join(CONFIG, "profiles", "Engine Test", "current")
    check(not os.path.isdir(current), "nothing saved before the quit")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    saves = [f for f in os.listdir(current) if f.endswith(".xml")] if os.path.isdir(current) else []
    check(bool(saves), f"the profile was saved at exit: {saves}")

    tls_checks(port)
    profile_requests(port)
    input_line()
    console()
    links()
    app_state()
    if failures:
        print(f"\n{len(failures)} FAILED; engine log: {LOG}")
        with open(LOG, errors="replace") as f:
            print(f.read()[-4000:])
        sys.exit(1)
    print("\nall passed")


def self_signed_context():
    """A TLS server context with a new self-signed certificate for localhost, as many games have."""
    key, cert = os.path.join(TMP, "server.key"), os.path.join(TMP, "server.crt")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost", "-keyout", key, "-out", cert],
                   check=True, capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    return ctx


def tls_checks(plain_port):
    """TLS through the engine's Qt and OpenSSL. Certificates are checked as in Mudlet's GUI: one that is not trusted ends
    the connection with the reasons, unless the profile accepts what is wrong with it (the GUI's "Accept self-signed
    certificates" and its other two boxes, which the app does not offer yet)."""
    tls_port = fake_telnet.start_in_thread(tls=self_signed_context())
    profile = "TLS Test"
    proc, c = start_engine(profile)
    c.wait_for(lambda m: m["t"] == "synced")

    # Refused: the certificate is self-signed. A secure connection needs the server's name, as its certificate names it.
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": tls_port, "tls": True})
    c.wait_for(lambda m: m["t"] in ("line", "tail") and "self-signed" in m["text"], timeout=30, collect=msgs)
    check(any("Socket got disconnected" in t for t in texts_of(msgs)), f"Mudlet's own words: {texts_of(msgs)[-3:]}")
    wait_status(c, "connection", lambda v: v == "disconnected", msgs, timeout=10)
    statuses = [(m["key"], m["value"]) for m in msgs if m["t"] == "status"]
    check(("connection", "connected") not in statuses, f"no connection over an untrusted certificate: {statuses}")
    check(not any("Red bold" in t for t in texts_of(msgs)), "and none of the game's text")
    check([r for r, _ in fake_telnet.TLS_HANDSHAKES] == ["failed"],
          f"the server saw the handshake end unfinished: {fake_telnet.TLS_HANDSHAKES}")

    # TLS to a port that does not speak it
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": plain_port, "tls": True})
    c.wait_for(lambda m: m["t"] in ("line", "tail") and "Secure handshake failed" in m["text"], timeout=30, collect=msgs)
    check(not any("Red bold" in t for t in texts_of(msgs)), "TLS to a plain port: the handshake fails, no game text")

    # The profile keeps the TLS it started with
    c.send({"t": "connect", "host": "localhost", "port": plain_port, "tls": False})
    m = wait_status(c, "connection", lambda v: v.startswith("error:"), [], timeout=10)
    check("started with TLS" in m["value"], f"a connect without TLS is refused: {m['value']}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    # Accepted once the profile accepts self-signed certificates, as its preferences would set it
    saves = sorted(glob.glob(os.path.join(CONFIG, "profiles", profile, "current", "*.xml")), key=os.path.getmtime)
    if not saves:
        check(False, "the TLS profile was saved at exit")
        return
    with open(saves[-1], encoding="utf-8") as f:
        xml = f.read()
    check(xml.count('mSslTsl="yes"') == 1, "the profile was saved with TLS on")
    check(xml.count('mSslIgnoreSelfSigned="no"') == 1, "and refusing self-signed certificates")
    with open(saves[-1], "w", encoding="utf-8") as f:
        f.write(xml.replace('mSslIgnoreSelfSigned="no"', 'mSslIgnoreSelfSigned="yes"'))
    proc, c = start_engine(profile)
    c.wait_for(lambda m: m["t"] == "synced")
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": tls_port, "tls": True})
    c.wait_for(lambda m: m["t"] == "tail" and m["text"].startswith("HP:100>"), timeout=30, collect=msgs)
    statuses = [(m["key"], m["value"]) for m in msgs if m["t"] == "status"]
    check(("connection", "connected") in statuses, "connected over TLS")
    check("Red bold normal green" in texts_of(msgs), "the banner came over TLS")
    msgs = []
    c.send({"t": "send", "text": "tls"})
    m = c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].startswith("TLS: "), collect=msgs)
    check(m["text"].startswith("TLS: TLSv1."), f"the game sees an encrypted connection: {m['text']}")
    check([r for r, _ in fake_telnet.TLS_HANDSHAKES] == ["failed", "ok"], f"handshakes: {fake_telnet.TLS_HANDSHAKES}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def mudlet_item(profile, item):
    """One of the files Mudlet's connection dialog writes in a profile's folder: a QString in a QDataStream."""
    with open(os.path.join(CONFIG, "profiles", profile, item), "rb") as f:
        data = f.read()
    (size,) = struct.unpack(">I", data[:4])
    return "" if size == 0xFFFFFFFF else data[4:4 + size].decode("utf-16-be")


def profile_requests(port):
    """The profile requests, in Mudlet's own files, with a switch of profiles while connected; and a password that
    reaches the game through Mudlet's auto-login, but no frame and not the engine's log."""
    proc, c = start_engine()
    c.wait_for(lambda m: m["t"] == "synced")
    password = "pw-Kq7-never-logged"

    def logged_in():
        # Mudlet sends the login 2 s after connecting, then the password 1 s later
        deadline = time.monotonic() + 15
        while password not in fake_telnet.RECEIVED and time.monotonic() < deadline:
            time.sleep(0.1)
        got = fake_telnet.RECEIVED
        check("hero" in got and password in got and got.index("hero") < got.index(password),
              f"Mudlet sent the profile's login, then the password, to the game ({len(got)} lines received)")

    seen = profile_checks.round_trips(c, check, port, password, lambda: len(fake_telnet.ACCEPTED), logged_in, timeout=30)
    profile_checks.password_kept_out(check, password, seen, LOG)
    items = {item: mudlet_item("Beta", item) for item in ("url", "port", "ssl_tsl", "login", "autoreconnect")}
    check(items == {"url": "127.0.0.1", "port": str(port), "ssl_tsl": "0", "login": "", "autoreconnect": "2"},
          f"a profile is stored as Mudlet's connection dialog stores it: {items}")
    check(not os.path.exists(os.path.join(CONFIG, "profiles", "Alpha")), "a deleted profile's folder is gone")

    # The connect of protocol version 1 connects the open profile
    msgs = []
    c.send({"t": "disconnect"})
    wait_status(c, "connection", lambda v: v == "disconnected", msgs)
    c.send({"t": "connect", "host": "127.0.0.1", "port": port})
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    check(("profile", "Beta closing") not in [(m["key"], m["value"]) for m in msgs if m["t"] == "status"],
          "connect connects the open profile, without a switch")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    check(password not in open(LOG, errors="replace").read(), "nor did it log the password on its way out")


def input_line():
    """hiddenInput and sendHidden from a game's password prompt, dismissHidden, history (kept by the profile's save, so
    an engine started again lists it), complete, and a script's printCmdLine() as commandLine. The game sends no
    WILL ECHO until asked for a password, as Mudlet keeps nothing in the history while the game echoes."""
    game = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    secret = "pw-Hid3-never-logged"
    profile = "Input Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check({"t": "hiddenInput", "wanted": False} in seen and {"t": "history", "lines": []} in seen,
          "a UI that attaches is told that input is not hidden, and the history, empty with no profile open")
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen)
    for command in ("echo alpha", "echo beta"):
        c.send({"t": "send", "text": command})
    c.wait_for(shown("You said: beta"), collect=seen)

    for request, expected, what in [({"text": "say alp"}, "say alpha", "a word the game sent"),
                                    ({"text": "echo a", "fromHistory": True}, "echo alpha", "a line from the history"),
                                    ({"text": "say zzz"}, "", "nothing, when nothing starts with it")]:
        c.send(dict(request, t="complete", id=f"complete {what}"))
        m = c.wait_for(lambda m: m["t"] == "status" and m.get("re") == f"complete {what}", collect=seen)
        check(m["key"] == "completion" and m["value"] == expected, f"complete gives {what}: {m}")

    c.send({"t": "runLua", "id": "cmdline", "code": 'printCmdLine("x")'})
    # Each send that empties Mudlet's copy of the line sends an empty commandLine too
    m = c.wait_for(lambda m: m["t"] == "commandLine" and m["text"], collect=seen)
    check(m["text"] == "x", f"printCmdLine() sets the input line: {m}")

    c.send({"t": "send", "text": "password"})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", timeout=10, collect=seen)
    check(m["wanted"] is True, "the game's WILL ECHO gives hiddenInput")
    c.send({"t": "sendHidden", "text": secret})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", timeout=10, collect=seen)
    check(m["wanted"] is False, "the game's WONT ECHO after the password ends it")
    # On the prompt's line, which no line break ended
    c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].endswith("Password accepted"), collect=seen)
    check(secret in fake_telnet.RECEIVED, "sendHidden reaches the game")

    c.send({"t": "send", "text": "password"})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", timeout=10, collect=seen)
    check(m["wanted"] is True, "WILL ECHO again gives hiddenInput")
    c.send({"t": "dismissHidden"})
    m = c.wait_for(lambda m: m["t"] in ("hiddenInput", "status") and (m["t"] == "hiddenInput" or m["key"] == "sendError"),
                   timeout=10, collect=seen)
    check(m == {"t": "hiddenInput", "wanted": False}, f"dismissHidden ends it: {m}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    history = [m["lines"] for m in msgs if m["t"] == "history"]
    check(history == [["password", "echo beta", "echo alpha"]],
          f"a UI that attaches gets the history, newest first, each once, without the password: {history}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    proc, c = start_engine(profile)
    msgs = []
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", msgs)
    m = c.wait_for(lambda m: m["t"] == "history", collect=msgs)
    seen += msgs
    check(m["lines"] == ["password", "echo beta", "echo alpha"], f"the profile's history outlives the engine: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    profile_checks.password_kept_out(check, secret, seen, LOG)



def wait_naws(size, timeout=5):
    """Whether the game's last NAWS becomes size within timeout: Mudlet tells it of a change once it has held 0.3 s."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if fake_telnet.NAWS_SIZES[-1:] == [size]:
            return True
        time.sleep(0.05)
    return False


def console():
    """setScreenSize to the game's NAWS, consoleColors from the profile and a script's setBackgroundColor(), and a
    script's announce()."""
    game = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    profile = "Console Test"
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    # Kept until the profile has loaded
    c.send({"t": "setScreenSize", "columns": 80, "rows": 30})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    m = wait_status(c, "consoleColors", lambda v: True, msgs)
    check(re.fullmatch(r"#[0-9a-f]{6} #[0-9a-f]{6}", m["value"]) is not None, f"consoleColors when the profile loads: {m}")
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    # Else the game's answer would continue the open "Login: " line
    c.wait_for(shown("Login: "), timeout=10, collect=msgs)

    c.send({"t": "send", "text": "naws"})
    c.wait_for(shown("NAWS asked"), collect=msgs)
    check(wait_naws((80, 30)), f"the game's DO NAWS is answered with the size given before the profile loaded: {fake_telnet.NAWS_SIZES}")
    c.send({"t": "setScreenSize", "columns": 72, "rows": 20})
    check(wait_naws((72, 20)), f"a new size reaches the game: {fake_telnet.NAWS_SIZES}")
    c.send({"t": "setScreenSize", "columns": 30, "rows": 20})
    check(wait_naws((40, 20)), f"a width below 40 is told as 40, as Mudlet does: {fake_telnet.NAWS_SIZES}")

    c.send({"t": "runLua", "id": "bg", "code": 'setBackgroundColor("main", 10, 20, 30)'})
    m = wait_status(c, "consoleColors", lambda v: True, msgs, timeout=10)
    check(m["value"].endswith(" #0a141e"), f"setBackgroundColor() on the main console changes consoleColors: {m}")

    c.send({"t": "runLua", "id": "announce", "code": 'announce("hi") announce("now", "importantmostrecent")'})
    got = [c.wait_for(lambda m: m["t"] == "announce", collect=msgs) for _ in range(2)]
    check(got == [{"t": "announce", "text": "hi", "assertive": False}, {"t": "announce", "text": "now", "assertive": True}],
          f"announce() gives announce, assertive for an important style: {got}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    colours = [m["value"] for m in msgs if m["t"] == "status" and m["key"] == "consoleColors"]
    check(len(colours) == 1 and colours[0].endswith(" #0a141e"), f"a UI that attaches is told the colours: {colours}")
    check(not any(m["t"] == "announce" for m in msgs), "but not the announcements it missed")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def link_ids(m):
    """The link ids of a line's or tail's runs, in order."""
    return [r[5] for r in m["runs"] if len(r) == 6 and r[5] > 0]


def wait_received(line, timeout=5):
    """Whether the game gets line within timeout."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if line in fake_telnet.RECEIVED:
            return True
        time.sleep(0.05)
    return False


def links():
    """getLink and followLink on a script's echoLink() and on a game's MXP <send> menu, linksChanged when a link
    changes, link ids a profile opened later keeps apart from the earlier one's, and the changes a UI missed."""
    game = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    proc, c = start_engine("Links Test")
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    c.wait_for(shown("Login: "), timeout=10, collect=msgs)

    def ask_link(request):
        c.send(dict(request, id="link"))
        return c.wait_for(lambda m: m.get("re") == "link" and m["t"] in ("link", "error"), collect=msgs)

    def linked_line(text):
        return c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].endswith(text) and link_ids(m), timeout=10, collect=msgs)

    c.send({"t": "runLua", "id": "echoLink", "code": 'echo("links: ") echoLink("x", [[send("y")]], "tip") echo("\\n")'})
    echo_id = link_ids(linked_line("links: x"))[-1]
    m = ask_link({"t": "getLink", "link": echo_id})
    check(m.get("t") == "link" and m["link"] == echo_id and m["tooltip"] == "tip" and not m["menu"] and not m["visited"]
          and m["items"] == [{"label": "tip", "enabled": True, "separator": False}], f"getLink gives echoLink()'s tooltip: {m}")
    msgs = []
    c.send({"t": "followLink", "link": echo_id, "id": "follow"})
    m = c.wait_for(lambda m: m["t"] == "linksChanged", timeout=10, collect=msgs)
    check(echo_id in m["ids"], f"followLink leaves it visited, which linksChanged tells: {m}")
    check(wait_received("y"), f"followLink runs its send(\"y\"), which reaches the game: {fake_telnet.RECEIVED[-3:]}")
    check(not any(m.get("re") == "follow" for m in msgs), f"a followLink that ran is not answered: {msgs}")
    m = ask_link({"t": "getLink", "link": echo_id})
    check(m.get("visited") is True, f"getLink then gives it visited: {m}")

    c.send({"t": "send", "text": "mxp"})
    mxp_id = link_ids(linked_line("Food: stew"))[-1]
    m = ask_link({"t": "getLink", "link": mxp_id})
    check(m.get("t") == "link" and m["menu"] and m["tooltip"] == "the stew"
          and m["items"] == [{"label": "Look", "enabled": True, "separator": False}, {"label": "Eat", "enabled": True, "separator": False}],
          f"getLink gives an MXP <send>'s menu: {m}")
    c.send({"t": "followLink", "link": mxp_id, "item": 1})
    check(wait_received("eat stew"), f"followLink with an item runs that item: {fake_telnet.RECEIVED[-3:]}")
    for request, message in [({"t": "followLink", "link": mxp_id, "item": 2}, "followLink: noSuchItem"),
                             ({"t": "getLink", "link": 99999}, "getLink: unknown"),
                             ({"t": "followLink", "link": 99999}, "followLink: unknown")]:
        m = ask_link(request)
        check(m == {"t": "error", "message": message, "re": "link"}, f"{request} gets {message!r}: {m}")

    m = c.ask({"t": "saveProfile", "name": "Links Other", "host": "127.0.0.1", "port": game, "tls": False, "login": "",
               "autoReconnect": False})
    check(m["t"] == "profiles", f"saveProfile: {m}")
    m = c.ask({"t": "openProfile", "name": "Links Other", "connect": False})
    check(m["t"] == "profiles", f"openProfile: {m}")
    wait_status(c, "profile", lambda v: v == "Links Other loaded", msgs, timeout=60)
    c.send({"t": "runLua", "id": "echoLink2", "code": 'echo("other: ") echoLink("z", [[send("w")]], "other tip") echo("\\n")'})
    other_id = link_ids(linked_line("other: z"))[-1]
    check(other_id not in (echo_id, mxp_id), f"the next profile's link ids are not the last one's: {other_id}, {echo_id}, {mxp_id}")
    m = ask_link({"t": "getLink", "link": echo_id})
    check(m == {"t": "error", "message": "getLink: unknown", "re": "link"}, f"an id from the profile before names no link: {m}")
    m = ask_link({"t": "getLink", "link": other_id})
    check(m.get("tooltip") == "other tip", f"one of this profile's does: {m}")

    # Emptying the console lets go of the link while no UI is attached
    c.send({"t": "runLua", "id": "clear", "code": 'tempTimer(0.5, [[clearWindow()]])'})
    wait_status(c, "luaResult", lambda v: True, msgs)
    c.close()
    time.sleep(1.5)
    for missed in (True, False):
        c = Client(SOCK)
        msgs = []
        c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
        changed = [m for m in msgs if m["t"] == "linksChanged"]
        if missed:
            check(changed == [{"t": "linksChanged", "ids": []}], f"a UI that attaches after a change it missed is told any link may have changed: {changed}")
        else:
            check(changed == [], f"one that missed nothing is not: {changed}")
        c.close()
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def send_together(c, *requests):
    """In one write, so the engine reads them all before its event loop runs again."""
    payloads = [json.dumps(r, separators=(",", ":")).encode() for r in requests]
    c.sock.sendall(b"".join(struct.pack(">I", len(p)) + p for p in payloads))


def newest_save(profile):
    """The newest of the profile's saves, and its mtime: (None, 0) for none."""
    saves = glob.glob(os.path.join(CONFIG, "profiles", profile, "current", "*.xml"))
    if not saves:
        return None, 0
    newest = max(saves, key=os.path.getmtime)
    return newest, os.path.getmtime(newest)


def app_state():
    """setAppState: sysApplicationFocusChangeEvent, and a save each time the app leaves the front; attention from alert()
    and from game text while minimized, with the profile's "notify on new data while minimized" on; and moveTypedLine
    for a line typed ahead of a password prompt."""
    game = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    profile = "App State Test"
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    c.wait_for(shown("Login: "), timeout=10, collect=msgs)
    c.send({"t": "runLua", "id": "focus", "code": 'registerAnonymousEventHandler("sysApplicationFocusChangeEvent", '
                                                  'function(_, active) echo("focus " .. tostring(active) .. "\\n") end)'})
    m = wait_status(c, "luaResult", lambda v: True, msgs)
    check(m["value"] == "ran", f"the focus handler is registered: {m}")

    def focus(active):
        return c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].endswith(f"focus {active}"), timeout=10, collect=msgs)

    check(newest_save(profile)[0] is None, "nothing saved before the app leaves the front")
    c.send({"t": "setAppState", "active": False, "minimized": True})
    focus("false")
    m = wait_status(c, "save", lambda v: True, msgs)
    first, first_mtime = newest_save(profile)
    check(m["value"] == "saved" and first is not None, f"leaving the front raises the focus event and saves the profile: {m}, {first}")
    c.send({"t": "setAppState", "active": True, "minimized": False})
    focus("true")
    back = len(msgs)
    time.sleep(1.2)
    c.send({"t": "setAppState", "active": False, "minimized": False})
    focus("false")
    check(not any(m["t"] == "status" and m["key"] == "save" for m in msgs[back:]), "coming back to the front saves nothing")
    m = wait_status(c, "save", lambda v: True, msgs)
    second, second_mtime = newest_save(profile)
    check(m["value"] == "saved" and second_mtime > first_mtime, f"leaving it again saves again: {first_mtime} then {second_mtime}")
    c.send({"t": "setAppState", "active": True, "minimized": False})
    focus("true")

    # Mudlet refuses to close a profile while it saves, so a switch asked meanwhile waits for the save
    m = c.ask({"t": "saveProfile", "name": "App State Other", "host": "127.0.0.1", "port": game, "tls": False, "login": "",
               "autoReconnect": False}, collect=msgs)
    check(m["t"] == "profiles", f"saveProfile: {m}")
    mark = len(msgs)
    send_together(c, {"t": "setAppState", "active": False, "minimized": False},
                  {"t": "openProfile", "name": "App State Other", "connect": False})
    wait_status(c, "profile", lambda v: v == "App State Other loaded", msgs, timeout=60)
    seen = [(m["key"], m["value"]) for m in msgs[mark:] if m["t"] == "status" and m["key"] in ("save", "profile")]
    check(seen[:3] == [("profile", f"{profile} closing"), ("save", "saved"), ("profile", f"{profile} closed")],
          f"a switch asked during the save closes the profile once it is saved: {seen}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    # Mudlet's "notify on new data while minimized" has no Lua call: it is turned on in the save the next start loads
    newest, _ = newest_save(profile)
    with open(newest, encoding="utf-8") as f:
        xml = f.read()
    check(xml.count('mAlertOnNewData="no"') == 1, "the profile was saved with notify on new data off")
    with open(newest, "w", encoding="utf-8") as f:
        f.write(xml.replace('mAlertOnNewData="no"', 'mAlertOnNewData="yes"'))
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    c.wait_for(shown("Login: "), timeout=10, collect=msgs)

    c.send({"t": "runLua", "id": "alert", "code": "alert(2)"})
    m = c.wait_for(lambda m: m["t"] == "attention", collect=msgs)
    check(m == {"t": "attention", "ms": 2000}, f"alert() asks for attention for that long: {m}")
    c.send({"t": "send", "text": "echo in front"})
    c.wait_for(shown("You said: in front"), collect=msgs)

    # Typed ahead of the prompt: complete gives Mudlet the line as typed, and finds nothing to complete in it
    c.send({"t": "complete", "text": "zqxjv", "id": "typed"})
    m = wait_status(c, "completion", lambda v: True, msgs)
    check(m == {"t": "status", "key": "completion", "value": "", "re": "typed"}, f"nothing completes the typed line: {m}")
    c.send({"t": "runLua", "id": "pw", "code": 'send("password", false)'})
    c.wait_for(lambda m: m["t"] == "hiddenInput" and m["wanted"], timeout=10, collect=msgs)
    order = [m["t"] for m in msgs if m["t"] in ("moveTypedLine", "hiddenInput")]
    check(order == ["moveTypedLine", "hiddenInput"], f"the typed line is moved just before hiddenInput: {order}")
    c.send({"t": "sendHidden", "text": "secret"})
    c.wait_for(lambda m: m["t"] == "hiddenInput" and not m["wanted"], timeout=10, collect=msgs)
    check(not any(m["t"] == "attention" and m["ms"] == 0 for m in msgs), f"no attention for game text while in front: {msgs}")

    c.send({"t": "setAppState", "active": False, "minimized": True})
    wait_status(c, "save", lambda v: True, msgs)
    c.send({"t": "send", "text": "echo minimized"})
    m = c.wait_for(lambda m: m["t"] == "attention", timeout=10, collect=msgs)
    check(m == {"t": "attention", "ms": 0}, f"game text while minimized asks for attention until the player is back: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


if __name__ == "__main__":
    main()
