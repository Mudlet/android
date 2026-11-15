#!/usr/bin/env python3
"""Host test of the engine with libmudlet as the engine (MudletEngine on THeadlessSession), through the socket
protocol, against fake_telnet.py, in the clear and then over TLS, which needs the openssl command for a self-signed
certificate.

Build the engine against a host libmudlet first (scripts/build-libmudlet-desktop.sh, then this directory's CMake
with -DLIBMUDLET_BUILD_DIR=<that build>).

usage: test_mudlet_engine.py <path-to-engine-binary>
"""
import base64
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
import zlib

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
    script_windows()
    script_labels()
    button_bars()
    map_view()
    app_map()
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

    # Mudlet's "notify on new data while minimized" has no Lua call; it is on unless the player turned it off
    newest, _ = newest_save(profile)
    with open(newest, encoding="utf-8") as f:
        xml = f.read()
    check(xml.count('mAlertOnNewData="yes"') == 1, "the profile was saved with notify on new data on")
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
    order = [m["t"] for m in msgs if m["t"] == "moveTypedLine" or (m["t"] == "hiddenInput" and m["wanted"])]
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


def window_of(msgs, name, kind):
    """The last state the windows messages gave of a script window, or None."""
    found = None
    for m in msgs:
        if m["t"] == "windows":
            found = next((w for w in m["windows"] if w["name"] == name and w["kind"] == kind), found)
    return found


def windows_with(name, kind, pred=lambda w: True):
    return lambda m: m["t"] == "windows" and any(w["name"] == name and w["kind"] == kind and pred(w) for w in m["windows"])


def seen_or_next(c, pred, seen, since, timeout=5.0):
    """The first message from seen[since:] that pred takes, or else the next one to come. The session sends a turn's
    main console lines as it runs and its script windows, their text and command lines from the event loop after it,
    so a wait for one of those has already collected a line the same turn wrote. On a timeout, says what came."""
    found = next((m for m in seen[since:] if pred(m)), None)
    if found is not None:
        return found
    try:
        return c.wait_for(pred, timeout=timeout, collect=seen)
    except TimeoutError:
        came = [str(m)[:200] for m in seen[since:]]
        raise TimeoutError(f"message not received; since the request: {came}") from None


