#!/usr/bin/env python3
"""SOCKS5 proxy for the engine host tests (RFC 1928, with RFC 1929's user name and password), on 127.0.0.1.

It takes CONNECT only, and only to this machine: a request for anywhere else is refused, so no test reaches an outside
host through it. Each Proxy keeps what its clients did in .requests, one entry a connection, never with the password
a client gave: only whether it was the one wanted.
"""
import ipaddress
import socket
import threading

VERSION = 5
NO_AUTH, USER_PASSWORD, NO_ACCEPTABLE = 0, 2, 0xFF
CONNECT = 1
IPV4, NAME, IPV6 = 1, 3, 4
SUCCEEDED, NOT_ALLOWED, CONNECTION_REFUSED, COMMAND_NOT_SUPPORTED = 0, 2, 5, 7
LOCAL_NAMES = ("localhost",)


def read_exactly(conn, count):
    data = b""
    while len(data) < count:
        chunk = conn.recv(count - len(data))
        if not chunk:
            raise ConnectionError("the client closed")
        data += chunk
    return data


def is_local(host):
    if host in LOCAL_NAMES:
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def relay(source, sink):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            sink.sendall(data)
    except OSError:
        pass
    finally:
        for end in (source, sink):
            try:
                end.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


class Proxy:
    """Listens once made. With a user, a client must give that user name and password; refuse_connect answers every
    CONNECT with "connection refused", as a proxy does whose way to the game is closed."""

    def __init__(self, user=None, password=None, refuse_connect=False):
        self.user = user
        self.password = password
        self.refuse_connect = refuse_connect
        # {"auth": "none", "password", "wrong password" or "no method", "user": the name given, "target": (host, port),
        #  "reply": the reply code sent}; the keys a connection did not get to are left out
        self.requests = []
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(8)
        self.port = self.listener.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while True:
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def close(self):
        """Stops listening; a connection after this is refused by the system, as one to a proxy that is not there."""
        # A close alone leaves the thread in accept() holding the port open
        try:
            self.listener.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.listener.close()

    def _serve(self, conn):
        request = {}
        self.requests.append(request)
        try:
            target = self._negotiate(conn, request)
            if target is None:
                conn.close()
                return
            for source, sink in ((conn, target), (target, conn)):
                threading.Thread(target=relay, args=(source, sink), daemon=True).start()
        except (OSError, ConnectionError):
            conn.close()

    def _negotiate(self, conn, request):
        """The game's socket once the client is through, or None for a client that was turned away."""
        version, count = read_exactly(conn, 2)
        methods = read_exactly(conn, count)
        wanted = USER_PASSWORD if self.user is not None else NO_AUTH
        if version != VERSION or wanted not in methods:
            request["auth"] = "no method"
            conn.sendall(bytes([VERSION, NO_ACCEPTABLE]))
            return None
        conn.sendall(bytes([VERSION, wanted]))
        if wanted == USER_PASSWORD:
            _, length = read_exactly(conn, 2)
            user = read_exactly(conn, length).decode(errors="replace")
            (length,) = read_exactly(conn, 1)
            password = read_exactly(conn, length).decode(errors="replace")
            ok = user == self.user and password == self.password
            request["user"] = user
            request["auth"] = "password" if ok else "wrong password"
            conn.sendall(bytes([1, 0 if ok else 1]))
            if not ok:
                return None
        else:
            request["auth"] = "none"
        _, command, _, kind = read_exactly(conn, 4)
        if kind == IPV4:
            host = socket.inet_ntop(socket.AF_INET, read_exactly(conn, 4))
        elif kind == IPV6:
            host = socket.inet_ntop(socket.AF_INET6, read_exactly(conn, 16))
        elif kind == NAME:
            (length,) = read_exactly(conn, 1)
            host = read_exactly(conn, length).decode(errors="replace")
        else:
            raise ConnectionError(f"address type {kind}")
        port = int.from_bytes(read_exactly(conn, 2), "big")
        request["target"] = (host, port)

        def reply(code):
            request["reply"] = code
            conn.sendall(bytes([VERSION, code, 0, IPV4, 0, 0, 0, 0, 0, 0]))

        if command != CONNECT:
            reply(COMMAND_NOT_SUPPORTED)
            return None
        if not is_local(host):
            reply(NOT_ALLOWED)
            return None
        if self.refuse_connect:
            reply(CONNECTION_REFUSED)
            return None
        try:
            target = socket.create_connection((host, port), timeout=5)
        except OSError:
            reply(CONNECTION_REFUSED)
            return None
        target.settimeout(None)
        reply(SUCCEEDED)
        return target
