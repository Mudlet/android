#!/usr/bin/env python3
"""ctest driver: run_test.py <engine_selftest binary> <fake_telnet_server.py>"""
import json
import subprocess
import sys


def main():
    selftest, server_script = sys.argv[1], sys.argv[2]
    server = subprocess.Popen([sys.executable, "-I", server_script], stdout=subprocess.PIPE, text=True)
    port = server.stdout.readline().strip()
    client = subprocess.run([selftest, port], timeout=30)
    report = json.loads(server.stdout.readline())
    server.wait(timeout=10)
    failures = 0 if client.returncode == 0 else 1
    for key in ("got_look", "wont_ttype", "dont_echo", "dont_gmcp", "dont_split_option_3"):
        ok = bool(report[key])
        print(("PASS " if ok else "FAIL ") + "server saw " + key)
        failures += 0 if ok else 1
    print("client sent:", report["received_hex"])
    print("FAKE SERVER TEST " + ("OK" if failures == 0 else "FAILED"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
