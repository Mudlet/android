"""The profile requests (listProfiles, saveProfile, deleteProfile, openProfile) through the protocol, as both host
tests run them: the stand-in's (test_engine.py) and libmudlet's (test_mudlet_engine.py)."""
import time


def profile(name, port, login="", auto_reconnect=False):
    return {"name": name, "host": "127.0.0.1", "port": port, "tls": False, "login": login, "autoReconnect": auto_reconnect}


def names(answer):
    return [p["name"] for p in answer.get("profiles", [])]


def is_status(key, value):
    return lambda m: m["t"] == "status" and m["key"] == key and m["value"] == value


def round_trips(c, check, port, password, game_connections, logged_in=lambda: None, timeout=5.0):
    """Leaves "Beta" open and connected to the game at port, and "Alpha" deleted. game_connections() counts the game's
    connections so far; logged_in() waits, after "Alpha" has connected with the password, for what the engine sends.
    Returns every frame seen, for the caller to search for the password."""
    seen = []

    def ask(msg):
        return c.ask(msg, timeout, seen)

    def wait_since(mark, pred):
        """The first frame since seen[mark] that pred accepts, waiting for it if none has come yet."""
        for m in seen[mark:]:
            if pred(m):
                return m
        return c.wait_for(pred, timeout, seen)

    def wait_connections(beyond):
        """game_connections(), waiting up to the timeout for it to pass beyond: the engine reports "connected" once the
        kernel has made the connection, which can be before the game's accept thread counts it."""
        deadline = time.monotonic() + timeout
        while game_connections() <= beyond and time.monotonic() < deadline:
            time.sleep(0.01)
        return game_connections()

    m = ask({"t": "listProfiles"})
    check(m["t"] == "profiles" and "Alpha" not in names(m), f"listProfiles answers with the profiles: {names(m)}")

    alpha = profile("Alpha", port)
    m = ask({"t": "saveProfile", **alpha})
    check(m["t"] == "profiles" and m["profiles"][:1] == [alpha], f"saveProfile creates a profile, listed first: {m}")
    beta = profile("Beta", port, auto_reconnect=True)
    m = ask({"t": "saveProfile", **beta})
    check(names(m)[:2] == ["Beta", "Alpha"], f"the newest profile is listed first: {names(m)}")
    alpha["login"] = "hero"
    m = ask({"t": "saveProfile", **dict(alpha, name="ALPHA")})
    check(names(m)[:2] == ["Beta", "Alpha"] and m["profiles"][1] == alpha,
          f"saveProfile updates the profile named in any case, keeping its name and its place: {m['profiles'][:2]}")

    for bad, why in [(dict(beta, name="New/Name"), "a name Mudlet cannot use"),
                     (dict(beta, name=" New"), "a name with a space round it"),
                     (dict(beta, name="New", port=0), "port 0"),
                     (dict(beta, name="New", port=1.5), "a port that is not whole"),
                     (dict(beta, name="New", host=" "), "no host"),
                     (dict(beta, name="New", tls="yes"), "a tls that is not a boolean")]:
        m = ask({"t": "saveProfile", **bad})
        check(m["t"] == "error" and m["message"].startswith("saveProfile: "), f"saveProfile refuses {why}: {m}")
    m = ask({"t": "listProfiles"})
    check(not {"New", "New/Name", " New"} & set(names(m)), f"and stores none of them: {names(m)}")

    mark = len(seen)
    m = ask({"t": "openProfile", "name": "alpha", "password": password})
    check(m["t"] == "profiles" and names(m)[:2] == ["Alpha", "Beta"], f"openProfile lists the opened profile first: {names(m)}")
    wait_since(mark, is_status("profile", "Alpha loaded"))
    wait_since(mark, is_status("connection", "connected"))
    check(True, "and starts it, connected to its game")
    logged_in()

    m = ask({"t": "deleteProfile", "name": "Alpha"})
    check(m["t"] == "error" and m["message"] == 'deleteProfile: "Alpha" is open: open another profile before deleting it',
          f"deleteProfile of the running profile is refused: {m}")

    connections = game_connections()
    mark = len(seen)
    m = ask({"t": "openProfile", "name": "Beta"})
    check(m["t"] == "profiles" and names(m)[:2] == ["Beta", "Alpha"], f"openProfile switches profiles: {names(m)}")
    wait_since(mark, is_status("profile", "Beta loaded"))
    wait_since(mark, is_status("connection", "connected"))
    count = wait_connections(connections)
    check(count > connections, f"the profile switched to is connected to its game: {count} connections, {connections} before")

    m = ask({"t": "deleteProfile", "name": "alpha"})
    check(m["t"] == "profiles" and "Alpha" not in names(m) and names(m)[:1] == ["Beta"],
          f"deleteProfile deletes a profile that is not running: {names(m)}")
    m = ask({"t": "deleteProfile", "name": "Alpha"})
    check(m["t"] == "error" and m["message"] == 'deleteProfile: there is no profile named "Alpha"',
          f"deleteProfile of an unknown profile is refused: {m}")
    m = ask({"t": "openProfile", "name": "Alpha"})
    check(m["t"] == "error" and m["message"] == 'openProfile: there is no profile named "Alpha"',
          f"openProfile of an unknown profile is refused: {m}")
    m = ask({"t": "openProfile", "name": "Beta", "connect": "yes"})
    check(m["t"] == "error" and m["message"] == "openProfile: connect must be true or false", f"a connect that is not a boolean: {m}")
    m = ask({"t": "openProfile", "name": "Beta", "password": 5})
    check(m["t"] == "error" and m["message"] == "openProfile: password must be a string", f"a password that is not a string: {m}")

    c.send({"t": "disconnect"})
    wait_since(len(seen), is_status("connection", "disconnected"))
    connections = game_connections()
    mark = len(seen)
    m = ask({"t": "openProfile", "name": "Beta"})
    check(m["t"] == "profiles", f"openProfile of the running profile: {m}")
    wait_since(mark, is_status("connection", "connected"))
    closed = [x for x in seen[mark:] if is_status("profile", "Beta closing")(x)]
    count = wait_connections(connections)
    check(count > connections and not closed,
          f"connects it again, disconnected, without closing it: {count} connections, {connections} before; {closed}")
    return seen


def password_kept_out(check, password, seen, log_path):
    """Neither a frame the UI got nor the engine's log has the password in it."""
    in_frames = [repr(m).replace(password, "<the password>") for m in seen if password in repr(m)]
    check(not in_frames, f"no frame carries the password: {in_frames[:3]}")
    with open(log_path, errors="replace") as f:
        log = f.read()
    check(password not in log, f"the engine's log does not have the password in it ({len(log)} characters searched)")
