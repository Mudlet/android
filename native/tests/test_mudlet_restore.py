#!/usr/bin/env python3
"""Host test of the session restore of the engine with libmudlet (MudletEngine, SessionStore): the engine is killed
(SIGKILL) mid-session and started again, as Android does, and must bring the profile back and reconnect it to
fake_telnet.py; it must not after an orderly stop, the app's stop marker or the user's disconnect, nor keep restoring
in a crash loop. Build the engine as for test_mudlet_engine.py.

usage: test_mudlet_restore.py <path-to-engine-binary>
"""
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402
from protocol_client import Client  # noqa: E402

ENGINE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build-desktop-mudlet/enginehost/engine")
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp(prefix="mudlet-restore-test-")
SOCK = os.path.join(TMP, "engine.sock")
CONFIG = os.path.join(TMP, "config")
FILES = os.path.join(TMP, "files")
STATE = os.path.join(FILES, "engine-session.json")
STOPPED = os.path.join(FILES, "engine-stopped")
LOG = os.path.join(TMP, "engine.log")
PROFILE = "Restore Test"
AUTORECONNECT = os.path.join(CONFIG, "profiles", PROFILE, "autoreconnect")
# How long a test waits to be sure something does not happen
QUIET_S = 3
failures = []


def check(cond, what):
    print(("PASS " if cond else "FAIL ") + what, flush=True)
    if not cond:
        failures.append(what)


class Engine:
    """One run of the engine, with a UI attached through the socket protocol."""

    def __init__(self):
        home = os.path.join(TMP, "home")
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen", MUDLET_CONFIG_DIR=CONFIG, MUDLET_PROFILE=PROFILE,
                   MUDLET_ENGINE_STATE=STATE, MUDLET_ENGINE_STOPPED=STOPPED, HOME=home,
                   XDG_CONFIG_HOME=os.path.join(home, ".config"), XDG_DATA_HOME=os.path.join(home, ".local/share"),
                   XDG_CACHE_HOME=os.path.join(home, ".cache"))
        self.started = time.monotonic()
        self.log_from = os.path.getsize(LOG) if os.path.exists(LOG) else 0
        self.proc = subprocess.Popen([ENGINE, "--socket", SOCK, "--heartbeat-ms", "60000"], env=env,
                                     stdout=subprocess.DEVNULL, stderr=open(LOG, "ab"))
        self.status = {}
        # Every value of status restore, in order
        self.restores = []
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError(f"engine exited with {self.proc.returncode}; see {LOG}")
            if time.monotonic() - self.started > 20:
                raise RuntimeError("engine did not listen")
            try:
                self.client = Client(SOCK)
                break
            except (FileNotFoundError, ConnectionRefusedError):
                time.sleep(0.02)
        self.wait(lambda m: m["t"] == "synced")

    def log(self):
        """What this run of the engine has logged: every restore status, which a UI attaching late gets only the last of."""
        with open(LOG, errors="replace") as f:
            f.seek(self.log_from)
            return f.read()

    def _take(self, m):
        if m["t"] == "status":
            self.status[m["key"]] = m["value"]
            if m["key"] == "restore":
                self.restores.append(m["value"])

    def wait(self, pred, timeout=30):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("message not received")
            m = self.client.recv(remaining)
            if m is None:
                raise TimeoutError("message not received")
            self._take(m)
            if pred(m):
                return m

    def wait_status(self, key, pred, timeout=30):
        if key in self.status and pred(self.status[key]):
            return self.status[key]
        return self.wait(lambda m: m["t"] == "status" and m["key"] == key and pred(m["value"]), timeout)["value"]

    def drain(self, seconds):
        """Takes what the engine sends for this long."""
        try:
            self.wait(lambda m: False, seconds)
        except TimeoutError:
            pass

    def send(self, msg):
        self.client.send(msg)

    def connect(self, port):
        self.send({"t": "connect", "host": "127.0.0.1", "port": port})
        self.wait_status("connection", lambda v: v == "connected")

    def kill(self):
        """As Android's low-memory killer, or "Kill engine process": no chance to clean up."""
        self.client.close()
        self.proc.kill()
        self.proc.wait(10)

    def stop(self):
        """As an orderly stop: SIGTERM ends the event loop, as the service's destruction does on Android."""
        self.client.close()
        self.proc.send_signal(signal.SIGTERM)
        try:
            return self.proc.wait(20)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            return "hung"


