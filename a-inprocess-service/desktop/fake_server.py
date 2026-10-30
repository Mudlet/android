#!/usr/bin/env python3
"""Fake telnet MUD for the engine's host-side test. Prints its port, serves one client, exits."""
import socket
import sys
import time

IAC, DONT, DO, WONT, WILL, SB, SE, GA = 255, 254, 253, 252, 251, 250, 240, 249
ECHO, TTYPE = 1, 24


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    print(srv.getsockname()[1], flush=True)
    srv.settimeout(15)
    conn, _ = srv.accept()
    conn.settimeout(10)

    def send(data, pause=0.05):
        conn.sendall(data)
        time.sleep(pause)

    send(bytes([IAC, WILL, ECHO, IAC, DO, TTYPE, IAC, SB, TTYPE, 1, IAC, SE]))
    send(b"Welcome to \x1b[1;31mFake\x1b[0mMUD\r\n")
    # A line split across two TCP segments, with an IAC sequence in the middle
    send(b"Bob tel")
    send(bytes([IAC, WONT, 3]) + b"ls you 'hello'\r\n")
    send(b"\x1b[32mgreen line\n")
    send(b"still green\x1b[0m\r\n")
    send(b"HP:100> " + bytes([IAC, GA]))
    send(b"Silent prompt> ", pause=0)

    received = b""
    deadline = time.time() + 8
    while b"look\r\n" not in received and time.time() < deadline:
        chunk = conn.recv(4096)
        if not chunk:
            break
        received += chunk

    expected = [bytes([IAC, DONT, ECHO]), bytes([IAC, WONT, TTYPE])]
    if all(e in received for e in expected) and b"look\r\n" in received:
        send(b"NEGOTIATION OK\r\n")
    else:
        send(b"NEGOTIATION BAD " + received.hex().encode() + b"\r\n")
    send("\x1b[38;5;200mxterm \x1b[90mgrey\x1b[39m café\r\n".encode("utf-8"))
    send(b"BYE\r\n", pause=0.3)
    conn.close()
    srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
