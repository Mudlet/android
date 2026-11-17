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
import http.server
import json
import os
import re
import signal
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
import profile_checks  # noqa: E402
from protocol_client import PROTOCOL_VERSION, Client, Closed  # noqa: E402

ENGINE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build-desktop-mudlet/enginehost/engine")
TMP = tempfile.mkdtemp(prefix="mudlet-engine-test-")
SOCK = os.path.join(TMP, "engine.sock")
CONFIG = os.path.join(TMP, "config")
LOG = os.path.join(TMP, "engine.log")
BOLD = 1
failures = []
engines = []
# Secrets a check and the engine log's dump show as <label>, never as they are: label to secret
masked_secrets = {}


def masked(text):
    """text with each of masked_secrets as its label, the longest first."""
    for label, secret in sorted(masked_secrets.items(), key=lambda item: -len(item[1])):
        text = text.replace(secret, f"<{label}>")
    return text


def check(cond, what):
    what = masked(what)
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
    engines.append(proc)
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
    checks = engine_basics
    try:
        for checks in (engine_basics, tls_checks, profile_requests):
            checks(port)
        for checks in (bridge_refusals, input_line, console, screen_and_main_window, links, app_state, script_windows, window_links,
                       script_labels, label_links, script_text_boxes, frame_tabs, button_bars, map_view, app_map,
                       map_info, map_moves, map_rooms, map_edits, map_download, profile_settings, script_items, sounds,
                       packages, error_console):
            checks()
        checks = secure_offer
        checks(port)
    except Exception as e:
        # A wait that timed out, or an engine that did not start: the rest cannot run, but the log says why
        check(False, f"stopped in {checks.__name__}: {e!r}")
        for proc in engines:
            if proc.poll() is None:
                proc.kill()
    if failures:
        print(f"\n{len(failures)} FAILED; engine log: {LOG}")
        with open(LOG, errors="replace") as f:
            print(masked(f.read())[-4000:])
        sys.exit(1)
    print("\nall passed")


def engine_basics(port):
    """The main console through the protocol: the banner, prompts, triggers, Lua, and the save at SIGTERM."""
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

    # The ring: a UI that attaches afresh is replayed the last 2000 lines (scrollbackLines) and a gap for the rest; one
    # that names the instance and the last seq it has, only the lines it lacks
    c.close()
    c = Client(SOCK)
    msgs = []
    synced = c.wait_for(lambda m: m["t"] == "synced", timeout=20, collect=msgs)
    replayed = lines_of(msgs)
    check(len(replayed) == 2000 and all(m.get("replay") for m in replayed) and any(m["t"] == "gap" for m in msgs),
          f"a fresh UI is replayed the 2000-line ring and a gap: {len(replayed)} lines")
    check("FLOOD DONE" in texts_of(msgs)[-2:], f"ending with the last lines seen: {texts_of(msgs)[-2:]}")
    c.close()
    c = Client(SOCK, hello=False)
    c.send({"t": "hello", "v": PROTOCOL_VERSION, "client": "python-test", "instance": instance, "lastSeq": synced["seq"] - 3})
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seqs = [m["seq"] for m in lines_of(msgs)]
    check(seqs == [synced["seq"] - 2, synced["seq"] - 1, synced["seq"]], f"a UI that sends instance and lastSeq gets only the lines it lacks: {seqs}")

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


def self_signed_context(host="localhost"):
    """A TLS server context with a new self-signed certificate for host, as many games have."""
    key, cert = os.path.join(TMP, f"{host}.key"), os.path.join(TMP, f"{host}.crt")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", f"/CN={host}",
                    "-addext", f"subjectAltName=DNS:{host}", "-keyout", key, "-out", cert],
                   check=True, capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    return ctx