def script_windows():
    """What scripts draw: a Geyser MiniConsole and UserWindow as windows, with the console's text as windowText; the
    main window's size from setMainWindowSize, and the borders a script sets, as mainArea; placeUserWindow raising
    the resize event a script handles; a console's own command line both ways; hiding, closing and deleting; and all
    of it again for a UI that attaches."""
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Window Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check({"t": "windows", "full": True, "windows": []} in seen and not any(m["t"] == "mainArea" for m in seen),
          "with no profile open, a UI that attaches is told of no script windows, and no main area")
    m = c.ask({"t": "getWindowText", "window": "chat", "first": 0, "count": 5}, answers=("windowText", "error"), collect=seen)
    check(m["t"] == "error", f"getWindowText with no profile open is refused: {m}")
    c.send({"t": "placeUserWindow", "name": "side", "x": 0, "y": 0, "w": 10, "h": 10, "floating": False})
    m = c.wait_for(lambda m: m["t"] == "error", collect=seen)
    check(m["message"] == "placeUserWindow: no profile running", f"placeUserWindow with no profile open is refused: {m}")

    # Kept until the profile loads, which takes it
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    m = c.wait_for(lambda m: m["t"] == "mainArea", timeout=30, collect=seen)
    check(m["w"] == 1000 and m["h"] == 800 and (m["textX"], m["textY"], m["textW"], m["textH"]) == (0, 0, 1000, 800)
          and m["columns"] > 0 and m["rows"] > 0, f"the profile takes the size the UI gave before it loaded: {m}")
    columns = m["columns"]
    wait_status(c, "connection", lambda v: v == "connected", seen)

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'chat = Geyser.MiniConsole:new({name = "chat", x = 0, y = 0, width = "30%", height = "40%"})',
        'chat:cecho("<red>red<reset> plain\\n")',
        'side = Geyser.UserWindow:new({name = "side"})',
        'registerAnonymousEventHandler("sysUserWindowResizeEvent", function(_, width, height, window)',
        '  echo(string.format("resized %s %dx%d\\n", window, width, height))',
        'end)',
        'local mw, mh = getMainWindowSize()',
        'echo(string.format("main window %dx%d\\n", mw, mh))',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "windowText" and m["window"] == "chat" and m["lines"]
                     and m["lines"][0]["text"] == "red plain", seen, since)
    check("re" not in m and m["first"] == 0 and m["lineCount"] >= 1, f"chat:cecho() gives the console's text: {m}")
    runs = m["lines"][0]["runs"]
    check(bool(runs) and runs[0][:3] == [0, 3, 0x1000000 | 0xFF0000], f"with its colours: {runs}")
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("main window "), seen, since)
    check(m["text"] == "main window 1000x800", f"scripts read the size the UI gave as the main window's: {m['text']}")
    chat = window_of(seen, "chat", "miniConsole")
    check(chat is not None and (chat["x"], chat["y"], chat["w"], chat["h"]) == (0, 0, 300, 320) and chat["parent"] == ""
          and chat["shown"] and chat["visible"] and not chat["inDock"] and chat.get("columns", 0) > 0 and chat.get("fontSize", 0) > 0,
          f"a Geyser MiniConsole is a miniConsole at its place in the main window: {chat}")
    side = window_of(seen, "side", "userWindow")
    check(side is not None and side["inDock"] and "dockArea" in side and "columns" in side,
          f"a Geyser UserWindow is a userWindow in a dock: {side}")

    since = len(seen)
    c.send({"t": "placeUserWindow", "name": "side", "x": 10, "y": 20, "w": 250, "h": 400, "floating": False})
    seen_or_next(c, shown("resized side 250x400"), seen, since)
    seen_or_next(c, windows_with("side", "userWindow", lambda w: w["w"] == 250), seen, since)
    side = window_of(seen, "side", "userWindow")
    check((side["x"], side["y"], side["w"], side["h"]) == (10, 20, 250, 400),
          f"placeUserWindow places it, and raises sysUserWindowResizeEvent: {side}")
    since = len(seen)
    c.send({"t": "placeUserWindow", "name": "chat", "x": 0, "y": 0, "w": 10, "h": 10, "floating": False})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("placeUserWindow: ") and "chat" in m["message"], f"a mini console is no user window: {m}")

    since = len(seen)
    # The profile's mudlet-base-ui package keeps its container attached to the right border, so the text ends there
    c.send({"t": "runLua", "code": 'setBorderLeft(100)\necho(string.format("right border %d\\n", getBorderRight()))'})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("right border "), seen, since)
    right = int(m["text"].split()[-1])
    m = seen_or_next(c, lambda m: m["t"] == "mainArea", seen, since)
    check(m["w"] == 1000 and (m["textX"], m["textW"]) == (100, 900 - right) and m["columns"] < columns,
          f"setBorderLeft() moves the main console's text: {m}, right border {right}")
    since = len(seen)
    c.send({"t": "setMainWindowSize", "w": 1, "h": 1})
    m = seen_or_next(c, lambda m: m["t"] in ("error", "mainArea"), seen, since)
    check(m["t"] == "error" and m["message"].startswith("setMainWindowSize: "), f"a size with no room for a character is refused: {m}")
    since = len(seen)
    c.send({"t": "setMainWindowSize", "w": 1200, "h": 800})
    m = seen_or_next(c, lambda m: m["t"] == "mainArea", seen, since)
    check((m["w"], m["h"], m["textX"], m["textW"]) == (1200, 800, 100, 1100 - right),
          f"a size given while the profile runs is taken at once: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'enableCommandLine("chat")',
        'setCmdLineAction("chat", function(text) echo("acted " .. text .. "\\n") end)',
        'printCmdLine("chat", "typed")',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "windowCommandLine" and m["text"], seen, since)
    check(m == {"t": "windowCommandLine", "name": "chat", "text": "typed"}, f"printCmdLine() on a console's line: {m}")
    line = window_of(seen, "chat", "commandLine")
    check(line is not None and line["parent"] == "chat" and line.get("parentKind") == "miniConsole" and line["shown"],
          f"enableCommandLine() gives the console a commandLine: {line}")
    since = len(seen)
    c.send({"t": "sendWindowCommandLine", "name": "chat", "text": "look"})
    seen_or_next(c, shown("acted look"), seen, since)
    check(True, "sendWindowCommandLine runs the line's action")

    m = c.ask({"t": "getWindowText", "window": "chat", "first": 0, "count": 10}, answers=("windowText", "error"), collect=seen)
    check(m["t"] == "windowText" and m["window"] == "chat" and m["first"] == 0 and m["lines"][0]["text"] == "red plain",
          f"getWindowText answers with the rows: {m}")
    m = c.ask({"t": "getWindowText", "window": "nonesuch", "first": 0, "count": 10}, answers=("windowText", "error"),
              collect=seen)
    check(m["t"] == "error" and "nonesuch" in m["message"], f"and refuses a console there is not: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": "chat:hide()"})
    seen_or_next(c, windows_with("chat", "miniConsole", lambda w: not w["shown"]), seen, since)
    chat = window_of(seen, "chat", "miniConsole")
    line = window_of(seen, "chat", "commandLine")
    check(not chat["visible"] and line is not None and line["visible"] and not line["shown"],
          f"hiding it hides what it holds: {chat} {line}")
    since = len(seen)
    c.send({"t": "closeUserWindow", "name": "side"})
    seen_or_next(c, windows_with("side", "userWindow", lambda w: not w["visible"]), seen, since)
    check(True, "closeUserWindow hides the user window")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    full = [m for m in msgs if m["t"] == "windows"]
    keys = {(w["name"], w["kind"]) for w in full[0]["windows"]} if full else set()
    check(len(full) == 1 and full[0]["full"] and {("chat", "miniConsole"), ("chat", "commandLine"), ("side", "userWindow")} <= keys,
          f"a UI that attaches gets every window: {full}")
    texts = {m["window"]: m for m in msgs if m["t"] == "windowText"}
    check("chat" in texts and "side" in texts and texts["chat"]["lines"][0]["text"] == "red plain",
          f"and each console's text: {list(texts)}")
    areas = [m for m in msgs if m["t"] == "mainArea"]
    check(len(areas) == 1 and (areas[0]["w"], areas[0]["textX"]) == (1200, 100), f"and the main area: {areas}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'deleteMiniConsole("chat")'})
    m = seen_or_next(c, lambda m: m["t"] == "windows" and m.get("removed"), seen, since)
    check({"name": "chat", "kind": "miniConsole"} in m["removed"], f"a deleted console is removed: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def png_file(path, width, height):
    """A PNG of width by height opaque red pixels."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rows = b"".join(b"\0" + b"\xff\0\0" * width for _ in range(height))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def png_size(data):
    """The width and height in a PNG's header, or None for what is not a PNG."""
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def label_of(msgs, name):
    """The last label message for a label, or None."""
    return next((m for m in reversed(msgs) if m["t"] == "label" and m["name"] == name), None)


def script_labels():
    """What labels show: a Geyser.Label's text, stylesheet, image and accessible name as label messages, the image as
    a PNG from getImage, and its click callback run by pointer; a Geyser.Gauge as three labels whose geometry gives
    its value, with its text label transparent; a deleted label removed; and every label again for a UI that
    attaches."""
    picture = os.path.join(TMP, "dot.png")
    png_file(picture, 4, 3)
    vector = os.path.join(TMP, "square.svg")
    with open(vector, "w") as f:
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16"/></svg>')
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Label Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    m = c.ask({"t": "getImage", "path": picture}, answers=("image", "error"), collect=seen)
    check(m["t"] == "image" and (m["w"], m["h"]) == (4, 3) and png_size(base64.b64decode(m["png"])) == (4, 3),
          f"getImage needs no profile, and answers with the file as a PNG: {str(m)[:200]}")
    c.send({"t": "pointer", "window": "main", "type": "move", "x": 1, "y": 1})
    m = c.wait_for(lambda m: m["t"] == "error", collect=seen)
    check(m["message"] == "pointer: no profile running", f"pointer with no profile open is refused: {m}")

    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=30)

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'box = Geyser.Label:new({name = "box", x = 20, y = 20, width = 120, height = 40})',
        'box:echo("hello label")',
        'box:setStyleSheet([[QLabel { border: 2px solid red; }]])',
        'box:setClickCallback(function(event) echo(string.format("clicked %s %d,%d\\n", event.button, event.x, event.y)) end)',
        'box:setAccessibleName("Hit me")',
        'box:setToolTip("a tip")',
        'pic = Geyser.Label:new({name = "pic", x = 200, y = 20, width = 40, height = 30})',
        'pic:echo("covered")',
        f'pic:setBackgroundImage([==[{picture}]==])',
        'ico = Geyser.Label:new({name = "ico", x = 260, y = 20, width = 32, height = 32})',
        f'ico:setBackgroundImage([==[{vector}]==])',
        'ico:setSvgTint(0, 128, 255)',
        'ico:setSvgRotation(90)',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "box" and m["accessibleName"] == "Hit me", seen, since)
    check("hello label" in m["text"] and m["styleSheet"] == "QLabel { border: 2px solid red; }" and m["accessibleButton"]
          and m["toolTip"] == "a tip" and m["image"] == "" and not m["clickThrough"] and m["movie"] == "",
          f"a Geyser.Label's echo(), stylesheet, click callback, accessible name and tool tip: {m}")
    first_label = next(i for i, m in enumerate(seen) if m["t"] == "label" and m["name"] == "box")
    first_window = next((i for i, m in enumerate(seen) if windows_with("box", "label")(m)), len(seen))
    check(first_window < first_label, "the label comes after the windows message that has it")
    box = window_of(seen, "box", "label")
    check(box is not None and (box["x"], box["y"], box["w"], box["h"]) == (20, 20, 120, 40) and box["shown"],
          f"and is a label window at its place: {box}")
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "pic" and m["image"], seen, since)
    check(m["image"] == picture and m["text"] == "" and not m["svg"], f"setBackgroundImage() shows the file in place of the text: {m}")
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "ico" and m.get("svgRotation"), seen, since)
    check(m["image"] == vector and m["svg"] and m.get("svgTint") == 0x1000000 | 0x0080FF and "svgTintAlpha" not in m
          and m["svgRotation"] == 90, f"an SVG one with its tint and rotation: {m}")

    m = c.ask({"t": "getImage", "path": picture, "w": 8, "h": 6}, answers=("image", "error"), collect=seen)
    check(m["t"] == "image" and (m["w"], m["h"]) == (8, 6) and png_size(base64.b64decode(m["png"])) == (8, 6)
          and m["path"] == picture, f"getImage scales it to the size asked: {str(m)[:200]}")
    m = c.ask({"t": "getImage", "path": picture, "frame": 0}, answers=("image", "error"), collect=seen)
    check(m["t"] == "image" and (m["w"], m["h"]) == (4, 3), f"a still image's frame 0 is the image: {str(m)[:200]}")
    m = c.ask({"t": "getImage", "path": picture, "frame": 1}, answers=("image", "error"), collect=seen)
    check(m["t"] == "error" and m["message"] == f"getImage: no frame 1 in {picture}", f"and it has no frame 1: {m}")
    m = c.ask({"t": "getImage", "path": os.path.join(TMP, "nonesuch.png")}, answers=("image", "error"), collect=seen)
    check(m["t"] == "error" and m["message"].startswith("getImage: ") and "nonesuch.png" in m["message"],
          f"and refuses a file there is not: {m}")

    since = len(seen)
    c.send({"t": "pointer", "window": "box", "type": "press", "x": 5, "y": 3, "button": 1, "buttons": 1})
    c.send({"t": "pointer", "window": "box", "type": "release", "x": 5, "y": 3, "button": 1})
    c.send({"t": "runLua", "code": 'echo("after the click\\n")'})
    seen_or_next(c, shown("after the click"), seen, since)
    # A line, as the echo after it ends it
    clicks = [m["text"] for m in lines_of(seen[since:]) if m["text"].startswith("clicked")]
    errors = [m for m in seen[since:] if m["t"] == "error"]
    check(clicks == ["clicked LeftButton 5,3"] and not errors,
          f"a pointer press runs the label's click callback, with the event table, and the release does not: {clicks} {errors}")
    since = len(seen)
    c.send({"t": "pointer", "window": "nonesuch", "type": "press", "x": 5, "y": 3, "button": 1, "buttons": 1})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("pointer: ") and "nonesuch" in m["message"], f"pointer on a window there is not is refused: {m}")
    since = len(seen)
    c.send({"t": "pointer", "window": "box", "type": "move", "x": 500, "y": 3})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("pointer: "), f"and outside the window: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'box:echo("new words")'})
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "box" and "new words" in m["text"], seen, since)
    check(m["accessibleName"] == "Hit me" and not any(windows_with("box", "label")(w) for w in seen[since:]),
          f"a label whose content alone changed is sent again, with no windows message for it: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'hp = Geyser.Gauge:new({name = "hp", x = 10, y = 600, width = 200, height = 20})',
        'hp:setValue(30, 60, "30/60")',
    ])})
    seen_or_next(c, windows_with("hp_front", "label", lambda w: w["w"] == 100), seen, since)
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "hp_text" and "30/60" in m["text"], seen, since)
    back, front, text = (window_of(seen, f"hp_{part}", "label") for part in ("back", "front", "text"))
    check(back is not None and (back["parent"], back["x"], back["y"], back["w"], back["h"]) == ("", 10, 600, 200, 20)
          and back.get("bgAlpha") == 100 and "bg" in back, f"a Geyser.Gauge's back label is the gauge, half see-through: {back}")
    check((front["x"], front["y"], front["w"], front["h"]) == (10, 600, 100, 20) and "bgAlpha" not in front,
          f"its front label's width against the back's is the value: {front}")
    check(text is not None and (text["x"], text["w"]) == (10, 200) and text.get("bgAlpha") == 0
          and back["stack"] < front["stack"] < text["stack"], f"and its text label is transparent, on top: {text}")
    since = len(seen)
    c.send({"t": "runLua", "code": "hp:setValue(15, 60)"})
    seen_or_next(c, windows_with("hp_front", "label", lambda w: w["w"] == 50), seen, since)
    c.send({"t": "runLua", "code": 'echo("gauge sent\\n")'})
    seen_or_next(c, shown("gauge sent"), seen, since)
    front = window_of(seen, "hp_front", "label")
    resent = [m["name"] for m in seen[since:] if m["t"] == "label"]
    check((front["x"], front["w"]) == (10, 50) and not resent,
          f"a new value resizes the front label, and sends no label whose content stayed: {front} {resent}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    full = next((i for i, m in enumerate(msgs) if m["t"] == "windows" and m.get("full")), None)
    names = {m["name"]: i for i, m in enumerate(msgs) if m["t"] == "label"}
    check(full is not None and {"box", "pic", "ico", "hp_back", "hp_front", "hp_text"} <= set(names)
          and all(i > full for i in names.values()), f"a UI that attaches gets every label after the windows: {sorted(names)}")
    box = label_of(msgs, "box")
    check(box is not None and box["accessibleName"] == "Hit me" and "new words" in box["text"], f"as it was: {box}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'deleteLabel("pic")'})
    m = seen_or_next(c, lambda m: m["t"] == "windows" and m.get("removed"), seen, since)
    check({"name": "pic", "kind": "label"} in m["removed"] and label_of(seen[since:], "pic") is None,
          f"a deleted label is removed, with no label message: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def last_of(msgs, kind):
    """The last message of a kind, or None."""
    return next((m for m in reversed(msgs) if m["t"] == kind), None)


def bar_named(msg, name):
    return next((b for b in msg["bars"] if b["name"] == name), None) if msg else None


def command_named(msg, name):
    return next((k for k in msg["commands"] if k["name"] == name), None) if msg else None


def received_after(start, text, timeout=5):
    """What the game received from the start'th line on, once text is among it or the time is up."""
    deadline = time.monotonic() + timeout
    while text not in fake_telnet.RECEIVED[start:] and time.monotonic() < deadline:
        time.sleep(0.05)
    return fake_telnet.RECEIVED[start:]


def action_xml(name, children=(), folder=False, push=False, active=True, **fields):
    """An Action of a Mudlet package, with the elements XMLimport reads; fields fill the ones named."""
    yes = {True: "yes", False: "no"}
    values = dict(script="", css="", commandButtonUp="", commandButtonDown="", icon="", orientation=0, location=0,
                  buttonRotation=0, sizeX=0, sizeY=0, mButtonState=1, buttonColumn=1, buttonFillerOffset=0, posX=0, posY=0)
    values.update(fields)
    body = "".join(f"<{k}>{v}</{k}>" for k, v in values.items())
    return (f'<Action isActive="{yes[active]}" isFolder="{yes[folder]}" isPushButton="{yes[push]}" isFlatButton="no" '
            f'useCustomLayout="no"><name>{name}</name><packageName></packageName>{body}{"".join(children)}</Action>')


def button_bars():
    """What a package's buttons and commands give the UI: its bars, their buttons and a folder's menu as buttonBars, a
    press running a button's command and script and a push-down button going down and up; the commands addCommand()
    adds as commands, a click raising sysCommandClicked and ticking a checkable one, a disabled one refused; and both
    again for a UI that attaches."""
    package = os.path.join(TMP, "buttontest.xml")
    folder_icon = os.path.join(TMP, "more.png")
    lamp = action_xml("Lamp", push=True, css="QPushButton { color: blue; }", commandButtonDown="push on",
                      commandButtonUp="push off", script='echo(string.format("pushed %d\\n", getButtonState()))')
    more = action_xml("More", folder=True, icon=folder_icon, children=(
        action_xml("Look", commandButtonDown="look"), action_xml("Hidden", active=False)))
    tools = action_xml("Tools", folder=True, css="QToolBar { color: red; }", buttonColumn=2, buttonFillerOffset=1,
                       children=(lamp, more))
    with open(package, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE MudletPackage>\n<MudletPackage version="1.001">'
                f"<ActionPackage>{tools}</ActionPackage></MudletPackage>\n")
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Button Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check({"t": "buttonBars", "bars": []} in seen and {"t": "commands", "commands": []} in seen,
          "with no profile open, a UI that attaches is told of no button bars and no commands")
    for request in ({"t": "pressButton", "button": 1}, {"t": "activateCommand", "command": 1}):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")

    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=30)

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        f"local ok, err = installPackage([==[{package}]==])",
        'echo(string.format("installed %s %s\\n", tostring(ok), tostring(err)))',
        # Before Side, so the bars that have Side were read with it there
        'tempButtonToolbar("Float", 3, 0)',
        'tempButton("Float", "Fly", 0)',
        'tempButtonToolbar("Side", 1, 1)',
        'tempButton("Side", "Go", 1)',
    ])})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "buttonBars" and bar_named(m, "Tools") and bar_named(m, "Side"), seen, since,
                         timeout=15)
    except TimeoutError as e:
        check(False, f"installing a package with a bar, and tempButtonToolbar(), send buttonBars: {e}")
        stop_engine(proc)
        return
    tools, side, floating = bar_named(m, "Tools"), bar_named(m, "Side"), bar_named(m, "Float")
    check((tools["place"], tools["vertical"], tools["columns"], tools["fillerOffset"], tools["styleSheet"])
          == ("top", False, 2, 1, "QToolBar { color: red; }") and [b["name"] for b in tools["buttons"]] == ["Lamp", "More"],
          f"a package's bar at the top, with its grid, stylesheet and buttons: {tools}")
    lamp, more = tools["buttons"]
    check(lamp["pushDown"] and not lamp["checked"] and not lamp["menu"] and lamp["styleSheet"] == "QPushButton { color: blue; }"
          and lamp["children"] == [], f"a push-down button, up: {lamp}")
    check(more["menu"] and more["icon"] == folder_icon and [e["name"] for e in more["children"]] == ["Look"]
          and more["children"][0]["icon"] == folder_icon and more["children"][0]["styleSheet"] == "",
          f"a folder is a menu of its active buttons, each with the folder's icon: {more}")
    check((side["place"], side["vertical"]) == ("left", True) and [b["name"] for b in side["buttons"]] == ["Go"],
          f"tempButtonToolbar() at the left, vertical: {side}")
    check(floating is not None and (floating["place"], floating["vertical"]) == ("floating", False)
          and [b["name"] for b in floating["buttons"]] == ["Fly"], f"and one that floats, horizontal: {floating}")

    def lamp_down(down):
        return lambda m: m["t"] == "buttonBars" and any(b["id"] == lamp["id"] and b["checked"] == down
                                                        for b in (bar_named(m, "Tools") or {}).get("buttons", ()))

    since = len(seen)
    sent = len(fake_telnet.RECEIVED)
    c.send({"t": "pressButton", "button": lamp["id"]})
    seen_or_next(c, lamp_down(True), seen, since)
    seen_or_next(c, shown("pushed 2"), seen, since)
    got = received_after(sent, "push on")
    check("push on" in got, f"pressButton sends a push-down button's command for down, runs its script, which sees it down, "
          f"and the bars come again with it checked: {got}")
    since = len(seen)
    sent = len(fake_telnet.RECEIVED)
    c.send({"t": "pressButton", "button": lamp["id"]})
    seen_or_next(c, lamp_down(False), seen, since)
    seen_or_next(c, shown("pushed 1"), seen, since)
    got = received_after(sent, "push off")
    check("push off" in got, f"pressed again it comes up, with its command for up: {got}")
    sent = len(fake_telnet.RECEIVED)
    c.send({"t": "pressButton", "button": more["children"][0]["id"]})
    got = received_after(sent, "look")
    check("look" in got, f"a menu entry is pressed as a button: {got}")
    since = len(seen)
    c.send({"t": "pressButton", "button": 999999})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"] == "pressButton: The profile shows no button 999999.", f"a button the bars do not show is refused: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'speech = addCommand{name = "Speech", tooltip = "Read aloud", menuPath = "Tools/Voices", surfaces = {"menu"}}',
        "setCommandChecked(speech, false)",
        'registerAnonymousEventHandler("sysCommandClicked", function(_, id) echo(string.format("clicked command %d\\n", id)) end)',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "commands" and command_named(m, "Speech"), seen, since, timeout=10)
    speech = command_named(m, "Speech")
    check(speech["tooltip"] == "Read aloud" and speech["menuPath"] == "Tools/Voices" and speech["onMenu"]
          and not speech["onToolbar"] and speech["enabled"] and speech["checkable"] and not speech["checked"]
          and not speech["pinned"] and not speech["pulse"] and speech["icon"] == "" and "package" in speech,
          f"addCommand() and setCommandChecked() give a checkable command on the menu: {speech}")
    since = len(seen)
    c.send({"t": "activateCommand", "command": speech["id"]})
    seen_or_next(c, shown(f"clicked command {speech['id']}"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "commands" and (command_named(m, "Speech") or {}).get("checked"), seen, since)
    check(True, "activateCommand raises sysCommandClicked with its id, and ticks it")
    since = len(seen)
    c.send({"t": "runLua", "code": "disableCommand(speech)"})
    seen_or_next(c, lambda m: m["t"] == "commands" and (command_named(m, "Speech") or {}).get("enabled") is False, seen, since)
    m = c.ask({"t": "activateCommand", "command": speech["id"]}, answers=("error",), collect=seen)
    check(m["message"] == 'activateCommand: The command "Speech" is disabled.', f"a disabled command is refused: {m}")
    m = c.ask({"t": "activateCommand", "command": 999999}, answers=("error",), collect=seen)
    check(m["message"] == "activateCommand: The profile has no command 999999.", f"and one there is not: {m}")

    bars, commands = last_of(seen, "buttonBars"), last_of(seen, "commands")
    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    check(last_of(msgs, "buttonBars") == bars and last_of(msgs, "commands") == commands,
          "a UI that attaches gets the bars and commands as they were")
    since = len(seen)
    c.send({"t": "runLua", "code": "removeCommand(speech)"})
    seen_or_next(c, lambda m: m["t"] == "commands" and not command_named(m, "Speech"), seen, since)
    check(True, "a removed command is gone from commands")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def png_pixel(data, x, y):
    """The (r, g, b, a) of a pixel of an 8-bit RGB or RGBA PNG with no interlace, as Qt saves one."""
    width, height = png_size(data)
    chunks, at = {}, 8
    while at < len(data):
        length, kind = struct.unpack(">I4s", data[at:at + 8])
        chunks.setdefault(kind, []).append(data[at + 8:at + 8 + length])
        at += 12 + length
    depth, colour, interlace = chunks[b"IHDR"][0][8], chunks[b"IHDR"][0][9], chunks[b"IHDR"][0][12]
    if depth != 8 or colour not in (2, 6) or interlace:
        raise ValueError(f"a PNG of depth {depth}, colour type {colour}, interlace {interlace}")
    channels = 4 if colour == 6 else 3
    raw, stride = zlib.decompress(b"".join(chunks[b"IDAT"])), width * channels
    previous = bytearray(stride)
    for row in range(y + 1):
        kind, line = raw[row * (stride + 1)], bytearray(raw[row * (stride + 1) + 1:(row + 1) * (stride + 1)])
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up, corner = previous[i], previous[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + (left + up) // 2) & 0xFF
            elif kind == 4:
                guess = left + up - corner
                nearest = min((abs(guess - left), 0, left), (abs(guess - up), 1, up), (abs(guess - corner), 2, corner))[2]
                line[i] = (line[i] + nearest) & 0xFF
        previous = line
    pixel = tuple(previous[x * channels:(x + 1) * channels])
    return pixel if channels == 4 else pixel + (255,)


def map_view():
    """The 2D map as the UI gets it: a map a script builds in a Geyser.Mapper as map frames at the mapper's size, with
    the rooms where the layout puts them; mapPointer selecting a room, walking to it and zooming; setMapViewOnly
    switching to editing; pointer on the mapper window; the map to a UI that attaches, and none while it hides."""
    profile = "Map Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check(not any(m["t"] == "map" for m in seen), "with no profile open, a UI that attaches gets no map")
    for request in ({"t": "mapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, {"t": "setMapViewOnly", "viewOnly": False}):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")

    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    # Opened with no game: the base UI builds its dock on what a game first sends, and then puts the profile's one
    # mapper in a Geyser.Mapper of its own once the map has rooms, away from the one this test makes
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the map's profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    m = c.ask({"t": "mapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, answers=("error",), collect=seen)
    check(m["message"] == "mapPointer: No map is open; a script opens one, as with openMapWidget().",
          f"mapPointer before a script opens a map is refused: {m}")

    # Rooms 1 and 2 two apart, east and west of each other, the player in 1: at a zoom of 10 the 300 pixels high
    # mapper is 10 rooms high, so room 1 is in its middle and room 2 60 pixels to its right
    room1, room2 = (200, 150), (260, 150)
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'local area = addAreaName("Map Test")',
        "setCustomEnvColor(301, 200, 30, 30, 255)",
        "for id, x in pairs({[1] = 0, [2] = 2}) do",
        "  addRoom(id)",
        "  setRoomArea(id, area)",
        "  setRoomCoordinates(id, x, 0, 0)",
        "end",
        "setRoomEnv(2, 301)",
        'setExit(1, 2, "e")',
        'setExit(2, 1, "w")',
        'testMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})',
        "setMapZoom(10, area)",
        "centerview(1)",
        'function doSpeedWalk() echo("walk " .. table.concat(speedWalkDir, ",") .. " to " .. table.concat(speedWalkPath, ",") .. "\\n") end',
        'registerAnonymousEventHandler("sysMapWindowMousePressEvent", function() echo("map pressed\\n") end)',
        'registerAnonymousEventHandler("mapModeChangeEvent", function(_, mode) echo("map mode " .. mode .. "\\n") end)',
        "function selected() local rooms = getMapSelection().rooms or {} table.sort(rooms) echo(\"selected \" .. table.concat(rooms, \",\") .. \"\\n\") end",
    ])})

    def room2_drawn(m):
        return (m["t"] == "map" and (m["w"], m["h"]) == (400, 300)
                and png_pixel(base64.b64decode(m["png"]), *room2)[:3] == (200, 30, 30))

    try:
        m = seen_or_next(c, room2_drawn, seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"a Geyser.Mapper with rooms in it sends the map with room 2 drawn in its colour: {e}")
        stop_engine(proc)
        return
    png = base64.b64decode(m["png"])
    # The windows come from the event loop too, before or after the frame
    seen_or_next(c, windows_with("mapper", "mapper", lambda w: (w["w"], w["h"]) == (400, 300)), seen, since)
    check(png_size(png) == (400, 300) and m["viewOnly"] is True and png_pixel(png, room2[0], room2[1] + 20)[:3] != (200, 30, 30),
          f"a Geyser.Mapper sends the map at its size, viewed, with room 2 where the layout puts it and not below it: "
          f"{png_size(png)}, {m['viewOnly']}")

    def selection():
        c.send({"t": "runLua", "code": "selected()"})
        return seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("selected "), seen, since)["text"]

    since = len(seen)
    c.send({"t": "mapPointer", "type": "press", "x": room2[0], "y": room2[1], "button": 1})
    seen_or_next(c, shown("map pressed"), seen, since)
    got = selection()
    check(got == "selected 2", f"a mapPointer press selects the room under it and raises sysMapWindowMousePressEvent: {got}")
    since = len(seen)
    c.send({"t": "pointer", "window": "mapper", "type": "press", "x": room1[0], "y": room1[1], "button": 1, "buttons": 1})
    c.send({"t": "pointer", "window": "mapper", "type": "release", "x": room1[0], "y": room1[1], "button": 1})
    seen_or_next(c, shown("map pressed"), seen, since)
    got = selection()
    check(got == "selected 1" and not any(m["t"] == "error" for m in seen[since:]),
          f"a pointer press on the window mapper reaches the map, drawn at the window's size: {got}")

    since = len(seen)
    c.send({"t": "mapPointer", "type": "doubleClick", "x": room2[0], "y": room2[1], "button": 2})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"] == "mapPointer: Only a double-click of the left button acts on the map.",
          f"a double-click of the right button is refused: {m}")
    c.send({"t": "mapPointer", "type": "doubleClick", "x": room2[0], "y": room2[1], "button": 1})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("walk "), seen, since)
    check(m["text"] == "walk e to 2", f"a double-click on a room walks there through doSpeedWalk(): {m}")

    since = len(seen)
    c.send({"t": "mapPointer", "type": "wheel", "x": room2[0], "y": room2[1], "wheelY": 59})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"] == "mapPointer: Less than half a notch of the wheel does not zoom the map.",
          f"less than half a notch is refused: {m}")
    since = len(seen)
    c.send({"t": "mapPointer", "type": "wheel", "x": room2[0], "y": room2[1], "wheelY": -600})
    c.send({"t": "runLua", "code": 'echo(string.format("zoom %.2f\\n", getMapZoom()))'})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("zoom "), seen, since)
    # The map is drawn again from the event loop, before or after the line of the next request's turn
    before = last_of(seen[:since], "map")
    seen_or_next(c, lambda f: f["t"] == "map" and f["png"] != before["png"], seen, since)
    check(m["text"] == "zoom 14.03", f"five notches towards the player zoom out, as the GUI's wheel does, and the map comes again: {m}")

    since = len(seen)
    c.send({"t": "setMapViewOnly", "viewOnly": False})
    seen_or_next(c, shown("map mode editing"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "map" and m["viewOnly"] is False, seen, since)
    check(True, "setMapViewOnly false raises mapModeChangeEvent and sends the map, edited")
    m = c.ask({"t": "setMapViewOnly", "viewOnly": "no"}, answers=("error",), collect=seen)
    check(m["message"] == "setMapViewOnly: viewOnly must be true or false", f"a viewOnly that is no boolean is refused: {m}")

    def attach():
        nonlocal c
        c.close()
        c = Client(SOCK)
        msgs = []
        c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
        seen.extend(msgs)
        return msgs

    msgs = attach()
    kinds = [m["t"] for m in msgs]
    maps = [m for m in msgs if m["t"] == "map"]
    check(len(maps) == 1 and (maps[0]["w"], maps[0]["h"], maps[0]["viewOnly"]) == (400, 300, False)
          and kinds.index("windows") < kinds.index("map") < kinds.index("buttonBars"),
          f"a UI that attaches gets the map, after the windows: {kinds}")

    since = len(seen)
    c.send({"t": "runLua", "code": "testMapper:hide()"})
    seen_or_next(c, windows_with("mapper", "mapper", lambda w: not w["shown"] or (w["w"], w["h"]) == (0, 0)), seen, since)
    msgs = attach()
    check(not any(m["t"] == "map" for m in msgs), "a hidden mapper gives a UI that attaches no map")
    # Geyser's hide() leaves the mapper shown at 0 by 0, so showing it again only sizes it, and no repaint follows:
    # the map comes from the windows' sizes
    since = len(seen)
    c.send({"t": "runLua", "code": "testMapper:show()"})
    seen_or_next(c, lambda m: m["t"] == "map" and (m["w"], m["h"]) == (400, 300), seen, since)
    check(True, "shown again, it sends the map again")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def app_map():
    """The app's own map, with no script mapper: setAppMapSize drawing the profile's map as appMap frames at the size and
    scale asked for, with nothing to draw before a profile or rooms; appMapPointer selecting and walking without the
    script's mapper events; findRooms by name and id; showRoom centring the map on a room; setMapViewOnly with only
    this map open; the frame to a UI that attaches; and 0 by 0 stopping it."""
    profile = "App Map Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check(not any(m["t"] == "appMap" for m in seen), "a UI that attaches before asking for the app's map gets none")
    empty = {"t": "appMap", "png": "", "w": 0, "h": 0, "viewOnly": True}
    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 2})
    m = seen_or_next(c, lambda m: m["t"] in ("appMap", "error"), seen, since)
    check(m == empty, f"with no profile open the app's map has nothing to draw: {m}")
    for request in ({"t": "appMapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, {"t": "showRoom", "room": 1},
                    {"t": "findRooms", "text": "hall"}):
        m = c.ask(request, answers=("error", "rooms"), collect=seen)
        check(m.get("message") == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    m = c.ask({"t": "setAppMapSize", "w": 3000, "h": 10, "scale": 2}, answers=("error",), collect=seen)
    check(m["message"] == "setAppMapSize: the map at 3000x10 by 2 is larger than 4096 pixels a side",
          f"a map larger than a PNG may be is refused: {m}")

    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the app map's profile is made: {m}")
    since = len(seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    m = c.ask({"t": "appMapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, answers=("error",), collect=seen)
    check(m["message"] == "appMapPointer: The app's map has not been drawn yet; the position is in what renderAppMap() drew.",
          f"a press on a map with no rooms, so never drawn, is refused: {m}")
    check(not any(m["t"] == "appMap" and m["w"] for m in seen[since:]), "a profile whose map has no rooms draws nothing")

    # As in map_view(): rooms 1 and 2 two apart, the player in 1, but at the area's first zoom of 20, as setMapZoom()
    # needs a script's mapper: on a map 300 high room 1 is in the middle and room 2 30 pixels to its right; the PNG is
    # at twice that. Nothing opens a script mapper.
    room1, room2, middle = (200, 150), (230, 150), (200, 150)
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'local area = addAreaName("App Map Area")',
        "setCustomEnvColor(301, 200, 30, 30, 255)",
        "for id, x in pairs({[1] = 0, [2] = 2, [3] = 0}) do",
        "  addRoom(id)",
        "  setRoomArea(id, area)",
        "  setRoomCoordinates(id, x, id == 3 and 4 or 0, 0)",
        "end",
        'setRoomName(1, "Great Hall")',
        'setRoomName(2, "Kitchen")',
        'setRoomName(3, "hallway")',
        "setRoomEnv(2, 301)",
        'setExit(1, 2, "e")',
        'setExit(2, 1, "w")',
        "centerview(1)",
        "updateMap()",
        'function doSpeedWalk() echo("walk " .. table.concat(speedWalkDir, ",") .. " to " .. table.concat(speedWalkPath, ",") .. "\\n") end',
        'registerAnonymousEventHandler("sysMapWindowMousePressEvent", function() echo("map pressed\\n") end)',
        'registerAnonymousEventHandler("mapModeChangeEvent", function(_, mode) echo("map mode " .. mode .. "\\n") end)',
    ])})

    def pixel(m, point):
        return png_pixel(base64.b64decode(m["png"]), point[0] * 2, point[1] * 2)

    # Only in a frame at the scale asked for, whose pixels the points are read at
    def red_at(m, point):
        return png_size(base64.b64decode(m["png"])) == (800, 600) and pixel(m, point)[:3] == (200, 30, 30)

    # Its size first: the pixels below are read at the scale asked for, so a PNG at another size has none of them
    try:
        m = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"], seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"a map with rooms sends the app's map: {e}")
        stop_engine(proc)
        return
    png = base64.b64decode(m["png"])
    sized = (m["w"], m["h"], m["viewOnly"]) == (400, 300, True) and png_size(png) == (800, 600)
    check(sized, f"the app's map comes at the size asked for, its PNG at the scale: "
                 f"{m['w']}x{m['h']} {png_size(png)} {m['viewOnly']}")
    if not sized:
        stop_engine(proc)
        return
    try:
        m = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and red_at(m, room2), seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"a map with rooms sends the app's map with room 2 drawn in its colour: {e}")
        stop_engine(proc)
        return
    first = m
    check(not red_at(m, middle), f"and the player's room is in the middle, not room 2: {pixel(m, middle)}")
    check(not any(m["t"] == "map" for m in seen), "and no script map is sent, as no script opened a mapper")

    m = c.ask({"t": "findRooms", "text": "HALL"}, answers=("rooms", "error"), collect=seen)
    check(m == {"t": "rooms", "text": "HALL", "more": False, "re": m.get("re"),
                "rooms": [{"id": 1, "name": "Great Hall", "area": "App Map Area"}, {"id": 3, "name": "hallway", "area": "App Map Area"}]},
          f"findRooms finds the rooms whose name holds the text, ignoring case, by name: {m}")
    m = c.ask({"t": "findRooms", "text": "2"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [2], f"and the room whose id it is: {m}")
    m = c.ask({"t": "findRooms", "text": "cellar"}, answers=("rooms", "error"), collect=seen)
    check(m.get("rooms") == [] and m.get("more") is False, f"and none when none match: {m}")

    since = len(seen)
    c.send({"t": "appMapPointer", "type": "press", "x": room2[0], "y": room2[1], "button": 1})
    c.send({"t": "appMapPointer", "type": "doubleClick", "x": room2[0], "y": room2[1], "button": 1})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail", "error") and (m["t"] == "error" or m["text"].startswith("walk ")), seen, since)
    check(m.get("text") == "walk e to 2", f"a double-click on a room of the app's map walks there: {m}")
    c.send({"t": "runLua", "code": 'echo("marker\\n")'})
    seen_or_next(c, shown("marker"), seen, since)
    check(not any(m["t"] in ("line", "tail") and m["text"] == "map pressed" for m in seen[since:]),
          "a press on the app's map raises no sysMapWindowMousePressEvent, as it is not a script's map")

    # Room 2 is selected now, so drawn shaded rather than red: room 1, the player's, is what shows where the map moved
    player = pixel(first, middle)
    since = len(seen)
    c.send({"t": "showRoom", "room": 2})
    left = (room1[0] - (room2[0] - room1[0]), room1[1])
    m = seen_or_next(c, lambda m: m["t"] == "error" or (m["t"] == "appMap" and m["w"] and pixel(m, left) == player), seen, since)
    check(m["t"] == "appMap" and pixel(m, middle) != player, f"showRoom centres the app's map on that room: {m.get('message', m['t'])}")
    m = c.ask({"t": "showRoom", "room": 99}, answers=("error",), collect=seen)
    check(m["message"] == "showRoom: There is no room 99 in the map.", f"showRoom of a room not in the map is refused: {m}")

    since = len(seen)
    c.send({"t": "setMapViewOnly", "viewOnly": False})
    seen_or_next(c, shown("map mode editing"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "appMap" and m["viewOnly"] is False, seen, since)
    check(True, "setMapViewOnly works with only the app's map open, and its frame comes again, edited")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    kinds = [m["t"] for m in msgs]
    check(kinds.count("appMap") == 1 and kinds.index("windows") < kinds.index("appMap") < kinds.index("buttonBars"),
          f"a UI that attaches gets the app's map, after the windows: {kinds}")

    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 0, "h": 0, "scale": 1})
    m = seen_or_next(c, lambda m: m["t"] in ("appMap", "error"), seen, since)
    check(m == empty, f"0 by 0 stops the app's map: {m}")
    m = c.ask({"t": "appMapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, answers=("error",), collect=seen)
    check(m["message"].startswith("appMapPointer: The app's map has not been drawn yet"), f"and lets its view go: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


if __name__ == "__main__":
    main()
