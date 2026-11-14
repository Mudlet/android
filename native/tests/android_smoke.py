#!/usr/bin/env python3
"""Smoke test of the debug APK with libmudlet as its engine, on an emulator (CI's emulator job) or a device, from the
host through adb. The only test that runs the real engine on Android: a crash there, such as Lua 5.1's FORTIFY abort
in luaL_openlibs, passes every build-time check.

It installs and launches the app, then drives the UI through the debug build's SmokeTestReceiver
(app/src/debug) and checks, from what the UI holds and from the device:
  1. the UI starts the engine service, Qt comes up in the :engine process, and the UI attaches to it;
  2. the service runs in the foreground in :engine;
  3. connecting to fake_telnet.py on this host (10.0.2.2 from the emulator) starts a profile: Mudlet's Lua is up,
     with luaL_openlibs and Mudlet's own Lua files;
  4. the game's coloured lines reach the UI with their colours;
  5. a Lua trigger fires on a game line, cecho()s on it (a function from Mudlet's Lua files) and its
     showNotification() reaches the UI and is posted as an Android notification;
  6. after the :engine process is killed, the UI starts a new engine and attaches to it.
If the engine process crashes, it stops there and gives the crash from logcat, symbolised with ndk-stack when
--symbols names the unstripped engine's directory and ANDROID_NDK_ROOT is set.

usage: android_smoke.py --apk <app-debug.apk> --out <dir for logcat, screenshot, state> [--symbols <dir>]
"""
import argparse
import base64
import json
import os
import re
import shlex
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_telnet  # noqa: E402

PKG = "org.mudlet.mudlet"
ENGINE_PROCESS = PKG + ":engine"
ACTIVITY = PKG + "/org.mudlet.android.ui.MainActivity"
RECEIVER = PKG + "/org.mudlet.android.debug.SmokeTestReceiver"
# The emulator's address for the host's loopback, where fake_telnet listens
HOST_FROM_EMULATOR = "10.0.2.2"
# The profile MudletEngine makes when MUDLET_PROFILE is unset
PROFILE = "Android"
BOLD = 1
RUN_STRIDE = 6


class SmokeFailure(Exception):
    pass


def adb(*args, check=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise SmokeFailure(f"adb {' '.join(args)} failed ({result.returncode}): {result.stdout}{result.stderr}")
    return result.stdout


def shell(command, check=True):
    return adb("shell", command, check=check)


class Device:
    def __init__(self, out, symbols):
        self.out = out
        self.symbols = symbols
        self.logcat_path = os.path.join(out, "logcat.txt")
        self.logcat = None
        self.last_state = None

    def start_logcat(self):
        adb("logcat", "-c")
        self.logcat = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=open(self.logcat_path, "w"),
                                       stderr=subprocess.STDOUT)

    def stop_logcat(self):
        if self.logcat:
            self.logcat.terminate()
            self.logcat.wait(10)

    def log_text(self):
        with open(self.logcat_path, errors="replace") as f:
            return f.read()

    def command(self, cmd, **extras):
        """Runs one SmokeTestReceiver command and returns the UI's state."""
        args = ["am", "broadcast", "-n", RECEIVER, "--es", "cmd", cmd]
        for key, value in extras.items():
            args += ["--ei" if isinstance(value, int) else "--es", key, str(value)]
        out = shell(" ".join(shlex.quote(a) for a in args))
        m = re.search(r'Broadcast completed: result=(-?\d+), data="([A-Za-z0-9+/=]*)"', out)
        if not m:
            raise SmokeFailure(f"the debug hook did not answer {cmd}: {out.strip()} (is this a debug APK?)")
        state = json.loads(base64.b64decode(m.group(2)))
        self.last_state = state
        if m.group(1) != "0":
            raise SmokeFailure(f"the UI refused {cmd}: {state.get('error')}")
        return state

    def crash(self):
        """The engine's crash in logcat, symbolised if possible, or None."""
        log = self.log_text()
        fatal = re.search(r"^.*Fatal signal .*\(.*mudlet.*\).*$", log, re.M)
        if not fatal:
            return None
        time.sleep(5)  # debuggerd's report follows the signal
        log = self.log_text()
        aborts = [line for line in log.splitlines() if re.search(r" F (libc|DEBUG) *: .*(FORTIFY|Abort message)", line)]
        frames = [line for line in log.splitlines() if re.search(r" F DEBUG *: +#\d+ pc ", line)]
        report = "\n".join([fatal.group(0), *aborts, *frames[:25]])
        ndk = os.environ.get("ANDROID_NDK_ROOT")
        if self.symbols and ndk:
            result = subprocess.run([os.path.join(ndk, "ndk-stack"), "-sym", self.symbols, "-i", self.logcat_path],
                                    capture_output=True, text=True)
            symbolised = os.path.join(self.out, "crash-symbolised.txt")
            with open(symbolised, "w") as f:
                f.write(result.stdout + result.stderr)
            report += "\n\nndk-stack:\n" + "\n".join(result.stdout.splitlines()[:40])
        return report

    def wait(self, what, pred, timeout):
        """Polls the UI's state until pred(state) holds; stops early if the engine crashed."""
        deadline = time.monotonic() + timeout
        while True:
            state = self.command("state")
            if pred(state):
                return state
            report = self.crash()
            if report:
                hint = ""
                if "FORTIFY" in report:
                    hint = ("\nThis is bionic's FORTIFY abort. If it is in Lua 5.1 (luaL_openlibs), the Lua 5.1 FORTIFY "
                            "fix in scripts/build-libmudlet.sh was not in effect.")
                raise SmokeFailure(f"the engine process crashed while waiting for {what}:\n{report}{hint}")
            if time.monotonic() > deadline:
                raise SmokeFailure(f"timed out after {timeout} s waiting for {what}; the UI holds:\n{summary(state)}")
            time.sleep(0.5)

    def screenshot(self, name):
        with open(os.path.join(self.out, name), "wb") as f:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=60)