def expired_context():
    """A TLS server context with a self-signed certificate for localhost that ran out on 2 January 2020."""
    folder = os.path.join(TMP, "expired")
    os.makedirs(folder, exist_ok=True)
    key, csr, cert = (os.path.join(folder, n) for n in ("server.key", "server.csr", "server.crt"))
    with open(os.path.join(folder, "index.txt"), "w", encoding="utf-8"):
        pass
    with open(os.path.join(folder, "serial"), "w", encoding="utf-8") as f:
        f.write("01\n")
    config = os.path.join(folder, "ca.cnf")
    with open(config, "w", encoding="utf-8") as f:
        f.write(f"[ca]\ndefault_ca = d\n[d]\ndatabase = {folder}/index.txt\nnew_certs_dir = {folder}\nserial = {folder}/serial\n"
                "default_md = sha256\npolicy = p\ncopy_extensions = copy\nunique_subject = no\n[p]\ncommonName = supplied\n")
    subprocess.run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost", "-keyout", key, "-out", csr], check=True, capture_output=True)
    # openssl req takes no date in the past; openssl ca does
    subprocess.run(["openssl", "ca", "-batch", "-config", config, "-selfsign", "-keyfile", key, "-in", csr, "-out", cert,
                    "-startdate", "20200101000000Z", "-enddate", "20200102000000Z"], check=True, capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    return ctx


def tls_checks(plain_port):
    """TLS through the engine's Qt and OpenSSL. Certificates are checked as in Mudlet's GUI: one that is not trusted ends
    the connection with the reasons, unless the profile accepts what is wrong with it (the GUI's "Accept self-signed
    certificates" and its other two boxes). The certificate refused is passed on, certificateRefused, with what the
    GUI's preferences show of it: a self-signed one, then, once self-signed ones are accepted, an expired one and one
    for another server."""
    tls_port = fake_telnet.start_in_thread(tls=self_signed_context())
    expired_port = fake_telnet.start_in_thread(tls=expired_context())
    elsewhere_port = fake_telnet.start_in_thread(tls=self_signed_context("elsewhere.invalid"))
    profile = "TLS Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    told = [m for m in seen if m["t"] == "certificateRefused"]
    check(told == [{"t": "certificateRefused", "refused": False}], f"an attach is told no certificate was refused: {told}")

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
    m = c.wait_for(lambda m: m["t"] == "certificateRefused", timeout=10, collect=msgs)
    check(m.get("refused") is True and m.get("selfSigned") is True and m.get("expired") is False and m.get("others") is False,
          f"the self-signed certificate is passed on as refused: {m}")
    check(m.get("issuedTo") == "localhost" and m.get("issuer") == "localhost" and m.get("serial", "") != ""
          and m.get("expires", "").endswith("Z"), f"with what the GUI's preferences show of it: {m}")
    check(any("self-signed" in e for e in m.get("errors", [])), f"and Mudlet's reasons: {m.get('errors')}")

    # TLS to a port that does not speak it
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": plain_port, "tls": True})
    c.wait_for(lambda m: m["t"] in ("line", "tail") and "Secure handshake failed" in m["text"], timeout=30, collect=msgs)
    check(not any("Red bold" in t for t in texts_of(msgs)), "TLS to a plain port: the handshake fails, no game text")
    wait_status(c, "connection", lambda v: v == "disconnected", msgs, timeout=10)
    told = [m["refused"] for m in msgs if m["t"] == "certificateRefused"]
    check(told == [False], f"the refused certificate is forgotten as the next connection is tried, which had none: {told}")

    # The profile keeps the TLS it started with
    c.send({"t": "connect", "host": "localhost", "port": plain_port, "tls": False})
    m = wait_status(c, "connection", lambda v: v.startswith("error:"), [], timeout=10)
    check("connects with TLS" in m["value"], f"a connect without TLS is refused: {m['value']}")
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

    # An expired certificate is still refused: accepting self-signed ones lets only that error past
    c.send({"t": "disconnect"})
    wait_status(c, "connection", lambda v: v == "disconnected", [], timeout=10)
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": expired_port, "tls": True})
    m = c.wait_for(lambda m: m["t"] == "certificateRefused" and m["refused"], timeout=30, collect=msgs)
    check(m.get("expired") is True and m.get("selfSigned") is False and m.get("others") is False,
          f"refused for its expiry alone, the self-signed error let past: {m}")
    check(m.get("expires") == "2020-01-02T00:00:00Z", f"it ran out on 2 January 2020: {m.get('expires')}")
    check(not any("Red bold" in t for t in texts_of(msgs)), "and none of the game's text")

    # A certificate for another server: an error only "Accept all certificate errors" lets past
    if ("connection", "disconnected") not in [(m["key"], m["value"]) for m in msgs if m["t"] == "status"]:
        wait_status(c, "connection", lambda v: v == "disconnected", msgs, timeout=10)
    msgs = []
    c.send({"t": "connect", "host": "localhost", "port": elsewhere_port, "tls": True})
    m = c.wait_for(lambda m: m["t"] == "certificateRefused" and m["refused"], timeout=30, collect=msgs)
    check(m.get("others") is True and m.get("expired") is False and m.get("selfSigned") is False
          and m.get("issuedTo") == "elsewhere.invalid", f"refused for naming another server: {m}")
    check(not any("Red bold" in t for t in texts_of(msgs)), "and none of the game's text")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def secure_offer(plain_port):
    """The game's offer of its secure port (MSSP's TLS), passed on as secureOffer and answered with answerSecureOffer as
    desktop Mudlet's question is: refused with no offer, lapsing with its connection, a no reconnecting where it was and
    stopping the asking, a yes storing the secure port with TLS on, as the profile list then says, and connecting
    there."""
    tls_port = fake_telnet.start_in_thread(tls=self_signed_context())
    handshakes = len(fake_telnet.TLS_HANDSHAKES)
    profile = "Secure Offer"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check({"t": "secureOffer", "offered": False} in seen, f"an attach is told no offer waits: {[m for m in seen if m['t'] == 'secureOffer']}")
    c.ask({"t": "saveProfile", "name": profile, "host": "localhost", "port": plain_port, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    m = c.ask({"t": "answerSecureOffer", "accept": True}, answers=("error",), collect=seen)
    check(m["message"] == "answerSecureOffer: the game has offered no secure port to answer", f"no offer, no answer: {m}")
    m = c.ask({"t": "answerSecureOffer", "accept": "yes"}, answers=("error",), collect=seen)
    check(m["message"] == "answerSecureOffer: accept must be true or false", f"accept is a boolean: {m}")
    # The fake game's certificate is its own
    c.ask({"t": "setSettings", "values": {"sslIgnoreSelfSigned": True}}, answers=("settings", "error"), collect=seen)

    def offered(msgs):
        c.send({"t": "connect", "host": "localhost", "port": plain_port, "tls": False})
        wait_status(c, "connection", lambda v: v == "connected", msgs)
        c.send({"t": "send", "text": f"mssp {tls_port}"})
        c.wait_for(lambda m: m["t"] == "secureOffer" and m["offered"], timeout=10, collect=msgs)

    msgs = []
    offered(msgs)
    check(any(f"secure connection on port {tls_port} is available" in t for t in texts_of(msgs)),
          f"Mudlet's console says so too: {texts_of(msgs)[-3:]}")
    msgs = []
    c.send({"t": "disconnect"})
    c.wait_for(lambda m: m["t"] == "secureOffer" and not m["offered"], timeout=10, collect=msgs)
    m = c.ask({"t": "answerSecureOffer", "accept": True}, answers=("error",), collect=msgs)
    check("no secure port" in m["message"], f"the offer lapses with its connection: {m}")
    # The status can come before the lapsed offer or the refusal, already collected
    seen_or_next(c, lambda m: m["t"] == "status" and m["key"] == "connection" and m["value"] == "disconnected", msgs, 0,
                 timeout=30, what="disconnected")

    # No: connects again where it was, and asks no more
    msgs = []
    offered(msgs)
    accepted = len(fake_telnet.ACCEPTED)
    c.send({"t": "answerSecureOffer", "accept": False})
    c.wait_for(lambda m: m["t"] == "secureOffer" and not m["offered"], timeout=10, collect=msgs)
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    check(len(fake_telnet.ACCEPTED) == accepted + 1, "a no connects again to the same port")
    m = c.ask({"t": "getSettings", "keys": ["askTlsAvailable"]}, answers=("settings", "error"), collect=msgs)
    check(m.get("values") == {"askTlsAvailable": False}, f"and the profile no longer offers a secure connection: {m}")
    since = len(msgs)
    c.send({"t": "send", "text": f"mssp {tls_port}"})
    c.wait_for(shown("MSSP sent"), collect=msgs)
    later = seen_or_none(c, lambda m: m["t"] == "secureOffer" and m["offered"], msgs, since, timeout=3)
    check(later is None, "the game's next offer is not passed on")
    check(not any(f"port {tls_port} is available" in t for t in texts_of(msgs[since:])), "nor told in the console")

    # Yes: the secure port, stored with TLS on and connected to
    c.ask({"t": "setSettings", "values": {"askTlsAvailable": True}}, answers=("settings", "error"), collect=msgs)
    c.send({"t": "disconnect"})
    wait_status(c, "connection", lambda v: v == "disconnected", msgs)
    msgs = []
    offered(msgs)
    c.send({"t": "answerSecureOffer", "accept": True})
    m = c.wait_for(lambda m: m["t"] == "profiles", timeout=10, collect=msgs)
    listed = [p for p in m["profiles"] if p["name"] == profile]
    check(listed and listed[0]["port"] == tls_port and listed[0]["tls"] is True,
          f"the profile list says the secure port, with TLS, unasked: {listed}")
    check("re" not in m, f"as no request's answer: {m}")
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    # The fake game notes its side of the handshake after the engine may already say it is connected
    deadline = time.monotonic() + 5
    while "ok" not in [r for r, _ in fake_telnet.TLS_HANDSHAKES[handshakes:]] and time.monotonic() < deadline:
        time.sleep(0.05)
    check("ok" in [r for r, _ in fake_telnet.TLS_HANDSHAKES[handshakes:]], f"the game's port saw a handshake: {fake_telnet.TLS_HANDSHAKES[handshakes:]}")
    c.send({"t": "send", "text": "tls"})
    m = c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].startswith("TLS: "), collect=msgs)
    check(m["text"].startswith("TLS: TLSv1."), f"the game sees an encrypted connection: {m['text']}")
    items = {item: mudlet_item(profile, item) for item in ("url", "port", "ssl_tsl")}
    check(items == {"url": "localhost", "port": str(tls_port), "ssl_tsl": "2"}, f"stored as the connection dialog stores it: {items}")
    # The UI's next connect, with what the list now says, is taken
    c.send({"t": "disconnect"})
    wait_status(c, "connection", lambda v: v == "disconnected", msgs)
    c.send({"t": "connect", "host": "localhost", "port": tls_port, "tls": True})
    m = wait_status(c, "connection", lambda v: v == "connected" or v.startswith("error:"), msgs)
    check(m["value"] == "connected", f"a connect with the secure port and TLS is taken: {m['value']}")
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
    several_profiles(c, port)
    running_profile_login(port)
    running_profile_details(port)
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    check(password not in open(LOG, errors="replace").read(), "nor did it log the password on its way out")


def several_profiles(c, port):
    """Version 15's control and profile sockets, with "Beta" open and connected: "Solo" opened alongside it, on a game
    of its own, in the same pool of profiles (docs/multi-profile.md). A trigger in Beta, which is behind, reaches
    Solo's script through raiseGlobalEvent; setFront raises sysProfileFocusChangeEvent in both, and setAppState
    sysApplicationFocusChangeEvent once in each, with one save; closing either leaves the other running."""
    def ask(client, msg):
        return client.ask(msg, answers=("profiles", "openProfiles", "error"))

    def lua(client, code, frames):
        client.next_id += 1
        client.send({"t": "runLua", "id": client.next_id, "code": code})
        return client.wait_for(lambda m: m["t"] == "status" and m["key"] == "luaResult" and m.get("re") == client.next_id,
                               collect=frames)

    def shows(client, text, frames, timeout=10):
        if any(shown(text)(m) for m in frames):
            return True
        try:
            client.wait_for(shown(text), timeout=timeout, collect=frames)
            return True
        except TimeoutError:
            return False

    def drain(client, frames, seconds=1):
        try:
            client.wait_for(lambda m: False, timeout=seconds, collect=frames)
        except TimeoutError:
            pass

    solo_port = fake_telnet.start_in_thread(banner=b"Solo's game\r\n")
    ask(c, {"t": "saveProfile", **profile_checks.profile("Solo", solo_port)})
    m = ask(c, {"t": "closeProfile", "name": "Solo"})
    check(m["t"] == "error" and m["message"] == 'closeProfile: "Solo" is not open', f"closeProfile of a profile not open: {m}")
    c.close()

    ctl = Client(SOCK, profile_sockets=True)
    msgs = []
    ctl.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    listed = [m for m in msgs if m["t"] == "openProfiles"]
    open_now = listed[-1]["profiles"] if listed else []
    check([(p["name"], p["slot"], p["state"], p["front"]) for p in open_now] == [("Beta", 1, "loaded", True)]
          and not [m for m in msgs if m["t"] == "line"], f"the control socket lists the open profile and carries no lines: {listed}")
    if not open_now:
        return
    beta = Client(open_now[0]["socket"])
    frames = []
    beta.wait_for(lambda m: m["t"] == "synced", collect=frames)
    connection = [m["value"] for m in frames if m["t"] == "status" and m["key"] == "connection"]
    check(frames[0].get("profile") == "Beta" and frames[0]["instance"].endswith("-1") and connection[-1:] == ["connected"],
          f"the profile's socket serves it: {frames[0]}, connection {connection}")
    m = ask(beta, {"t": "listProfiles"})
    check(m["t"] == "error" and m["message"] == "listProfiles: send it on the control socket", f"a control request on a profile's socket: {m}")
    m = ask(ctl, {"t": "send", "text": "look"})
    check(m["t"] == "error" and m["message"] == "send: send it on the profile's socket", f"a profile's request on the control socket: {m}")

    m = ask(ctl, {"t": "openProfile", "name": "Solo", "alongside": True})
    check(m["t"] == "openProfiles" and [p["name"] for p in m["profiles"]] == ["Beta", "Solo"],
          f"openProfile alongside answers with both profiles: {m}")
    both = ctl.wait_for(lambda m: m["t"] == "openProfiles" and [(p["name"], p["state"]) for p in m["profiles"]]
                        == [("Beta", "loaded"), ("Solo", "loaded")], timeout=30)["profiles"]
    check([(p["slot"], p["front"]) for p in both] == [(1, False), (2, True)],
          f"the second profile loads beside the first, and is in front: {both}")
    solo = Client(both[1]["socket"])
    solo_frames = []
    solo.wait_for(lambda m: m["t"] == "synced", collect=solo_frames)
    if not [m for m in solo_frames if m["t"] == "status" and m["key"] == "connection" and m["value"] == "connected"]:
        solo.wait_for(profile_checks.is_status("connection", "connected"), timeout=15, collect=solo_frames)
    check(solo_frames[0].get("profile") == "Solo" and shows(solo, "Solo's game", solo_frames),
          f"its socket serves it, connected to its own game: {solo_frames[0]}")

    lua(solo, 'registerAnonymousEventHandler("betaHeard", function(_, t) echo("Solo heard: " .. t .. "\\n") end)', solo_frames)
    lua(beta, 'tempTrigger("You said: ping", function() raiseGlobalEvent("betaHeard", line) end)', frames)
    beta.send({"t": "send", "text": "echo ping"})
    check(shows(beta, "You said: ping", frames) and shows(solo, "Solo heard: You said: ping", solo_frames),
          "a trigger in the profile behind runs, and its raiseGlobalEvent reaches the other profile's script")

    focus = 'registerAnonymousEventHandler("sysProfileFocusChangeEvent", function(_, gained) echo("focus " .. tostring(gained) .. "\\n") end)'
    lua(beta, focus, frames)
    lua(solo, focus, solo_frames)
    ctl.send({"t": "setFront", "name": "Beta"})
    check(shows(beta, "focus true", frames) and shows(solo, "focus false", solo_frames),
          "setFront raises sysProfileFocusChangeEvent: gained in the new front, lost in the one before")
    m = ctl.wait_for(lambda m: m["t"] == "openProfiles" and m["profiles"][0]["front"], timeout=10)
    check([p["front"] for p in m["profiles"]] == [True, False], f"and lists it in front: {m}")

    app = 'registerAnonymousEventHandler("sysApplicationFocusChangeEvent", function(_, active) echo("app " .. tostring(active) .. "\\n") end)'
    lua(beta, app, frames)
    lua(solo, app, solo_frames)
    control = []
    ctl.send({"t": "setAppState", "active": False, "minimized": True})
    left = shows(beta, "app false", frames) and shows(solo, "app false", solo_frames)
    saved = wait_status(ctl, "save", lambda v: True, control)["value"]
    drain(beta, frames)
    drain(solo, solo_frames)
    once = [len([m for m in got if m["t"] == "line" and m["text"] == "app false"]) for got in (frames, solo_frames)]
    check(left and once == [1, 1] and saved == "saved",
          f"the app leaving the front raises sysApplicationFocusChangeEvent once in each profile, and saves: {once}, {saved}")
    ctl.send({"t": "setAppState", "active": True, "minimized": False})
    check(shows(beta, "app true", frames) and shows(solo, "app true", solo_frames), "and coming back, in each again")

    m = ask(ctl, {"t": "closeProfile", "name": "Solo"})
    check(m["t"] == "openProfiles" and [(p["name"], p["state"]) for p in m["profiles"]] == [("Beta", "loaded"), ("Solo", "closing")],
          f"closeProfile answers with that profile closing: {m}")
    ctl.wait_for(lambda m: m["t"] == "openProfiles" and [p["name"] for p in m["profiles"]] == ["Beta"], timeout=30)
    beta.send({"t": "send", "text": "echo still here"})
    check(shows(beta, "You said: still here", frames), "the other profile runs on, connected, once one has closed")

    m = ask(ctl, {"t": "closeProfile", "name": "Beta"})
    check(m["t"] == "openProfiles" and [(p["name"], p["state"]) for p in m["profiles"]] == [("Beta", "closing")],
          f"closeProfile answers with the profile closing: {m}")
    ctl.wait_for(lambda m: m["t"] == "openProfiles" and not m["profiles"], timeout=30)
    try:
        beta.wait_for(lambda m: False, timeout=5)
    except Closed:
        closed = True
    except TimeoutError:
        closed = False
    check(closed, "and once it has closed, none is open and its socket is closed")
    ask(ctl, {"t": "deleteProfile", "name": "Solo"})
    ctl.close()


def running_profile_login(port):
    """A login and a password for the open profile "Beta", started without either, as the player adds them in the
    editor: saveProfile hands the running profile its login, and an openProfile that neither connects nor closes
    anything its password, so that its next connect logs in with them. It never closes for either."""
    ctl = Client(SOCK, profile_sockets=True)
    ctl.wait_for(lambda m: m["t"] == "synced")
    seen = []
    listed = []

    def ask(msg):
        return ctl.ask(msg, timeout=30, collect=listed, answers=("profiles", "openProfiles", "error"))

    def give_password(password):
        """As the editor does for a profile that is open; None for one the player had the app forget."""
        given = {} if password is None else {"password": password}
        return ask({"t": "openProfile", "name": "Beta", "connect": False, "alongside": True, **given})

    def statuses(since):
        return [(m["key"], m["value"]) for m in seen[since:] if m["t"] == "status" and m["key"] in ("profile", "connection")]

    def stayed_open(since):
        return not [value for key, value in statuses(since) if key == "profile" and value.startswith("Beta clos")]

    def game_got(since, *lines):
        """The game was sent lines, in that order, since fake_telnet.RECEIVED[since]."""
        deadline = time.monotonic() + 15
        while not all(line in fake_telnet.RECEIVED[since:] for line in lines) and time.monotonic() < deadline:
            time.sleep(0.1)
        got = fake_telnet.RECEIVED[since:]
        if not all(line in got for line in lines):
            return False
        places = [got.index(line) for line in lines]
        return places == sorted(places)

    def connect():
        """A bare connect, as the game screen's Reconnect sends: where the game's lines and the profile's statuses
        since then start."""
        marks = len(fake_telnet.RECEIVED), len(seen)
        beta.send({"t": "connect", "host": "127.0.0.1", "port": port})
        wait_status(beta, "connection", lambda v: v == "connected", seen)
        return marks

    def disconnect():
        beta.send({"t": "disconnect"})
        wait_status(beta, "connection", lambda v: v == "disconnected", seen)

    def watch(seconds):
        """Takes what Beta is sent for this long."""
        try:
            beta.wait_for(lambda m: False, timeout=seconds, collect=seen)
        except TimeoutError:
            pass

    def beta_socket(since):
        """The socket of Beta, the only profile open, once it has loaded, as the control socket said since listed[since]."""
        def loaded(m):
            return m["t"] == "openProfiles" and [(p["name"], p["state"]) for p in m["profiles"]] == [("Beta", "loaded")]
        told = [m for m in listed[since:] if loaded(m)]
        m = told[-1] if told else ctl.wait_for(loaded, timeout=30, collect=listed)
        return m["profiles"][0]["socket"]

    ask({"t": "saveProfile", **profile_checks.profile("Beta", port, auto_reconnect=True)})
    ask({"t": "openProfile", "name": "Beta"})
    path = beta_socket(0)
    beta = Client(path)
    if ("connection", "connected") not in statuses(0):
        wait_status(beta, "connection", lambda v: v == "connected", seen)
    first, second, third = "pw-first-Zr4-never-logged", "pw-second-Tq8-never-logged", "pw-third-Kd2-never-logged"

    disconnect()
    m = ask({"t": "saveProfile", **profile_checks.profile("Beta", port, login="beta-hero", auto_reconnect=True)})
    check(m["t"] == "profiles", f"saveProfile gives the open profile a login: {m}")
    got, mark = connect()
    check(game_got(got, "beta-hero") and stayed_open(mark),
          f"a login saved for the running profile is sent at its next connect, with no close: {statuses(mark)}")

    disconnect()
    m = give_password(first)
    got, mark = connect()
    check(m["t"] == "openProfiles" and game_got(got, "beta-hero", first) and stayed_open(mark),
          f"a password given to the running profile is sent after its login at its next connect, with no close: {statuses(mark)}")

    # Past its login by now: both are for the next connection
    got, mark = len(fake_telnet.RECEIVED), len(seen)
    ask({"t": "saveProfile", **profile_checks.profile("Beta", port, login="beta-hero-2", auto_reconnect=True)})
    give_password(second)
    beta.send({"t": "runLua", "code": "echo('')"})
    wait_status(beta, "luaResult", lambda v: True, seen)
    check(not {"beta-hero-2", second} & set(fake_telnet.RECEIVED[got:]) and not statuses(mark),
          f"a connected profile keeps its connection, with neither typed into it: {statuses(mark)}")
    disconnect()
    got, mark = len(fake_telnet.RECEIVED), len(seen)
    m = ask({"t": "openProfile", "name": "Beta", "password": second, "alongside": True})
    wait_status(beta, "connection", lambda v: v == "connected", seen)
    check(m["t"] == "openProfiles" and game_got(got, "beta-hero-2", second) and stayed_open(mark),
          f"and opened again to connect, it logs in with the changed ones, with no close: {statuses(mark)}")

    disconnect()
    give_password(None)
    got, mark = connect()
    # The password would follow the login by a second
    logged_in = game_got(got, "beta-hero-2")
    watch(3)
    check(logged_in and second not in fake_telnet.RECEIVED[got:] and stayed_open(mark),
          f"a password withdrawn from the running profile is not sent at its next connect: {statuses(mark)}")

    disconnect()
    ask({"t": "saveProfile", **profile_checks.profile("Beta", port, auto_reconnect=True)})
    got, mark = connect()
    # The login would come two seconds into the connection
    watch(4)
    check("beta-hero-2" not in fake_telnet.RECEIVED[got:] and stayed_open(mark),
          f"a login cleared for the running profile is not sent at its next connect: {statuses(mark)}")

    # Both while the profile loads, which it is given once it has
    beta.close()
    ask({"t": "closeProfile", "name": "Beta"})
    ctl.wait_for(lambda m: m["t"] == "openProfiles" and not m["profiles"], timeout=30)
    since = len(listed)
    ask({"t": "openProfile", "name": "Beta", "connect": False, "alongside": True})
    ask({"t": "saveProfile", **profile_checks.profile("Beta", port, login="beta-hero-3", auto_reconnect=True)})
    give_password(third)
    beta = Client(beta_socket(since))
    beta.wait_for(lambda m: m["t"] == "synced", collect=seen)
    got, mark = connect()
    check(game_got(got, "beta-hero-3", third) and stayed_open(mark),
          f"a login and a password given while the profile loads are sent at its first connect: {statuses(mark)}")

    for password in (first, second, third):
        profile_checks.password_kept_out(check, password, seen + listed, LOG)
    beta.close()
    ctl.close()


def running_profile_details(port):
    """The open profile "Beta", connected to the game at port with a password given and automatic reconnect on, as the
    player changes its other details in the editor: saveProfile hands the running profile its address, its TLS choice
    and whether it reconnects by itself, and the next connection is made with them, the one Mudlet makes by itself
    included. It never closes for any. Before that, openProfiles tells whether it holds a password, and handing it
    one leaves the list of profiles in its order."""
    second = fake_telnet.start_in_thread(banner=b"The second game\r\n")
    tls_port = fake_telnet.start_in_thread(tls=self_signed_context())
    connected_at = time.monotonic()
    ctl = Client(SOCK, profile_sockets=True)
    seen = []
    listed = []
    ctl.wait_for(lambda m: m["t"] == "synced", collect=listed)

    def ask(msg):
        return ctl.ask(msg, timeout=30, collect=listed, answers=("profiles", "openProfiles", "error"))

    def entry(m):
        """What an openProfiles frame says of Beta."""
        return next((p for p in m.get("profiles", []) if p["name"] == "Beta"), {})

    def give_password(password):
        given = {} if password is None else {"password": password}
        return entry(ask({"t": "openProfile", "name": "Beta", "connect": False, "alongside": True, **given}))

    def save(**changed):
        details = {**profile_checks.profile("Beta", port, login="beta-hero-3", auto_reconnect=True), **changed}
        return ask({"t": "saveProfile", **details})["t"] == "profiles"

    def takes(pred, timeout=20):
        """The next frame Beta is sent that pred takes, or None when the timeout comes first."""
        try:
            return beta.wait_for(pred, timeout=timeout, collect=seen)
        except TimeoutError:
            return None

    def shows(text):
        return lambda m: m["t"] in ("line", "tail") and text in m["text"]

    def connection(*values):
        """A connection status that is one of values, or an error."""
        return lambda m: m["t"] == "status" and m["key"] == "connection" and (m["value"] in values or m["value"].startswith("error"))

    def lose_connection():
        """The game ends a connection that lasted the five seconds after which Mudlet makes it again by itself."""
        takes(lambda m: False, connected_at + 5.5 - time.monotonic())
        beta.send({"t": "send", "text": "quit"})
        return len(seen)

    def statuses(since):
        return [(m["key"], m["value"]) for m in seen[since:] if m["t"] == "status" and m["key"] in ("profile", "connection")]

    told = [m for m in listed if m["t"] == "openProfiles"]
    beta = Client(entry(told[-1])["socket"])
    beta.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check(entry(told[-1]).get("passwordGiven") is True, f"openProfiles says the profile holds a password: {entry(told[-1])}")
    took = give_password(None)
    check(took.get("passwordGiven") is False, f"and that it holds none once that is withdrawn: {took}")
    password = "pw-fourth-Hm6-never-logged"
    took = give_password(password)
    check(took.get("passwordGiven") is True, f"and that it holds the one it is given: {took}")

    # A new profile comes first in the list, where the one opened last is
    order = profile_checks.names(ask({"t": "saveProfile", **profile_checks.profile("Delta", port)}))
    give_password(password)
    after = profile_checks.names(ask({"t": "listProfiles"}))
    check(order[:1] == ["Delta"] and after == order, f"a password handed to the running profile leaves the list's order: {after}")
    ask({"t": "deleteProfile", "name": "Delta"})

    saved = save(port=second)
    mark = lose_connection()
    check(saved and takes(shows("The second game")) is not None,
          f"Mudlet's automatic reconnect goes to the port saved for the profile while it was connected: {statuses(mark)}")
    connected_at = time.monotonic()

    saved = save(port=second, autoReconnect=False)
    mark = lose_connection()
    lost = takes(connection("disconnected"))
    again = takes(connection("connecting"), timeout=4)
    check(saved and lost is not None and again is None,
          f"with the automatic reconnect saved off, a lost connection is not made again: {statuses(mark)}")

    saved = save()
    mark = len(seen)
    beta.send({"t": "runLua", "code": "reconnect()"})
    check(saved and takes(shows("Red bold normal green")) is not None,
          f"Lua's reconnect() goes to the port saved for the profile while it was disconnected: {statuses(mark)}")
    connected_at = time.monotonic()
    mark = lose_connection()
    takes(connection("disconnected"))
    again = takes(connection("connected"))
    check(again is not None and again["value"] == "connected",
          f"with the automatic reconnect saved on again, a lost connection is made again: {statuses(mark)}")

    # A secure connection needs the server's name, as its certificate names it
    beta.send({"t": "disconnect"})
    takes(connection("disconnected"))
    saved = save(host="localhost", port=tls_port, tls=True, autoReconnect=False)
    mark = len(seen)
    beta.send({"t": "connect", "host": "localhost", "port": tls_port, "tls": True})
    ended = takes(lambda m: shows("self-signed")(m) or connection()(m), timeout=30)
    check(saved and ended is not None and ended["t"] != "status",
          "with TLS saved on, the next connect is made over TLS, which ends at the game's self-signed certificate: "
          f"{statuses(mark)}")

    saved = save(port=tls_port, tls=True, autoReconnect=False)
    beta.send({"t": "connect", "host": "127.0.0.1", "port": tls_port, "tls": True})
    refused = (takes(connection("connecting")) or {}).get("value", "")
    check(saved and "Mudlet refused the address saved for the profile" in refused and "IP address" in refused,
          f"an address Mudlet refuses, as TLS to an IP address, is stored, and the running profile's connect says why: {refused}")

    saved = save(autoReconnect=False)
    mark = len(seen)
    beta.send({"t": "connect", "host": "127.0.0.1", "port": port, "tls": False})
    up = takes(connection("connected"))
    beta.send({"t": "send", "text": "tls"})
    check(saved and up is not None and up["value"] == "connected" and takes(shows("TLS: none")) is not None,
          f"with TLS saved off again, the next connect is made in the clear: {statuses(mark)}")

    closes = [value for key, value in statuses(0) if key == "profile" and value.startswith("Beta clos")]
    check(not closes, f"the profile never closed for any of them: {closes}")
    profile_checks.password_kept_out(check, password, seen + listed, LOG)
    beta.close()
    ctl.close()


def bridge_refusals():
    """What the socket bridges (SocketBridge, ControlBridge) refuse before the engine sees it: a hello of another protocol
    version, a request of no known type, and each request whose fields are wrong, answered with the error that names
    the field and carries the request's id. Then the one UI a socket serves: a new one replaces it, and one that never
    says hello is sent nothing."""
    proc, c = start_engine("Bridge Test")
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    # libmudlet opens no profile until it is given a game or told to open one
    m = c.ask(dict(profile_checks.profile("Bridge Test", 1), t="saveProfile"))
    check(m["t"] == "profiles", f"saveProfile: {m}")
    c.send({"t": "openProfile", "name": "Bridge Test", "connect": False})
    wait_status(c, "profile", lambda v: v == "Bridge Test loaded", msgs, timeout=60)
    check(not [m for m in msgs if m["t"] == "openProfiles"], "a UI that leaves out profileSockets is not told of the open profiles")

    # A new connection replaces c, so c is attached again after it
    bad = Client(SOCK, version=99)
    m = bad.recv(2)
    check(m == {"t": "error", "message": f"expected hello with v={PROTOCOL_VERSION}"}, f"hello with v=99 gets an error frame: {m}")
    try:
        after = bad.recv(2)
    except Closed:
        after = "closed"
    check(after == "closed", f"and the connection is closed: {after}")
    c.close()
    c = Client(SOCK)
    c.wait_for(lambda m: m["t"] == "synced")
    m = c.ask({"t": "unknownThing"}, answers=("error",))
    check(m["message"] == "unknown type unknownThing", f"a request of no known type is refused, with its id: {m}")

    side = {"name": "side", "x": 0, "y": 0, "w": 300, "h": 200, "floating": False}
    room_size = "w and h must be whole numbers above 0, or both 0"
    for request, message in (
            ({"t": "setScreenSize", "columns": 0, "rows": 25}, "columns and rows must be whole numbers from 1 to 65535"),
            ({"t": "setScreenSize", "columns": 80, "rows": 65536}, "columns and rows must be whole numbers from 1 to 65535"),
            ({"t": "setScreenSize", "columns": 80.5, "rows": 25}, "columns and rows must be whole numbers from 1 to 65535"),
            ({"t": "setScreenSize", "columns": "80", "rows": 25}, "columns and rows must be whole numbers from 1 to 65535"),
            ({"t": "setScreenSize", "columns": 80}, "columns and rows must be whole numbers from 1 to 65535"),
            ({"t": "getLink", "link": 1, "window": 5}, "window must be a console's name"),
            ({"t": "followLink", "link": 1, "window": None}, "window must be a console's name"),
            ({"t": "getLink", "link": 0}, "link must be a whole number from 1"),
            ({"t": "followLink", "link": "1"}, "link must be a whole number from 1"),
            ({"t": "followLink", "link": 1, "item": 1.5}, "item must be a whole number from 0"),
            ({"t": "setAppState", "active": "true", "minimized": False}, "active and minimized must be true or false"),
            ({"t": "setAppState", "active": True}, "active and minimized must be true or false"),
            ({"t": "connect", "host": "127.0.0.1", "port": 23, "tls": "yes"}, "tls must be true or false"),
            ({"t": "complete", "text": "x", "fromHistory": "yes"}, "fromHistory must be true or false"),
            ({"t": "getWindowText", "window": "chat", "first": -300, "count": 300}, 'there is no console named "chat"'),
            ({"t": "setMainWindowSize", "w": 0, "h": 600}, "w and h must be whole numbers above 0"),
            ({"t": "setMainWindowSize", "w": 800.5, "h": 600}, "w and h must be whole numbers above 0"),
            (dict(side, t="placeUserWindow", x="0"), "x, y, w and h must be whole numbers, w and h above 0"),
            (dict(side, t="placeUserWindow", floating="no"), "floating must be true or false"),
            ({"t": "getWindowText", "window": "chat", "first": 0, "count": 0}, "first and count must be whole numbers, count above 0"),
            ({"t": "setTextBoxText", "name": "notes", "text": 5}, "text must be a string"),
            ({"t": "pointer", "window": "box", "type": "tap", "x": 1, "y": 2}, "type must be press, release, doubleClick, move, enter, leave or wheel"),
            ({"t": "pointer", "window": "box", "type": "press", "x": "1", "y": 2}, "x and y must be numbers"),
            ({"t": "pointer", "window": "box", "type": "press", "x": 1, "y": 2, "button": -1},
             "button, buttons and modifiers must be whole numbers from 0, wheelY a whole number"),
            ({"t": "pointer", "window": "box", "type": "wheel", "x": 1, "y": 2, "wheelY": 0.5},
             "button, buttons and modifiers must be whole numbers from 0, wheelY a whole number"),
            ({"t": "getImage", "path": "a.png", "w": 16}, "w and h go together, whole numbers from 1 to 4096"),
            ({"t": "getImage", "path": "a.png", "w": 16, "h": 5000}, "w and h go together, whole numbers from 1 to 4096"),
            ({"t": "getImage", "path": "a.png", "frame": -1}, "frame must be a whole number from 0"),
            ({"t": "getImage", "path": ""}, "path must not be empty"),
            ({"t": "pressButton", "button": "1"}, "button must be a whole number"),
            ({"t": "pressButton", "button": 1.5}, "button must be a whole number"),
            ({"t": "activateCommand"}, "command must be a whole number"),
            ({"t": "mapPointer", "type": "move", "x": 1, "y": 2}, "type must be press, doubleClick or wheel"),
            ({"t": "mapPointer", "type": "press", "x": "1", "y": 2}, "x and y must be numbers"),
            ({"t": "mapPointer", "type": "wheel", "x": 1, "y": 2, "wheelY": 0.5},
             "button and modifiers must be whole numbers from 0, wheelY a whole number"),
            ({"t": "mapPointer", "type": "press", "x": 1, "y": 2, "modifiers": -1},
             "button and modifiers must be whole numbers from 0, wheelY a whole number"),
            ({"t": "setMapViewOnly", "viewOnly": 1}, "viewOnly must be true or false"),
            ({"t": "setMapViewOnly"}, "viewOnly must be true or false"),
            ({"t": "appMapPointer", "type": "release", "x": 1, "y": 2}, "type must be press, doubleClick or wheel"),
            ({"t": "showRoom", "room": "1"}, "room must be a whole number"),
            ({"t": "moveMap", "map": "both", "dx": 0, "dy": 0, "zoom": 1, "x": 0, "y": 0, "seq": 1}, "map must be app or mapper"),
            ({"t": "moveMap", "map": "app", "dx": "0", "dy": 0, "zoom": 1, "x": 0, "y": 0, "seq": 1}, "dx, dy, zoom, x and y must be numbers"),
            ({"t": "moveMap", "map": "app", "dx": 0, "dy": 0, "zoom": 1, "y": 0, "seq": 1}, "dx, dy, zoom, x and y must be numbers"),
            ({"t": "moveMap", "map": "app", "dx": 0, "dy": 0, "zoom": 0, "x": 0, "y": 0, "seq": 1}, "zoom must be above 0"),
            ({"t": "moveMap", "map": "mapper", "dx": 0, "dy": 0, "zoom": 1, "x": 0, "y": 0, "seq": 0}, "seq must be a whole number from 1"),
            ({"t": "moveMap", "map": "mapper", "dx": 0, "dy": 0, "zoom": 1, "x": 0, "y": 0}, "seq must be a whole number from 1"),
            ({"t": "followPlayer"}, "map must be app or mapper"),
            ({"t": "showMapLevel", "map": "app"}, "level must be a whole number"),
            ({"t": "showMapLevel", "level": 1}, "map must be app or mapper"),
            ({"t": "showMapArea", "map": "mapper", "area": "2"}, "area must be a whole number"),
            ({"t": "walkToRoom", "room": 1.5}, "room must be a whole number"),
            ({"t": "findRooms", "text": " "}, "text must not be empty"),
            ({"t": "roomInfo", "room": "1"}, "room must be a whole number"),
            ({"t": "setAppMapSize", "w": 0, "h": 10, "scale": 1}, room_size),
            ({"t": "setAppMapSize", "w": 1.5, "h": 10, "scale": 1}, room_size),
            ({"t": "setAppMapSize", "w": 10, "h": 10, "scale": 0.5}, "scale must be a number from 1 to 4"),
            ({"t": "setAppMapSize", "w": 10, "h": 10}, "scale must be a number from 1 to 4"),
            ({"t": "setMapScale", "scale": 0.5}, "scale must be a number from 1 to 8"),
            ({"t": "setMapScale", "scale": 8.5}, "scale must be a number from 1 to 8"),
            ({"t": "setMapScale", "scale": "2"}, "scale must be a number from 1 to 8"),
            ({"t": "editRoom", "room": 1, "weight": "2"}, "weight must be a whole number"),
            ({"t": "editRoom", "room": 1, "x": 1, "y": 2}, "x, y and z go together"),
            ({"t": "editRoom", "room": 1, "locked": 1}, "locked must be true or false"),
            ({"t": "setRoomExit", "room": 1, "direction": "e", "to": -1}, "to must be a whole number from 0"),
            ({"t": "lockRoomExit", "room": 1, "direction": "e"}, "locked must be true or false"),
            ({"t": "deleteRoom", "room": "1"}, "room must be a whole number"),
            ({"t": "renameArea", "area": 1}, "name must be a string"),
            ({"t": "getSettings", "keys": []}, "keys must be a list of names"),
            ({"t": "getSettings", "keys": "blankLinesBehaviour"}, "keys must be a list of names"),
            ({"t": "getSettings", "keys": ["blankLinesBehaviour", 1]}, "keys must be a list of names"),
            ({"t": "getSettings", "keys": [""]}, "keys must be a list of names"),
            ({"t": "setSettings", "values": {}}, "values must be an object of settings"),
            ({"t": "setSettings", "values": ["blankLinesBehaviour"]}, "values must be an object of settings")):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: {message}", f"a bad field is refused: {request} -> {m['message']}")

    ctl = Client(SOCK, profile_sockets=True)
    msgs = []
    ctl.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    try:
        while c.recv(1) is not None:
            pass
        replaced = False
    except Closed:
        replaced = True
    check(replaced, "a UI that attaches replaces (closes) the one before")
    listed = [m for m in msgs if m["t"] == "openProfiles"]
    names = [p["name"] for p in listed[-1]["profiles"]] if listed else []
    check(names == ["Bridge Test"], f"the control socket lists the profile: {names}")
    m = ctl.ask({"t": "markSeen", "name": "Bridge Test", "seq": -1}, answers=("error",))
    check(m["message"] == "markSeen: seq must be a whole number from 0", f"markSeen of a seq below 0: {m}")
    others = ["Bridge 2", "Bridge 3", "Bridge 4"]
    for name in others:
        ctl.ask(dict(profile_checks.profile(name, 1), t="saveProfile"))
        ctl.send({"t": "openProfile", "name": name, "alongside": True, "connect": False})
    ctl.wait_for(lambda m: m["t"] == "openProfiles" and len(m["profiles"]) == 4, timeout=60)
    m = ctl.ask({"t": "openProfile", "name": "Bridge 5", "alongside": True}, answers=("openProfiles", "error"))
    check(m["t"] == "error" and m["message"] == "openProfile: at most 4 open", f"no more than 4 open at once: {m}")
    silent = Client(SOCK, hello=False)
    try:
        sent = silent.recv(1)
    except Closed:
        sent = None
    check(sent is None, f"a UI that never says hello is sent nothing: {sent}")
    silent.close()
    ctl = Client(SOCK, profile_sockets=True)
    m = ctl.ask({"t": "listProfiles"})
    check(m["t"] == "profiles", f"and the engine serves the next UI: {m['t']}")
    ctl.close()
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


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


def main_window_size(c, msgs, tag):
    """What getMainWindowSize() gives the profile's scripts now, as "<w>x<h>"; tag tells this read's line apart."""
    c.send({"t": "runLua", "code": f'local w, h = getMainWindowSize() echo(string.format("main window {tag} %dx%d\\n", w, h))'})
    m = c.wait_for(lambda m: m["t"] in ("line", "tail") and m["text"].startswith(f"main window {tag} "), collect=msgs)
    return m["text"].split()[-1]


def naws_stays(size, told):
    """Whether the game, last told size, is told nothing after its first told NAWS once Mudlet's 0.3 s settling time
    has passed."""
    time.sleep(1)
    return fake_telnet.NAWS_SIZES[told:] == [] and fake_telnet.NAWS_SIZES[-1:] == [size]


def connect_and_ask_naws(c, game, msgs):
    """Connects to the fake game and, once its login prompt is shown, has it ask for NAWS."""
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", msgs)
    # Else the game's answer would continue the open "Login: " line
    c.wait_for(shown("Login: "), timeout=10, collect=msgs)
    c.send({"t": "send", "text": "naws"})
    c.wait_for(shown("NAWS asked"), collect=msgs)


def area_grid(m):
    """A mainArea's main window width (0 for none) and grid."""
    return m["w"], m["columns"], m["rows"]


def screen_and_main_window():
    """setScreenSize and setMainWindowSize, which the game screen sends in either order, before the profile loads and
    while it runs: the game is told the console's columns and rows, not the grid Mudlet's font fits in the main
    window, and scripts read the main window's size the windows UI gave, also after a later setScreenSize. Until a
    profile has that size, setScreenSize sizes the main window, also when the profile refuses the size kept for it."""
    game = fake_telnet.start_in_thread(fake_telnet.LOGIN_BANNER)
    profile = "Screen Size Test"
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "setScreenSize", "columns": 80, "rows": 30})
    connect_and_ask_naws(c, game, msgs)
    check(wait_naws((80, 30)), f"the game is told the console's size: {fake_telnet.NAWS_SIZES}")
    areas = [m for m in msgs if m["t"] == "mainArea"]
    check(areas and area_grid(areas[-1]) == (0, 80, 30),
          f"with no main window's size given, the console's size is the main window's: {areas[-1:]}")

    # As the windows UI gives it once the first mainArea has told it Mudlet's font
    told = len(fake_telnet.NAWS_SIZES)
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    m = c.wait_for(lambda m: m["t"] == "mainArea" and m["w"] == 1000, collect=msgs)
    check((m["textW"], m["textH"]) == (1000, 800), f"the profile takes the main window's size while it runs: {m}")
    check(naws_stays((80, 30), told), f"and the game is still told the console's size, not that window's grid: {fake_telnet.NAWS_SIZES}")
    # A keyboard that shows takes rows from the console, and setScreenSize may come after setMainWindowSize
    c.send({"t": "setScreenSize", "columns": 72, "rows": 20})
    check(wait_naws((72, 20)), f"a later setScreenSize reaches the game: {fake_telnet.NAWS_SIZES}")
    size = main_window_size(c, msgs, "after")
    check(size == "1000x800", f"and leaves scripts the main window's size the windows UI gave: {size}")
    areas = [m for m in msgs if m["t"] == "mainArea"]
    check((areas[-1]["w"], areas[-1]["textW"], areas[-1]["textH"]) == (1000, 1000, 800),
          f"and the main window's area, which the borders and windows take from: {areas[-1]}")
    # A large font on a portrait phone
    c.send({"t": "setScreenSize", "columns": 30, "rows": 20})
    check(wait_naws((40, 20)), f"a width below 40 is told as 40 then too: {fake_telnet.NAWS_SIZES}")
    size = main_window_size(c, msgs, "narrow")
    check(size == "1000x800", f"and the main window keeps its size: {size}")
    told = len(fake_telnet.NAWS_SIZES)
    c.send({"t": "setMainWindowSize", "w": 1200, "h": 800})
    c.wait_for(lambda m: m["t"] == "mainArea" and m["w"] == 1200, collect=msgs)
    check(naws_stays((40, 20), told), f"a new main window size leaves the game told the console's size: {fake_telnet.NAWS_SIZES}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    # Both given before the profile loads, as to an engine the app attaches to again: the start, which finishes after
    # the load, keeps them apart too. The game was last told 40 by 20, so 80 by 30 is told anew
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "setScreenSize", "columns": 80, "rows": 30})
    connect_and_ask_naws(c, game, msgs)
    check(wait_naws((80, 30)), f"the game is told the console's size given before the load: {fake_telnet.NAWS_SIZES}")
    size = main_window_size(c, msgs, "loaded")
    check(size == "1000x800", f"and scripts read the main window's size given before it, once the start finished: {size}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    # A main window's size the profile refuses as it loads, as one that holds no character of its font: the console's
    # size sizes the main window, and the game is told each new one
    proc, c = start_engine(profile)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    c.send({"t": "setMainWindowSize", "w": 1, "h": 1})
    c.send({"t": "setScreenSize", "columns": 60, "rows": 24})
    connect_and_ask_naws(c, game, msgs)
    check(wait_naws((60, 24)), f"the game is told the console's size with a refused main window's size: {fake_telnet.NAWS_SIZES}")
    areas = [m for m in msgs if m["t"] == "mainArea"]
    check(areas and area_grid(areas[-1]) == (0, 60, 24), f"which is the main window's, as with none: {areas[-1:]}")
    c.send({"t": "setScreenSize", "columns": 72, "rows": 20})
    m = c.wait_for(lambda m: m["t"] == "mainArea" and m["columns"] == 72, collect=msgs)
    check(area_grid(m) == (0, 72, 20), f"a later setScreenSize sizes the main window: {m}")
    check(wait_naws((72, 20)), f"and reaches the game: {fake_telnet.NAWS_SIZES}")
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


def seen_or_none(c, pred, seen, since, timeout=5.0):
    """As seen_or_next, but None on a timeout, for a check to fail and the test to go on."""
    try:
        return seen_or_next(c, pred, seen, since, timeout=timeout)
    except TimeoutError:
        return None


def seen_or_next(c, pred, seen, since, timeout=5.0, what=None):
    """The first message from seen[since:] that pred takes, or else the next one to come. The session sends a turn's
    main console lines as it runs and its script windows, their text and command lines from the event loop after it,
    so a wait for one of those has already collected a line the same turn wrote. On a timeout, says what it waited
    for, where what is given, and what came."""
    found = next((m for m in seen[since:] if pred(m)), None)
    if found is not None:
        return found
    try:
        return c.wait_for(pred, timeout=timeout, collect=seen)
    except TimeoutError:
        came = [masked(str(m))[:200] for m in seen[since:]]
        waited = f" ({what})" if what else ""
        raise TimeoutError(f"message not received{waited}; since the request: {came}") from None


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


def window_links():
    """getLink and followLink with a window's name, for a mini console's echoLink() and echoPopup(): the window's own
    link ids, its menu, the command reaching the game, the window's text sent again as a followed link changes, and the
    refusals for a window or id there is not; main names the main console. In the next profile opened, whose main
    console's ids are offset, a window's are still its own."""
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Window Links Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=60)

    def ask_link(request):
        return c.ask(request, answers=("link", "error"), collect=seen)

    def window_link_ids(m):
        return [r[5] for line in m["lines"] for r in line["runs"] if len(r) == 6 and r[5] > 0]

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'win = Geyser.MiniConsole:new({name = "win", x = 0, y = 0, width = "30%", height = "40%"})',
        'win:echo("go: ")',
        'echoLink("win", "go", [[send("winlink")]], "win tip", true)',
        'win:echo(" food: ")',
        'echoPopup("win", "stew", {[[send("look win stew")]], [[send("eat win stew")]]}, {"Look", "Eat"}, true)',
        'win:echo("\\n")',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "windowText" and m["window"] == "win" and m["lines"]
                     and m["lines"][0]["text"] == "go: go food: stew" and len(window_link_ids(m)) >= 2, seen, since, timeout=10)
    ids = list(dict.fromkeys(window_link_ids(m)))
    check(len(ids) == 2, f"the window's row marks its two links: {m}")
    go_id, stew_id = ids[0], ids[-1]

    m = ask_link({"t": "getLink", "link": go_id, "window": "win"})
    check(m.get("t") == "link" and m["link"] == go_id and m.get("window") == "win" and m["tooltip"] == "win tip"
          and not m["menu"] and not m["visited"], f"getLink with the window's name gives its echoLink(): {m}")
    m = ask_link({"t": "getLink", "link": stew_id, "window": "win"})
    check(m.get("t") == "link" and m["menu"] and [i["label"] for i in m["items"]] == ["Look", "Eat"],
          f"and its echoPopup()'s menu: {m}")

    since = len(seen)
    c.send({"t": "followLink", "link": go_id, "window": "win", "id": "follow"})
    check(wait_received("winlink"), f"followLink with the window's name runs its send(), which reaches the game: {fake_telnet.RECEIVED[-3:]}")
    m = seen_or_next(c, lambda m: m["t"] == "windowText" and m["window"] == "win" and "re" not in m, seen, since, timeout=10)
    check(bool(m["lines"]), f"the link turning visited sends the window's text again, which is how a UI learns of it: {m}")
    m = ask_link({"t": "getLink", "link": go_id, "window": "win"})
    check(m.get("visited") is True, f"and leaves it visited: {m}")
    check(not any(m.get("re") == "follow" for m in seen[since:]), "a followLink that ran is not answered")
    check(not any(m["t"] == "linksChanged" and go_id in m["ids"] for m in seen[since:]),
          "a window's link changing is no linksChanged, which is the main console's")
    c.send({"t": "followLink", "link": stew_id, "item": 1, "window": "win"})
    check(wait_received("eat win stew"), f"followLink with an item runs that item of the window's menu: {fake_telnet.RECEIVED[-3:]}")

    for request, message in [({"t": "getLink", "link": go_id, "window": "nonesuch"}, "getLink: unknown"),
                             ({"t": "followLink", "link": go_id, "window": "nonesuch"}, "followLink: unknown"),
                             ({"t": "getLink", "link": 99999, "window": "win"}, "getLink: unknown"),
                             ({"t": "followLink", "link": stew_id, "item": 2, "window": "win"}, "followLink: noSuchItem"),
                             ({"t": "getLink", "link": go_id, "window": 5}, "getLink: window must be a console's name")]:
        m = ask_link(request)
        check(m.get("t") == "error" and m["message"] == message, f"{request} gets {message!r}: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'echo("main: ") echoLink("m", [[send("mainlink")]], "main tip") echo("\\n")'})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].endswith("main: m") and link_ids(m), seen, since)
    main_id = link_ids(m)[-1]
    for window in ("main", ""):
        m = ask_link({"t": "getLink", "link": main_id, "window": window})
        check(m.get("tooltip") == "main tip" and "window" not in m, f"window {window!r} names the main console: {m}")

    m = c.ask({"t": "saveProfile", "name": "Window Links Other", "host": "127.0.0.1", "port": game, "tls": False,
               "login": "", "autoReconnect": False})
    check(m["t"] == "profiles", f"saveProfile: {m}")
    m = c.ask({"t": "openProfile", "name": "Window Links Other", "connect": False})
    check(m["t"] == "profiles", f"openProfile: {m}")
    wait_status(c, "profile", lambda v: v == "Window Links Other loaded", seen, timeout=60)
    # The link's send() reaches the game only while this profile is connected
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=60)
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'win = Geyser.MiniConsole:new({name = "win", x = 0, y = 0, width = "30%", height = "40%"})',
        'echoLink("win", "again", [[send("other winlink")]], "other win tip", true)',
        'win:echo("\\n")',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "windowText" and m["window"] == "win" and m["lines"]
                     and m["lines"][0]["text"] == "again" and window_link_ids(m), seen, since, timeout=10)
    other_id = window_link_ids(m)[0]
    m = ask_link({"t": "getLink", "link": other_id, "window": "win"})
    check(m.get("tooltip") == "other win tip", f"in the next profile, a window's link id is its own, not offset: {m}")
    c.send({"t": "followLink", "link": other_id, "window": "win"})
    check(wait_received("other winlink"), f"and followLink runs it: {fake_telnet.RECEIVED[-3:]}")
    m = ask_link({"t": "getLink", "link": main_id})
    check(m.get("t") == "error" and m["message"] == "getLink: unknown", f"while the main console's id from before names none: {m}")
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


