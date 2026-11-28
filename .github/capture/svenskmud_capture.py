#!/usr/bin/env python3
"""Capture what svenskmud.com sends on connect, negotiating telnet as Mudlet does. Never logs in."""
import json
import re
import socket
import sys
import time
import urllib.request
import zlib

IAC, DONT, DO, WONT, WILL, SB, SE, GA, EOR_CMD = 255, 254, 253, 252, 251, 250, 240, 249, 239
ECHO, SGA, TTYPE, EOR, NAWS, CHARSET, MSDP, MSSP, MCCP2, MSP, MXP, GMCP = 1, 3, 24, 25, 31, 42, 69, 70, 86, 90, 91, 201
NAMES = {1: "ECHO", 3: "SGA", 24: "TTYPE", 25: "EOR", 31: "NAWS", 42: "CHARSET", 69: "MSDP", 70: "MSSP", 86: "MCCP2",
         90: "MSP", 91: "MXP", 201: "GMCP", 39: "NEW-ENVIRON", 34: "LINEMODE", 24: "TTYPE", 102: "102"}
# Mudlet's defaults: what it answers WILL x with DO, and DO x with WILL
ACCEPT_WILL = {ECHO, SGA, EOR, GMCP, MSSP, MCCP2, MSP, MXP}
ACCEPT_DO = {TTYPE, NAWS, CHARSET}
HOST = sys.argv[1]
STOP_WORDS = ("l\u00f6senord", "password", "(j/n)", "[j/n]", "\u00e4r du s\u00e4ker", "ny karakt\u00e4r", "nytt namn", "skapa")
OUT = sys.argv[2]


def ports_from_website():
    found = []
    for url in (f"https://{HOST}/", f"http://{HOST}/", f"https://www.{HOST}/"):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "telnet-capture"}), timeout=20) as r:
                page = r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            print(f"website {url}: {e}")
            continue
        print(f"website {url}: {len(page)} bytes")
        text = re.sub(r"<[^>]+>", " ", page)
        for m in re.finditer(r"(?i).{0,120}(telnet|port|anslut|klient|g\u00e4st|gast|guest|bes\u00f6k|prova|ny spelare|nya spelare).{0,120}", text):
            print("  website says:", " ".join(m.group(0).split()))
        for m in re.finditer(r"(?i)(?:svenskmud\.(?:com|se)[\s:]+|port\s*:?\s*)(\d{2,5})", page):
            found.append(int(m.group(1)))
        for m in re.finditer(r"telnet://[\w.]+:(\d{2,5})", page):
            found.append(int(m.group(1)))
        break
    return found


def name(o):
    return NAMES.get(o, str(o))


def capture(port, width, height, seconds, log, steps=()):
    s = socket.create_connection((HOST, port), timeout=10)
    s.settimeout(0.5)
    raw = bytearray()  # every byte after decompression, telnet included
    marked = bytearray()  # the text, with each telnet command as a <marker>
    events = []
    decomp = None
    buf = bytearray()
    ttype_cycle = 0
    end = time.time() + seconds

    def send(b, what):
        s.sendall(bytes(b))
        events.append({"t": round(time.time() - start, 3), "sent": what, "hex": bytes(b).hex()})

    start = time.time()
    steps = list(steps)
    last_step = start
    while time.time() < end:
        if steps:
            want, say = steps[0]
            low = bytes(marked).decode("utf-8", "replace").lower()
            if any(w in low for w in STOP_WORDS):
                events.append({"t": round(time.time() - start, 3), "note": "asked for a password or a confirmation: stopping"})
                break
            due = (time.time() - last_step >= want) if isinstance(want, float) else want.lower() in low[-400:]
            if due:
                last_step = time.time()
                steps.pop(0)
                marked += f"<SENT {say!r}>".encode()
                send(list((say + "\r\n").encode()), "text " + say)
        try:
            chunk = s.recv(65536)
        except socket.timeout:
            chunk = None
        except OSError as e:
            events.append({"t": round(time.time() - start, 3), "closed": str(e)})
            break
        if chunk == b"":
            events.append({"t": round(time.time() - start, 3), "closed": True})
            break
        if not chunk:
            continue
        log.write(f"# recv {len(chunk)} bytes at {time.time() - start:.3f}s (compressed={decomp is not None})\n")
        if decomp is not None:
            chunk = decomp.decompress(chunk)
        buf += chunk
        # handle telnet in buf
        i = 0
        out = bytearray()
        while i < len(buf):
            b = buf[i]
            if b != IAC:
                out.append(b)
                raw.append(b)
                i += 1
                continue
            if i + 1 >= len(buf):
                break
            c = buf[i + 1]
            if c == IAC:
                out.append(IAC)
                raw += buf[i:i + 2]
                i += 2
                continue
            if c in (WILL, WONT, DO, DONT):
                if i + 2 >= len(buf):
                    break
                o = buf[i + 2]
                cmd = {WILL: "WILL", WONT: "WONT", DO: "DO", DONT: "DONT"}[c]
                events.append({"t": round(time.time() - start, 3), "got": f"{cmd} {name(o)}"})
                raw += buf[i:i + 3]
                out += f"<IAC {cmd} {name(o)}>".encode()
                i += 3
                if c == WILL:
                    if o in ACCEPT_WILL:
                        send([IAC, DO, o], f"DO {name(o)}")
                        if o == GMCP:
                            for msg in ('Core.Hello {"client":"Mudlet","version":"4.20.1"}',
                                        'Core.Supports.Set ["Char 1","Char.Skills 1","Char.Items 1","Room 1","IRE.Rift 1","IRE.Composer 1","External.Discord 1","Client.Media 1"]'):
                                send([IAC, SB, GMCP] + list(msg.encode()) + [IAC, SE], "GMCP " + msg.split()[0])
                    else:
                        send([IAC, DONT, o], f"DONT {name(o)}")
                elif c == DO:
                    if o in ACCEPT_DO:
                        send([IAC, WILL, o], f"WILL {name(o)}")
                        if o == NAWS:
                            send([IAC, SB, NAWS, width >> 8, width & 255, height >> 8, height & 255, IAC, SE], f"NAWS {width}x{height}")
                    else:
                        send([IAC, WONT, o], f"WONT {name(o)}")
                continue
            if c == SB:
                j = buf.find(bytes([IAC, SE]), i)
                if j < 0:
                    break
                body = bytes(buf[i + 2:j])
                raw += buf[i:j + 2]
                o = body[0] if body else -1
                events.append({"t": round(time.time() - start, 3), "got": f"SB {name(o)}", "body": body[1:].decode("utf-8", "replace")[:300]})
                out += f"<IAC SB {name(o)} {len(body) - 1}b>".encode()
                i = j + 2
                if o == TTYPE and body[1:2] == b"\x01":
                    term = ["MUDLET", "ANSI-TRUECOLOR", "MTTS 2349", "MTTS 2349"][min(ttype_cycle, 3)]
                    ttype_cycle = (ttype_cycle + 1) % 4
                    send([IAC, SB, TTYPE, 0] + list(term.encode()) + [IAC, SE], "TTYPE IS " + term)
                elif o == CHARSET and body[1:2] == b"\x01":
                    offered = body[3:].decode("ascii", "replace").split(body[2:3].decode("ascii", "replace") or " ")
                    pick = next((x for x in offered if x.upper() in ("UTF-8", "UTF8")), None)
                    if pick:
                        send([IAC, SB, CHARSET, 2] + list(pick.encode()) + [IAC, SE], "CHARSET ACCEPTED " + pick)
                    else:
                        send([IAC, SB, CHARSET, 3, IAC, SE], "CHARSET REJECTED")
                elif o == MCCP2:
                    rest = bytes(buf[i:])
                    decomp = zlib.decompressobj()
                    buf = bytearray(decomp.decompress(rest))
                    i = 0
                    events.append({"t": round(time.time() - start, 3), "note": "MCCP2 on"})
                continue
            # GA, EOR, NOP and others
            label = {GA: "GA", EOR_CMD: "EOR", 241: "NOP"}.get(c, str(c))
            raw += buf[i:i + 2]
            out += f"<IAC {label}>".encode()
            events.append({"t": round(time.time() - start, 3), "got": label})
            i += 2
        buf = buf[i:]
        marked += out
        log.write(bytes(out).decode("latin-1"))
    s.close()
    return raw, marked, events


