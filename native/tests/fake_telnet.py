#!/usr/bin/env python3
"""Scripted fake telnet game server for the stand-in engine.

Commands a client may send (one per line):
  echo <text>     -> "You said: <text>"
  flood <n>       -> n coloured lines, then "FLOOD DONE"
  prompt-nogo     -> "Silent prompt> " with no GA, to exercise the 500 ms rule
  negotiation     -> "NEG: <hex>" of every telnet byte the client sent so far
  tell            -> another "tells you" line
  tls             -> "TLS: <protocol>" of the connection, or "TLS: none"
  quit            -> close

Given an ssl.SSLContext, it speaks TLS on its port: each connection's handshake comes first, and its outcome is
appended to TLS_HANDSHAKES.
"""
import socket
import ssl
import sys
import threading
import time

IAC, DONT, DO, WONT, WILL, SB, GA, SE = 255, 254, 253, 252, 251, 250, 249, 240
TTYPE, ECHO, GMCP = 24, 1, 201
# (lines, start, end) of each flood, as seen by the sender
FLOOD_TIMES = []
# ("ok", protocol) or ("failed", why) for each TLS connection, in the order their handshakes ended
TLS_HANDSHAKES = []

BANNER = (
    bytes([IAC, DO, TTYPE, IAC, WILL, ECHO, IAC, WILL, GMCP])
    + bytes([IAC, SB, TTYPE, 1, IAC, SE])
    + b"\x1b[1;31mRed bold\x1b[0m normal \x1b[32mgreen\x1b[0m\r\n"
    + "Héllo wörld ✓\r\n".encode()
    + b"Bob tells you 'hello there'\r\n"
    + b"a\xff\xffb\r\n"
    + b"\x1b[38;5;12mbright blue 256\x1b[39m plain\x1b[2J\r\n"
    + b"HP:100> " + bytes([IAC, GA])
)


class Session(threading.Thread):
    def __init__(self, conn, tls=None):
        super().__init__(daemon=True)
        self.conn = conn
        self.tls = tls
        self.negotiation = bytearray()

    def run(self):
        conn = self.conn
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        if self.tls is not None:
            try:
                conn = self.conn = self.tls.wrap_socket(conn, server_side=True)
            except (ssl.SSLError, OSError) as e:
                TLS_HANDSHAKES.append(("failed", str(e)))
                conn.close()
                return
            TLS_HANDSHAKES.append(("ok", conn.version()))
        conn.sendall(BANNER)
        buf = bytearray()
        in_sb = False
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    return
                i = 0
                while i < len(data):
                    b = data[i]
                    # Subnegotiation (Mudlet answers the GMCP offer with Core.Hello), possibly split across reads
                    if in_sb:
                        self.negotiation.append(b)
                        if b == SE and self.negotiation[-2:-1] == bytes([IAC]):
                            in_sb = False
                        i += 1
                        continue
                    if b == IAC and i + 1 < len(data) and data[i + 1] == SB:
                        in_sb = True
                        self.negotiation += data[i:i + 2]
                        i += 2
                        continue
                    if b == IAC and i + 2 < len(data) and data[i + 1] in (DO, DONT, WILL, WONT):
                        self.negotiation += data[i:i + 3]
                        i += 3
                        continue
                    buf.append(b)
                    i += 1
                while b"\n" in buf:
                    line, _, rest = bytes(buf).partition(b"\n")
                    buf = bytearray(rest)
                    if not self.handle(line.rstrip(b"\r").decode("utf-8", "replace")):
                        return
        except OSError:
            pass
        finally:
            conn.close()

    def handle(self, line):
        conn = self.conn
        if line.startswith("echo "):
            conn.sendall(f"You said: {line[5:]}\r\n".encode())
        elif line.startswith("flood "):
            n = int(line[6:])
            self.flood_start = time.perf_counter()
            chunk = []
            for k in range(n):
                chunk.append(f"\x1b[3{k % 8}mLine {k:07d}\x1b[0m of the flood, some filler text to look like a room\r\n")
                if len(chunk) == 512:
                    conn.sendall("".join(chunk).encode())
                    chunk = []
            chunk.append("FLOOD DONE\r\n")
            conn.sendall("".join(chunk).encode())
            FLOOD_TIMES.append((n, self.flood_start, time.perf_counter()))
        elif line.startswith("pace "):
            # pace <n> <lines per second>: the same lines as flood, sent in 5 ms ticks
            n, rate = (int(x) for x in line[5:].split())
            per_tick = max(1, rate // 200)
            start = time.perf_counter()
            k = 0
            while k < n:
                batch = "".join(f"\x1b[3{j % 8}mLine {j:07d}\x1b[0m of the flood, some filler text to look like a room\r\n"
                                for j in range(k, min(n, k + per_tick)))
                conn.sendall(batch.encode())
                k += per_tick
                delay = start + k / rate - time.perf_counter()
                if delay > 0:
                    time.sleep(delay)
            conn.sendall(b"FLOOD DONE\r\n")
            FLOOD_TIMES.append((n, start, time.perf_counter()))
        elif line == "prompt-nogo":
            conn.sendall(b"Silent prompt> ")
        elif line == "negotiation":
            conn.sendall(f"NEG: {self.negotiation.hex()}\r\n".encode())
        elif line == "tell":
            conn.sendall(b"Alice tells you 'ping'\r\n")
        elif line == "tls":
            conn.sendall(f"TLS: {conn.version() if isinstance(conn, ssl.SSLSocket) else 'none'}\r\n".encode())
        elif line == "quit":
            conn.sendall(b"Bye\r\n")
            return False
        else:
            conn.sendall(f"Huh? {line}\r\n".encode())
        return True


def serve(port=0, ready=None, tls=None):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)
    if ready is not None:
        ready(srv.getsockname()[1])
    while True:
        conn, _ = srv.accept()
        Session(conn, tls).start()


def start_in_thread(tls=None):
    got = {}
    ev = threading.Event()

    def ready(port):
        got["port"] = port
        ev.set()

    threading.Thread(target=serve, args=(0, ready, tls), daemon=True).start()
    ev.wait(5)
    return got["port"]


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 4000, lambda p: print(f"fake telnet on {p}", flush=True))