def summary(state):
    shown = dict(state)
    shown["lines"] = [line["text"] for line in state.get("lines", [])][-15:]
    return json.dumps(shown, indent=1, ensure_ascii=False)


def line_with(state, text):
    return next((line for line in state["lines"] if line["text"] == text), None)


def runs_of(line):
    flat = line["runs"]
    return [flat[i:i + RUN_STRIDE] for i in range(0, len(flat) - RUN_STRIDE + 1, RUN_STRIDE)]


def run_at(line, index):
    return next((r for r in runs_of(line) if r[0] <= index < r[0] + r[1]), None)


def engine_pids():
    return shell(f"pidof {ENGINE_PROCESS}", check=False).split()


LOG = None  # <out>/smoke.log: what this prints, for CI's job summary


def log(text):
    print(text, flush=True)
    if LOG:
        with open(LOG, "a") as f:
            f.write(text + "\n")


def step(text):
    log(f"--- {text}")


def ok(text):
    log(f"PASS {text}")


def run(dev, apk):
    port = fake_telnet.start_in_thread()
    step(f"fake game server on the host's port {port}, {HOST_FROM_EMULATOR}:{port} from the emulator")

    step("install and launch")
    adb("install", "-r", "-g", apk, timeout=300)  # -g: POST_NOTIFICATIONS without the dialog
    for command in ("input keyevent KEYCODE_WAKEUP", "svc power stayon true", "wm dismiss-keyguard",
                    "settings put system screen_off_timeout 1800000"):
        shell(command, check=False)  # the UI detaches from the engine when its Activity stops
    dev.start_logcat()
    shell(f"am start -W -n {ACTIVITY}")

    step("the engine service, Qt in :engine, the UI attached")
    dev.command("start")
    state = dev.wait("the UI to attach to the libmudlet engine, with Qt up in :engine",
                     lambda s: s["link"].startswith("attached to pid") and s["status"].get("engineKind") == "libmudlet"
                     and "qtStartMsAfterAppOnCreate" in s["status"], 120)
    status = state["status"]
    engine_pid = state["enginePid"]
    if [str(engine_pid)] != engine_pids():
        raise SmokeFailure(f"the UI is attached to pid {engine_pid}, but {ENGINE_PROCESS} is {engine_pids()}")
    if engine_pid == state["uiPid"]:
        raise SmokeFailure("the engine runs in the UI's process")
    if status.get("serviceReadyWait") != "ok":
        raise SmokeFailure(f"the engine's main() did not get the service-ready call: {status.get('serviceReadyWait')}")
    ok(f"Qt up in {ENGINE_PROCESS} (pid {engine_pid}) {status['qtStartMsAfterAppOnCreate']} ms after "
       f"Application.onCreate, QPA {status.get('platform')}; the UI (pid {state['uiPid']}) is attached")

    state = dev.wait("the engine service in the foreground in :engine",
                     lambda s: "foreground=true" in s["fgs"] and f"process={ENGINE_PROCESS}" in s["fgs"], 30)
    ok(f"foreground service: {state['fgs']}")

    step(f"connect to {HOST_FROM_EMULATOR}:{port}: a profile starts, and the game's lines arrive")
    dev.command("connect", host=HOST_FROM_EMULATOR, port=port)
    state = dev.wait("the profile to load and the game's banner to arrive",
                     lambda s: s["status"].get("profile", "").startswith("start failed")
                     or (s["status"].get("profile") == f"{PROFILE} loaded" and line_with(s, "bright blue 256 plain")), 180)
    status = state["status"]
    if status["profile"].startswith("start failed"):
        raise SmokeFailure(f"the profile did not start: {status['profile']}")
    ok(f"profile '{PROFILE}' loaded in {status.get('profileLoadMs')} ms (Mudlet's Lua, luaL_openlibs and its Lua files)")
    if status.get("connection") != "connected":
        raise SmokeFailure(f"the game connection is '{status.get('connection')}'")
    red = line_with(state, "Red bold normal green")
    first = run_at(red, 0) if red else None
    if not first or first[1] != 8 or first[2] == -1 or not first[4] & BOLD:
        raise SmokeFailure(f"the banner's first line lacks its bold red run: {red}")
    green = run_at(red, red["text"].index("green"))
    if not green or green[2] in (-1, first[2]):
        raise SmokeFailure(f"the banner's first line lacks its green run: {red}")
    ok(f"coloured game lines in the UI: {red['text']!r} with runs {runs_of(red)}")

    step("a Lua trigger: cecho() on the line, and a notification")
    dev.command("lua", code='tempTrigger("tells you", function() cecho("<green>[smoke trigger]") '
                            'showNotification("Smoke tell", line) end)')
    dev.wait("the trigger's Lua to run", lambda s: s["status"].get("luaResult") == "ran", 30)
    dev.command("send", text="tell")
    marked = "Alice tells you 'ping'[smoke trigger]"
    state = dev.wait("the trigger's cecho() and notification", lambda s: line_with(s, marked) and s["notifyCount"] >= 1, 30)
    cecho_run = run_at(line_with(state, marked), marked.index("[smoke"))
    if not cecho_run or cecho_run[2] == -1:
        raise SmokeFailure(f"cecho()'s text has no colour: {line_with(state, marked)}")
    ok(f"the trigger fired: {marked!r}, cecho() run {cecho_run}; the UI got {state['notifyCount']} notify")
    deadline = time.monotonic() + 15
    while "Smoke tell" not in shell("dumpsys notification --noredact"):
        if time.monotonic() > deadline:
            raise SmokeFailure("no Android notification titled 'Smoke tell' was posted")
        time.sleep(0.5)
    ok("the engine process posted the trigger's notification")

    step("kill the :engine process: the UI starts a new engine and attaches to it")
    dev.screenshot("before-kill.png")
    dev.command("kill")
    state = dev.wait("the UI to attach to a new engine",
                     lambda s: s["engineLosses"] >= 1 and s["enginePid"] not in (0, engine_pid)
                     and "new engine instance" in s["link"] and s["status"].get("engineKind") == "libmudlet", 120)
    new_pid = state["enginePid"]
    if [str(new_pid)] != engine_pids():
        raise SmokeFailure(f"the UI is attached to pid {new_pid}, but {ENGINE_PROCESS} is {engine_pids()}")
    state = dev.wait("the new engine service in the foreground",
                     lambda s: "foreground=true" in s["fgs"] and f"pid={new_pid}" in s["fgs"], 30)
    ok(f"reattached to the new engine, pid {new_pid}: {state['link']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--symbols", help="directory with the unstripped engine library, for ndk-stack")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    global LOG
    LOG = os.path.join(args.out, "smoke.log")
    open(LOG, "w").close()
    dev = Device(args.out, args.symbols)
    failure = None
    try:
        run(dev, os.path.abspath(args.apk))
    except (SmokeFailure, subprocess.TimeoutExpired) as e:
        failure = str(e)
    finally:
        try:
            dev.screenshot("screenshot.png")
        except (OSError, subprocess.SubprocessError) as e:
            log(f"no screenshot: {e}")
        dev.stop_logcat()
        if dev.last_state is not None:
            with open(os.path.join(args.out, "ui-state.json"), "w") as f:
                json.dump(dev.last_state, f, indent=1, ensure_ascii=False)
    if failure:
        log(f"\nFAIL {failure}")
        if os.environ.get("GITHUB_ACTIONS"):
            text = failure + "\n(logcat, screenshot and the UI's state are in the emulator-smoke-test artifact)"
            text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Emulator smoke test::{text}", flush=True)
        sys.exit(1)
    log("\nall passed")


if __name__ == "__main__":
    main()
