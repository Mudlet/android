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
import profile_checks  # noqa: E402
from protocol_client import PROTOCOL_VERSION, Client, Closed  # noqa: E402

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


def input_line(c):
    """hiddenInput, sendHidden, dismissHidden, history and complete. The game hides input from its banner's WILL ECHO
    on. Returns the client the checks end with, as they attach a new one."""
    secret = "pw-Hid3-never-logged"
    seen = []
    c.send({"t": "send", "text": "password"})
    c.wait_for(lambda m: m["t"] == "line" and m["text"] == "Password: ", collect=seen)
    c.send({"t": "sendHidden", "text": secret + "\r\n"})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", collect=seen)
    check(m["wanted"] is False, f"the game's WONT ECHO after the password ends hiddenInput: {m}")
    c.wait_for(lambda m: m["t"] == "line" and m["text"] == "Password accepted", collect=seen)
    check(fake_telnet.RECEIVED[-1] == secret, "sendHidden reaches the game, without its line breaks")
    c.send({"t": "sendHidden", "text": secret})
    m = c.wait_for(lambda m: m["t"] == "status" and m["key"] == "sendError", collect=seen)
    check(m["value"] == "the game is not hiding input", f"sendHidden while input is not hidden is refused: {m}")

    c.send({"t": "send", "text": "password"})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", collect=seen)
    check(m["wanted"] is True, "WILL ECHO again gives hiddenInput")
    c.wait_for(lambda m: m["t"] == "line" and m["text"] == "Password: ", collect=seen)
    c.send({"t": "dismissHidden"})
    m = c.wait_for(lambda m: m["t"] == "hiddenInput", collect=seen)
    check(m["wanted"] is False, "dismissHidden ends it")
    # The game still waits for its password
    c.send({"t": "send", "text": "echo dismissed"})
    c.wait_for(lambda m: m["t"] == "line" and m["text"] == "Password accepted", collect=seen)

    for request, expected, what in [({"text": "prompt", "fromHistory": True}, "prompt-nogo", "a line sent, from the history"),
                                    ({"text": "say neg"}, "say negotiation", "a word of the lines sent"),
                                    ({"text": "say zzz"}, "", "nothing, when nothing starts with it")]:
        c.send(dict(request, t="complete", id=f"complete {what}"))
        m = c.wait_for(lambda m: m["t"] == "status" and m.get("re") == f"complete {what}", collect=seen)
        check(m["key"] == "completion" and m["value"] == expected, f"complete gives {what}: {m}")
    c.send({"t": "complete", "text": "x", "fromHistory": "yes", "id": "bad"})
    m = c.wait_for(lambda m: m.get("re") == "bad", collect=seen)
    check(m == {"t": "error", "message": "complete: fromHistory must be true or false", "re": "bad"}, f"a fromHistory that is not a boolean: {m}")

    c.close()
    c = Client(SOCK)
    msgs = handshake(c)
    seen += msgs
    check({"t": "hiddenInput", "wanted": False} in msgs, "a UI that attaches is told whether input is hidden")
    history = [m["lines"] for m in msgs if m["t"] == "history"]
    check(history == [["echo dismissed", "password", "prompt-nogo", "size", "naws", "negotiation"]],
          f"and the history, what send sent, newest first, each once, without what sendHidden sent: {history}")
    profile_checks.password_kept_out(check, secret, seen, os.path.join(TMP, "engine.log"))
    return c


def script_windows(c):
    """The stand-in has no script windows or labels: it refuses their requests, once their fields are right."""
    refusal = "the stand-in engine has no script windows"
    side = {"name": "side", "x": 0, "y": 0, "w": 300, "h": 200, "floating": False}
    for request in ({"t": "setMainWindowSize", "w": 800, "h": 600}, dict(side, t="placeUserWindow"),
                    {"t": "closeUserWindow", "name": "side"}, {"t": "sendWindowCommandLine", "name": "chat", "text": "hi"},
                    {"t": "setTextBoxText", "name": "notes", "text": "hi"}, {"t": "selectFrameTab", "name": "chat"},
                    {"t": "pointer", "window": "box", "type": "press", "x": 1.5, "y": 2, "button": 1, "buttons": 1},
                    {"t": "getImage", "path": "/no/such.png", "w": 16, "h": 16, "frame": 0}):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: {refusal}", f"{request['t']} is refused: {m}")
    m = c.ask({"t": "getWindowText", "window": "chat", "first": -300, "count": 300}, answers=("error", "windowText"))
    check(m["t"] == "error" and m["message"] == 'getWindowText: there is no console named "chat"', f"getWindowText answers error: {m}")
    for request, message in (
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
            ({"t": "getImage", "path": ""}, "path must not be empty")):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: {message}", f"a bad field is refused first: {request} -> {m['message']}")