def accessible_value(m):
    """A label message's value, valueMin and valueMax, each None when absent."""
    return tuple(m.get(k) for k in ("value", "valueMin", "valueMax"))


def script_labels():
    """What labels show: a Geyser.Label's text, stylesheet, image, accessible name, live region and accessible value as
    label messages, the image as a PNG from getImage, and its click callback run by pointer; a Geyser.Gauge as three
    labels whose geometry gives its value, with its text label transparent and its back label carrying the value, as
    a createGauge() gauge's does; a deleted label removed; and every label again for a UI that attaches."""
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
        'news = Geyser.Label:new({name = "news", x = 300, y = 80, width = 120, height = 20, liveRegion = "assertive"})',
        'news:echo("all quiet")',
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
    check("liveRegion" not in m, f"a label whose live region is off has none: {m}")
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "news" and "all quiet" in m["text"], seen, since)
    check(m.get("liveRegion") == "assertive", f"a Geyser.Label made with a live region has it: {m}")
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
    for code, region in (('box:setLiveRegion("polite")', "polite"), ('setLabelLiveRegion("box", "assertive")', "assertive"),
                         ('box:setLiveRegion("off")', None)):
        since = len(seen)
        c.send({"t": "runLua", "code": code})
        m = seen_or_next(c, lambda m, region=region: m["t"] == "label" and m["name"] == "box" and m.get("liveRegion") == region, seen, since)
        check("new words" in m["text"] and not any(windows_with("box", "label")(w) for w in seen[since:]),
              f"{code} alone sends the label again, with its live region: {m}")
    check(accessible_value(m) == (None, None, None), f"a label with no accessible value has none: {m}")
    none = (None, None, None)
    # A value past the end is at the end, as a progress bar shows it; one refused, with nil and a message, leaves the
    # label as it was, so it is not sent again
    for code, value in (('setLabelAccessibleValue("box", 5, 0, 10)', (5, 0, 10)), ('setLabelAccessibleValue("box", 15, 0, 10)', (10, 0, 10)),
                        ('setLabelAccessibleValue("box", 40)', (40, 0, 100)), ("refused", None), ('clearLabelAccessibleValue("box")', none)):
        since = len(seen)
        if value is None:
            c.send({"t": "runLua", "code": 'local ok, err = setLabelAccessibleValue("box", 0 / 0)\necho(string.format("refused %s: %s\\n", tostring(ok), err))'})
            m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("refused"), seen, since)
            c.send({"t": "runLua", "code": 'echo("after the refusal\\n")'})
            seen_or_next(c, shown("after the refusal"), seen, since)
            check(m["text"] == "refused nil: the value, minimum and maximum must be finite numbers" and label_of(seen[since:], "box") is None,
                  f"a value that is not a finite number is refused, and the label not sent again: {m} {label_of(seen[since:], 'box')}")
            continue
        c.send({"t": "runLua", "code": code})
        m = seen_or_none(c, lambda m, value=value: m["t"] == "label" and m["name"] == "box" and accessible_value(m) == value, seen, since)
        check(m is not None and "new words" in m["text"] and not any(windows_with("box", "label")(w) for w in seen[since:]),
              f"{code} alone sends the label again, with its accessible value: {m or label_of(seen[since:], 'box')}")

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
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "hp_back" and "value" in m, seen, since)
    check(accessible_value(m) == (30, 0, 60), f"its back label carries its value, out of the maximum given: {m}")
    check(all("value" not in (label_of(seen[since:], f"hp_{part}") or {}) for part in ("front", "text")),
          "and its front and text labels carry none")
    since = len(seen)
    c.send({"t": "runLua", "code": "hp:setValue(15, 60)"})
    seen_or_next(c, windows_with("hp_front", "label", lambda w: w["w"] == 50), seen, since)
    c.send({"t": "runLua", "code": 'echo("gauge sent\\n")'})
    seen_or_next(c, shown("gauge sent"), seen, since)
    front = window_of(seen, "hp_front", "label")
    resent = [m["name"] for m in seen[since:] if m["t"] == "label"]
    back = label_of(seen[since:], "hp_back")
    check((front["x"], front["w"]) == (10, 50) and resent == ["hp_back"] and accessible_value(back) == (15, 0, 60),
          f"a new value resizes the front label and sends the back label with it, and no label whose content stayed: {front} {resent}")
    # A new style sheet lays the gauge out again from the value and maximum it was given, not its percentage; one past
    # the end is at the end; a reading it cannot give, out of an infinite maximum, is none, so a UI reads the front
    # label's width
    for code, value in (('hp:setStyleSheet("background-color: red", "background-color: black")', (15, 0, 60)),
                        ("hp:setValue(90, 60)", (60, 0, 60)), ("hp:setValue(5, math.huge)", none), ("hp:setValue(15, 60)", (15, 0, 60))):
        since = len(seen)
        c.send({"t": "runLua", "code": code})
        m = seen_or_none(c, lambda m, value=value: m["t"] == "label" and m["name"] == "hp_back" and accessible_value(m) == value, seen, since)
        check(m is not None, f"after {code}, the back label's value is {value}: {label_of(seen[since:], 'hp_back')}")
    # createGauge()'s gauges have it too, out of their maximum, kept as they move
    since = len(seen)
    c.send({"t": "runLua", "code": 'createGauge("old", 100, 10, 500, 600)\nsetGauge("old", 3, 12)\nmoveGauge("old", 500, 620)'})
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "old_back" and "value" in m, seen, since)
    check(accessible_value(m) == (3, 0, 12) and accessible_value(label_of(seen[since:], "old_front") or {}) == none,
          f"setGauge() gives a createGauge() gauge's back label its value, which moveGauge() keeps: {m}")

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
    news = label_of(msgs, "news")
    check(news is not None and news.get("liveRegion") == "assertive", f"and with its live region: {news}")
    back = label_of(msgs, "hp_back")
    check(back is not None and accessible_value(back) == (15, 0, 60), f"and a gauge's back label with its value: {back}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'deleteLabel("pic")'})
    m = seen_or_next(c, lambda m: m["t"] == "windows" and m.get("removed"), seen, since)
    check({"name": "pic", "kind": "label"} in m["removed"] and label_of(seen[since:], "pic") is None,
          f"a deleted label is removed, with no label message: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def text_box_of(msgs, name):
    """The last textBox message for a text box, or None."""
    return next((m for m in reversed(msgs) if m["t"] == "textBox" and m["name"] == name), None)


# A label's links as Qt reads them: a quoted value whole, a quote or a ">" in it kept, and entities decoded but in
# single quotes. RichTextTest.linksKeepTheirTargetsAsQtReadsThem gives the app's reading of this same text, which must
# be QUOTED_LINK_TARGETS, the hrefs the engine follows below.
QUOTED_LINKS = ("""<a href="send:echo 'hi'">hi</a> <a href="send:echo hp > 50">hp</a> """
                """<a href="labelSaid = 'caf&eacute; &#233;'">cafe</a> <a href='labelSingle = "&amp;"'>single</a>""")
QUOTED_LINK_TARGETS = ("send:echo 'hi'", "send:echo hp > 50", "labelSaid = 'caf\u00e9 \u00e9'", 'labelSingle = "&amp;"')
# Links Qt reads though Mudlet's containsAnchorTag() misses them, with no other link in the label that it finds: a raw
# "<" in a quoted href, and a space after the tag's "<". RichTextTest.linksKeepTheirTargetsAsQtReadsThem reads them too.
RAW_LT_LINKS = '<a href="labelLt = 1 < 2">lt</a> < a href="labelSpaced = true">spaced</a>'
RAW_LT_LINK_TARGETS = ("labelLt = 1 < 2", "labelSpaced = true")


def label_links():
    """A label's links followed as the GUI's click follows them (patch 0014): followLabelLink sending a send: link's
    command through the aliases, putting a prompt: link's text on the input line and running anything else as Lua,
    without an answer; the hrefs the app reads from quoted values and entities, and from links with a raw "<",
    followed; a web link left to the UI, and a link the label does not have, a Lua error and a scheme Mudlet does not
    handle refused, each running nothing; a prompt: link refused while the game hides input, as the desktop would put
    its text in its hidden-input box, which the app is not sent; and a followed link sent again in the label, in the
    visited colour setLinkStyle gives, and a web link not. The game sends WILL ECHO only for that one step, asked for
    with its password command: while it hides input, Mudlet puts printCmdLine()'s text on no line the app shows."""
    game = fake_telnet.start_in_thread(banner=b"Label links game\r\n")
    proc, c = start_engine("Label Links Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    m = c.ask({"t": "followLabelLink", "label": "links", "href": "send:n"}, answers=("error",), collect=seen)
    check(m["message"] == "followLabelLink: no profile running", f"followLabelLink with no profile open is refused: {m}")

    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=30)

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'tempAlias("^n$", [[send("echo north", false)]])',
        'createLabel("links", 0, 0, 400, 40, 1)',
        'echo("links", [[<a href="send:n">go</a> <a href="prompt:tell Bob ">tell</a> <a href="https://example.org/">site</a> '
        '<a href="labelRan = (labelRan or 0) + 1">count</a> <a href="error(\'label link error\')">bad</a> '
        '<a href="mailto:bob@example.org">mail</a>]])',
        'createLabel("plain", 0, 50, 100, 20, 1)',
        'echo("plain", "no links here")',
        'createLabel("quoted", 0, 80, 400, 20, 1)',
        f'echo("quoted", [[{QUOTED_LINKS}]])',
        'createLabel("rawlt", 0, 110, 400, 20, 1)',
        f'echo("rawlt", [[{RAW_LT_LINKS}]])',
    ])})
    seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "links" and "mail" in m["text"], seen, since)
    for name in ("plain", "quoted", "rawlt"):
        seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == name, seen, since)
    followed = 0
    # The latest follow, as a timeout names it
    following = ""

    def follow(href, label="links"):
        """The error that answers followLabelLink, or None where there was none before the echo sent after it."""
        nonlocal followed, following
        followed += 1
        request_id = f"follow{followed}"
        following = f"{request_id}, {href!r} in {label!r}"
        since = len(seen)
        c.send({"t": "followLabelLink", "label": label, "href": href, "id": request_id})
        c.send({"t": "runLua", "code": f'echo("after {request_id}\\n")'})
        seen_or_next(c, shown(f"after {request_id}"), seen, since, what=f"the echo after {following}")
        return next((m for m in seen[since:] if m["t"] == "error" and m.get("re") == request_id), None)

    since = len(seen)
    m = follow("send:n")
    seen_or_next(c, shown("You said: north"), seen, since, what=f"the game's answer to {following}")
    check(m is None, f"a send: link sends its command through the aliases, unanswered: {m}")
    since = len(seen)
    m = follow("prompt:tell Bob ")
    line = seen_or_next(c, lambda m: m["t"] == "commandLine" and m["text"], seen, since, what=f"the commandLine from {following}")
    check(m is None and line["text"] == "tell Bob ", f"a prompt: link puts its text on the input line, its space kept: {line} {m}")
    since = len(seen)
    c.send({"t": "send", "text": "password"})
    seen_or_next(c, lambda m: m["t"] == "hiddenInput" and m["wanted"], seen, since, what="hiddenInput from the game's password prompt")
    # The prompt's line, "Password: ", ends here, so the echo after the follow is a line of its own
    c.send({"t": "runLua", "code": 'echo("\\n")'})
    m = follow("prompt:tell Bob ")
    put = [x for x in seen[since:] if x["t"] == "commandLine" and x["text"]]
    check(m is not None and m["message"].startswith("followLabelLink: ") and "hiding input" in m["message"] and not put,
          f"while the game hides input, a prompt: link is refused, with nothing put on the input line: {m} {put}")
    since = len(seen)
    # Any line ends the game's password prompt, with WONT ECHO
    c.send({"t": "sendHidden", "text": "label links"})
    seen_or_next(c, lambda m: m["t"] == "hiddenInput" and not m["wanted"], seen, since, what="the game's WONT ECHO after the password")
    m = follow("labelRan = (labelRan or 0) + 1")
    check(m is None, f"and any other link runs as Lua: {m}")
    since = len(seen)
    c.send({"t": "runLua", "code": 'echo("ran " .. tostring(labelRan) .. "\\n")'})
    seen_or_next(c, shown("ran 1"), seen, since)

    since = len(seen)
    answers = [follow(href, "quoted") for href in QUOTED_LINK_TARGETS]
    check(answers == [None] * len(QUOTED_LINK_TARGETS), f"the hrefs the app reads from quoted values and entities are the label's: {answers}")
    c.send({"t": "runLua", "code": 'echo("quoted " .. tostring(labelSaid) .. " " .. tostring(labelSingle) .. "\\n")'})
    for text in ("You said: 'hi'", "You said: hp > 50", "quoted caf\u00e9 \u00e9 &amp;"):
        seen_or_next(c, shown(text), seen, since, what=f"{text!r} from the quoted links")
    since = len(seen)
    answers = [follow(href, "rawlt") for href in RAW_LT_LINK_TARGETS]
    check(answers == [None] * len(RAW_LT_LINK_TARGETS), f"and so are links with a raw '<', as Qt reads them: {answers}")
    c.send({"t": "runLua", "code": 'echo("rawlt " .. tostring(labelLt) .. " " .. tostring(labelSpaced) .. "\\n")'})
    seen_or_next(c, shown("rawlt true true"), seen, since, what="what the raw '<' links ran")

    for href in ("https://example.org/", " HTTP://example.org/ "):
        m = follow(href)
        check(m is not None and m["message"] == "followLabelLink: the UI opens web links", f"a web link, {href!r}, is the UI's to open, and refused: {m}")
    m = follow("error('label link error')")
    check(m is not None and m["message"].startswith("followLabelLink: ") and "label link error" in m["message"],
          f"a link whose Lua fails is refused with its error: {m}")
    m = follow("mailto:bob@example.org")
    check(m is not None and m["message"].startswith("followLabelLink: ") and "mailto: is not a scheme Mudlet handles" in m["message"],
          f"a scheme Mudlet does not handle, that is no Lua, is refused: {m}")
    for label, href in (("links", "send:south"), ("links", "send:n "), ("nonesuch", "send:n"), ("plain", "send:n"), ("main", "send:n")):
        m = follow(href, label)
        check(m is not None and m["message"].startswith("followLabelLink: ") and f'"{label}"' in m["message"],
              f"a link {label!r} does not have, {href!r}, is refused: {m}")
    since = len(seen)
    c.send({"t": "runLua", "code": 'echo("ran " .. tostring(labelRan) .. " again\\n")'})
    seen_or_next(c, shown("ran 1 again"), seen, since)
    norths = [m for m in lines_of(seen) if m["text"] == "You said: north"]
    stray = [m for m in seen if m["t"] == "commandLine" and m["text"] not in ("", "tell Bob ")]
    check(len(norths) == 1 and not stray, f"and none of those ran anything: {norths} {stray}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'setLinkStyle("links", "#00ff00", "#ff00ff")'})
    m = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "links" and "#00ff00" in m["text"], seen, since)
    check("#ff00ff" not in m["text"], f"setLinkStyle sends the label again, in the link colour: {m['text'][:300]}")
    since = len(seen)
    web = follow("https://example.org/")
    m = follow("send:n")
    # Every restyle that shows send:n visited comes after the web link's refusal, so it would show that one visited too
    restyled = seen_or_next(c, lambda m: m["t"] == "label" and m["name"] == "links" and '<a href="send:n" style="color: #ff00ff;' in m["text"],
                            seen, since, what=f"the label restyled by {following}")
    check(web is not None and m is None and not any(windows_with("links", "label")(w) for w in seen[since:]),
          f"a link followed is sent again in the visited colour, with no windows message: {restyled['text'][:300]} {web} {m}")
    check(restyled["text"].count("#ff00ff") == 1 and '<a href="https://example.org/" style="color: #00ff00;' in restyled["text"],
          f"and only that link, not the web link refused before it: {restyled['text'][:600]}")
    seen_or_next(c, shown("You said: north"), seen, since, what=f"the game's answer to {following}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def script_text_boxes():
    """What text boxes hold: createTextEdit() and the setTextEdit* functions as a textBox message after its windows
    message, with how many setTextBoxText the engine had; the player's typing, by setTextBoxText, read back by
    getTextEditText() as plain text and not sent back, though a script's text after it is, even the text it had before;
    a hidden or read-only box refusing it with the box as Mudlet holds it; and every box again for a UI that attaches."""
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Text Box Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "early"})
    m = c.wait_for(lambda m: m["t"] == "error", collect=seen)
    check(m["message"] == "setTextBoxText: no profile running", f"setTextBoxText with no profile open is refused: {m}")

    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=30)

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'createTextEdit("notes", 20, 30, 200, 100)',
        'setTextEditText("notes", "first line\\nsecond")',
        'setTextEditPlaceholder("notes", "Write here")',
        'setTextEditStyleSheet("notes", [[QPlainTextEdit { color: red; }]])',
        'setTextEditTabMovesFocus("notes", true)',
    ])})
    m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes" and m["tabMovesFocus"], seen, since)
    # typed counts the refused "early" too, as the UI counts what it sent
    check(m == {"t": "textBox", "name": "notes", "text": "first line\nsecond", "readOnly": False, "placeholder": "Write here",
                "styleSheet": "QPlainTextEdit { color: red; }", "tabMovesFocus": True, "typed": 1},
          f"createTextEdit() and setTextEdit*() give the text box's content: {m}")
    first_box = next(i for i, m in enumerate(seen) if m["t"] == "textBox" and m["name"] == "notes")
    first_window = next((i for i, m in enumerate(seen) if windows_with("notes", "textBox")(m)), len(seen))
    check(first_window < first_box, "the textBox comes after the windows message that has it")
    notes = window_of(seen, "notes", "textBox")
    check(notes is not None and (notes["x"], notes["y"], notes["w"], notes["h"]) == (20, 30, 200, 100) and notes["shown"],
          f"and is a textBox window at its place: {notes}")

    since = len(seen)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "the player\ntyped this"})
    c.send({"t": "runLua", "code": 'echo("box holds [" .. getTextEditText("notes") .. "]\\n")'})
    seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("box holds ["), seen, since)
    c.send({"t": "runLua", "code": 'echo("typed read\\n")'})
    seen_or_next(c, shown("typed read"), seen, since)
    held = [text for text in texts_of(seen[since:]) if text.startswith(("box holds", "typed this"))]
    check(held == ["box holds [the player", "typed this]"], f"setTextBoxText gives getTextEditText() what the player typed: {held}")
    told = [m for m in seen[since:] if m["t"] in ("textBox", "error")]
    check(not told, f"and the player's text is not sent back, nor refused: {told}")

    # A script's text after the player's is sent, even the text the box had before the player typed
    since = len(seen)
    c.send({"t": "runLua", "code": 'setTextEditText("notes", "first line\\nsecond")'})
    m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes", seen, since, timeout=10)
    check(m["text"] == "first line\nsecond" and m["typed"] == 2,
          f"a script setting the text the box had before the player typed sends it, after the player's two: {m}")

    # Nor is the player's text sent back by a change of the windows, which sends any box that differs from Mudlet's
    since = len(seen)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "the player\ntyped this"})
    c.send({"t": "runLua", "code": 'createLabel("other", 0, 0, 10, 10, 1)'})
    seen_or_next(c, windows_with("other", "label"), seen, since)
    c.send({"t": "runLua", "code": 'echo("label made\\n")'})
    seen_or_next(c, shown("label made"), seen, since)
    told = [m for m in seen[since:] if m["t"] in ("textBox", "error")]
    check(not told, f"a windows message after the player's typing sends no textBox for it: {told}")

    # Kept as plain text, as the desktop's box keeps a paste, which a windows message then does not send back either
    since = len(seen)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "pasted\u00a0text\r\nand\u2028more"})
    c.send({"t": "runLua", "code": 'echo("pasted [" .. getTextEditText("notes"):gsub("\\n", "|") .. "]\\n")'})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("pasted ["), seen, since)
    check(m["text"] == "pasted [pasted text|and|more]", f"a non-breaking space, CRLF and line separator are kept as Mudlet keeps them: {m}")
    c.send({"t": "runLua", "code": 'createLabel("other2", 0, 0, 10, 10, 1)'})
    seen_or_next(c, windows_with("other2", "label"), seen, since)
    c.send({"t": "runLua", "code": 'echo("second label made\\n")'})
    seen_or_next(c, shown("second label made"), seen, since)
    told = [m for m in seen[since:] if m["t"] in ("textBox", "error")]
    check(not told, f"and a windows message after the paste sends no textBox for it: {told}")
    pasted = "pasted text\nand\nmore"

    # Each of the others alone sends the box again
    for code, key, value in (('setTextEditPlaceholder("notes", "Other")', "placeholder", "Other"),
                             ('setTextEditTabMovesFocus("notes", false)', "tabMovesFocus", False)):
        since = len(seen)
        c.send({"t": "runLua", "code": code})
        m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes" and m[key] == value, seen, since)
        check(m["text"] == pasted and m["typed"] == 4, f"{code} alone sends the box again, with the player's text: {m}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'hideWindow("notes")'})
    seen_or_next(c, lambda m: m["t"] == "windows" and any(w["name"] == "notes" and not w["shown"] for w in m["windows"]), seen, since)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "hidden"})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("setTextBoxText: ") and "not shown" in m["message"], f"a hidden box refuses the player's typing: {m}")
    m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes", seen, since)
    check(m["text"] == pasted and m["typed"] == 5, f"and is sent again as Mudlet holds it: {m}")
    since = len(seen)
    c.send({"t": "runLua", "code": 'showWindow("notes")'})
    seen_or_next(c, lambda m: m["t"] == "windows" and any(w["name"] == "notes" and w["shown"] for w in m["windows"]), seen, since)

    since = len(seen)
    c.send({"t": "runLua", "code": 'setTextEditReadOnly("notes", true)'})
    m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes" and m["readOnly"], seen, since)
    check(m["text"] == pasted and not any(windows_with("notes", "textBox")(w) for w in seen[since:]),
          f"setTextEditReadOnly() alone sends the box again, with no windows message for it: {m}")
    since = len(seen)
    c.send({"t": "setTextBoxText", "name": "notes", "text": "not taken"})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("setTextBoxText: ") and "read-only" in m["message"], f"a read-only box refuses the player's typing: {m}")
    m = seen_or_next(c, lambda m: m["t"] == "textBox" and m["name"] == "notes", seen, since)
    check(m["text"] == pasted and m["readOnly"] and m["typed"] == 6, f"and is sent again as Mudlet holds it: {m}")
    since = len(seen)
    c.send({"t": "runLua", "code": 'echo("still [" .. getTextEditText("notes") .. "]\\n")'})
    seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"] == "more]", seen, since)
    check(True, "getTextEditText() keeps what it held")
    since = len(seen)
    c.send({"t": "setTextBoxText", "name": "nonesuch", "text": "x"})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("setTextBoxText: ") and "nonesuch" in m["message"], f"and so does a box there is not: {m}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    full = next((i for i, m in enumerate(msgs) if m["t"] == "windows" and m.get("full")), None)
    box = next((i for i, m in enumerate(msgs) if m["t"] == "textBox" and m["name"] == "notes"), None)
    check(full is not None and box is not None and box > full, "a UI that attaches gets every text box after the windows")
    notes = text_box_of(msgs, "notes")
    check(notes is not None and notes["readOnly"] and notes["placeholder"] == "Other" and notes["text"] == pasted and notes["typed"] == 0,
          f"as it was, with its typing counted afresh: {notes}")

    since = len(seen)
    c.send({"t": "runLua", "code": 'deleteTextEdit("notes")'})
    m = seen_or_next(c, lambda m: m["t"] == "windows" and m.get("removed"), seen, since)
    check({"name": "notes", "kind": "textBox"} in m["removed"] and text_box_of(seen[since:], "notes") is None,
          f"a deleted text box is removed, with no textBox message: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def frame_tabs():
    """An MXP frame's tab header (patch 0008): a titled frame's tabs, tabTitles and tabCurrent in windows, its own
    console first, and only the page in front shown; selectFrameTab bringing another forward, where the one in front
    was, and the header's own console; the refusals, a hidden header's among them; the tabs again for a UI that
    attaches; and a page that closes leaving them."""
    game = fake_telnet.start_in_thread()
    proc, c = start_engine("Frame Tabs Test")
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "selectFrameTab", "name": "chat"})
    m = c.wait_for(lambda m: m["t"] == "error", collect=seen)
    check(m["message"] == "selectFrameTab: no profile running", f"selectFrameTab with no profile open is refused: {m}")

    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "connect", "host": "127.0.0.1", "port": game})
    wait_status(c, "connection", lambda v: v == "connected", seen, timeout=30)

    def feed(*tags):
        return [f"feedTriggers([[{tag}]] .. '\\n')" for tag in tags]

    def tabs_of(window):
        return None if window is None or "tabs" not in window else (window["tabs"], window.get("tabTitles"), window.get("tabCurrent"))

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join(["setConfig('specialForceMXPProcessorOn', true)"] + feed(
        '<FRAME Name="tabs" Title="Tabs" Align="left" Width="200" Height="300">',
        '<FRAME Name="chat" Title="Chat" DOCK="tabs" Align="client">',
        '<FRAME Name="log" DOCK="tabs" Align="client">',
        '<FRAME Name="side" Align="right" Width="200" Height="300">',
    ))})
    seen_or_next(c, windows_with("tabs", "mxpFrame", lambda w: len(w.get("tabs", [])) == 3), seen, since)
    seen_or_next(c, windows_with("side", "mxpFrame"), seen, since)
    tabs, chat, log, side = (window_of(seen, name, "mxpFrame") for name in ("tabs", "chat", "log", "side"))
    check(tabs_of(tabs) == (["tabs", "chat", "log"], ["Tabs", "Chat", "log"], 1),
          f"a titled frame has tabs, its own console first, each with its title or name, the first page in front: {tabs}")
    check(chat is not None and log is not None and chat["shown"] and not log["shown"] and not tabs["shown"],
          f"and only the page in front is shown: {chat} {log}")
    check(tabs_of(chat) is None and tabs_of(side) is None, f"a page has no tabs, nor has a frame with no title: {chat} {side}")

    since = len(seen)
    c.send({"t": "selectFrameTab", "name": "log"})
    seen_or_next(c, windows_with("tabs", "mxpFrame", lambda w: w.get("tabCurrent") == 2), seen, since)
    tabs, chat, log = (window_of(seen, name, "mxpFrame") for name in ("tabs", "chat", "log"))
    check(log["shown"] and not chat["shown"] and (log["x"], log["y"], log["w"], log["h"]) == (chat["x"], chat["y"], chat["w"], chat["h"]),
          f"selectFrameTab brings the page forward where the one in front was: {log} {chat}")
    check(not any(m["t"] == "error" for m in seen[since:]), "and is not answered")
    for name in ("side", "nonesuch"):
        since = len(seen)
        c.send({"t": "selectFrameTab", "name": name})
        m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
        check(m["message"].startswith("selectFrameTab: ") and name in m["message"], f"a name that is no page is refused: {m}")

    # A header a player could not click, in an EXTERNAL frame that is hidden, is refused; shown again, its own console
    # comes forward
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join(feed(
        '<FRAME Name="away" EXTERNAL Width="300" Height="200">',
        '<DEST away><FRAME Name="inner" Title="Inner" Align="top" Width="100%" Height="100%"></DEST>',
        '<FRAME Name="innerPage" DOCK="inner" Align="client">',
    ) + ["hideWindow('away')"])})
    seen_or_next(c, windows_with("inner", "mxpFrame", lambda w: len(w.get("tabs", [])) == 2), seen, since)
    seen_or_next(c, windows_with("away", "mxpFrame", lambda w: not w["shown"]), seen, since)
    inner = window_of(seen, "inner", "mxpFrame")
    check(tabs_of(inner) == (["inner", "innerPage"], ["Inner", "innerPage"], 1), f"a frame opened in an EXTERNAL one has tabs: {inner}")
    since = len(seen)
    c.send({"t": "selectFrameTab", "name": "inner"})
    m = seen_or_next(c, lambda m: m["t"] == "error", seen, since)
    check(m["message"].startswith("selectFrameTab: ") and "inner" in m["message"], f"a hidden header's tab is refused: {m}")
    since = len(seen)
    c.send({"t": "runLua", "code": "showWindow('away')"})
    seen_or_next(c, windows_with("away", "mxpFrame", lambda w: w["shown"]), seen, since)
    since = len(seen)
    c.send({"t": "selectFrameTab", "name": "inner"})
    seen_or_next(c, windows_with("inner", "mxpFrame", lambda w: w.get("tabCurrent") == 0), seen, since)
    inner, inner_page = (window_of(seen, name, "mxpFrame") for name in ("inner", "innerPage"))
    check(inner["shown"] and not inner_page["shown"], f"the header's own console comes forward: {inner} {inner_page}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen += msgs
    full = next((m for m in msgs if m["t"] == "windows" and m.get("full")), None)
    tabs = window_of([full] if full else [], "tabs", "mxpFrame")
    check(tabs_of(tabs) == (["tabs", "chat", "log"], ["Tabs", "Chat", "log"], 2), f"a UI that attaches gets the tabs as they are: {tabs}")

    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join(feed('<FRAME log ACTION="close">'))})
    seen_or_next(c, windows_with("tabs", "mxpFrame", lambda w: len(w.get("tabs", [])) == 2), seen, since)
    removed = [r for m in seen[since:] if m["t"] == "windows" for r in m.get("removed", [])]
    tabs = window_of(seen, "tabs", "mxpFrame")
    check({"name": "log", "kind": "mxpFrame"} in removed and tabs_of(tabs) == (["tabs", "chat"], ["Tabs", "Chat"], 1)
          and window_of(seen, "chat", "mxpFrame")["shown"],
          f"a page in front that closes leaves the tabs, and the one to its left comes forward: {removed} {tabs}")
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


