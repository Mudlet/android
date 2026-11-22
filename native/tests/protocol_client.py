"""Python client for the engine socket protocol (same framing as the Kotlin client)."""
import json
import select
import socket
import struct
import time

PROTOCOL_VERSION = 51


class Closed(Exception):
    pass


class Client:
    def __init__(self, path, hello=True, version=PROTOCOL_VERSION, profile_sockets=False, opens_urls=False):
        """With profile_sockets, a control socket's client gets only the control traffic, as docs/multi-profile.md
        says; without, version 14's, the front profile's too."""
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(path)
        self.buf = bytearray()
        self.bytes_read = 0
        self.next_id = 0
        if hello:
            msg = {"t": "hello", "v": version, "client": "python-test"}
            if profile_sockets:
                msg["profileSockets"] = True
            if opens_urls:
                msg["opensUrls"] = True
            self.send(msg)

    def send(self, msg):
        payload = json.dumps(msg, separators=(",", ":")).encode()
        self.sock.sendall(struct.pack(">I", len(payload)) + payload)

    def _fill(self, timeout):
        r, _, _ = select.select([self.sock], [], [], timeout)
        if not r:
            return False
        data = self.sock.recv(1 << 20)
        if not data:
            raise Closed()
        self.bytes_read += len(data)
        self.buf += data
        return True

    def next_raw(self, timeout=5.0):
        """Return one undecoded frame payload, or None on timeout."""
        deadline = time.monotonic() + timeout
        while True:
            if len(self.buf) >= 4:
                (n,) = struct.unpack_from(">I", self.buf)
                if len(self.buf) >= 4 + n:
                    payload = bytes(self.buf[4:4 + n])
                    del self.buf[:4 + n]
                    return payload
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self._fill(remaining):
                return None

    def recv(self, timeout=5.0):
        raw = self.next_raw(timeout)
        return None if raw is None else json.loads(raw)

    def wait_for(self, pred, timeout=5.0, collect=None):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("message not received")
            msg = self.recv(remaining)
            if msg is None:
                raise TimeoutError("message not received")
            if collect is not None:
                collect.append(msg)
            if pred(msg):
                return msg

    def ask(self, msg, timeout=5.0, collect=None, answers=("profiles", "error")):
        """Send a request with a fresh id and return the frame of one of those types that answers it."""
        self.next_id += 1
        request_id = f"ask{self.next_id}"
        self.send(dict(msg, id=request_id))
        return self.wait_for(lambda m: m["t"] in answers and m.get("re") == request_id, timeout, collect)

    def close(self):
        self.sock.close()