def main():
    ports = ports_from_website()
    print("ports from website:", ports)
    # The website's port first; then the usual MUD ports, tried only if it names none that answers
    candidates = list(dict.fromkeys(ports + [23, 4000, 2043, 4242, 3000, 2000, 5000, 6666, 7000, 1234, 6000, 8000]))
    port = None
    for p in candidates:
        try:
            socket.create_connection((HOST, p), timeout=5).close()
            port = p
            print(f"port {p}: open")
            break
        except OSError as e:
            print(f"port {p}: {e}")
    if port is None:
        sys.exit("no port answered")
    summary = {"host": HOST, "port": port, "runs": []}
    for width in (85,):
        with open(f"{OUT}/text-{width}.txt", "w", encoding="latin-1") as log:
            raw, marked, events = capture(port, width, 40, float(__import__("os").environ.get("CAPTURE_SECONDS", "12")), log)
        with open(f"{OUT}/raw-{width}.bin", "wb") as f:
            f.write(raw)
        summary["runs"].append({"width": width, "bytes": len(raw), "events": events})
        print(f"\n===== NAWS width {width}: {len(raw)} bytes =====")
        for e in events:
            print("  ", json.dumps(e, ensure_ascii=False))
        print("--- lines (repr of each line of the text, telnet as <markers>) ---")
        for n, line in enumerate(bytes(marked).split(b"\n")):
            print(f"{n:3d} {len(line):3d} {line!r}")
        print("--- hex ---")
        data = bytes(raw)
        for off in range(0, min(len(data), 6000), 32):
            row = data[off:off + 32]
            print(f"{off:05x} {row.hex(' ')}  {''.join(chr(c) if 32 <= c < 127 else '.' for c in row)}")
        time.sleep(float(__import__("os").environ.get("CAPTURE_GAP", "2")))
    for guest in ("g\u00e4st", "guest"):
        with open(f"{OUT}/text-{guest}.txt", "w", encoding="latin-1") as log:
            raw, marked, events = capture(port, 85, 40, 25, log, [("heter du", guest), (5.0, "titta"), (5.0, "sluta")])
        with open(f"{OUT}/raw-{guest}.bin", "wb") as f:
            f.write(raw)
        print(f"\n===== name {guest!r}: {len(raw)} bytes =====")
        for e in events:
            print("  ", json.dumps(e, ensure_ascii=False))
        for n, line in enumerate(bytes(marked).split(b"\n")):
            print(f"{n:3d} {len(line):3d} {line!r}")
        data = bytes(raw)
        for off in range(0, min(len(data), 8000), 32):
            row = data[off:off + 32]
            print(f"{off:05x} {row.hex(' ')}  {''.join(chr(c) if 32 <= c < 127 else '.' for c in row)}")
    with open(f"{OUT}/summary.json", "w") as f:
        json.dump(summary, f, indent=1, ensure_ascii=False)


main()