def state():
    try:
        with open(STATE) as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def set_autoreconnect(on):
    """Mudlet's "Reconnect automatically", as its connection dialog stores it: a QString in a QDataStream."""
    if on:
        value = "2".encode("utf-16-be")
        with open(AUTORECONNECT, "wb") as f:
            f.write(struct.pack(">I", len(value)) + value)
    elif os.path.exists(AUTORECONNECT):
        os.remove(AUTORECONNECT)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def start_game(port):
    """A fake game of its own, in a process this test can end, then start again on the same port."""
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_telnet.py"), str(port)], stdout=subprocess.PIPE)
    proc.stdout.readline()
    return proc


def accepted_since(t):
    return sum(1 for a in fake_telnet.ACCEPTED if a >= t)


def restarted_after_kill(port):
    """Kill mid-session: the profile comes back, reconnects, and takes commands."""
    e = Engine()
    t0 = time.monotonic()
    e.connect(port)
    e.send({"t": "send", "text": "echo before the kill"})
    e.wait(lambda m: m["t"] == "line" and m["text"] == "You said: before the kill")
    kept = state()
    check(kept is not None and kept["profile"] == PROFILE and kept["connect"] and kept["host"] == "127.0.0.1"
          and kept["port"] == port, f"the running session is kept on disk: {kept}")
    e.kill()
    check(state() == kept, "the kept session survives the kill")

    killed_at = time.monotonic()
    e = Engine()
    target = f"127.0.0.1:{port}"
    value = e.wait_status("restore", lambda v: v.startswith("reconnected"))
    reconnect_ms = (time.monotonic() - killed_at) * 1000
    check(value == f"reconnected {target}", f"the restore reports the reconnect: {value}")
    check(f"restore: reconnecting {target}\n" in e.log(), "and reported reconnecting first")
    check(e.wait_status("profile", lambda v: v.endswith("loaded")) == f"{PROFILE} loaded", "the profile was loaded again")
    check(accepted_since(t0) == 2, f"the game saw one new connection: {accepted_since(t0)} in all")
    e.send({"t": "send", "text": "echo after the restore"})
    e.wait(lambda m: m["t"] == "line" and m["text"] == "You said: after the restore")
    check(True, f"the restored profile takes commands; engine start to reconnected {reconnect_ms:.0f} ms")
    check(state()["restores"] and len(state()["restores"]) == 1, f"the restore is counted: {state()}")

    # A UI that attaches later is told too, as the engine keeps its statuses
    e.client.close()
    e.client = Client(SOCK)
    e.status = {}
    e.wait(lambda m: m["t"] == "synced")
    check(e.status.get("restore") == f"reconnected {target}", f"a UI attaching later gets the restore status: {e.status.get('restore')}")
    return e


def nothing_restored(e, t0, what):
    e.drain(QUIET_S)
    check(e.restores == [], f"{what}: no restore reported: {e.restores}")
    check(e.status.get("profile") == f"{PROFILE} (not started)", f"{what}: no profile started: {e.status.get('profile')}")
    check(accepted_since(t0) == 0, f"{what}: the game saw no new connection")


def orderly_stop(e):
    """SIGTERM, as Stop engine: the state is cleared, and the next engine starts with no profile."""
    code = e.stop()
    check(code == 0, f"the engine exits cleanly on SIGTERM: {code}")
    check(state() is None, f"an orderly stop clears the kept session: {state()}")
    t0 = time.monotonic()
    e = Engine()
    nothing_restored(e, t0, "after an orderly stop")
    return e


def stop_marker(e, port):
    """The app's stop marker, which EngineService.onDestroy and Stop engine write: a kill after it restores nothing."""
    e.connect(port)
    open(STOPPED, "w").close()
    e.kill()
    t0 = time.monotonic()
    e = Engine()
    nothing_restored(e, t0, "after the stop marker")
    check(not os.path.exists(STOPPED), "the engine took the stop marker")
    check(state() is None, f"and dropped the kept session: {state()}")
    return e


def user_disconnect(e, port):
    """The user's disconnect: the profile comes back, but is not connected."""
    e.connect(port)
    e.send({"t": "disconnect"})
    e.wait_status("connection", lambda v: v == "disconnected")
    check(state() is not None and not state()["connect"], f"the disconnect is kept: {state()}")
    e.kill()
    t0 = time.monotonic()
    e = Engine()
    value = e.wait_status("restore", lambda v: v.startswith("restored"))
    check(value == f"restored {PROFILE}", f"after the user's disconnect the profile is restored unconnected: {value}")
    e.drain(QUIET_S)
    check(accepted_since(t0) == 0, "and the game saw no new connection")
    check(e.status.get("connection") == "disconnected", f"connection stays disconnected: {e.status.get('connection')}")
    return e