def buttons_and_commands(c):
    """The stand-in has no button bars or commands: it refuses presses of them, once their fields are right."""
    for request in ({"t": "pressButton", "button": 1}, {"t": "activateCommand", "command": 1}):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: the stand-in engine has no buttons or commands", f"{request['t']} is refused: {m}")
    for request, message in (({"t": "pressButton", "button": "1"}, "button must be a whole number"),
                             ({"t": "pressButton", "button": 1.5}, "button must be a whole number"),
                             ({"t": "activateCommand"}, "command must be a whole number")):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: {message}", f"a bad field is refused first: {request} -> {m['message']}")


def no_map(c):
    """The stand-in has no map: it refuses presses on it, switching it to editing and downloading it, once their fields are
    right. It takes a size for the app's map, and tells the UI there is nothing to draw, until that UI goes, and a scale
    for the mapper's map."""
    # Refused, its error would come before the appMap
    c.send({"t": "setMapScale", "scale": 2.75})
    c.send({"t": "setAppMapSize", "w": 360, "h": 640, "scale": 2.75})
    m = c.wait_for(lambda m: m["t"] in ("appMap", "error"))
    check(m == {"t": "appMap", "png": "", "w": 0, "h": 0, "viewOnly": True}, f"setMapScale is taken, and the app's map has nothing to draw: {m}")
    for request in ({"t": "mapPointer", "type": "press", "x": 1, "y": 2, "button": 1}, {"t": "setMapViewOnly", "viewOnly": False},
                    {"t": "appMapPointer", "type": "doubleClick", "x": 1, "y": 2, "button": 1}, {"t": "showRoom", "room": 1},
                    {"t": "downloadMap"}, {"t": "cancelMapDownload"}):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: the stand-in engine has no map", f"{request['t']} is refused: {m}")
    for request, message in (({"t": "mapPointer", "type": "move", "x": 1, "y": 2}, "type must be press, doubleClick or wheel"),
                             ({"t": "mapPointer", "type": "press", "x": "1", "y": 2}, "x and y must be numbers"),
                             ({"t": "mapPointer", "type": "wheel", "x": 1, "y": 2, "wheelY": 0.5},
                              "button and modifiers must be whole numbers from 0, wheelY a whole number"),
                             ({"t": "mapPointer", "type": "press", "x": 1, "y": 2, "modifiers": -1},
                              "button and modifiers must be whole numbers from 0, wheelY a whole number"),
                             ({"t": "setMapViewOnly", "viewOnly": 1}, "viewOnly must be true or false"),
                             ({"t": "setMapViewOnly"}, "viewOnly must be true or false"),
                             ({"t": "appMapPointer", "type": "release", "x": 1, "y": 2}, "type must be press, doubleClick or wheel"),
                             ({"t": "showRoom", "room": "1"}, "room must be a whole number"),
                             ({"t": "findRooms", "text": " "}, "text must not be empty"),
                             ({"t": "setAppMapSize", "w": 0, "h": 10, "scale": 1}, "w and h must be whole numbers above 0, or both 0"),
                             ({"t": "setAppMapSize", "w": 1.5, "h": 10, "scale": 1}, "w and h must be whole numbers above 0, or both 0"),
                             ({"t": "setAppMapSize", "w": 10, "h": 10, "scale": 0.5}, "scale must be a number from 1 to 4"),
                             ({"t": "setAppMapSize", "w": 10, "h": 10}, "scale must be a number from 1 to 4"),
                             ({"t": "setMapScale", "scale": 0.5}, "scale must be a number from 1 to 8"),
                             ({"t": "setMapScale", "scale": 8.5}, "scale must be a number from 1 to 8"),
                             ({"t": "setMapScale", "scale": "2"}, "scale must be a number from 1 to 8")):
        m = c.ask(request, answers=("error",))
        check(m["message"] == f"{request['t']}: {message}", f"a bad field is refused first: {request} -> {m['message']}")
    m = c.ask({"t": "findRooms", "text": "Hall"}, answers=("error", "rooms"))
    check(m["t"] == "error" and m["message"] == "findRooms: the stand-in engine has no map", f"findRooms is refused: {m}")
    # A UI that attaches replaces this one, and goes on in its place
    c.close()
    c = Client(SOCK)
    frames = handshake(c)
    check([m for m in frames if m["t"] == "appMap"] == [], "a UI that attaches is told nothing of the app's map until it asks")
    check([m for m in frames if m["t"] == "mapDownload"] == [], "nor of a map to download: the stand-in's game offers none")
    c.send({"t": "setAppMapSize", "w": 0, "h": 0, "scale": 1})
    m = c.wait_for(lambda m: m["t"] in ("appMap", "error"))
    check(m == {"t": "appMap", "png": "", "w": 0, "h": 0, "viewOnly": True}, f"asked again, it says so again: {m}")
    return c


