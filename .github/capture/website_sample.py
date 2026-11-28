#!/usr/bin/env python3
"""Print svenskmud.com's samples of game output exactly, and list its public FTP directory."""
import html
import re
import urllib.request

page = urllib.request.urlopen("http://svenskmud.com/", timeout=20).read()
print("page bytes", len(page))
for n, m in enumerate(re.finditer(rb"(?is)<(pre|code|textarea|samp)[^>]*>(.*?)</\1>", page)):
    print(f"--- block {n} <{m.group(1).decode()}> ---")
    for line in html.unescape(re.sub(rb"<[^>]+>", b"", m.group(2)).decode("utf-8", "replace")).split("\n"):
        print(f"{len(line):3d} {line!r}")
# any element carrying white-space: pre
for m in re.finditer(rb"(?is).{0,200}white-space\s*:\s*pre.{0,200}", page):
    print("white-space:pre near:", m.group(0)[:400])
print("--- links ---")
for m in re.finditer(rb'(?i)href="([^"]+)"', page):
    print(m.group(1).decode("utf-8", "replace"))
for url in ("https://ftp.se.freebsd.org/pub/svenskmud/", "http://ftp.se.freebsd.org/pub/svenskmud/"):
    try:
        listing = urllib.request.urlopen(url, timeout=20).read().decode("utf-8", "replace")
        print("--- ftp listing", url)
        print("\n".join(re.findall(r'href="([^"?][^"]*)"', listing)))
        break
    except Exception as e:  # noqa: BLE001
        print(url, e)
