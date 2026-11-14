#!/usr/bin/env python3
"""Writes <build-dir>/libmudlet.cmake, an imported target for an app that links libmudlet from a Mudlet build tree.

Mudlet installs and exports nothing for an app to link against: mudlet_headless is a static library whose
dependencies (mudlet_core, Lua, communi, the bundled Lua C modules, QtKeychain, PCRE2, ...) are only spelled out at
the link of the mudlet-headless runner. This reads that link line from CMake's file API (the codemodel reply of the
configure) and writes it as the INTERFACE IMPORTED target libmudlet::headless:

  include(<build-dir>/libmudlet.cmake)
  target_link_libraries(app PRIVATE libmudlet::headless)

The target's include directories are only those THeadlessSession.h needs; it carries none of Mudlet's compile
definitions, so an app sees nothing of Mudlet but THeadlessSession's public API. Qt libraries on the link line become
Qt6:: targets, found by the file with find_package(), so Qt's own deployment (androiddeployqt) sees them.

usage: export-libmudlet.py --query <build-dir>   before the configure: asks the file API for the codemodel
       export-libmudlet.py <build-dir>           after the configure (and the build): writes libmudlet.cmake
"""
import json
import os
import re
import sys

CLIENT = "client-mudlet-android"
RUNNER = "mudlet_headless_executable"
QT_LIBRARY = re.compile(r"(?:^|/)libQt6([A-Za-z0-9]+?)(?:_[a-z0-9_-]+)?\.so(?:\.[0-9.]+)?$")


def fail(message):
    sys.exit(f"export-libmudlet.py: {message}")


def query(build):
    path = os.path.join(build, ".cmake", "api", "v1", "query", CLIENT)
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, "query.json"), "w") as f:
        json.dump({"requests": [{"kind": "codemodel", "version": 2}]}, f)


def load(reply, name):
    with open(os.path.join(reply, name)) as f:
        return json.load(f)


def runner_target(build):
    reply = os.path.join(build, ".cmake", "api", "v1", "reply")
    indexes = sorted(n for n in os.listdir(reply) if n.startswith("index-")) if os.path.isdir(reply) else []
    if not indexes:
        fail(f"no file API reply in {build}: run with --query before Mudlet's configure")
    answer = load(reply, indexes[-1])["reply"].get(CLIENT, {}).get("query.json", {}).get("responses", [])
    codemodels = [r for r in answer if r.get("kind") == "codemodel"]
    if not codemodels:
        fail(f"the file API reply in {build} has no codemodel for {CLIENT}: run with --query, then configure again")
    codemodel = load(reply, codemodels[0]["jsonFile"])
    for target in codemodel["configurations"][0]["targets"]:
        if target["name"] == RUNNER:
            return codemodel["paths"], load(reply, target["jsonFile"])
    fail(f"Mudlet's build has no target {RUNNER}: was it configured with USE_HEADLESS off?")


def cmake_quote(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def export(build):
    paths, target = runner_target(build)
    source = os.path.join(build, paths["source"])
    libraries = []
    qt_modules = []
    for fragment in target.get("link", {}).get("commandFragments", []):
        if fragment.get("role") != "libraries":
            continue
        # The runner's own objects (main.cpp, LeakSanitizer hooks) are sources, not libraries, so are not here; its
        # run path is its own, and the app's build gives the app one
        item = fragment["fragment"].strip()
        if not item or item.startswith("-Wl,-rpath"):
            continue
        # A toolchain's standard libraries come as one fragment, "-latomic -lm"
        if item.startswith("-") and " " in item:
            libraries.extend(item.split())
            continue
        qt = QT_LIBRARY.search(item)
        if qt and not item.startswith("-"):
            module = qt.group(1)
            if module not in qt_modules:
                qt_modules.append(module)
            item = f"Qt6::{module}"
        elif not item.startswith("-") and not os.path.isabs(item):
            # Relative paths on a link line are relative to the top of the build tree, where the link runs
            item = os.path.normpath(os.path.join(build, item))
        libraries.append(item)
    if not any(i.endswith("libmudlet_headless.a") for i in libraries):
        fail(f"{RUNNER}'s link line has no libmudlet_headless.a; has Mudlet changed how the runner links?")
    includes = [os.path.join(source, "src", "headless"), os.path.join(source, "src")]
    if not os.path.isfile(os.path.join(includes[0], "THeadlessSession.h")):
        fail(f"no THeadlessSession.h in {includes[0]}")
    out = os.path.join(build, "libmudlet.cmake")
    with open(out, "w") as f:
        f.write("# Written by export-libmudlet.py from the link line of Mudlet's mudlet-headless runner; do not edit\n")
        f.write(f"find_package(Qt6 REQUIRED COMPONENTS {' '.join(qt_modules)})\n")
        f.write("if(NOT TARGET libmudlet::headless)\n")
        f.write("  add_library(libmudlet::headless INTERFACE IMPORTED)\n")
        f.write("  set_target_properties(libmudlet::headless PROPERTIES\n")
        f.write(f"    INTERFACE_INCLUDE_DIRECTORIES {cmake_quote(';'.join(includes))}\n")
        f.write(f"    INTERFACE_LINK_LIBRARIES {cmake_quote(';'.join(libraries))})\n")
        f.write("endif()\n")
    print(f"wrote {out}: {len(libraries)} link items, Qt modules {' '.join(qt_modules)}")


def main():
    args = sys.argv[1:]
    if len(args) == 2 and args[0] == "--query":
        query(os.path.abspath(args[1]))
    elif len(args) == 1:
        export(os.path.abspath(args[0]))
    else:
        fail("usage: export-libmudlet.py [--query] <build-dir>")


if __name__ == "__main__":
    main()