def open_profiles(m):
    return {p["name"]: p for p in m["profiles"]}


def multi_profile(c, port):
    """Several profiles at once (docs/multi-profile.md): with profileSockets the control socket carries only the
    control traffic, and each open profile is served on a socket of its own. Leaves "Beta" alone open and connected,
    and a version 14 style client in c's place."""
    c.close()
    ctl = Client(SOCK, profile_sockets=True)
    msgs = handshake(ctl)
    st = status_map(msgs)
    listed = [m for m in msgs if m["t"] == "openProfiles"]
    check("profile" not in msgs[0] and not [m for m in msgs if m["t"] in ("line", "tail", "windows")]
          and "connection" not in st and "heartbeatTicks" in st,
          f"a UI that takes profileSockets gets only the engine's statuses on the control socket: {sorted(st)}")
    check(listed and [(p["name"], p["front"], p["state"]) for p in listed[-1]["profiles"]] == [("Beta", True, "loaded")],
          f"and the open profiles: {listed}")
    beta = listed[-1]["profiles"][0]
    # "Alpha" still held slot 1 as "Beta" opened, which closed it
    beta_slot = beta["slot"]
    m = ctl.ask({"t": "send", "text": "look"}, answers=("error",))
    check(m["message"] == "send: send it on the profile's socket", f"a profile's request on the control socket is refused: {m}")

    def ask(msg):
        return ctl.ask(msg, answers=("openProfiles", "profiles", "error"))

    for name in ("Gamma", "Delta", "Epsilon", "Zeta"):
        ask({"t": "saveProfile", **profile_checks.profile(name, port)})
    m = ask({"t": "openProfile", "name": "Gamma", "alongside": True})
    opened = open_profiles(m) if m["t"] == "openProfiles" else {}
    check(list(opened) == ["Beta", "Gamma"] and opened["Gamma"]["slot"] == 1 and opened["Gamma"]["front"] and not opened["Beta"]["front"],
          f"openProfile alongside opens beside the others, in front, in the lowest free slot: {m}")
    gamma = opened.get("Gamma", {})
    check(gamma.get("socket") == os.path.join(TMP, "engine-p1.sock") and beta["socket"] == os.path.join(TMP, f"engine-p{beta_slot}.sock"),
          f"each on engine-p<slot>.sock beside the control socket: {gamma.get('socket')}, {beta['socket']}")

    p1 = Client(beta["socket"])
    frames = handshake(p1)
    beta_instance = frames[0]["instance"]
    check(frames[0].get("profile") == "Beta" and beta_instance.endswith(f"-{beta_slot}"), f"a profile's socket names its profile: {frames[0]}")
    p2 = Client(gamma["socket"])
    frames = handshake(p2)
    check(frames[0].get("profile") == "Gamma" and frames[0]["instance"].endswith("-1") and frames[0]["instance"] != beta_instance,
          f"and has an instance of its own: {frames[0]}")
    if status_map(frames).get("connection") != "connected":
        p2.wait_for(lambda m: m["t"] == "status" and m["key"] == "connection" and m["value"] == "connected")
    p2.send({"t": "send", "text": "echo gamma only"})
    m = p2.wait_for(lambda m: m["t"] == "line" and "gamma only" in m["text"])
    gamma_seq = m["seq"]
    leaked = []
    try:
        while True:
            m = p1.recv(0.3)
            if m is None:
                break
            leaked.append(m)
    except Closed:
        pass
    check(not [m for m in leaked if m["t"] == "line"], f"a profile's lines go to its own socket only: {leaked[:3]}")
    m = p1.ask({"t": "listProfiles"})
    check(m["t"] == "error" and m["message"] == "listProfiles: send it on the control socket", f"a control request on a profile's socket is refused: {m}")

    m = ctl.wait_for(lambda m: m["t"] == "openProfiles" and open_profiles(m)["Gamma"]["lastSeq"] >= gamma_seq, timeout=3)
    check(open_profiles(m)["Gamma"]["unread"] == open_profiles(m)["Gamma"]["lastSeq"] > 0, f"the control socket counts each profile's unread lines: {m}")
    ctl.send({"t": "markSeen", "name": "Gamma", "seq": gamma_seq})
    m = ctl.wait_for(lambda m: m["t"] == "openProfiles")
    gamma = open_profiles(m)["Gamma"]
    check(gamma["unread"] == gamma["lastSeq"] - gamma_seq, f"markSeen counts them from there: {gamma}")
    for name in ("Delta", "Epsilon"):
        ask({"t": "openProfile", "name": name, "alongside": True, "connect": False})
    ctl.send({"t": "setFront", "name": "beta"})
    m = ctl.wait_for(lambda m: m["t"] == "openProfiles")
    check([p["name"] for p in m["profiles"] if p["front"]] == ["Beta"], f"setFront moves the front: {m}")
    m = ask({"t": "openProfile", "name": "Zeta", "alongside": True})
    check(m == {"t": "error", "message": "openProfile: at most 4 open", "re": m.get("re")}, f"no more than 4 open at once: {m}")
    m = ask({"t": "setFront", "name": "Zeta"})
    check(m["t"] == "error" and m["message"] == 'setFront: "Zeta" is not open', f"setFront of a profile not open: {m}")
    m = ask({"t": "closeProfile", "name": "Nope"})
    check(m["t"] == "error" and m["message"] == 'closeProfile: "Nope" is not open', f"closeProfile of a profile not open: {m}")
    m = ask({"t": "deleteProfile", "name": "Delta"})
    check(m["t"] == "error" and m["message"] == 'deleteProfile: "Delta" is open: open another profile before deleting it',
          f"deleteProfile of any open profile is refused: {m}")
    m = ask({"t": "closeProfile", "name": "Beta"})
    check(m["t"] == "openProfiles" and [(p["name"], p["front"]) for p in m["profiles"]] == [("Gamma", True), ("Delta", False), ("Epsilon", False)],
          f"closeProfile closes it, and the front goes to the next: {m}")
    try:
        p1.wait_for(lambda m: False, timeout=2)
        closed = False
    except (Closed, TimeoutError) as e:
        closed = isinstance(e, Closed)
    check(closed, "the closed profile's socket closes")
    m = ask({"t": "openProfile", "name": "Beta", "alongside": True})
    beta = open_profiles(m).get("Beta", {}) if m["t"] == "openProfiles" else {}
    check(beta.get("slot") == beta_slot and beta.get("front"), f"a profile opened again takes the lowest free slot: {m}")
    p1 = Client(beta["socket"])
    frames = handshake(p1)
    check(frames[0]["instance"] not in (beta_instance, None) and not [m for m in frames if m["t"] == "line" and "gamma" in m["text"]],
          f"with a new instance, replaying only its own lines: {frames[0]}")
    p1.close()
    m = ask({"t": "closeProfile", "name": "Beta"})
    check(m["t"] == "openProfiles" and [(p["name"], p["front"]) for p in m["profiles"]] == [("Gamma", False), ("Delta", False), ("Epsilon", True)],
          f"with none to the right of the front closed, the front goes to the left: {m}")
    m = ask({"t": "markSeen", "name": "Gamma", "seq": -1})
    check(m["t"] == "error" and m["message"] == "markSeen: seq must be a whole number from 0", f"markSeen of a seq below 0: {m}")
    ctl.send({"t": "setFront", "name": "Gamma"})
    ctl.wait_for(lambda m: m["t"] == "openProfiles" and open_profiles(m)["Gamma"]["front"])
    p2.close()
    ctl.close()

    c = Client(SOCK)
    frames = handshake(c)
    check(not [m for m in frames if m["t"] == "openProfiles"], "a UI that leaves out profileSockets is not told of the open profiles")
    if status_map(frames).get("connection") != "connected":
        c.wait_for(lambda m: m["t"] == "status" and m["key"] == "connection" and m["value"] == "connected")
    c.send({"t": "send", "text": "echo to the front"})
    m = c.wait_for(lambda m: m["t"] == "line" and "to the front" in m["text"])
    check(True, "and talks to the front profile, as in version 14")
    check([m for m in frames if m["t"] == "hello"][0].get("profile") is None, "on the control socket, whose hello names no profile")
    m = c.ask({"t": "openProfile", "name": "Beta"})
    check(m["t"] == "profiles", f"openProfile without alongside closes the others: {m}")
    for name in ("Gamma", "Delta", "Epsilon", "Zeta"):
        m = c.ask({"t": "deleteProfile", "name": name})
    check(profile_checks.names(m) == ["Beta"], f"which can then be deleted: {profile_checks.names(m)}")
    return c


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
    check(hs[0]["t"] == "hello" and hs[0]["v"] == PROTOCOL_VERSION and hs[0]["pid"] == proc.pid,
          f"hello reply carries v={PROTOCOL_VERSION} and engine pid")
    st = status_map(hs)
    check(st.get("connection") == "disconnected", "initial connection status replayed")
    check(int(st.get("advanceM", "0")) > 0, f"font check: family={st.get('fontFamily')!r} advanceM={st.get('advanceM')} platform={st.get('platform')}")
    results["font"] = (st.get("fontFamily"), st.get("advanceM"))
    windows = [m for m in hs if m["t"] == "windows"]
    check(windows == [{"t": "windows", "full": True, "windows": []}], f"a UI that attaches is told there are no script windows: {windows}")
    check(not any(m["t"] in ("mainArea", "windowText", "label") for m in hs), "nor a main area, window text or label")
    check([m for m in hs if m["t"] == "buttonBars"] == [{"t": "buttonBars", "bars": []}], "and that there are no button bars")
    check([m for m in hs if m["t"] == "commands"] == [{"t": "commands", "commands": []}], "or commands")
    check(not any(m["t"] == "map" for m in hs), "and is sent no map")

    # Wrong protocol version is refused
    bad = Client(SOCK, version=99)
    try:
        m = bad.recv(2)
        check(m == {"t": "error", "message": f"expected hello with v={PROTOCOL_VERSION}"}, f"hello with v=99 gets an error frame: {m}")
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

    # The screen size, kept for the game's NAWS below
    for bad in ({"columns": 0, "rows": 25}, {"columns": 80, "rows": 65536}, {"columns": 80.5, "rows": 25}, {"columns": "80", "rows": 25}, {"columns": 80}):
        c.send(dict(bad, t="setScreenSize", id="size"))
        m = c.wait_for(lambda m: m.get("re") == "size")
        check(m == {"t": "error", "message": "setScreenSize: columns and rows must be whole numbers from 1 to 65535", "re": "size"},
              f"setScreenSize {bad} is refused: {m}")
    c.send({"t": "setScreenSize", "columns": 100, "rows": 30})

    # The stand-in has no links, and says so, of a window's as of the main console's; a link id or item that is not a
    # whole number, or a window that is not a name, is refused first
    for request, message in [({"t": "getLink", "link": 1}, "getLink: not supported"),
                             ({"t": "followLink", "link": 1, "item": 0}, "followLink: not supported"),
                             ({"t": "getLink", "link": 1, "window": "chat"}, "getLink: not supported"),
                             ({"t": "followLink", "link": 1, "window": "main"}, "followLink: not supported"),
                             ({"t": "getLink", "link": 1, "window": 5}, "getLink: window must be a console's name"),
                             ({"t": "followLink", "link": 1, "window": None}, "followLink: window must be a console's name"),
                             ({"t": "getLink", "link": 0}, "getLink: link must be a whole number from 1"),
                             ({"t": "followLink", "link": "1"}, "followLink: link must be a whole number from 1"),
                             ({"t": "followLink", "link": 1, "item": 1.5}, "followLink: item must be a whole number from 0")]:
        c.send(dict(request, id="link"))
        m = c.wait_for(lambda m: m.get("re") == "link")
        check(m == {"t": "error", "message": message, "re": "link"}, f"{request} gets {message!r}: {m}")

    # The stand-in takes setAppState and does nothing with it; only booleans are taken
    c.send({"t": "setAppState", "active": False, "minimized": True, "id": "state"})
    c.send({"t": "ping", "id": 77})
    m = c.wait_for(lambda m: m.get("re") == "state" or m["t"] == "pong")
    check(m == {"t": "pong", "id": 77}, f"setAppState is taken without an answer: {m}")
    c.send({"t": "setAppState", "active": True, "minimized": False})
    for bad in ({"active": "true", "minimized": False}, {"active": True}):
        c.send(dict(bad, t="setAppState", id="state"))
        m = c.wait_for(lambda m: m.get("re") == "state")
        check(m == {"t": "error", "message": "setAppState: active and minimized must be true or false", "re": "state"},
              f"setAppState {bad} is refused: {m}")

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
    check({"t": "hiddenInput", "wanted": True} in msgs, "the game's WILL ECHO gives hiddenInput")

    c.send({"t": "send", "text": "negotiation"})
    m = c.wait_for(lambda m: m["t"] == "line" and m["text"].startswith("NEG:"))
    neg = bytes.fromhex(m["text"][5:])
    check(neg == bytes([255, 252, 24, 255, 253, 1, 255, 254, 201]), f"DO->WONT, WILL->DONT replies, but WILL ECHO->DO: {neg.hex()}")

    c.send({"t": "send", "text": "naws"})
    c.wait_for(lambda m: m["t"] == "line" and m["text"] == "NAWS asked")
    c.send({"t": "send", "text": "size"})
    m = c.wait_for(lambda m: m["t"] == "line" and m["text"].startswith("SIZE:"))
    check(m["text"] == "SIZE: 100x30", f"the game's DO NAWS is answered with the size setScreenSize gave: {m['text']}")
    # 255 is sent doubled, as telnet's IAC
    c.send({"t": "setScreenSize", "columns": 300, "rows": 255})
    c.send({"t": "send", "text": "size"})
    m = c.wait_for(lambda m: m["t"] == "line" and m["text"].startswith("SIZE:"))
    check(m["text"] == "SIZE: 300x255", f"and a new size is sent at once: {m['text']}")

    c.send({"t": "runLua", "code": 'announce("polite") announce("now", "importantall")'})
    got = [c.wait_for(lambda m: m["t"] == "announce") for _ in range(2)]
    check(got == [{"t": "announce", "text": "polite", "assertive": False}, {"t": "announce", "text": "now", "assertive": True}],
          f"Lua announce() gives announce, assertive for an important style: {got}")

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

    c = input_line(c)

    # Profiles: the stand-in keeps them in profiles.json beside its socket, and has no login, so a password goes nowhere
    password = "pw-Kq7-never-logged"
    seen = profile_checks.round_trips(c, check, port, password, lambda: len(fake_telnet.ACCEPTED))
    profile_checks.password_kept_out(check, password, seen, os.path.join(TMP, "engine.log"))
    with open(os.path.join(TMP, "profiles.json")) as f:
        stored = json.load(f)
    check([p["name"] for p in stored["profiles"]] == ["Beta"], f"the profiles are stored beside the socket: {stored}")
    c.send({"t": "unknownThing", "id": 3})
    m = c.wait_for(lambda m: m["t"] == "error")
    check(m == {"t": "error", "message": "unknown type unknownThing", "re": 3}, f"an error carries the request's id: {m}")
    script_windows(c)
    buttons_and_commands(c)
    c = no_map(c)
    c = multi_profile(c, port)

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
    c.send({"t": "hello", "v": PROTOCOL_VERSION, "instance": instance, "lastSeq": 1 << 50})
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
    c.send({"t": "hello", "v": PROTOCOL_VERSION, "instance": instance, "lastSeq": last_seq - 3})
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
    m = c.ask({"t": "listProfiles"})
    check(profile_checks.names(m) == ["Beta"] and m["profiles"][0]["autoReconnect"], f"the profiles outlive the engine: {m}")
    proc.terminate()
    proc.wait()

    print()
    for k, v in results.items():
        print(f"  {k}: {v}")
    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
