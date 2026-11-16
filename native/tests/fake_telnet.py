#!/usr/bin/env python3
"""Scripted fake telnet game server for the stand-in engine.

Commands a client may send (one per line):
  echo <text>     -> "You said: <text>"
  flood <n>       -> n coloured lines, then "FLOOD DONE"
  prompt-nogo     -> "Silent prompt> " with no GA, to exercise the 500 ms rule
  ga-partial      -> a GA prompt, then "Press return: " with no newline, which "\r\nlast line\r\n" ends 1.5 s later
  negotiation     -> "NEG: <hex>" of every telnet byte the client sent so far
  tell            -> another "tells you" line
  tls             -> "TLS: <protocol>" of the connection, or "TLS: none"
  password        -> "Password: " and IAC WILL ECHO, as a game hides a password; the next line, whatever it is, gets
                     IAC WONT ECHO and "Password accepted"
  naws            -> IAC DO NAWS, then "NAWS asked"
  size            -> "SIZE: <columns>x<rows>" of the client's last NAWS, or "SIZE: none"
  mxp             -> IAC WILL MXP, then "Food: stew" in MXP's secure line mode, "stew" a <SEND> link whose menu is
                     "Look" (sends "look stew") and "Eat" (sends "eat stew"), with the tooltip "the stew"
  map <path>      -> GMCP Client.Map offering MAP_URL_BASE + "/" + <path> as the game's map, then "Map offered", so the
                     URL, which can hold a user name and password, is never a line the client sent
  quit            -> close
Any other line gets "Huh?", without the line, so a password the client sends never comes back as text.

Given an ssl.SSLContext, it speaks TLS on its port: each connection's handshake comes first, and its outcome is
appended to TLS_HANDSHAKES.
"""
import json
import socket
import ssl
import sys
import threading
import time

IAC, DONT, DO, WONT, WILL, SB, GA, SE = 255, 254, 253, 252, 251, 250, 249, 240
TTYPE, ECHO, NAWS, MXP, GMCP = 24, 1, 31, 91, 201
# (lines, start, end) of each flood, as seen by the sender
FLOOD_TIMES = []
# When each game connection was accepted (time.monotonic())
ACCEPTED = []
# time.perf_counter() just before the latest "banner" went out, and before ga-partial's "line-end"
SENT = {}
# ("ok", protocol) or ("failed", why) for each TLS connection, in the order their handshakes ended
TLS_HANDSHAKES = []
# Every line any client sent, in order
RECEIVED = []
# (columns, rows) of every NAWS any client sent, in order
NAWS_SIZES = []
# Where "map <path>" offers maps from, as a test sets it
MAP_URL_BASE = "http://127.0.0.1:1"

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
# A game that has sent no GA (yet): its prompt has neither a line feed nor GA after it
LOGIN_BANNER = b"Welcome\r\nLogin: "


class Session(threading.Thread):
    def __init__(self, conn, banner, tls=None):
        super().__init__(daemon=True)
        self.conn = conn
        self.banner = banner
        self.tls = tls
        self.negotiation = bytearray()
        self.password_next = False
        self.size = None

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
        SENT["banner"] = time.perf_counter()
        conn.sendall(self.banner)
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
                            self.subnegotiated(bytes(self.negotiation[sb_start:]))
                        i += 1
                        continue
                    if b == IAC and i + 1 < len(data) and data[i + 1] == SB:
                        in_sb = True
                        sb_start = len(self.negotiation)
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

    def subnegotiated(self, sb):
        """One IAC SB ... IAC SE the client sent: a NAWS one is kept, its doubled 255 bytes undone."""
        body = sb[2:-2].replace(bytes([IAC, IAC]), bytes([IAC]))
        if body[:1] == bytes([NAWS]) and len(body) == 5:
            self.size = (body[1] << 8 | body[2], body[3] << 8 | body[4])
            NAWS_SIZES.append(self.size)

    def handle(self, line):
        conn = self.conn
        RECEIVED.append(line)
        if self.password_next:
            self.password_next = False
            conn.sendall(bytes([IAC, WONT, ECHO]) + b"Password accepted\r\n")
        elif line.startswith("echo "):
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
        elif line == "ga-partial":
            conn.sendall(b"Ready> " + bytes([IAC, GA]))
            time.sleep(0.05)
            conn.sendall(b"Press return: ")
            time.sleep(1.5)
            SENT["line-end"] = time.perf_counter()
            conn.sendall(b"\r\nlast line\r\n")
        elif line == "negotiation":
            conn.sendall(f"NEG: {self.negotiation.hex()}\r\n".encode())
        elif line == "tell":
            conn.sendall(b"Alice tells you 'ping'\r\n")
        elif line == "tls":
            conn.sendall(f"TLS: {conn.version() if isinstance(conn, ssl.SSLSocket) else 'none'}\r\n".encode())
        elif line == "password":
            self.password_next = True
            conn.sendall(b"Password: " + bytes([IAC, WILL, ECHO]))
        elif line == "naws":
            conn.sendall(bytes([IAC, DO, NAWS]) + b"NAWS asked\r\n")
        elif line == "size":
            conn.sendall(f"SIZE: {self.size[0]}x{self.size[1]}\r\n".encode() if self.size else b"SIZE: none\r\n")
        elif line == "mxp":
            conn.sendall(bytes([IAC, WILL, MXP])
                         + b'\x1b[1zFood: <SEND href="look stew|eat stew" hint="the stew|Look|Eat">stew</SEND>\r\n')
        elif line.startswith("map "):
            offer = json.dumps({"url": f"{MAP_URL_BASE}/{line[4:]}"}).encode()
            conn.sendall(bytes([IAC, SB, GMCP]) + b"Client.Map " + offer + bytes([IAC, SE]) + b"Map offered\r\n")
        elif line == "quit":
            conn.sendall(b"Bye\r\n")
            return False
        else:
            conn.sendall(b"Huh?\r\n")
        return True


def serve(port=0, ready=None, banner=BANNER, tls=None):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)
    if ready is not None:
        ready(srv.getsockname()[1])
    while True:
        conn, _ = srv.accept()
        ACCEPTED.append(time.monotonic())
        Session(conn, banner, tls).start()


def start_in_thread(banner=BANNER, tls=None):
    got = {}
    ev = threading.Event()

    def ready(port):
        got["port"] = port
        ev.set()

    threading.Thread(target=serve, args=(0, ready, banner, tls), daemon=True).start()
    ev.wait(5)
    return got["port"]


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 4000, lambda p: print(f"fake telnet on {p}", flush=True))
