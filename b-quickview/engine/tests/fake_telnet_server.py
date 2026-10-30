#!/usr/bin/env python3
"""Fake MUD for the engine self-test. Prints its port, serves one client, then prints a JSON report."""
import json
import socket
import sys
import time

IAC, DONT, DO, WONT, WILL, SB, GA, SE = 255, 254, 253, 252, 251, 250, 249, 240
ESC = b"\x1b"


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    srv.settimeout(20)
    print(srv.getsockname()[1], flush=True)
    conn, _ = srv.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    received = bytearray()

    def send(data, pause=0.05):
        conn.sendall(data)
        time.sleep(pause)

    def recv_until(marker, timeout=5.0):
        conn.settimeout(timeout)
        end = time.time() + timeout
        while marker not in received and time.time() < end:
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            received.extend(chunk)
        return marker in received

    send(bytes([IAC, DO, 24, IAC, WILL, 1, IAC, WILL, 201]) + bytes([IAC, SB, 24, 1, IAC, SE]))
    send(b"Welcome to " + ESC + b"[1;")
    send(b"31mFake" + ESC + b"[0m MUD\r\n")
    send(ESC + b"[32mgreen line\r\n")
    send(b"still green" + ESC + b"[0m\r\n")
    send(b"Bob tells you 'hello'\r\n")
    send(b"before" + bytes([IAC]))
    send(bytes([WILL, 3]) + b"after\r\n")
    send(b"HP:100> " + bytes([IAC, GA]))
    got_look = recv_until(b"look\r\n")
    send(b"You look around.\r\n")
    send(b"Silent> ", pause=1.2)
    send(ESC + b"[93mbright yellow" + ESC + b"[39m default\r\n")
    send(b"256: " + ESC + b"[38;5;9midx9" + ESC + b"[38;2;1;2;3m then truecolor" + ESC + b"[0m\r\n")
    recv_until(b"\x00never", timeout=0.5)
    conn.close()

    def has(seq):
        return bytes(seq) in received

    report = {
        "got_look": got_look,
        "wont_ttype": has([IAC, WONT, 24]),
        "dont_echo": has([IAC, DONT, 1]),
        "dont_gmcp": has([IAC, DONT, 201]),
        "dont_split_option_3": has([IAC, DONT, 3]),
        "received_hex": received.hex(),
    }
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    sys.exit(main())