def png_chunks(data):
    """A PNG's chunks: the data of each, in the file's order, by kind."""
    chunks, at = {}, 8
    while at < len(data):
        length, kind = struct.unpack(">I4s", data[at:at + 8])
        chunks.setdefault(kind, []).append(data[at + 8:at + 8 + length])
        at += 12 + length
    return chunks


def map_writers_png(data):
    """Whether a PNG is from the engine's writer for maps (MapPng.cpp) and not from Qt's, which takes ten times as
    long: one IDAT, marked as zlib's fastest compression, that is one last block of the fixed Huffman codes."""
    data_chunks = png_chunks(data)[b"IDAT"]
    return len(data_chunks) == 1 and data_chunks[0][:2] == b"\x78\x01" and data_chunks[0][2] & 0b111 == 0b011


def png_pixel(data, x, y):
    """The (r, g, b, a) of a pixel of an 8-bit RGB or RGBA PNG with no interlace, as Qt saves one."""
    width, height = png_size(data)
    chunks = png_chunks(data)
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
    the rooms where the layout puts them; mapPointer selecting a room, walking to it and zooming; setMapScale drawing it
    on more pixels; setMapViewOnly switching to editing; pointer on the mapper window; the map to a UI that attaches, at
    the window's pixels again, and none while it hides."""
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
    check(map_writers_png(png), "the map's PNG is from the engine's own writer, which searches for nothing")

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

    # At the scale the UI shows it at: the same map on twice the pixels, sharp, its size and points still the window's
    since = len(seen)
    c.send({"t": "setMapScale", "scale": 2})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "map" and png_size(base64.b64decode(m["png"])) == (800, 600), seen, since)
        png = base64.b64decode(m["png"])
        check((m["w"], m["h"]) == (400, 300) and png_pixel(png, room2[0] * 2, room2[1] * 2)[:3] == (200, 30, 30)
              and png_pixel(png, room2[0], room2[1])[:3] != (200, 30, 30),
              f"setMapScale 2 sends the map as a PNG of 800 by 600 for the 400 by 300 mapper, room 2 where the "
              f"window's point doubled is: {m['w']}x{m['h']} {png_size(png)}")
    except TimeoutError as e:
        check(False, f"setMapScale 2 sends the map at twice the mapper's pixels: {e}")
    c.send({"t": "mapPointer", "type": "press", "x": room2[0], "y": room2[1], "button": 1})
    got = selection()
    check(got == "selected 2", f"a press at a point of the window still selects the room there: {got}")

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
          and png_size(base64.b64decode(maps[0]["png"])) == (400, 300)
          and kinds.index("windows") < kinds.index("map") < kinds.index("buttonBars"),
          f"a UI that attaches gets the map, after the windows, at the window's pixels, as the UI that asked for "
          f"another scale went: {kinds}")

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
    script's mapper events; centerview() moving the player on it, as it opens the map for scripts; findRooms by name
    and id, sorted and at most 100; showRoom centring the map on a room; setMapViewOnly with only this map open; a
    script's mapper beside it, each with its own centre; a UI that goes stopping it, and 0 by 0."""
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
    check(map_writers_png(png), "the app's map's PNG is from the engine's own writer, which searches for nothing")
    try:
        m = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and red_at(m, room2), seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"a map with rooms sends the app's map with room 2 drawn in its colour: {e}")
        stop_engine(proc)
        return
    first = m
    check(not red_at(m, middle), f"and the player's room is in the middle, not room 2: {pixel(m, middle)}")
    check(not any(m["t"] == "map" for m in seen), "and no script map is sent, as no script opened a mapper")

    # The app's map opens the map for scripts, as opening the GUI's mapper does, so a mapper script's centerview()
    # moves the player on it: to room 2, which is then in the middle under the player's mark and room 1 30 pixels to its
    # left, and back to 1, whichever room the map has first
    player = pixel(first, middle)
    left = (room1[0] - (room2[0] - room1[0]), room1[1])
    since = len(seen)
    c.send({"t": "runLua", "code": 'centerview(2) echo("player " .. tostring(getPlayerRoom()) .. "\\n")'})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("player "), seen, since)
    check(m["text"] == "player 2", f"with only the app's map, scripts see the map open and the player where centerview() put it: {m}")
    try:
        seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and pixel(m, middle) == player and pixel(m, left)[3] == 255
                     and pixel(m, left) != player and not red_at(m, room2), seen, since)
        since = len(seen)
        c.send({"t": "runLua", "code": "centerview(1)"})
        first = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and red_at(m, room2) and pixel(m, middle) == player, seen, since)
        check(True, "the app's map follows the player that centerview() moves, with no script mapper")
    except TimeoutError as e:
        check(False, f"the app's map follows the player that centerview() moves, with no script mapper: {e}")

    # The name that starts with the text first; room 1 is the player's, and 3 has no exit to it
    m = c.ask({"t": "findRooms", "text": "HALL"}, answers=("rooms", "error"), collect=seen)
    area = (m.get("rooms") or [{}])[0].get("areaId")
    check(m == {"t": "rooms", "text": "HALL", "more": False, "re": m.get("re"),
                "rooms": [{"id": 3, "name": "hallway", "areaId": area, "area": "App Map Area", "match": "prefix", "steps": -1},
                          {"id": 1, "name": "Great Hall", "areaId": area, "area": "App Map Area", "match": "contains", "steps": 0}]}
          and area > 0, f"findRooms finds the rooms whose name holds the text, ignoring case, a prefix first: {m}")
    m = c.ask({"t": "findRooms", "text": "2"}, answers=("rooms", "error"), collect=seen)
    check([(r["id"], r["match"], r["steps"]) for r in m.get("rooms", [])] == [(2, "id", 1)], f"and the room whose id it is: {m}")
    m = c.ask({"t": "findRooms", "text": "cellar"}, answers=("rooms", "error"), collect=seen)
    check(m.get("rooms") == [] and m.get("more") is False, f"and none when none match: {m}")
    # 105 cells in an area of their own, named so their names sort against their ids, two by two the same but for case
    c.send({"t": "runLua", "code": "\n".join([
        'local cells = addAreaName("Cells")',
        "for id = 101, 205 do",
        "  addRoom(id)",
        "  setRoomArea(id, cells)",
        "  setRoomCoordinates(id, id, 0, 0)",
        '  setRoomName(id, string.format("%s %d", id % 2 == 0 and "cell" or "CELL", 400 - math.floor(id / 2)))',
        "end",
    ])})
    cells = sorted(((f"{'cell' if i % 2 == 0 else 'CELL'} {400 - i // 2}", i) for i in range(101, 206)), key=lambda c: (c[0].lower(), c[1]))
    m = c.ask({"t": "findRooms", "text": "Cell"}, answers=("rooms", "error"), collect=seen)
    check(m.get("more") is True and [(r["name"], r["id"]) for r in m.get("rooms", [])] == cells[:100]
          and all(r["area"] == "Cells" for r in m["rooms"]),
          f"of 105 rooms found alike (prefixes, no way there), the first 100 by name, ignoring case, then by id, and more: {m.get('more')}, "
          f"{[r['id'] for r in m.get('rooms', [])][:6]}...")

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
    since = len(seen)
    c.send({"t": "showRoom", "room": 2})
    m = seen_or_next(c, lambda m: m["t"] == "error" or (m["t"] == "appMap" and m["w"] and pixel(m, left) == player), seen, since)
    check(m["t"] == "appMap" and pixel(m, middle) != player, f"showRoom centres the app's map on that room: {m.get('message', m['t'])}")
    m = c.ask({"t": "showRoom", "room": 99}, answers=("error",), collect=seen)
    check(m["message"] == "showRoom: There is no room 99 in the map.", f"showRoom of a room not in the map is refused: {m}")

    since = len(seen)
    c.send({"t": "setMapViewOnly", "viewOnly": False})
    seen_or_next(c, shown("map mode editing"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "appMap" and m["viewOnly"] is False, seen, since)
    check(True, "setMapViewOnly works with only the app's map open, and its frame comes again, edited")

    # A script's mapper beside it: each map has its own centre, and a press on the app's is still no script's
    def script_map_centred(m):
        return m["t"] == "map" and (m["w"], m["h"]) == (400, 300) and png_pixel(base64.b64decode(m["png"]), *room2)[:3] == (200, 30, 30)

    since = len(seen)
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "runLua", "code": 'testMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})'})
    try:
        seen_or_next(c, script_map_centred, seen, since, timeout=15)
        # Room 3 is 4 rooms north of room 1, so centred on 3 the app's map has room 1 60 pixels below its middle
        since = len(seen)
        c.send({"t": "showRoom", "room": 3})
        below = (middle[0], middle[1] + 60)
        m = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and pixel(m, below) == player, seen, since)
    except TimeoutError as e:
        check(False, f"with a script's mapper open, showRoom still moves the app's map: {e}")
        stop_engine(proc)
        return
    maps = [m for m in seen if m["t"] == "map"]
    check(all(script_map_centred(m) for m in maps[-1:] + [m for m in seen[since:] if m["t"] == "map"]),
          "with a script's mapper open, showRoom moves the app's map and leaves the script's on the player")
    since = len(seen)
    c.send({"t": "appMapPointer", "type": "press", "x": below[0], "y": below[1], "button": 1})
    seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"], seen, since)
    c.send({"t": "runLua", "code": 'echo("marker\\n")'})
    seen_or_next(c, shown("marker"), seen, since)
    check(not any(m["t"] in ("line", "tail") and m["text"] == "map pressed" for m in seen[since:]),
          "a press on the app's map still sends it again, and raises no script's mapper event")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    kinds = [m["t"] for m in msgs]
    check("appMap" not in kinds, f"a UI that attaches is told nothing of the app's map until it asks: {kinds}")
    m = c.ask({"t": "appMapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, answers=("error",), collect=seen)
    check(m["message"].startswith("appMapPointer: The app's map has not been drawn yet"),
          f"the UI that went took the app's map with it: {m}")
    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 2})
    m = seen_or_next(c, lambda m: m["t"] in ("appMap", "error"), seen, since)
    check(m["t"] == "appMap" and (m["w"], m["h"]) == (400, 300), f"asked for again, it is drawn again: {m}")

    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 0, "h": 0, "scale": 1})
    m = seen_or_next(c, lambda m: m["t"] in ("appMap", "error"), seen, since)
    check(m == empty, f"0 by 0 stops the app's map: {m}")
    m = c.ask({"t": "appMapPointer", "type": "press", "x": 1, "y": 1, "button": 1}, answers=("error",), collect=seen)
    check(m["message"].startswith("appMapPointer: The app's map has not been drawn yet"), f"and lets its view go: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def map_info():
    """The map info box as text, left out of the picture: the built-in Short contributor's part in the app's map, with the
    map's own text colour and the box's colour; a script's registerMapInfo() after it, bold, italic and in its colour,
    in a frame whose picture is as before; disableMapInfo() and setConfig("mapInfoColor"); no info with no contributor
    on but one that gives no text; and the same in a script's mapper."""
    profile = "Map Info Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the map info's profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 1})
    short = {"text": "Great Hall / 1 (Info Area)\n", "bold": False, "italic": False, "color": "#ffffff"}
    script = {"text": "room 1", "bold": True, "italic": True, "color": "#ff8000"}
    grey = {"bg": "#969696", "bgAlpha": 120}
    blue = {"bg": "#0a141e", "bgAlpha": 200}

    def frame_after(code, info, what, t="appMap"):
        """The first frame after the Lua whose info is that, None for a frame with none; failing that, the last frame
        there was, if any, for what else is checked of it."""
        since = len(seen)
        c.send({"t": "runLua", "code": "\n".join(code)})
        try:
            m = seen_or_next(c, lambda m: m["t"] == t and m["w"] and m.get("info") == info, seen, since, timeout=15)
        except TimeoutError:
            frames = [m for m in seen[since:] if m["t"] == t and m["w"]]
            check(False, f"{what}: the frames since had {[m.get('info', 'no info') for m in frames]}")
            return frames[-1] if frames else None
        check(True, what)
        return m

    # The box the desktop paints starts 10 pixels in and down: there the picture is the map's background, as in a
    # corner the box never reaches
    def unpainted(m):
        png = base64.b64decode(m["png"])
        return png_size(png) == (400, 300) and png_pixel(png, 15, 15) == png_pixel(png, 15, 285)

    first = frame_after(['local area = addAreaName("Info Area")', "addRoom(1)", "setRoomArea(1, area)", "setRoomCoordinates(1, 0, 0, 0)",
                         'setRoomName(1, "Great Hall")', "centerview(1)", "updateMap()"],
                        {"lines": [short], **grey}, "a new profile's map info is the Short contributor's part, in the map's text colour over the box's")
    if first is None:
        stop_engine(proc)
        return
    check(unpainted(first), "the app's map has no box painted into its picture")
    both = frame_after(['registerMapInfo("Test", function(room) return "room " .. room, true, true, 255, 128, 0 end)',
                     'enableMapInfo("Test")'],
                    {"lines": [short, script], **grey}, "a script's registerMapInfo() comes after it, bold, italic and in its colour")
    m = frame_after(['disableMapInfo("Short")'], {"lines": [script], **grey}, "disableMapInfo() takes a contributor's part out")
    check(None not in (both, m) and m["png"] == both["png"], "in a frame whose picture is as before")
    frame_after(['setConfig("mapInfoColor", {10, 20, 30, 200})'], {"lines": [script], **blue}, 'setConfig("mapInfoColor") sets the box\'s colour and alpha')
    frame_after(['registerMapInfo("Silent", function() return "" end)', 'enableMapInfo("Silent")', 'disableMapInfo("Test")'], None,
                "with no contributor on but one that says nothing a frame has no info")
    m = frame_after(['enableMapInfo("Short")', 'testMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})'],
                    {"lines": [short], **blue}, "a script's mapper has the map info too", t="map")
    check(m is not None and unpainted(m), "and no box painted into its picture")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def map_moves():
    """Moving a map by touch: view in each appMap and map, with the area, level, zoom and its bounds, following, the
    player's room, the room size and the rooms drawn at their places; moveMap panning and zooming about a point,
    carried as moved, stopping following, clamped at maxZoom; followPlayer and the player's move following again; the
    script's mapper moved apart from the app's map; and the refusals."""
    profile = "Map Moves Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    pan = {"t": "moveMap", "map": "app", "dx": 30, "dy": 0, "zoom": 1, "x": 200, "y": 150, "seq": 1}
    for request in (pan, {"t": "followPlayer", "map": "app"}):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the moves' profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    m = c.ask(pan, answers=("error",), collect=seen)
    check(m["message"] == "moveMap: The app's map has not been drawn yet; it moves what renderAppMap() drew.",
          f"a move of an app's map not drawn is refused: {m}")
    m = c.ask({"t": "followPlayer", "map": "mapper"}, answers=("error",), collect=seen)
    check(m["message"] == "followPlayer: No map is open; a script opens one, as with openMapWidget().",
          f"and of a script's map no script opened: {m}")

    # As in app_map(): the area's first zoom of 20 on a map 300 high is rooms of 15 pixels, room 1 (the player's) in the
    # middle, room 2 two to its east, room 3 four to its north
    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 1})
    c.send({"t": "runLua", "code": "\n".join([
        'local area = addAreaName("Move Area")',
        "for id, place in pairs({[1] = {0, 0}, [2] = {2, 0}, [3] = {0, 4}}) do",
        "  addRoom(id)",
        "  setRoomArea(id, area)",
        "  setRoomCoordinates(id, place[1], place[2], 0)",
        "end",
        'setRoomName(1, "Great Hall")',
        'setRoomName(2, "Kitchen")',
        'setRoomName(3, "Tower")',
        "centerview(1)",
        "updateMap()",
    ])})

    def places(m):
        return {r["id"]: (round(r["x"]), round(r["y"])) for r in m["view"]["rooms"]}

    def viewed(pred):
        return lambda m: m["t"] == "appMap" and m["w"] and "view" in m and pred(m["view"], places(m))

    try:
        m = seen_or_next(c, viewed(lambda v, p: p.get(1) == (200, 150)), seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"the app's map comes with its view: {e}")
        stop_engine(proc)
        return
    v = m["view"]
    check({k: v[k] for k in ("areaName", "level", "following", "player", "moved")}
          == {"areaName": "Move Area", "level": 0, "following": True, "player": 1, "moved": 0}
          and v["area"] > 0 and abs(v["zoom"] - 20) < 0.01 and v["minZoom"] == 3 and v["maxZoom"] == 30
          and abs(v["roomSize"] - 15) < 0.01, f"the view says the area, level, zoom and its bounds, following the player in room 1: {v}")
    check(places(m) == {1: (200, 150), 2: (230, 150), 3: (200, 90)}
          and {r["id"]: r["name"] for r in v["rooms"]} == {1: "Great Hall", 2: "Kitchen", 3: "Tower"},
          f"with the rooms drawn at their places and names: {v['rooms']}")

    since = len(seen)
    c.send(pan)
    try:
        m = seen_or_next(c, viewed(lambda v, p: v["moved"] == 1), seen, since)
        check(places(m) == {1: (230, 150), 2: (260, 150), 3: (230, 90)} and m["view"]["following"] is False,
              f"a pan moves the rooms by it and stops following: {m['view']}")
        since = len(seen)
        c.send({**pan, "dx": 0, "zoom": 2, "x": 230, "y": 150, "seq": 2})
        m = seen_or_next(c, viewed(lambda v, p: v["moved"] == 2), seen, since)
        check(places(m).get(1) == (230, 150) and places(m).get(2) == (290, 150) and abs(m["view"]["zoom"] - 10) < 0.01
              and abs(m["view"]["roomSize"] - 30) < 0.01, f"a zoom by 2 about room 1 doubles the rooms there: {m['view']}")
        since = len(seen)
        c.send({**pan, "dx": 0, "zoom": 0.001, "seq": 3})
        m = seen_or_next(c, viewed(lambda v, p: v["moved"] == 3), seen, since)
        check(abs(m["view"]["zoom"] - 30) < 0.01, f"a zoom out past maxZoom stops there: {m['view']}")
        since = len(seen)
        c.send({"t": "followPlayer", "map": "app"})
        m = seen_or_next(c, viewed(lambda v, p: v["following"] and p.get(1) == (200, 150)), seen, since)
        check(True, "followPlayer centres the map on the player and follows again")
        since = len(seen)
        c.send({**pan, "seq": 4})
        seen_or_next(c, viewed(lambda v, p: v["moved"] == 4 and not v["following"]), seen, since)
        c.send({"t": "runLua", "code": "centerview(2)"})
        m = seen_or_next(c, viewed(lambda v, p: v["following"] and v["player"] == 2 and p.get(2) == (200, 150)), seen, since)
        check(True, "the player's move to another room follows the player again")
    except TimeoutError as e:
        check(False, f"moveMap and followPlayer move the app's map: {e}")
        stop_engine(proc)
        return
    for request, message in (({**pan, "seq": 0}, "seq must be a whole number from 1"), ({**pan, "zoom": -1}, "zoom must be above 0")):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"moveMap: {message}", f"moveMap with {message} is refused: {m}")

    # A script's mapper is moved apart: its frames carry its own moved, and the app's map stays on the player
    since = len(seen)
    c.send({"t": "setMainWindowSize", "w": 1000, "h": 800})
    c.send({"t": "runLua", "code": 'testMapper = Geyser.Mapper:new({name = "map", x = 0, y = 0, width = 400, height = 300})'})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "map" and "view" in m and m["view"]["moved"] == 0, seen, since, timeout=15)
        check(m["view"]["following"] is True and m["view"]["player"] == 2, f"the script's map comes with its view: {m['view']}")
        since = len(seen)
        c.send({**pan, "map": "mapper", "dy": 15, "seq": 5})
        m = seen_or_next(c, lambda m: m["t"] == "map" and "view" in m and m["view"]["moved"] == 5, seen, since)
        check(not m["view"]["following"], f"moveMap of the mapper moves the script's map: {m['view']}")
    except TimeoutError as e:
        check(False, f"moveMap of the mapper moves the script's map: {e}")
    app = [m for m in seen if m["t"] == "appMap" and m["w"]]
    check(app[-1]["view"]["following"] and app[-1]["view"]["moved"] == 4, f"and leaves the app's map as it was: {app[-1].get('view')}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def map_rooms():
    """A room's details and the map's levels and areas: roomInfo with the exits and the steps from the player, which
    go by no locked exit and by special exits; findRooms ranking a prefix, then the player's area, then the steps;
    walkToRoom; showMapLevel and showMapArea on the app's map, with the levels in its view; mapAreas; and the
    refusals."""
    profile = "Map Rooms Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    level = {"t": "showMapLevel", "map": "app", "level": 1}
    for request in ({"t": "roomInfo", "room": 1}, {"t": "walkToRoom", "room": 1}, {"t": "mapAreas"}, level,
                    {"t": "showMapArea", "map": "app", "area": 1}):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the rooms' profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)

    # The Keep: the player in 1, 2 east of it, 3 above it, 4 east of 3 by a locked exit only. The Garden: 5 out of 2,
    # 6 by 2's special exit, 7 nearest the middle of the three. An area with no rooms is listed nowhere.
    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 1})
    c.send({"t": "runLua", "code": "\n".join([
        'local keep, garden = addAreaName("Keep"), addAreaName("Garden")',
        'addAreaName("Empty")',
        "for id, place in pairs({[1] = {keep, 0, 0, 0}, [2] = {keep, 2, 0, 0}, [3] = {keep, 0, 0, 1}, [4] = {keep, 2, 0, 1},",
        "                        [5] = {garden, 10, 10, 0}, [6] = {garden, 14, 10, 0}, [7] = {garden, 11, 10, 0}}) do",
        "  addRoom(id)",
        "  setRoomArea(id, place[1])",
        "  setRoomCoordinates(id, place[2], place[3], place[4])",
        "end",
        'for id, name in pairs({"Great Hall", "Kitchen", "Tower", "Attic", "Rose Garden", "Pond", "Gate"}) do setRoomName(id, name) end',
        'setExit(1, 2, "e") setExit(2, 1, "w") setExit(1, 3, "up") setExit(3, 1, "down") setExit(3, 4, "e") setExit(2, 5, "out")',
        'lockExit(3, "e", true)',
        'addSpecialExit(2, 6, "climb rope")',
        "centerview(1)",
        "updateMap()",
        'function doSpeedWalk() echo("walk " .. table.concat(speedWalkDir, ",") .. " to " .. table.concat(speedWalkPath, ",") .. "\\n") end',
    ])})

    def places(m):
        return {r["id"]: (round(r["x"]), round(r["y"])) for r in m["view"]["rooms"]}

    def viewed(pred):
        return lambda m: m["t"] == "appMap" and m["w"] and "view" in m and pred(m["view"], places(m))

    try:
        m = seen_or_next(c, viewed(lambda v, p: p.get(1) == (200, 150)), seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"the app's map comes with its view: {e}")
        stop_engine(proc)
        return
    keep = m["view"]["area"]
    check(m["view"]["levels"] == [0, 1] and m["view"]["level"] == 0, f"the view lists the area's levels: {m['view']}")

    def info(room):
        return c.ask({"t": "roomInfo", "room": room}, answers=("room", "error"), collect=seen)

    m = info(1)
    check({k: v for k, v in m.items() if k != "re"} == {
        "t": "room", "id": 1, "name": "Great Hall", "areaId": keep, "area": "Keep", "x": 0, "y": 0, "z": 0, "steps": 0,
        "exits": [{"direction": "e", "to": 2, "toName": "Kitchen", "locked": False},
                  {"direction": "up", "to": 3, "toName": "Tower", "locked": False}],
        "symbol": "", "env": -1, "color": "#800000", "weight": 1, "locked": False}, f"roomInfo gives the player's room: {m}")
    m = info(2)
    check(m.get("steps") == 1 and m.get("exits") == [
        {"direction": "w", "to": 1, "toName": "Great Hall", "locked": False},
        {"direction": "out", "to": 5, "toName": "Rose Garden", "locked": False},
        {"direction": "climb rope", "to": 6, "toName": "Pond", "locked": False}], f"the directions, then the special exits: {m}")
    m = info(3)
    check(m.get("exits", [{}])[0] == {"direction": "e", "to": 4, "toName": "Attic", "locked": True}, f"a locked exit says so: {m}")
    steps = {room: info(room).get("steps") for room in (4, 5, 6, 7)}
    check(steps == {4: -1, 5: 2, 6: 2, 7: -1}, f"the steps go by special exits and by no locked exit: {steps}")
    m = info(99)
    check(m.get("message") == "roomInfo: there is no room 99 in the map", f"roomInfo of a room not in the map is refused: {m}")

    m = c.ask({"t": "findRooms", "text": "t"}, answers=("rooms", "error"), collect=seen)
    found = [(r["id"], r["match"], r["steps"]) for r in m.get("rooms", [])]
    check(found == [(3, "prefix", 1), (1, "contains", 0), (2, "contains", 1), (4, "contains", -1), (7, "contains", -1)],
          f"findRooms ranks a prefix first, then the player's area, then the fewest steps, no way there last: {found}")
    m = c.ask({"t": "findRooms", "text": "POND"}, answers=("rooms", "error"), collect=seen)
    check([(r["id"], r["match"]) for r in m.get("rooms", [])] == [(6, "exact")], f"and says a name that is the text: {m}")

    m = c.ask({"t": "mapAreas"}, answers=("areas", "error"), collect=seen)
    check([a["name"] for a in m.get("areas", [])] == ["Garden", "Keep"], f"mapAreas lists the areas with rooms by name: {m}")
    garden = next((a["id"] for a in m.get("areas", []) if a["name"] == "Garden"), 0)

    since = len(seen)
    c.send(level)
    try:
        m = seen_or_next(c, viewed(lambda v, p: v["level"] == 1), seen, since)
        check(m["view"]["area"] == keep and not m["view"]["following"] and places(m).get(3) == (200, 150),
              f"showMapLevel shows the level above about the same point, not following: {m['view']}")
        since = len(seen)
        c.send({"t": "showMapArea", "map": "app", "area": garden})
        m = seen_or_next(c, viewed(lambda v, p: v["area"] == garden), seen, since)
        check(m["view"]["level"] == 0 and m["view"]["levels"] == [0] and not m["view"]["following"]
              and places(m).get(7) == (200, 150), f"showMapArea shows an area about the room nearest its middle: {m['view']}")
        since = len(seen)
        c.send({"t": "showMapArea", "map": "app", "area": keep})
        m = seen_or_next(c, viewed(lambda v, p: v["area"] == keep), seen, since)
        check(m["view"]["following"] and m["view"]["level"] == 0 and places(m).get(1) == (200, 150),
              f"and the player's area following the player: {m['view']}")
    except TimeoutError as e:
        check(False, f"showMapLevel and showMapArea move the app's map: {e}")
    for request, message in (({**level, "level": 5}, "The area shown has no rooms on level 5."),
                             ({**level, "level": 0.5}, "level must be a whole number"),
                             ({"t": "showMapArea", "map": "app", "area": 9999}, "There is no area 9999 with rooms in the map."),
                             ({**level, "map": "mapper"}, "No map is open; a script opens one, as with openMapWidget()."),
                             ({"t": "walkToRoom", "room": 99}, "There is no room 99 in the map.")):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: {message}", f"{request['t']} is refused with {message}: {m}")

    since = len(seen)
    c.send({"t": "walkToRoom", "room": 5})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail", "error") and (m["t"] == "error" or m["text"].startswith("walk ")), seen, since)
    check(m.get("text") == "walk e,out to 2,5", f"walkToRoom walks there by the shortest path: {m}")
    since = len(seen)
    c.send({"t": "walkToRoom", "room": 4})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail", "error") and (m["t"] == "error" or "find a path" in m["text"]), seen, since)
    check("Cannot find a path from 1 to 4" in m.get("text", ""), f"a walk with no way there is told in the console, not refused: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def map_edits():
    """Editing the map: editRoom's name, symbol, environment, weight, lock and place, with the colour that draws it;
    setRoomExit both ways and removed both ways; lockRoomExit; addRoom beside a room with exits both ways; deleteRoom;
    setPlayerRoom, which the app's map then follows; renameArea; mapEnvs with a script's environment; and the refusals."""
    profile = "Map Edits Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    edits = ({"t": "editRoom", "room": 1, "name": "Hall"}, {"t": "setRoomExit", "room": 1, "direction": "e", "to": 2},
             {"t": "lockRoomExit", "room": 1, "direction": "e", "locked": True}, {"t": "addRoom", "room": 1, "direction": "n"},
             {"t": "deleteRoom", "room": 1}, {"t": "setPlayerRoom", "room": 1}, {"t": "renameArea", "area": 1, "name": "Keep"},
             {"t": "mapEnvs"})
    for request in edits:
        m = c.ask(request, answers=("error", "room", "envs"), collect=seen)
        check(m.get("message") == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the edits' profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)

    # The Keep: the player in 1, 2 two east of it with no exits between; the Garden: 3, far off
    since = len(seen)
    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 1})
    c.send({"t": "runLua", "code": "\n".join([
        'local keep, garden = addAreaName("Keep"), addAreaName("Garden")',
        "for id, place in pairs({[1] = {keep, 0, 0, 0}, [2] = {keep, 2, 0, 0}, [3] = {garden, 10, 10, 0}}) do",
        "  addRoom(id)",
        "  setRoomArea(id, place[1])",
        "  setRoomCoordinates(id, place[2], place[3], place[4])",
        "end",
        'setRoomName(1, "Hall") setRoomName(2, "Yard") setRoomName(3, "Pond")',
        "setCustomEnvColor(200, 10, 20, 30, 255)",
        "centerview(1)",
        "updateMap()",
    ])})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"] and "view" in m and m["view"]["player"] == 1, seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"the app's map comes with its view: {e}")
        stop_engine(proc)
        return
    keep = m["view"]["area"]

    def ask(request):
        return c.ask(request, answers=("room", "envs", "areas", "error"), collect=seen)

    def info(room):
        return ask({"t": "roomInfo", "room": room})

    m = ask({"t": "mapEnvs"})
    envs = {e["env"]: e["color"] for e in m.get("envs", [])}
    check([e["env"] for e in m.get("envs", [])] == [200] + list(range(257, 273)) and envs[200] == "#0a141e"
          and all(re.fullmatch("#[0-9a-f]{6}", color) for color in envs.values()),
          f"mapEnvs lists the custom environments, the profile's sixteen colours among them, by number: {m}")

    m = ask({"t": "editRoom", "room": 1, "name": "Great Hall", "symbol": "H", "env": 200, "weight": 3, "locked": True})
    check({k: m.get(k) for k in ("t", "id", "name", "symbol", "env", "color", "weight", "locked")} == {
        "t": "room", "id": 1, "name": "Great Hall", "symbol": "H", "env": 200, "color": "#0a141e", "weight": 3, "locked": True},
        f"editRoom sets the room's name, symbol, environment, weight and lock, answered by the room: {m}")
    m = ask({"t": "editRoom", "room": 1, "locked": False, "x": 0, "y": 1, "z": 0})
    check((m.get("x"), m.get("y"), m.get("z"), m.get("locked"), m.get("name")) == (0, 1, 0, False, "Great Hall"),
          f"and its place, keeping what it does not set: {m}")
    ask({"t": "editRoom", "room": 1, "x": 0, "y": 0, "z": 0})

    m = ask({"t": "setRoomExit", "room": 1, "direction": "e", "to": 2, "bothWays": True})
    check(m.get("exits") == [{"direction": "e", "to": 2, "toName": "Yard", "locked": False}], f"setRoomExit leads the exit there: {m}")
    m = info(2)
    check(m.get("exits") == [{"direction": "w", "to": 1, "toName": "Great Hall", "locked": False}], f"and the exit back, both ways: {m}")
    m = ask({"t": "lockRoomExit", "room": 1, "direction": "e", "locked": True})
    check(m.get("exits") == [{"direction": "e", "to": 2, "toName": "Yard", "locked": True}], f"lockRoomExit locks it: {m}")
    m = ask({"t": "setRoomExit", "room": 1, "direction": "e", "to": 0, "bothWays": True})
    check(m.get("exits") == [] and info(2).get("exits") == [], f"setRoomExit to 0 removes it, and the exit back: {m}")

    m = ask({"t": "addRoom", "room": 1, "direction": "n"})
    added = m.get("id")
    check(m.get("t") == "room" and added not in (None, 1, 2, 3) and (m.get("areaId"), m.get("x"), m.get("y"), m.get("z")) == (keep, 0, 1, 0)
          and m.get("exits") == [{"direction": "s", "to": 1, "toName": "Great Hall", "locked": False}],
          f"addRoom makes a room a place north, in the same area, answered by it with its exit back: {m}")
    check(info(1).get("exits") == [{"direction": "n", "to": added, "toName": "", "locked": False}], "and the exit to it")
    c.send({"t": "deleteRoom", "room": added})
    m = info(added)
    check(m.get("message") == f"roomInfo: there is no room {added} in the map", f"deleteRoom removes the room: {m}")
    check(info(1).get("exits") == [], "and the exits to it")

    since = len(seen)
    c.send({"t": "setPlayerRoom", "room": 2})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "error" or (m["t"] == "appMap" and "view" in m and m["view"]["player"] == 2), seen, since)
        check(m["t"] == "appMap", f"setPlayerRoom places the player, and the app's map follows: {m}")
    except TimeoutError as e:
        check(False, f"setPlayerRoom places the player: {e}")
    check(info(2).get("steps") == 0, "the room the player is in is 0 steps away")

    c.send({"t": "renameArea", "area": keep, "name": "  Castle "})
    m = ask({"t": "mapAreas"})
    check([a["name"] for a in m.get("areas", [])] == ["Castle", "Garden"], f"renameArea renames the area, trimmed: {m}")

    for request, message in (({"t": "editRoom", "room": 1, "weight": 0}, "A room's weight is from 1; 0 is not."),
                             ({"t": "editRoom", "room": 1}, "The edit changes nothing of the room."),
                             ({"t": "editRoom", "room": 99, "name": "Nowhere"}, "There is no room 99 in the map."),
                             ({"t": "setRoomExit", "room": 1, "direction": "e", "to": 99}, "There is no room 99 in the map."),
                             ({"t": "setRoomExit", "room": 1, "direction": "climb", "to": 2},
                              "An exit goes one of n, ne, nw, e, w, s, se, sw, up, down, in and out; climb is not."),
                             ({"t": "lockRoomExit", "room": 1, "direction": "n", "locked": True}, "Room 1 has no exit n."),
                             ({"t": "addRoom", "room": 1, "direction": "in"},
                              "A new room goes one of n, ne, nw, e, w, s, se, sw, up and down; in is not."),
                             ({"t": "addRoom", "room": 1, "direction": "e"}, None),
                             ({"t": "deleteRoom", "room": 99}, "There is no room 99 in the map."),
                             ({"t": "setPlayerRoom", "room": 99}, "There is no room 99 in the map."),
                             ({"t": "renameArea", "area": keep, "name": "Garden"}, "Another area is already named Garden."),
                             ({"t": "renameArea", "area": keep, "name": "  "}, "An area's name is not empty, and spaces are trimmed from its ends."),
                             ({"t": "renameArea", "area": 9999, "name": "Moor"}, "There is no area 9999 in the map that can be renamed.")):
        if message is None:
            # A place east is free, but two east is the Yard: a second room east of the first is refused
            first = ask(request)
            request = {**request, "room": first.get("id")}
            message = "Room 2 is already in that place."
        m = ask(request)
        check(m.get("message") == f"{request['t']}: {message}", f"{request['t']} is refused with {message}: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


class MapServer(http.server.BaseHTTPRequestHandler):
    """The game's web server for map_download(): FILES by path, /slow.dat trickling 400000 bytes over 20 seconds, so a
    download of it can be cancelled, and 404 for any other path, with its reason from REASONS if there is one."""
    FILES = {}
    PATHS = []
    REASONS = {}

    def log_message(self, *args):
        pass

    def do_GET(self):
        MapServer.PATHS.append(self.path)
        if self.path == "/slow.dat":
            self.send_response(200)
            self.send_header("Content-Length", "400000")
            self.end_headers()
            try:
                for _ in range(400):
                    self.wfile.write(bytes(1000))
                    self.wfile.flush()
                    time.sleep(0.05)
            except OSError:
                pass
            return
        body = MapServer.FILES.get(self.path)
        if body is None:
            self.send_error(404, MapServer.REASONS.get(self.path, "Not Found"))
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def map_download_percent(m):
    return min(max(m["value"] * 100 // m["maximum"], 0), 100) if m["maximum"] > 0 else -1


def map_download():
    """The map the game offers with GMCP's Client.Map: mapDownload with no more of its URL than the host, though it holds
    a user name, a password and a token; downloadMap loading it, a Mudlet binary map or an XML one, in place of the
    profile's, with progress at most once a percent, no cancel once the map has arrived, sysMapDownloadEvent, the rooms
    then found and the app's map drawn; a UI that attaches told of it; a download that fails, with Mudlet's reason, or is
    cancelled, leaving the map as it was; the refusals; a script's map export and import, whose progress is not the
    download's, even an export's read with downloadMap; and the user name, password and token in no frame or log, nor in
    the reason a failure gives when the server's own words repeat them, however the password is encoded, and with a
    space in the user name."""
    # A password that reads differently percent-encoded, as the URL holds it
    user, password, encoded = "mapuser", "p@ss:w/rd%never", "p%40ss%3Aw%2Frd%25never"
    token = "sekrit-token"
    masked_secrets.update({"the URL's user name": user, "the URL's password": password,
                           "the URL's password encoded": encoded, "the URL's token": token})
    served = os.path.join(TMP, "served")
    os.makedirs(served, exist_ok=True)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), MapServer)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    http_port = server.server_address[1]
    fake_telnet.MAP_URL_BASE = f"http://{user}:{encoded}@127.0.0.1:{http_port}"
    # A version far past any Mudlet's: refused before the profile's map is touched
    MapServer.FILES["/garbage.dat"] = b"\x7f\xff\xff\xff" + b"not a map " * 100
    MapServer.FILES["/map.xml"] = (b'<?xml version="1.0" encoding="UTF-8"?>\n<map><areas><area id="5" name="Xml Area"/></areas>'
                                   b'<rooms><room id="7" area="5" title="Xml room"><coord x="0" y="0" z="0"/></room></rooms></map>\n')
    port = fake_telnet.start_in_thread()
    profile = "Map Download Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    check(not any(m["t"] == "mapDownload" for m in seen), "with no profile open, a UI that attaches hears of no map download")
    for request in ({"t": "downloadMap"}, {"t": "cancelMapDownload"}):
        m = c.ask(request, answers=("error",), collect=seen)
        check(m["message"] == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")

    def download(**fields):
        return lambda m: m["t"] == "mapDownload" and all(m.get(k) == v for k, v in fields.items())

    nothing = {"t": "mapDownload", "offered": False, "host": "", "state": "none", "label": "", "value": 0, "maximum": 0,
               "cancelable": False, "error": ""}
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": port, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the map download's profile is made: {m}")
    since = len(seen)
    c.send({"t": "openProfile", "name": profile})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "mapDownload", seen, since, timeout=30)
    except TimeoutError as e:
        check(False, f"a profile that loads sends mapDownload: {e}")
        stop_engine(proc)
        return
    check(m == nothing, f"a profile whose game has offered no map: {m}")
    m = c.ask({"t": "downloadMap"}, answers=("error",), collect=seen)
    check(m["message"] == "downloadMap: The game has offered no map to download.", f"downloadMap with no map offered is refused: {m}")

    # Three rooms saved where the server serves them, then gone from the profile's map, so only the download brings them back
    map_file = os.path.join(served, "map.dat")
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        'local area = addAreaName("Downloaded Area")',
        "for id = 1, 3 do",
        "  addRoom(id)",
        "  setRoomArea(id, area)",
        "  setRoomCoordinates(id, id * 2, 0, 0)",
        '  setRoomName(id, "Downloaded room " .. id)',
        "end",
        "centerview(1)",
        f"local saved = saveMap({json.dumps(map_file)})",
        "for id = 1, 3 do deleteRoom(id) end",
        'registerAnonymousEventHandler("sysMapDownloadEvent", function() echo("map downloaded\\n") end)',
        'echo("saved " .. tostring(saved) .. "\\n")',
    ])})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("saved "), seen, since, timeout=15)
    check(m["text"] == "saved true" and os.path.isfile(map_file), f"the game's map is saved to be served: {m}")
    with open(map_file, "rb") as f:
        MapServer.FILES["/map.dat"] = f.read()
    m = c.ask({"t": "findRooms", "text": "Downloaded"}, answers=("rooms", "error"), collect=seen)
    check(m.get("rooms") == [], f"and its rooms are gone from the profile's map: {m}")

    seen_or_next(c, lambda m: m["t"] in ("line", "tail") and "Red bold" in m["text"], seen, 0, timeout=15)
    since = len(seen)
    c.send({"t": "send", "text": "map map.dat"})
    try:
        m = seen_or_next(c, download(offered=True), seen, since, timeout=15)
    except TimeoutError as e:
        check(False, f"the game's Client.Map offers its map: {e}")
        stop_engine(proc)
        return
    check(m == dict(nothing, offered=True, host="127.0.0.1"), f"the game offers its map, from its host only: {m}")

    c.send({"t": "setAppMapSize", "w": 400, "h": 300, "scale": 1})
    c.send({"t": "runLua", "code": 'echo("marker\\n")'})
    seen_or_next(c, shown("marker"), seen, since)
    since = len(seen)
    c.send({"t": "downloadMap"})
    try:
        loaded = seen_or_next(c, download(state="loaded"), seen, since, timeout=30)
    except TimeoutError as e:
        check(False, f"downloadMap downloads and loads the game's map: {e}")
        stop_engine(proc)
        return
    states = [m for m in seen[since:] if m["t"] == "mapDownload"]
    check(states[0] == dict(nothing, offered=True, host="127.0.0.1", state="downloading", cancelable=True),
          f"downloadMap starts the download, which can be cancelled: {states[0]}")
    check(loaded == dict(nothing, offered=True, host="127.0.0.1", state="loaded"), f"and it ends loaded: {loaded}")
    check(all(m["state"] == "downloading" for m in states[:-1]), f"with only progress between: {[m['state'] for m in states]}")
    check(any(not m["cancelable"] for m in states[1:-1]), f"which cannot be cancelled once the map has arrived: {states}")
    check("/map.dat" in MapServer.PATHS, f"from the URL the game offered: {MapServer.PATHS}")
    seen_or_next(c, shown("map downloaded"), seen, since)
    check(True, "a loaded map raises sysMapDownloadEvent")
    check(any("Map download initiated" in t for t in texts_of(seen[since:])), "Mudlet says so in the console, as on the desktop")
    m = c.ask({"t": "findRooms", "text": "Downloaded"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [1, 2, 3], f"the downloaded map's rooms are found: {m}")
    # The app's map was empty, so any frame with an image is the downloaded map's. Mudlet can ask for the repaint while it
    # loads the map, so the frame can come before loaded, and the same frame after it is not sent again.
    try:
        seen_or_next(c, lambda m: m["t"] == "appMap" and m["w"], seen, since, timeout=15)
        check(True, "and the app's map draws the downloaded map")
    except TimeoutError:
        frames = [(i - seen.index(loaded), m["w"]) for i, m in enumerate(seen) if i >= since and m["t"] == "appMap"]
        check(False, f"and the app's map draws the downloaded map: none with an image; (place against loaded, w): {frames}")

    c.close()
    c = Client(SOCK)
    msgs = []
    c.wait_for(lambda m: m["t"] == "synced", collect=msgs)
    seen.extend(msgs)
    check([m for m in msgs if m["t"] == "mapDownload"] == [loaded], f"a UI that attaches is told of it: {[m['t'] for m in msgs]}")

    # The same host offers another map, which is not there: the download fails with the server's reason
    since = len(seen)
    c.send({"t": "send", "text": f"map nosuchmap.dat?token={token}"})
    seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
    c.send({"t": "downloadMap"})
    try:
        m = seen_or_next(c, download(state="failed"), seen, since, timeout=30)
        check(m["error"].startswith("Map download encountered an error: ") and "Not Found" in m["error"]
              and "127.0.0.1" in m["error"] and not m["cancelable"],
              f"a map the server does not have fails, with Mudlet's reason, untagged and on one line: {m}")
        check("nosuchmap" not in m["error"] and token not in m["error"],
              f"naming the URL by its host only, without its path or query: {m}")
    except TimeoutError as e:
        check(False, f"a map the server does not have fails: {e}")

    # A server whose reason repeats the URL's user name, password and token, which Mudlet's console shows as the server's
    # words: the engine's reason still names the host only, the password masked. The game writes the password's
    # percent-encoding in lower case, and encodes a '~' that need not be, which Qt normalises to upper case and '~'; the
    # server repeats it decoded, as Qt shows it (PrettyDecoded: '@', '/' and '~' decoded) and encodes it, and as the
    # game wrote it, as it is and with each hex digit in the other case. The user name is an e-mail address, whose '@',
    # repeated decoded, is not where the host begins, nor is another address's '@' in another URL the reason gives.
    echo_user, echo_password, echo_token = "echouser@mail.example", "Ec7q@Zt4k:Wm9v/Rb2d%Hn6j~", "echo-token-5Kw"
    echo_encoded, echo_written = "Ec7q%40Zt4k%3AWm9v%2FRb2d%25Hn6j~", "Ec7q%40Zt4k%3aWm9v%2fRb2d%25Hn6j%7E"
    echo_shown = "Ec7q@Zt4k%3AWm9v/Rb2d%25Hn6j~"
    echo_other_case = re.sub(r"%[0-9A-Fa-f]{2}", lambda m: m[0].swapcase(), echo_written)
    # A user name with a space, which would end a URL in text, that the server repeats decoded in the URL; and the URL's
    # query, which it repeats after the path alone
    spaced_user, spaced_password, spaced_token = "john doe", "ws-pass-8Qd", "ws-token-3Rt"
    echo_secrets = {"the password": echo_password, "the password encoded": echo_encoded,
                    "the password as written": echo_written, "the password as Qt shows it": echo_shown,
                    "the password in the other hex case": echo_other_case, "the user name": echo_user,
                    "the token": echo_token,
                    "the user name with a space": spaced_user, "its URL's password": spaced_password,
                    "its URL's token": spaced_token}
    # The decoded password's '/' and '@' would end a URL early, leaving pieces of it should it go unmasked
    echo_pieces = {f"the password's piece {i}": piece for i, piece in enumerate(re.findall(r"[A-Za-z0-9]{4,}", echo_password))}
    masked_secrets.update({**echo_secrets, **echo_pieces})
    base = fake_telnet.MAP_URL_BASE

    def failed_from(url_base, path, what):
        """The failed mapDownload of path, offered from url_base; None, with what as a FAIL, should it not come."""
        since = len(seen)
        fake_telnet.MAP_URL_BASE = url_base
        try:
            c.send({"t": "send", "text": f"map {path}"})
            seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
        finally:
            fake_telnet.MAP_URL_BASE = base
        c.send({"t": "downloadMap"})
        try:
            return seen_or_next(c, download(state="failed"), seen, since, timeout=30)
        except TimeoutError as e:
            check(False, f"{what}: {e}")
            return None

    MapServer.REASONS["/echo.dat?token=" + echo_token] = (
        f"No map for http://{echo_user}:{echo_password}@127.0.0.1:{http_port}/echo.dat?token={echo_token} whose password "
        f"is {echo_encoded}, {echo_shown}, {echo_written} or {echo_other_case} - try "
        f"http://guest@mail.example:guest@127.0.0.1:{http_port}/echo.dat")
    echo_masked = "whose password is ***, ***, *** or ***"
    echo = len(seen)
    m = failed_from(f"http://{echo_user.replace('@', '%40')}:{echo_written}@127.0.0.1:{http_port}",
                    f"echo.dat?token={echo_token}", "a map the server repeats the URL for fails")
    if m is not None:
        check(f"No map for http://127.0.0.1:{http_port} {echo_masked}" in m["error"],
              f"a server's reason that repeats the URL names its host only, the password masked in each form: {m['error']}")
        urls = set(re.findall(r"[A-Za-z][A-Za-z0-9+.-]*://\S*", m["error"]))
        check(urls == {f"http://127.0.0.1:{http_port}"}, f"and a user name's '@' does not stand for the host: {urls}")
    MapServer.REASONS["/spaced.dat?token=" + spaced_token] = (
        f"No map for http://{spaced_user}:{spaced_password}@127.0.0.1:{http_port}/spaced.dat - ask again at "
        f"/spaced.dat?token={spaced_token}")
    m = failed_from(f"http://{spaced_user.replace(' ', '%20')}:{spaced_password}@127.0.0.1:{http_port}",
                    f"spaced.dat?token={spaced_token}", "a map whose user name has a space fails")
    if m is not None:
        check(f"No map for http://127.0.0.1:{http_port} - ask again at " in m["error"],
              f"a URL the server repeats with a user name with a space in it names its host only: {m['error']}")
        check(m["error"].endswith(" /spaced.dat?***"), f"and the query after the path is masked: {m['error']}")
    # A password that is a word of the server's reason in another case: masked as it is, the word kept
    MapServer.REASONS["/gone.dat"] = "Gone: no map here, it is gone"
    masked_secrets["the password that is a word"] = "Gone"
    m = failed_from(f"http://caseuser:Gone@127.0.0.1:{http_port}", "gone.dat",
                    "a map whose password is a word of the reason fails")
    if m is not None:
        check(m["error"].endswith(" ***: no map here, it is gone"),
              f"a password that is a word of the reason in another case is masked as it is, the word kept: {m['error']}")
    since = len(seen)
    c.send({"t": "send", "text": "map garbage.dat"})
    seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
    c.send({"t": "downloadMap"})
    try:
        m = seen_or_next(c, download(state="failed"), seen, since, timeout=30)
        check("failure in parsing destination file" in m["error"], f"a file that is no map fails as Mudlet reads it: {m}")
    except TimeoutError as e:
        check(False, f"a file that is no map fails: {e}")
    m = c.ask({"t": "findRooms", "text": "Downloaded"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [1, 2, 3], f"and the profile's map is as it was: {m}")

    # A download that has not arrived can be cancelled, and only once
    since = slow = len(seen)
    c.send({"t": "send", "text": "map slow.dat"})
    seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
    c.send({"t": "downloadMap"})
    try:
        m = seen_or_next(c, download(state="downloading", cancelable=True), seen, since, timeout=15)
        m = seen_or_next(c, lambda m: m["t"] == "mapDownload" and m["state"] == "downloading" and m["value"] > 0, seen, since,
                         timeout=15)
        check(m["label"] != "" and m["maximum"] > 0 and m["cancelable"], f"a download sends its progress: {m}")
    except TimeoutError as e:
        check(False, f"a download sends its progress: {e}")
    m = c.ask({"t": "downloadMap"}, answers=("error",), collect=seen)
    check(m["message"] == "downloadMap: the map is already downloading", f"a second downloadMap is refused: {m}")
    since = len(seen)
    c.send({"t": "cancelMapDownload"})
    m = seen_or_next(c, lambda m: m["t"] == "error" or (m["t"] == "mapDownload" and m["state"] != "downloading"), seen, since,
                     timeout=15)
    check(m == dict(nothing, offered=True, host="127.0.0.1", state="cancelled"), f"cancelMapDownload cancels it: {m}")
    progress = [m for m in seen[slow:] if m["t"] == "mapDownload" and m["state"] == "downloading" and m["maximum"] > 0]
    repeats = [(a, b) for a, b in zip(progress, progress[1:])
               if map_download_percent(a) == map_download_percent(b)
               and (a["label"], a["maximum"], a["cancelable"]) == (b["label"], b["maximum"], b["cancelable"])]
    check(len(progress) > 1 and not repeats,
          f"its progress comes at most once a percent: {len(progress)} frames, the same percent twice in {repeats[:2]}")
    m = c.ask({"t": "cancelMapDownload"}, answers=("error",), collect=seen)
    check(m["message"] == "cancelMapDownload: no map is downloading", f"and a second is refused: {m}")
    m = c.ask({"t": "findRooms", "text": "Downloaded"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [1, 2, 3], f"and the profile's map is as it was: {m}")

    # An XML map, as MMP games serve, read by Mudlet's XML import
    since = len(seen)
    c.send({"t": "send", "text": "map map.xml"})
    seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
    since = len(seen)
    c.send({"t": "downloadMap"})
    try:
        loaded = seen_or_next(c, download(state="loaded"), seen, since, timeout=30)
        check(loaded == dict(nothing, offered=True, host="127.0.0.1", state="loaded"), f"an XML map downloads and loads: {loaded}")
    except TimeoutError as e:
        check(False, f"an XML map downloads and loads: {e}")
    states = [m for m in seen[since:] if m["t"] == "mapDownload"]
    check(any(m["state"] == "downloading" and not m["cancelable"] for m in states),
          f"and cannot be cancelled once it has arrived: {states}")
    check("/map.xml" in MapServer.PATHS, f"from the URL the game offered: {MapServer.PATHS}")
    seen_or_next(c, shown("map downloaded"), seen, since)
    check(True, "a loaded XML map raises sysMapDownloadEvent")
    m = c.ask({"t": "findRooms", "text": "Xml"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [7], f"the XML map's rooms are found: {m}")
    m = c.ask({"t": "findRooms", "text": "Downloaded"}, answers=("rooms", "error"), collect=seen)
    check(m.get("rooms") == [], f"in place of the map before it: {m}")

    # A script's map export and import, which Mudlet shows the progress of as it does the download's: not the download's.
    # Headless, a script's map functions work once the app's map has been opened, by the setAppMapSize above.
    json_map = os.path.join(TMP, "script-map.json")
    since = len(seen)
    c.send({"t": "runLua", "code": "\n".join([
        f"local saved = saveJsonMap({json.dumps(json_map)})",
        f"local loaded = loadJsonMap({json.dumps(json_map)})",
        'echo("json " .. tostring(saved) .. " " .. tostring(loaded) .. "\\n")',
    ])})
    m = seen_or_next(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("json "), seen, since, timeout=15)
    check(m["text"] == "json true true", f"a script exports the map as JSON and imports it again: {m}")
    # Its progress is relayed from the event loop: a marker after it has come after any
    c.send({"t": "runLua", "code": 'echo("json marker\\n")'})
    seen_or_next(c, shown("json marker"), seen, since)
    script_frames = [m for m in seen[since:] if m["t"] == "mapDownload"]
    check(not script_frames, f"its progress is not the download's: {script_frames}")
    m = c.ask({"t": "findRooms", "text": "Xml"}, answers=("rooms", "error"), collect=seen)
    check([r["id"] for r in m.get("rooms", [])] == [7], f"and the map is the one it exported: {m}")

    # A script's export and a downloadMap the engine reads in the same pass: the export's last progress, relayed from the
    # event loop once the download has begun, is not the download's either, which can be cancelled until it arrives
    since = len(seen)
    c.send({"t": "send", "text": "map slow.dat"})
    seen_or_next(c, shown("Map offered"), seen, since, timeout=15)
    since = len(seen)
    send_together(c, {"t": "runLua", "code": f'echo("d1 " .. tostring(saveJsonMap({json.dumps(json_map)})) .. "\\n")'},
                  {"t": "downloadMap"})
    try:
        seen_or_next(c, lambda m: m["t"] == "mapDownload" and m["state"] == "downloading" and m["value"] > 0, seen, since,
                     timeout=15)
    except TimeoutError as e:
        check(False, f"a download read with a script's export sends its progress: {e}")
    cancel = len(seen)
    c.send({"t": "cancelMapDownload"})
    m = seen_or_next(c, lambda m: m["t"] == "error" or (m["t"] == "mapDownload" and m["state"] != "downloading"), seen, cancel,
                     timeout=15)
    # The export ran, before the download began: else the checks below would hold with nothing to keep off it
    exported = seen_or_none(c, lambda m: m["t"] in ("line", "tail") and m["text"].startswith("d1 "), seen, since)
    check(exported is not None and exported["text"] == "d1 true", f"the script's export read with downloadMap succeeds: {exported}")
    downloading = [m for m in seen[since:] if m["t"] == "mapDownload" and m["state"] == "downloading"]
    check(downloading and not any("JSON" in m["label"] for m in downloading),
          f"a download read with a script's export shows none of the export's progress: {[m['label'] for m in downloading]}")
    check(all(m["cancelable"] for m in downloading), f"and can be cancelled until the map arrives: {downloading}")
    check(m == dict(nothing, offered=True, host="127.0.0.1", state="cancelled"), f"and cancelMapDownload cancels it: {m}")
    if m["t"] == "error":
        # The slow map's 20 seconds, for the next profile not to close this one mid-download
        seen_or_none(c, lambda m: m["t"] == "mapDownload" and m["state"] != "downloading", seen, cancel, timeout=30)

    # Another profile: the first one's offer goes with it
    other = "Map Download Other"
    c.ask({"t": "saveProfile", "name": other, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    since = len(seen)
    c.send({"t": "openProfile", "name": other, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{other} loaded", seen)
    closing_frames = [m for m in seen[since:] if m["t"] == "mapDownload"]
    check(closing_frames == [nothing], f"a profile that closes takes its offer with it: {closing_frames}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    for secret in (password, encoded, user, token):
        profile_checks.password_kept_out(check, secret, seen, LOG)
    # Mudlet's console has the server's words, the echoed ones too; no other frame, nor the engine's log, has any of them.
    # The pieces only in mapDownload, as an image's base64 can hold any four characters.
    since_echo = [m for m in seen[echo:] if m["t"] not in ("line", "tail")]
    with open(LOG, errors="replace") as f:
        log = f.read()
    logged = [line for line in log.splitlines() if "MudletEngine: the map download from" in line]
    check(any(echo_masked in line for line in logged), f"the engine logs the failure: {logged[-3:]}")
    echo_downloads = [m for m in since_echo if m["t"] == "mapDownload"]
    for secrets, frames in ((echo_secrets, since_echo), (echo_pieces, echo_downloads)):
        for label, secret in secrets.items():
            check(not any(secret in repr(m) for m in frames), f"no frame but the console's carries {label} the server's reason repeats")
            check(secret not in log, f"the engine's log does not have {label} the server's reason repeats")
    server.shutdown()


def profile_settings():
    """A profile's settings by getConfig()'s keys: getSettings reading them as getConfig() gives them, an option by its
    name, whatever a script has put in getConfig's place; setSettings changing them as setConfig() does and answering
    with what the profile now holds and what it refused, with why; autoClearInputLine, which Mudlet starts off, deciding
    whether a send empties the input line; the change in the profile's save; and both refused while no profile is
    open."""
    profile = "Settings Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    for request in ({"t": "getSettings", "keys": ["blankLinesBehaviour"]}, {"t": "setSettings", "values": {"blankLinesBehaviour": "hide"}}):
        m = c.ask(request, answers=("settings", "error"), collect=seen)
        check(m == {"t": "error", "message": f"{request['t']}: no profile running", "re": m.get("re")},
              f"{request['t']} with no profile open is refused: {m}")

    c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    c.send({"t": "runLua", "code": "getConfig = function() return 'a script' end setConfig = getConfig"})
    wait_status(c, "luaResult", lambda v: True, seen)
    m = c.ask({"t": "getSettings", "keys": ["blankLinesBehaviour", "showSentText", "announceIncomingText", "noSuchSetting"]},
              answers=("settings", "error"), collect=seen)
    values = m.get("values", {})
    check(values.get("blankLinesBehaviour") == "show" and values.get("showSentText") in ("never", "always", "script")
          and isinstance(values.get("announceIncomingText"), bool) and "noSuchSetting" not in values and "refused" not in m,
          f"getSettings reads them as Mudlet's own getConfig() gives them, an option by its name: {m}")

    m = c.ask({"t": "setSettings", "values": {"blankLinesBehaviour": "hide", "announceIncomingText": "yes", "noSuchSetting": True}},
              answers=("settings", "error"), collect=seen)
    values, refused = m.get("values", {}), m.get("refused", {})
    check(values.get("blankLinesBehaviour") == "hide" and isinstance(values.get("announceIncomingText"), bool)
          and "noSuchSetting" not in values, f"setSettings answers with what the profile holds now: {m}")
    check(sorted(refused) == ["announceIncomingText", "noSuchSetting"] and "noSuchSetting" in refused.get("noSuchSetting", "")
          and refused.get("announceIncomingText"), f"and what it refused, with why: {refused}")
    m = c.ask({"t": "getSettings", "keys": ["blankLinesBehaviour"]}, answers=("settings", "error"), collect=seen)
    check(m.get("values") == {"blankLinesBehaviour": "hide"}, f"getSettings reads the change back: {m}")

    def emptied_by_a_send():
        """The commandLine frames a send gives, up to the answer to a request made after it."""
        since = len(seen)
        c.send({"t": "send", "text": "look"})
        c.ask({"t": "getSettings", "keys": ["autoClearInputLine"]}, answers=("settings", "error"), collect=seen)
        return [x["text"] for x in seen[since:] if x["t"] == "commandLine"]

    m = c.ask({"t": "getSettings", "keys": ["autoClearInputLine"]}, answers=("settings", "error"), collect=seen)
    check(m.get("values") == {"autoClearInputLine": False}, f"Mudlet starts a profile keeping the line it sent: {m}")
    emptied = emptied_by_a_send()
    check(emptied == [], f"a profile that keeps the line it sent empties nothing after a send: {emptied}")
    m = c.ask({"t": "setSettings", "values": {"autoClearInputLine": True}}, answers=("settings", "error"), collect=seen)
    check(m.get("values") == {"autoClearInputLine": True} and "refused" not in m, f"autoClearInputLine changes: {m}")
    emptied = emptied_by_a_send()
    check(emptied == [""], f"a profile that clears the line empties it after each send: {emptied}")

    wanted = {"sslIgnoreExpired": True, "sslIgnoreSelfSigned": True, "sslIgnoreAll": True, "acceptServerGUI": False,
              "acceptServerMedia": False, "echoLuaErrors": True}
    m = c.ask({"t": "setSettings", "values": wanted}, answers=("settings", "error"), collect=seen)
    check(m.get("values") == wanted and "refused" not in m, f"the security and script error settings change too: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    save = newest_save(profile)[0]
    with open(save, errors="replace") if save else open(os.devnull) as f:
        saved = f.read()
    check('blankLineBehaviour="Hide"' in saved and 'mAcceptServerGUI="no"' in saved and 'mSslIgnoreAll="yes"' in saved
          and 'autoClearCommandLineAfterSend="yes"' in saved,
          f"the changes are in the profile's save: {save}")


def script_items():
    """Triggers, aliases, scripts, timers, keys and buttons as the desktop's script editor edits them: checkLua saying whether Lua compiles,
    and where it does not, without running it; scriptItems listing them as it does,
    without the temporary ones; addScriptItem at a place among a folder's items; editScriptItem saving as its save does,
    a trigger that does not compile switched off with its error, a trigger that runs, an alias given no name and one
    that would call itself refused, a script run with its event handler, a timer firing, an offset timer, a key named by
    its key, a button on its bar, and what a list shows under each name; enableScriptItem, moveScriptItem and
    deleteScriptItem; the edits kept by the save and read back once the profile is opened again; and the refusals."""
    profile = "Script Items Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)

    def ask(request):
        return c.ask(request, answers=("scriptItems", "scriptItem", "luaCheck", "error"), collect=seen)

    m = ask({"t": "checkLua", "code": "local mob = matches[2]\nif mob then\n  send(\"kill \" .. mob)\nend"})
    check((m["t"], m.get("ok")) == ("luaCheck", True) and "line" not in m, f"Lua that compiles checks out, with no profile open: {m}")
    m = ask({"t": "checkLua", "code": "local mob = matches[2]\nif mob then\n  send(\"kill \" .. mob\nend"})
    check((m.get("ok"), m.get("line"), m.get("message")) == (False, 4, "')' expected (to close '(' at line 3) near 'end'"),
          f"Lua that does not compile gives the line and what Lua says: {m}")
    m = ask({"t": "checkLua", "code": 7})
    check(m.get("message") == "checkLua: code must be a string", f"checkLua of what is no string is refused: {m}")

    requests = ({"t": "scriptItems", "kind": "trigger"}, {"t": "scriptItem", "kind": "trigger", "item": 1},
                {"t": "addScriptItem", "kind": "trigger", "parent": 0, "position": 0, "folder": False, "name": "x"},
                {"t": "editScriptItem", "kind": "trigger", "item": 1, "fields": {}},
                {"t": "enableScriptItem", "kind": "trigger", "item": 1, "enabled": True},
                {"t": "moveScriptItem", "kind": "trigger", "item": 1, "parent": 0, "position": 0},
                {"t": "deleteScriptItem", "kind": "trigger", "item": 1})
    for request in requests:
        m = ask(request)
        check(m.get("message") == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    m = c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
               "autoReconnect": False}, collect=seen)
    check(m["t"] == "profiles", f"the script items' profile is made: {m}")
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)

    def lua(code):
        """Where in seen what the code shows starts."""
        since = len(seen)
        c.send({"t": "runLua", "code": code})
        wait_status(c, "luaResult", lambda v: True, seen)
        return since

    def shown_after(since, text):
        return [m["text"] for m in seen[since:] if m["t"] in ("line", "tail") and text in m["text"]]

    def wait_text(text, since):
        """The text of the first line since that holds text, waiting for one if none came yet."""
        already = shown_after(since, text)
        if already:
            return already[0]
        return c.wait_for(lambda m: m["t"] in ("line", "tail") and text in m["text"], timeout=10, collect=seen)["text"]

    def add(kind, parent, position, name, folder=False):
        return ask({"t": "addScriptItem", "kind": kind, "parent": parent, "position": position, "folder": folder, "name": name})

    def edit(kind, item, **fields):
        return ask({"t": "editScriptItem", "kind": kind, "item": item, "fields": fields})

    def listed(kind):
        return ask({"t": "scriptItems", "kind": kind}).get("items", [])

    def children(kind, parent):
        return [i["name"] for i in listed(kind) if i["parent"] == parent]

    m = ask({"t": "checkLua", "code": 'checked = true echo("checkLua ran this\\n")'})
    check(m.get("ok") is True, f"Lua is checked with a profile open too: {m}")
    since = lua('echo("checked is " .. tostring(checked) .. "\\n")')
    check("checked is nil" in wait_text("checked is ", since) and not shown_after(0, "checkLua ran this"), "and what is checked is not run")

    since = lua('permRegexTrigger("Lua made", "", {"^lua$"}, "") echo("temp " .. tempTrigger("zzz", "") .. "\\n")')
    temp = int(re.search(r"temp (\d+)", wait_text("temp ", since)).group(1))
    m = ask({"t": "scriptItems", "kind": "trigger"})
    names = [i["name"] for i in m.get("items", [])]
    check(m.get("kind") == "trigger" and "Lua made" in names and temp not in [i["id"] for i in m.get("items", [])],
          f"scriptItems lists the triggers a script made, but not the temporary ones: {m}")
    m = ask({"t": "scriptItem", "kind": "trigger", "item": temp})
    check(m.get("message") == f"scriptItem: There is no item {temp} of that kind.", f"nor gives one: {m}")

    m = add("trigger", 0, 9999, "Combat", folder=True)
    fields = m.get("fields", {})
    combat = fields.get("id")
    check(m.get("t") == "scriptItem" and m.get("kind") == "trigger" and {k: fields.get(k) for k in (
        "parent", "name", "folder", "enabled", "active", "package", "error", "script", "patterns", "lineDelta")} == {
        "parent": 0, "name": "Combat", "folder": True, "enabled": True, "active": False, "package": "", "error": "",
        "script": "", "patterns": [], "lineDelta": -1} and [i["name"] for i in listed("trigger")][-1:] == ["Combat"],
        f"addScriptItem makes a folder past the end, switched on but not running until saved: {m}")
    m = edit("trigger", combat)
    check(m.get("fields", {}).get("active") is True, f"an edit that changes nothing saves it, and it runs: {m}")
    hit = add("trigger", combat, 0, "Hit").get("fields", {}).get("id")
    miss = add("trigger", combat, 0, "Miss").get("fields", {}).get("id")
    dodge = add("trigger", combat, 1, "Dodge").get("fields", {}).get("id")
    check(children("trigger", combat) == ["Miss", "Dodge", "Hit"], f"each at its place among the folder's items: {listed('trigger')}")
    c.send({"t": "moveScriptItem", "kind": "trigger", "item": hit, "parent": combat, "position": 0})
    c.send({"t": "moveScriptItem", "kind": "trigger", "item": miss, "parent": combat, "position": 99})
    check(children("trigger", combat) == ["Hit", "Dodge", "Miss"], f"moveScriptItem moves them within it: {listed('trigger')}")
    inner = add("trigger", combat, 99, "Inner", folder=True).get("fields", {}).get("id")
    c.send({"t": "moveScriptItem", "kind": "trigger", "item": dodge, "parent": inner, "position": 0})
    check(children("trigger", combat) == ["Hit", "Miss", "Inner"] and children("trigger", inner) == ["Dodge"],
          f"and into another folder: {listed('trigger')}")
    c.send({"t": "deleteScriptItem", "kind": "trigger", "item": inner})
    check(children("trigger", combat) == ["Hit", "Miss"] and ask({"t": "scriptItem", "kind": "trigger", "item": dodge}).get("t") == "error",
          f"deleteScriptItem deletes a folder with what is in it: {listed('trigger')}")

    m = edit("trigger", hit, script="echo(")
    fields = m.get("fields", {})
    check(fields.get("error") and fields.get("enabled") is False and fields.get("active") is False,
          f"a trigger saved with no patterns, and Lua that does not compile, is switched off with its error: {m}")
    error = fields.get("error")
    m = ask({"t": "enableScriptItem", "kind": "trigger", "item": hit, "enabled": True})
    check(m.get("message") == f"enableScriptItem: {error}", f"and refused when switched on, with its error: {m}")
    patterns = [{"text": "^You hit the (\\w+)$", "type": "regex"}, {"text": "1", "type": "spacer"}, {"text": "rat", "type": "substring"}]
    m = edit("trigger", hit, script='echo("struck " .. matches[2] .. "\\n")', patterns=patterns[:1], highlight=True,
             highlightFg="#FF0000", highlightBg="", lineDelta=3, fireLength=2)
    fields = m.get("fields", {})
    check({k: fields.get(k) for k in ("error", "enabled", "active", "patterns", "highlight", "highlightFg", "highlightBg",
                                       "lineDelta", "fireLength")} == {
        "error": "", "enabled": False, "active": False, "patterns": patterns[:1], "highlight": True, "highlightFg": "#ff0000",
        "highlightBg": "", "lineDelta": -1, "fireLength": 2},
        f"one that compiles keeps the fields given, -1 lines apart for one pattern, but stays off as it was switched off: {m}")
    m = ask({"t": "enableScriptItem", "kind": "trigger", "item": hit, "enabled": True})
    check((m.get("fields", {}).get("enabled"), m.get("fields", {}).get("active")) == (True, True), f"enableScriptItem switches it on: {m}")
    wait_text("struck rat", lua('feedTriggers("You hit the rat\\n")'))
    ask({"t": "enableScriptItem", "kind": "trigger", "item": hit, "enabled": False})
    since = lua('feedTriggers("You hit the bat\\n") echo("all fed\\n")')
    wait_text("all fed", since)
    check(not shown_after(since, "struck bat"), f"and off, so it no longer fires: {seen[since:]}")
    m = edit("trigger", miss, patterns=patterns, lineDelta=2)
    fields = m.get("fields", {})
    check((fields.get("patterns"), fields.get("lineDelta"), fields.get("active")) == (patterns, 2, True),
          f"a new trigger of several patterns keeps its lines apart, and runs once saved: {m}")

    alias = add("alias", 0, 9999, "").get("fields", {}).get("id")
    m = edit("alias", alias, pattern="^k (\\w+)$", script='echo("kicked " .. matches[2] .. "\\n")')
    fields = m.get("fields", {})
    check((fields.get("name"), fields.get("pattern"), fields.get("active")) == ("^k (\\w+)$", "^k (\\w+)$", True),
          f"an alias given no name takes its pattern, and runs once saved: {m}")
    wait_text("kicked rat", lua('expandAlias("k rat", false)'))
    m = edit("alias", alias, command="k again")
    check(m.get("message") == "editScriptItem: The alias's replacement matches its own pattern, so it would call itself forever.",
          f"an alias that would call itself is refused: {m}")
    check(ask({"t": "scriptItem", "kind": "alias", "item": alias}).get("fields", {}).get("command") == "", "and left as it was")

    script = add("script", 0, 9999, "onPing").get("fields", {}).get("id")
    since = len(seen)
    m = edit("script", script, eventHandlers=["testPing"],
             script='function onPing(event) echo("pinged " .. event .. "\\n") end echo("script ran\\n")')
    check(m.get("fields", {}).get("eventHandlers") == ["testPing"] and m.get("fields", {}).get("active") is True,
          f"a script is saved with its event handlers: {m}")
    wait_text("script ran", since)
    wait_text("pinged testPing", lua('raiseEvent("testPing")'))
    broken = add("script", 0, 9999, "Broken").get("fields", {}).get("id")
    m = edit("script", broken, script="function (")
    check(m.get("fields", {}).get("error") and m.get("fields", {}).get("active") is False,
          f"a script that does not compile is saved with its error, and does not run: {m}")
    c.send({"t": "deleteScriptItem", "kind": "script", "item": broken})

    summaries = {i["id"]: i["summary"] for i in listed("trigger")}
    check((summaries.get(miss), summaries.get(combat)) == ("^You hit the (\\w+)$\n1\nrat", ""),
          f"a trigger's summary is its patterns, one a line: {summaries}")
    check([i["summary"] for i in listed("alias") if i["id"] == alias] == ["^k (\\w+)$"]
          and [i["summary"] for i in listed("script") if i["id"] == script] == ["testPing"],
          "an alias's is its pattern and a script's its event handlers")

    m = add("timer", 0, 9999, "Ticks", folder=True)
    fields = m.get("fields", {})
    ticks = fields.get("id")
    check(m.get("kind") == "timer" and {k: fields.get(k) for k in ("folder", "enabled", "active", "summary")} == {
        "folder": True, "enabled": False, "active": False, "summary": ""}, f"a new timer folder is switched off: {m}")
    m = add("timer", ticks, 0, "Tick")
    fields = m.get("fields", {})
    tick = fields.get("id")
    check({k: fields.get(k) for k in ("parent", "enabled", "active", "command", "time", "offset", "summary")} == {
        "parent": ticks, "enabled": False, "active": False, "command": "", "time": 0, "offset": False, "summary": "00:00:00.000"},
        f"and so is a new timer, with no time: {m}")
    m = edit("timer", tick, script='echo("tick\\n")')
    error = m.get("fields", {}).get("error")
    check(bool(error) and m.get("fields", {}).get("active") is False, f"a timer saved with no time has that for its error: {m}")
    m = ask({"t": "enableScriptItem", "kind": "timer", "item": tick, "enabled": True})
    check(m.get("message") == f"enableScriptItem: {error}", f"and is refused when switched on: {m}")
    m = edit("timer", tick, time=300, command="")
    fields = m.get("fields", {})
    check((fields.get("time"), fields.get("summary"), fields.get("error"), fields.get("enabled")) == (300, "00:00:00.300", "", False),
          f"one given a time keeps it, and stays off: {m}")
    ask({"t": "enableScriptItem", "kind": "timer", "item": ticks, "enabled": True})
    m = ask({"t": "enableScriptItem", "kind": "timer", "item": tick, "enabled": True})
    check((m.get("fields", {}).get("enabled"), m.get("fields", {}).get("active")) == (True, True), f"switched on in a folder that is on, it runs: {m}")
    wait_text("tick", len(seen))
    ask({"t": "enableScriptItem", "kind": "timer", "item": tick, "enabled": False})
    since = lua('permTimer("Tock", "Tick", 0.1, [[echo("tock\\n")]])')
    tock = [i for i in listed("timer") if i["name"] == "Tock"]
    m = ask({"t": "scriptItem", "kind": "timer", "item": tock[0]["id"] if tock else 1})
    check(tock and tock[0]["parent"] == tick and m.get("fields", {}).get("offset") is True,
          f"a timer inside another timer is an offset timer: {m}")
    m = edit("timer", tock[0]["id"] if tock else 1, offset=False)
    check(m.get("message") == 'editScriptItem: "offset" is not a field of that kind that can be changed.', f"which is only read: {m}")

    m = add("key", 0, 9999, "")
    fields = m.get("fields", {})
    key = fields.get("id")
    check(m.get("kind") == "key" and {k: fields.get(k) for k in ("enabled", "active", "key", "modifiers", "keyName", "summary")} == {
        "enabled": True, "active": False, "key": 0, "modifiers": 0, "keyName": "", "summary": ""},
        f"a new key is switched on, with no key chosen: {m}")
    m = edit("key", key, command="north")
    check(m.get("fields", {}).get("error") and m.get("fields", {}).get("active") is False, f"saved with none, that is its error: {m}")
    # Qt::Key_8 with Control and the number pad
    m = edit("key", key, key=0x38, modifiers=0x24000000)
    fields = m.get("fields", {})
    check(fields.get("keyName") and (fields.get("name"), fields.get("summary")) == (fields.get("keyName"),) * 2
          and (fields.get("key"), fields.get("modifiers"), fields.get("error"), fields.get("active")) == (0x38, 0x24000000, "", True),
          f"given a key, one with no name takes the key's, and runs: {m}")
    key_name = fields.get("keyName")

    m = add("button", 0, 9999, "Bar", folder=True)
    fields = m.get("fields", {})
    bar = fields.get("id")
    check(m.get("kind") == "button" and (fields.get("enabled"), fields.get("location"), fields.get("orientation")) == (
        False, "top", "horizontal"), f"a new toolbar is switched off, at the top, its buttons side by side: {m}")
    sword = add("button", bar, 0, "Sword").get("fields", {}).get("id")
    m = edit("button", sword, commandDown="wield sword", commandUp="remove sword", pushDown=True)
    fields = m.get("fields", {})
    check((fields.get("commandDown"), fields.get("commandUp"), fields.get("pushDown"), fields.get("summary")) == (
        "wield sword", "remove sword", True, "wield sword"), f"a button keeps its commands, and shows the one it sends: {m}")
    m = edit("button", bar, location="left", orientation="horizontal", columns=2, css="QToolBar { color: red; }")
    fields = m.get("fields", {})
    check((fields.get("location"), fields.get("orientation"), fields.get("columns"), fields.get("css")) == (
        "left", "horizontal", 2, "QToolBar { color: red; }"), f"a toolbar keeps its place, direction, columns and style: {m}")
    since = len(seen)
    ask({"t": "enableScriptItem", "kind": "button", "item": sword, "enabled": True})
    ask({"t": "enableScriptItem", "kind": "button", "item": bar, "enabled": True})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "buttonBars" and bar_named(m, "Bar"), seen, since, timeout=10)
        shown = bar_named(m, "Bar")
        check((shown["place"], shown["vertical"], shown["columns"]) == ("left", False, 2)
              and [(b["name"], b["pushDown"]) for b in shown["buttons"]] == [("Sword", True)],
              f"switched on, the toolbar is sent in buttonBars as it was set: {shown}")
    except TimeoutError as e:
        check(False, f"switching a toolbar on sends buttonBars: {e}")
    quick = add("button", 0, 9999, "Quick", folder=True).get("fields", {}).get("id")
    look = add("button", quick, 0, "Look").get("fields", {}).get("id")
    since = len(seen)
    ask({"t": "enableScriptItem", "kind": "button", "item": look, "enabled": True})
    ask({"t": "enableScriptItem", "kind": "button", "item": quick, "enabled": True})
    try:
        m = seen_or_next(c, lambda m: m["t"] == "buttonBars" and bar_named(m, "Quick"), seen, since, timeout=10)
        shown = bar_named(m, "Quick")
        check((shown["place"], shown["vertical"]) == ("top", False) and [b["name"] for b in shown["buttons"]] == ["Look"],
              f"a new toolbar is drawn as soon as it is switched on, without an edit: {shown}")
    except TimeoutError as e:
        check(False, f"switching on a toolbar that was never edited sends buttonBars: {e}")
    for item in (look, quick):
        c.send({"t": "deleteScriptItem", "kind": "button", "item": item})

    # The temporary trigger made above sits among the top level's items, between "Lua made" and "Combat"
    last = add("trigger", 0, 9999, "Last").get("fields", {}).get("id")
    top = children("trigger", 0)
    between = add("trigger", 0, top.index("Last") if "Last" in top else 0, "Between").get("fields", {}).get("id")
    check(children("trigger", 0)[-3:] == ["Combat", "Between", "Last"],
          f"a place among the top level's items counts only those shown, not the temporary ones: {listed('trigger')}")
    for item in (between, last):
        c.send({"t": "deleteScriptItem", "kind": "trigger", "item": item})

    for request, message in (
            ({"t": "scriptItems", "kind": "gauge"}, "kind must be trigger, alias, script, timer, key or button"),
            ({"t": "scriptItem", "kind": "alias", "item": 0}, "item must be a whole number from 1"),
            ({"t": "scriptItem", "kind": "alias", "item": hit + 999}, f"There is no item {hit + 999} of that kind."),
            ({"t": "addScriptItem", "kind": "trigger", "parent": -1, "position": 0, "folder": False, "name": "x"},
             "parent must be a whole number from 0"),
            ({"t": "addScriptItem", "kind": "trigger", "parent": 0, "position": 0, "folder": "no", "name": "x"},
             "folder must be true or false"),
            ({"t": "addScriptItem", "kind": "trigger", "parent": hit, "position": 0, "folder": False, "name": "x"},
             f"There is no folder {hit} of that kind."),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": []}, "fields must be an object"),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"id": 3}}, '"id" is not a field of that kind that can be changed.'),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"pattern": "x"}}, '"pattern" is not a field of that kind that can be changed.'),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"name": 3}}, 'The value given for "name" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"lineDelta": -2}}, 'The value given for "lineDelta" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"highlightFg": "red"}}, 'The value given for "highlightFg" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "trigger", "item": hit, "fields": {"patterns": [{"text": "x", "type": "later"}]}},
             'The value given for "patterns" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "script", "item": script, "fields": {"eventHandlers": "testPing"}},
             'The value given for "eventHandlers" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "timer", "item": tick, "fields": {"time": 86400000}}, 'The value given for "time" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "key", "item": key, "fields": {"modifiers": 1}}, 'The value given for "modifiers" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "key", "item": key, "fields": {"keyName": "F1"}}, '"keyName" is not a field of that kind that can be changed.'),
            ({"t": "editScriptItem", "kind": "button", "item": bar, "fields": {"location": "bottom"}}, 'The value given for "location" is not one it takes.'),
            ({"t": "editScriptItem", "kind": "button", "item": bar, "fields": {"columns": 0}}, 'The value given for "columns" is not one it takes.'),
            ({"t": "addScriptItem", "kind": "timer", "parent": tick, "position": 0, "folder": False, "name": "x"},
             f"There is no folder {tick} of that kind."),
            ({"t": "enableScriptItem", "kind": "trigger", "item": hit, "enabled": 1}, "enabled must be true or false"),
            ({"t": "moveScriptItem", "kind": "trigger", "item": combat, "parent": combat, "position": 0},
             "A folder cannot be moved into itself or into a folder inside it."),
            ({"t": "moveScriptItem", "kind": "trigger", "item": miss, "parent": hit, "position": 0}, f"There is no folder {hit} of that kind."),
            ({"t": "deleteScriptItem", "kind": "trigger", "item": temp}, f"There is no item {temp} of that kind.")):
        m = ask(request)
        check(m.get("message") == f"{request['t']}: {message}", f"{request['t']} is refused with {message}: {m}")
    check(children("trigger", combat) == ["Hit", "Miss"], "and the refusals changed nothing")

    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    triggers = {i["name"]: i for i in listed("trigger")}
    combat = triggers.get("Combat", {}).get("id")
    check(children("trigger", combat) == ["Hit", "Miss"] and triggers.get("Hit", {}).get("enabled") is False and triggers.get("Miss", {}).get("active") is True,
          f"the save kept the triggers, in their order, each on or off: {triggers}")
    m = ask({"t": "scriptItem", "kind": "trigger", "item": triggers.get("Miss", {}).get("id", 0)})
    check((m.get("fields", {}).get("patterns"), m.get("fields", {}).get("lineDelta")) == (patterns, 2), f"with their fields: {m}")
    scripts = [i["id"] for i in listed("script") if i["name"] == "onPing"]
    m = ask({"t": "scriptItem", "kind": "script", "item": scripts[0] if scripts else 1})
    check(m.get("fields", {}).get("eventHandlers") == ["testPing"], f"and the script its event handlers: {m}")
    check([i["name"] for i in listed("alias")].count("^k (\\w+)$") == 1, "and the alias")
    timers = {i["name"]: i for i in listed("timer")}
    check(timers.get("Tick", {}).get("summary") == "00:00:00.300" and timers.get("Tick", {}).get("parent") == timers.get("Ticks", {}).get("id")
          and timers.get("Tock", {}).get("parent") == timers.get("Tick", {}).get("id"), f"and the timers: {timers}")
    check([i["summary"] for i in listed("key") if i["name"] == key_name] == [key_name], "and the key")
    buttons = {i["name"]: i for i in listed("button")}
    m = ask({"t": "scriptItem", "kind": "button", "item": buttons.get("Bar", {}).get("id", 1)})
    check(buttons.get("Sword", {}).get("enabled") is True and (m.get("fields", {}).get("location"), m.get("fields", {}).get("columns")) == ("left", 2),
          f"and the buttons: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")



def silent_wav():
    """A tenth of a second of silence, as a WAV file's bytes."""
    rate, frames = 8000, 800
    data = bytes(frames * 2)
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


def sounds():
    """The sounds message: nothing played and nothing muted as a profile loads, a preload not taken for a sound, a sound
    a script plays reported once, desktop's two mutes as setSettings changes them through getConfig()'s muteMediaAPI
    and muteMediaGame, and the mutes kept in Mudlet.ini, where desktop keeps them, from one run of the engine to the
    next."""
    profile = "Sounds Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    first = c.wait_for(lambda m: m["t"] == "sounds", collect=seen)
    check(first == {"t": "sounds", "played": False, "scriptsMuted": False, "gameMuted": False},
          f"a profile loads with no sound played and none muted: {first}")

    wav = "".join(f"\\{b}" for b in silent_wav())
    c.send({"t": "runLua", "code": f'lfs.mkdir(getMudletHomeDir() .. "/media") '
                                   f'local f = io.open(getMudletHomeDir() .. "/media/quiet.wav", "wb") f:write("{wav}") f:close() '
                                   'playSoundFile({name = "quiet.wav", volume = 0})'})
    wait_status(c, "luaResult", lambda v: True, seen)
    since = len(seen)
    c.ask({"t": "getSettings", "keys": ["muteMediaAPI"]}, answers=("settings", "error"), collect=seen)
    check(not [m for m in seen[since:] if m["t"] == "sounds"], "a preload, which plays nothing, is not a sound played")

    c.send({"t": "runLua", "code": 'playSoundFile({name = "quiet.wav"})'})
    played = c.wait_for(lambda m: m["t"] == "sounds", collect=seen)
    check(played == {"t": "sounds", "played": True, "scriptsMuted": False, "gameMuted": False}, f"a sound a script plays is reported: {played}")
    since = len(seen)
    c.send({"t": "runLua", "code": 'playSoundFile({name = "quiet.wav"})'})
    wait_status(c, "luaResult", lambda v: True, seen)
    c.ask({"t": "getSettings", "keys": ["muteMediaAPI"]}, answers=("settings", "error"), collect=seen)
    check(not [m for m in seen[since:] if m["t"] == "sounds"], "only the first sound is news")
    c.close()
    c = Client(SOCK)
    seen = []
    again = c.wait_for(lambda m: m["t"] == "sounds", collect=seen)
    check(again == played, f"a UI that attaches again is told a sound has played: {again}")

    m = c.ask({"t": "setSettings", "values": {"muteMediaAPI": True, "muteMediaGame": True}}, answers=("settings", "error"), collect=seen)
    check(m.get("values") == {"muteMediaAPI": True, "muteMediaGame": True} and "refused" not in m, f"setSettings mutes both: {m}")
    muted = c.wait_for(lambda m: m["t"] == "sounds" and m["scriptsMuted"] and m["gameMuted"], collect=seen)
    check(muted["played"], f"and the sounds message follows: {muted}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")

    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    kept = c.wait_for(lambda m: m["t"] == "sounds", collect=seen)
    check(kept == {"t": "sounds", "played": False, "scriptsMuted": True, "gameMuted": True},
          f"the next run of the engine keeps the mutes, with no sound played yet: {kept}")
    m = c.ask({"t": "setSettings", "values": {"muteMediaAPI": False}}, answers=("settings", "error"), collect=seen)
    one = c.wait_for(lambda m: m["t"] == "sounds" and not m["scriptsMuted"], collect=seen)
    check(one["gameMuted"], f"each mute changes alone: {one}")
    c.ask({"t": "setSettings", "values": {"muteMediaGame": False}}, answers=("settings", "error"), collect=seen)
    c.wait_for(lambda m: m["t"] == "sounds" and not m["gameMuted"], collect=seen)
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def package_file(name, folder, details, icon=None):
    """A Mudlet package as the desktop's exporter makes one: a zip of its config.lua, its XML with a script, and its icon
    in .mudlet/Icon."""
    path = os.path.join(folder, f"{name}.mpackage")
    config = "".join(f'{key} = [[{value}]]\n' for key, value in dict(mpackage=name, **details).items())
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE MudletPackage>\n<MudletPackage version="1.001"><ScriptPackage>'
           f'<Script isActive="yes" isFolder="no"><name>{name} script</name><packageName>{name}</packageName>'
           f'<script>_G["{name} loaded"] = true</script><eventHandlerList/></Script></ScriptPackage></MudletPackage>\n')
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("config.lua", config)
        z.writestr(f"{name}.xml", xml)
        if icon:
            png_file(os.path.join(folder, icon), 4, 4)
            z.write(os.path.join(folder, icon), f".mudlet/Icon/{icon}")
    return path


def packages():
    """A profile's packages as the desktop's Package Manager handles them: packages listing them in the order installed,
    with their details, the description's $packagePath standing for the package's folder and the icon's file;
    installPackage answering with packageInstalled, raising sysInstall and running the package's script, then
    packagesChanged; the same package again left as it is; a file that cannot be read failing; uninstallPackage raising
    sysUninstall and answering with what is left; and the refusals."""
    profile = "Packages Test"
    folder = os.path.join(TMP, "packages")
    os.makedirs(folder, exist_ok=True)
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)

    def ask(request):
        return c.ask(request, answers=("packages", "error"), collect=seen)

    for request in ({"t": "packages"}, {"t": "installPackage", "path": "/nowhere.mpackage"}, {"t": "uninstallPackage", "name": "x"}):
        m = ask(request)
        check(m.get("message") == f"{request['t']}: no profile running", f"{request['t']} with no profile open is refused: {m}")
    c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)
    c.send({"t": "runLua", "code": "\n".join([
        'registerAnonymousEventHandler("sysInstall", function(_, name) echo("sysInstall " .. name .. "\\n") end)',
        'registerAnonymousEventHandler("sysUninstall", function(_, name) echo("sysUninstall " .. name .. "\\n") end)',
    ])})
    wait_status(c, "luaResult", lambda v: True, seen)

    m = ask({"t": "packages"})
    before = [p["name"] for p in m.get("packages", [])]
    check(m["t"] == "packages" and "Pack Test" not in before, f"packages lists what the profile has, its default packages: {m}")

    path = package_file("Pack Test", folder, {"title": "A test package", "author": "Mudlet", "version": "1.2",
                                              "description": "Kept at $packagePath", "icon": "pack.png"}, icon="pack.png")
    plain = os.path.join(folder, "Plain.xml")
    with open(plain, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE MudletPackage>\n<MudletPackage version="1.001"></MudletPackage>\n')
    since = len(seen)
    c.send({"t": "installPackage", "path": path})
    m = seen_or_next(c, lambda m: m["t"] == "packageInstalled", seen, since, timeout=15)
    check(m == {"t": "packageInstalled", "path": path, "result": "installed", "error": ""}, f"installPackage answers that it installed: {m}")
    seen_or_next(c, shown("sysInstall Pack Test"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "packagesChanged", seen, since)
    check(True, "it raises sysInstall, and packagesChanged follows")
    m = ask({"t": "packages"})
    listed = m.get("packages", [])
    pack = next((p for p in listed if p["name"] == "Pack Test"), {})
    home = os.path.join(CONFIG, "profiles", profile, "Pack Test")
    check([p["name"] for p in listed] == before + ["Pack Test"], f"it is listed last, in the order installed: {listed}")
    check(pack.get("title") == "A test package" and pack.get("author") == "Mudlet" and pack.get("version") == "1.2"
          and pack.get("description") == f"Kept at {home}" and pack.get("icon") == os.path.join(home, ".mudlet", "Icon", "pack.png")
          and os.path.isfile(pack.get("icon", "")), f"with its details, $packagePath its folder, and its icon's file: {pack}")
    m = c.ask({"t": "getImage", "path": pack.get("icon", "")}, answers=("image", "error"), collect=seen)
    check((m["t"], m.get("w"), m.get("h")) == ("image", 4, 4), f"which getImage reads: {m.get('t')} {m.get('message', '')}")
    c.send({"t": "runLua", "code": 'echo(string.format("loaded %s\\n", tostring(rawget(_G, "Pack Test loaded"))))'})
    seen_or_next(c, shown("loaded true"), seen, since)
    check(True, "its script ran")

    since = len(seen)
    c.send({"t": "installPackage", "path": path})
    m = seen_or_next(c, lambda m: m["t"] == "packageInstalled", seen, since, timeout=15)
    check(m.get("result") == "alreadyInstalled" and "Pack Test" in m.get("error", ""), f"the same package again is left as it is: {m}")
    since = len(seen)
    c.send({"t": "installPackage", "path": os.path.join(folder, "missing.mpackage")})
    m = seen_or_next(c, lambda m: m["t"] == "packageInstalled", seen, since, timeout=15)
    check(m.get("result") == "failed" and "missing.mpackage" in m.get("error", ""), f"a file that cannot be read fails, with why: {m}")
    since = len(seen)
    c.send({"t": "installPackage", "path": plain})
    m = seen_or_next(c, lambda m: m["t"] == "packageInstalled", seen, since, timeout=15)
    check(m.get("result") == "installed", f"a plain .xml package installs: {m}")
    m = ask({"t": "packages"})
    bare = next((p for p in m.get("packages", []) if p["name"] == "Plain"), None)
    check(bare == {"name": "Plain", "title": "", "author": "", "version": "", "description": "", "icon": ""},
          f"with no details to show: {bare}")

    since = len(seen)
    m = ask({"t": "uninstallPackage", "name": "Pack Test"})
    check(m["t"] == "packages" and [p["name"] for p in m.get("packages", [])] == before + ["Plain"],
          f"uninstallPackage answers with the packages left: {m}")
    seen_or_next(c, shown("sysUninstall Pack Test"), seen, since)
    seen_or_next(c, lambda m: m["t"] == "packagesChanged", seen, since)
    check(not os.path.exists(home), f"it raises sysUninstall, packagesChanged follows, and its folder goes: {home}")
    m = ask({"t": "uninstallPackage", "name": "Pack Test"})
    check(m.get("message") == 'uninstallPackage: The package "Pack Test" is no longer installed, so there was nothing to remove.',
          f"one not installed is refused: {m}")
    for kind, field in (("installPackage", "path"), ("uninstallPackage", "name")):
        m = ask({"t": kind, field: ""})
        check(m.get("message") == f"{kind}: {field} must not be empty", f"{kind} with no {field} is refused: {m}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


def error_console():
    """The error console as desktop Mudlet's editor shows it, entry by entry: a trigger's or an alias's Lua error with
    the item and the line, an event handler's with the script it is in, found by the chunk's name even when Lua cut it,
    and none for a name two scripts share; Lua run from the UI and a temporary trigger's with no item, a debugc(); all of
    them again to a UI that attaches, clearErrorConsole emptying it, and how much it keeps."""
    profile = "Error Console Test"
    proc, c = start_engine(profile)
    seen = []
    c.wait_for(lambda m: m["t"] == "synced", collect=seen)
    c.ask({"t": "saveProfile", "name": profile, "host": "127.0.0.1", "port": 23, "tls": False, "login": "",
           "autoReconnect": False}, collect=seen)
    c.send({"t": "openProfile", "name": profile, "connect": False})
    wait_status(c, "profile", lambda v: v == f"{profile} loaded", seen)

    def ask(request):
        return c.ask(request, answers=("scriptItem", "error"), collect=seen)

    def entries_after(code):
        """The entries the error console adds as the code runs, waiting for the first."""
        since = len(seen)
        c.send({"t": "runLua", "code": code})
        wait_status(c, "luaResult", lambda v: True, seen)
        # An entry is sent as Mudlet writes it, before the status that ends the run
        return [e for m in seen[since:] if m["t"] == "errorConsole" and not m["full"] for e in m["entries"]]

    trigger = ask({"t": "addScriptItem", "kind": "trigger", "parent": 0, "position": 0, "folder": False, "name": "Bad trigger"})
    trigger = trigger.get("fields", {}).get("id")
    ask({"t": "editScriptItem", "kind": "trigger", "item": trigger, "fields": {
        "patterns": [{"text": "hit me", "type": "substring"}], "script": "local mob = nil\nmob.hp = 1"}})
    entries = entries_after('feedTriggers("hit me\\n")')
    check(len(entries) == 1 and {k: entries[0].get(k) for k in ("debug", "object", "function", "event", "kind", "item", "line")} == {
        "debug": False, "object": "Bad trigger", "function": f"Trigger{trigger}", "event": "", "kind": "trigger",
        "item": trigger, "line": 2} and "attempt to index local 'mob'" in entries[0].get("message", "")
          and entries[0].get("n") == 1 and entries[0].get("at", 0) > 0,
          f"a trigger's Lua error names the trigger and the line: {entries}")

    # Mudlet calls the function named as the script for each of its events
    script = ask({"t": "addScriptItem", "kind": "script", "parent": 0, "position": 0, "folder": False, "name": "onBad"})
    script = script.get("fields", {}).get("id")
    ask({"t": "editScriptItem", "kind": "script", "item": script, "fields": {
        "eventHandlers": ["badEvent"], "script": "function onBad()\n  error(\"handler boom\")\nend"}})
    entries = entries_after('raiseEvent("badEvent")')
    check([(e.get("kind"), e.get("item"), e.get("line"), e.get("function")) for e in entries] == [("script", script, 2, "onBad")]
          and "handler boom" in entries[0].get("message", ""),
          f"an event handler's names the script it is in, by its chunk's name: {entries}")

    entries = entries_after('error("ui boom")')
    check([(e.get("kind"), e.get("item"), e.get("line")) for e in entries] == [("", 0, 0)] and "ui boom" in entries[0].get("message", ""),
          f"Lua run from the UI names no item: {entries}")
    entries = entries_after('tempTrigger("temp hit", function() error("temp boom") end) feedTriggers("temp hit\\n")')
    check([(e.get("kind"), e.get("item")) for e in entries] == [("", 0)] and "temp boom" in entries[0].get("message", ""),
          f"nor does a temporary trigger, which the editor does not show: {entries}")
    entries = entries_after('debugc("just saying")')
    check([(e.get("debug"), e.get("message"), e.get("item")) for e in entries] == [(True, "just saying", 0)],
          f"a debugc() is an entry too, as on the desktop: {entries}")

    alias = ask({"t": "addScriptItem", "kind": "alias", "parent": 0, "position": 0, "folder": False, "name": "Bad alias"})
    alias = alias.get("fields", {}).get("id")
    ask({"t": "editScriptItem", "kind": "alias", "item": alias, "fields": {"pattern": "^boom$", "script": "\nlocal t = nil\nt.x = 1"}})
    entries = entries_after('expandAlias("boom")')
    check([(e.get("kind"), e.get("item"), e.get("line"), e.get("function")) for e in entries] == [("alias", alias, 3, f"Alias{alias}")],
          f"an alias's names the alias and the line: {entries}")

    # Lua cuts a chunk's name to fit LUA_IDSIZE (60 bytes with its [string ""]), so this one is known by how it starts
    long = "onAVeryLongEventHandlerNameThatLuaCutsShortInItsChunkName"
    script = ask({"t": "addScriptItem", "kind": "script", "parent": 0, "position": 0, "folder": False, "name": long})
    script = script.get("fields", {}).get("id")
    ask({"t": "editScriptItem", "kind": "script", "item": script, "fields": {
        "eventHandlers": ["longEvent"], "script": f"function {long}()\n  error(\"long boom\")\nend"}})
    entries = entries_after('raiseEvent("longEvent")')
    check([(e.get("kind"), e.get("item"), e.get("line")) for e in entries] == [("script", script, 2)] and "...\"]:2:" in entries[0].get("message", ""),
          f"a script whose chunk's name Lua cut is found by how it starts: {entries}")

    for _ in range(2):
        twin = ask({"t": "addScriptItem", "kind": "script", "parent": 0, "position": 0, "folder": False, "name": "onTwin"})
        ask({"t": "editScriptItem", "kind": "script", "item": twin.get("fields", {}).get("id"), "fields": {
            "eventHandlers": ["twinEvent"], "script": "function onTwin()\n  error(\"twin boom\")\nend"}})
    entries = entries_after('raiseEvent("twinEvent")')
    check(entries and all((e.get("kind"), e.get("item"), e.get("line")) == ("", 0, 0) for e in entries),
          f"but a chunk's name that two scripts share names neither: {entries}")
    count = entries[-1].get("n", 0)

    c.close()
    c = Client(SOCK)
    again = []
    c.wait_for(lambda m: m["t"] == "synced", collect=again)
    full = [m for m in again if m["t"] == "errorConsole"]
    check(len(full) == 1 and full[0]["full"] and [e.get("n") for e in full[0]["entries"]] == list(range(1, count + 1)),
          f"a UI that attaches gets every entry, oldest first: {full}")
    c.send({"t": "clearErrorConsole"})
    m = c.wait_for(lambda m: m["t"] == "errorConsole", timeout=5)
    check(m["full"] and m["entries"] == [], f"clearErrorConsole empties it: {m}")
    c.close()
    c = Client(SOCK)
    again = []
    c.wait_for(lambda m: m["t"] == "synced", collect=again)
    check([m["entries"] for m in again if m["t"] == "errorConsole"] == [[]], f"and it stays empty: {again}")

    # One message carries them all to a UI that attaches, so it must stay under the protocol's largest frame
    entries = entries_after('debugc(string.rep("y", 12000))')
    check([len(e.get("message", "")) for e in entries] == [10001] and entries[0]["message"].endswith("…"),
          f"a long text is cut: {[len(e.get('message', '')) for e in entries]}")
    entries_after('for i = 1, 120 do debugc(string.rep("z", 10000)) end')
    c.close()
    c = Client(SOCK)
    again = []
    c.wait_for(lambda m: m["t"] == "synced", collect=again)
    full = [m["entries"] for m in again if m["t"] == "errorConsole"]
    check(len(full) == 1 and [e.get("n") for e in full[0]] == list(range(count + 22, count + 122)),
          f"and the oldest go once the texts pass a million characters: {[[e.get('n') for e in f] for f in full]}")
    code = stop_engine(proc)
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")


if __name__ == "__main__":
    main()