def game_dropped(e, port, autoreconnect):
    """The game closed the connection: it is wanted again only if the profile reconnects automatically."""
    set_autoreconnect(autoreconnect)
    e.connect(port)
    e.send({"t": "send", "text": "quit"})
    e.wait_status("connection", lambda v: v == "disconnected")
    e.kill()
    t0 = time.monotonic()
    e = Engine()
    what = "on" if autoreconnect else "off"
    if autoreconnect:
        value = e.wait_status("restore", lambda v: v.startswith("reconnected"))
        check(value == f"reconnected 127.0.0.1:{port}", f"auto-reconnect {what}: a game's drop is reconnected: {value}")
    else:
        value = e.wait_status("restore", lambda v: v.startswith("restored"))
        check(value == f"restored {PROFILE}", f"auto-reconnect {what}: a game's drop is not reconnected: {value}")
        e.drain(QUIET_S)
        check(accepted_since(t0) == 0, f"auto-reconnect {what}: the game saw no new connection")
    set_autoreconnect(False)
    return e


def game_down(e, autoreconnect):
    """The game is down when the engine restarts: one try, or Mudlet's own retries if the profile reconnects."""
    port = free_port()
    game = start_game(port)
    set_autoreconnect(autoreconnect)
    e.connect(port)
    e.kill()
    game.kill()
    game.wait()
    e = Engine()
    target = f"127.0.0.1:{port}"
    if not autoreconnect:
        value = e.wait_status("restore", lambda v: not v.startswith("reconnecting"))
        check(value == f"notReconnected {target}", f"a refused reconnect is reported: {value}")
        check(state() is not None and not state()["connect"], f"and not tried again: {state()}")
    else:
        value = e.wait_status("restore", lambda v: not v.startswith("reconnecting"))
        check(value == f"retrying {target}", f"auto-reconnect on: Mudlet tries again: {value}")
        game = start_game(port)
        value = e.wait_status("restore", lambda v: v.startswith("reconnected"), timeout=40)
        check(value == f"reconnected {target}", f"and reconnects once the game is back: {value}")
        game.kill()
        game.wait()
    set_autoreconnect(False)
    return e


def crash_loop(e, port):
    """An engine that keeps dying is restored at most three times in ten minutes, each later one waiting to connect."""
    e.connect(port)
    target = f"127.0.0.1:{port}"
    e.kill()
    e = Engine()
    value = e.wait_status("restore", lambda v: v.startswith("reconnected"))
    check(value == f"reconnected {target}", f"first restore reconnects at once: {e.restores}")
    e.kill()
    e = Engine()
    started = e.started
    check(e.wait_status("restore", lambda v: True) == f"waiting 10 {target}", f"the second restore waits 10 s: {e.restores}")
    check(e.status.get("connection") == "disconnected", "and is not connected meanwhile")
    value = e.wait_status("restore", lambda v: v.startswith("reconnected"), timeout=40)
    waited = time.monotonic() - started
    check(value == f"reconnected {target}" and waited >= 9.5, f"then reconnects: {value} after {waited:.1f} s")
    e.kill()
    e = Engine()
    check(e.wait_status("restore", lambda v: True) == f"waiting 30 {target}", f"the third restore waits 30 s: {e.restores}")
    e.kill()
    t0 = time.monotonic()
    e = Engine()
    value = e.wait_status("restore", lambda v: True)
    check(value == "gaveUp 3 10", f"the fourth start in a row restores nothing: {value}")
    e.drain(QUIET_S)
    check(e.status.get("profile") == f"{PROFILE} (not started)" and accepted_since(t0) == 0,
          f"no profile started, no connection: {e.status.get('profile')}")
    check(state() is None, f"the kept session is dropped: {state()}")
    e.kill()
    t0 = time.monotonic()
    e = Engine()
    nothing_restored(e, t0, "after giving up")
    return e


def main():
    port = fake_telnet.start_in_thread()
    e = restarted_after_kill(port)
    e = orderly_stop(e)
    e = stop_marker(e, port)
    e = user_disconnect(e, port)
    e = orderly_stop(e)
    e = game_dropped(e, port, autoreconnect=False)
    e = orderly_stop(e)
    e = game_dropped(e, port, autoreconnect=True)
    e = orderly_stop(e)
    e = game_down(e, autoreconnect=False)
    e = orderly_stop(e)
    e = game_down(e, autoreconnect=True)
    e = orderly_stop(e)
    e = crash_loop(e, port)
    e.stop()
    if failures:
        print(f"\n{len(failures)} FAILED; engine log: {LOG}")
        with open(LOG, errors="replace") as f:
            print(f.read()[-6000:])
        sys.exit(1)
    print("\nall passed")


if __name__ == "__main__":
    main()
