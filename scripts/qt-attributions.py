#!/usr/bin/env python3
"""Writes the third-party code inside the Qt libraries the APK ships, for the app's licences screen.

Reads every qt_attribution.json in a source checkout of each Qt module given, at the Qt version the build pins, and
writes app/src/main/assets/licences/qt-third-party.json: one entry for each attribution the Android libraries can
carry. Copies each licence the entries name from the module's
LICENSES/ into app/src/main/assets/licences/texts/ unless a text of that name is there already. Run it again when Qt is
bumped; scripts/check-licences.py fails while the file names another Qt version. See docs/licences.md.

usage: qt-attributions.py <qt-version> <module-checkout>...
  e.g. qt-attributions.py 6.12.0 ../qtbase ../qtsvg ../qtmultimedia
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "licences")

# Attributions of what no Android library of Qt holds: other platforms, the build, tests and examples
SKIPPED_PATHS = re.compile(r"(^|/)(cmake|tests?|testlib|testinternal|examples|wayland|xcb|wintab|wasm|dbus|"
                           r"D3D12MemoryAllocator|gradle|plugins/platforms/cocoa)(/|$)")
SKIPPED_IDS = {"qeventdispatcher_cf", "rhi-miniengine-d3d12-mipmap"}
TEMPLATE_COPYRIGHT = re.compile(r"^[ \t]*Copyright[^\n]*<(year|owner|copyright holders)>[^\n]*\n", re.MULTILINE)


def fail(message):
    sys.exit(f"qt-attributions: {message}")


def licence_ids(expression):
    return sorted(set(re.findall(r"[A-Za-z0-9.+-]+", expression)) - {"AND", "OR", "WITH"})


def entries_of(checkout, module):
    found = []
    for path in sorted(glob.glob(os.path.join(checkout, "**", "qt_attribution.json"), recursive=True)):
        rel = os.path.relpath(os.path.dirname(path), checkout).replace(os.sep, "/")
        if SKIPPED_PATHS.search(rel):
            continue
        with open(path, encoding="utf-8") as f:
            # some carry raw newlines inside strings
            data = json.loads(f.read(), strict=False)
        for e in data if isinstance(data, list) else [data]:
            if e.get("Id") in SKIPPED_IDS or set(e.get("QtParts", ["libs"])) <= {"tools", "tests", "examples"}:
                continue
            for key in ("Id", "Name", "LicenseId"):
                if not e.get(key):
                    fail(f"{path}: an entry has no {key}")
            copyright_ = e.get("Copyright", "")
            if isinstance(copyright_, list):
                copyright_ = "\n".join(copyright_)
            found.append({
                "id": f"qt-{module}-{e['Id']}",
                "name": e["Name"],
                "version": e.get("Version", ""),
                "licence": e["LicenseId"],
                "copyright": copyright_.strip(),
                "url": e.get("Homepage", ""),
                "source": e.get("DownloadLocation", ""),
            })
    return found


def main():
    if len(sys.argv) < 3:
        fail(__doc__.strip().splitlines()[-2].strip())
    qt_version, args = sys.argv[1], sys.argv[2:]
    entries = []
    licence_dirs = []
    for checkout in args:
        module = os.path.basename(os.path.normpath(checkout))
        found = entries_of(checkout, module)
        if not found and module == "qtbase":
            fail(f"no qt_attribution.json in {checkout}")
        entries += found
        licence_dirs.append(os.path.join(checkout, "LICENSES"))
    texts = os.path.join(ASSETS, "texts")
    for lid in sorted({i for e in entries for i in licence_ids(e["licence"])}):
        target = os.path.join(texts, f"{lid}.txt")
        if os.path.exists(target):
            continue
        source = next((os.path.join(d, f"{lid}.txt") for d in licence_dirs if os.path.exists(os.path.join(d, f"{lid}.txt"))),
                      None)
        if source is None:
            fail(f"no LICENSES/{lid}.txt in any module given: add texts/{lid}.txt from https://spdx.org/licenses/")
        with open(source, encoding="utf-8") as f:
            text = f.read()
        # A template's "Copyright (c) <year> <owner>": each entry shows its own copyright above the text
        with open(target, "w", encoding="utf-8") as f:
            f.write(TEMPLATE_COPYRIGHT.sub("", text).lstrip("\n"))
        print(f"added texts/{lid}.txt")
    with open(os.path.join(ASSETS, "qt-third-party.json"), "w", encoding="utf-8") as f:
        json.dump({"qt": qt_version, "components": entries}, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(entries)} attributions of Qt {qt_version}")


if __name__ == "__main__":
    main()
