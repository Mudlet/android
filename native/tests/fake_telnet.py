#!/usr/bin/env python3
"""Scripted fake telnet game server for the stand-in engine.

Commands a client may send (one per line):
  echo <text>     -> "You said: <text>"
  flood <n>       -> n coloured lines, then "FLOOD DONE"
  prompt-nogo     -> "Silent prompt> " with no GA, to exercise the 500 ms rule
  ga-partial      -> a GA prompt, then "Press return: " with no newline, which "\r\nlast line\r\n" ends 1.5 s later
  negotiation     -> "NEG: <hex>" of every telnet byte the client sent so far
  tell            -> another "tells you" line
  quit            -> close
"""
import socket
import sys
import threading
import time

IAC, DONT, DO, WONT, WILL, SB, GA, SE = 255, 254, 253, 252, 251, 250, 249, 240
TTYPE, ECHO, GMCP = 24, 1, 201
# (lines, start, end) of each flood, as seen by the sender
FLOOD_TIMES = []
# time.perf_counter() just before the latest "banner" went out, and before ga-partial's "line-end"
SENT = {}

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
    def __init__(self, conn, banner):
        super().__init__(daemon=True)
        self.conn = conn
        self.banner = banner
        self.negotiation = bytearray()

    def run(self):
        conn = self.conn
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
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
        elif line == "quit":
            conn.sendall(b"Bye\r\n")
            return False
        else:
            conn.sendall(f"Huh? {line}\r\n".encode())
        return True


def serve(port=0, ready=None, banner=BANNER):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)
    if ready is not None:
        ready(srv.getsockname()[1])
    while True:
        conn, _ = srv.accept()
        Session(conn, banner).start()


def start_in_thread(banner=BANNER):
    got = {}
    ev = threading.Event()

    def ready(port):
        got["port"] = port
        ev.set()

    threading.Thread(target=serve, args=(0, ready, banner), daemon=True).start()
    ev.wait(5)
    return got["port"]


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 4000, lambda p: print(f"fake telnet on {p}", flush=True))
