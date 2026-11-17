#!/usr/bin/env python3
"""Checks the licences the app's About screen lists (app/src/main/assets/licences, docs/licences.md).

Always, the repository: every component has a licence text for each identifier its SPDX expression names, every
text is used, ids are unique, the libraries map names existing components, the versions listed are the ones the
build scripts pin (scripts/build-mudlet-deps.sh, scripts/build-openssl.sh, Qt and the NDK), and LICENSE is the text
of the licence listed for this app's own code.

--mudlet-source DIR: also that every Lua C module, font and third-party Lua file Mudlet's checkout builds into
libmudlet has a component.

--apk APK --abi ABI: also the APK: that it carries the same licence files, and android.json, which :app writes from
the Gradle runtime dependencies, with a text for each of their licences; and that every native library in lib/ABI is
claimed by a component.

usage: check-licences.py [--mudlet-source DIR] [--apk APK --abi ABI]
"""
import argparse
import fnmatch
import glob
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "licences")
GROUPS = {"app", "native"}
# the component for this repository's own code, whose licence is in LICENSE
APP_COMPONENT = "mudlet-android"
# Mudlet's third-party Lua files (src/mudlet-lua/lua/3rdparty) and the component for each
MUDLET_LUA_FILES = {"Inspect.lua": "inspect-lua"}
# Mudlet's own 3rdparty/ directories an Android build of mudlet_core compiles in
MUDLET_3RDPARTY = {"communi": "communi", "lua51": "lua51", "kdtoolbox": "kdtoolbox"}

errors = []


def error(message):
    errors.append(message)


def licence_ids(expression):
    return set(re.findall(r"[A-Za-z0-9.+-]+", expression)) - {"AND", "OR", "WITH"}


def texts_of(component):
    return licence_ids(component.get("licence", "")) | set(component.get("texts", []))


def load(read):
    """The licence data, through read(name) -> bytes or None, from the repository or an APK"""
    notices = json.loads(read("notices.json"))
    qt = json.loads(read("qt-third-party.json"))
    return notices, qt


def check_data(notices, qt, text_names, where):
    components = {}
    for c in notices["components"] + qt["components"]:
        cid = c.get("id")
        if not cid or cid in components:
            error(f"{where}: component id '{cid}' is missing or not unique")
            continue
        components[cid] = c
        for key in ("name", "licence"):
            if not c.get(key):
                error(f"{where}: {cid} has no {key}")
        missing = sorted(t for t in texts_of(c) if t not in text_names)
        if missing:
            error(f"{where}: {cid} names licences with no texts/<id>.txt: {missing}")
    for c in notices["components"]:
        if c.get("group") not in GROUPS:
            error(f"{where}: {c.get('id')} has the group '{c.get('group')}', not one of {sorted(GROUPS)}")
    for pattern, ids in notices["libraries"].items():
        for cid in ids:
            if cid not in components:
                error(f"{where}: the libraries map sends {pattern} to {cid}, which is no component")
    for name, lid in notices["pomLicences"].items():
        if lid not in text_names:
            error(f"{where}: pomLicences maps '{name}' to {lid}, which has no text")
    return components


def check_repository():
    def read(name):
        with open(os.path.join(ASSETS, name), "rb") as f:
            return f.read()

    notices, qt = load(read)
    text_names = {os.path.basename(p)[:-4] for p in glob.glob(os.path.join(ASSETS, "texts", "*.txt"))}
    components = check_data(notices, qt, text_names, "the repository")
    used = {t for c in components.values() for t in texts_of(c)} | set(notices["pomLicences"].values())
    for unused in sorted(text_names - used):
        error(f"texts/{unused}.txt is no component's licence: remove it")
    # this repository's LICENSE is the text of the licence the list gives this app's own code
    app_licence = components.get(APP_COMPONENT, {}).get("licence")
    if app_licence not in text_names:
        error(f"notices.json has no component '{APP_COMPONENT}' (this app's own code) with a licence that has a text")
    else:
        with open(os.path.join(ROOT, "LICENSE"), "rb") as f, \
                open(os.path.join(ASSETS, "texts", f"{app_licence}.txt"), "rb") as text:
            if f.read() != text.read():
                error(f"LICENSE is not texts/{app_licence}.txt, the licence notices.json gives {APP_COMPONENT}")

    def version_of(cid):
        return components.get(cid, {}).get("version")

    def pinned(path, pattern):
        with open(os.path.join(ROOT, path), encoding="utf-8") as f:
            return re.findall(pattern, f.read(), re.MULTILINE)

    for name, version in pinned("scripts/build-mudlet-deps.sh", r"^([a-z0-9]+)_version=(\S+)"):
        if name not in components:
            error(f"scripts/build-mudlet-deps.sh builds {name}, which has no component in notices.json")
        elif version_of(name) != version:
            error(f"notices.json lists {name} {version_of(name)}, scripts/build-mudlet-deps.sh builds {version}")
    for version in pinned("scripts/build-openssl.sh", r"^openssl_version=(\S+)"):
        if version_of("openssl") != version:
            error(f"notices.json lists OpenSSL {version_of('openssl')}, scripts/build-openssl.sh builds {version}")
    toolchain = ".github/actions/android-toolchain/action.yml"
    qt_version = pinned(toolchain, r'qt-version:\n(?:\s+description:.*\n)?\s+default: "([^"]+)"')
    ndk_version = pinned(toolchain, r'ndk-version:\n(?:\s+description:.*\n)?\s+default: "([^"]+)"')
    if len(qt_version) != 1 or len(ndk_version) != 1:
        error(f"cannot read the Qt and NDK versions from {toolchain}")
    else:
        if version_of("qt") != qt_version[0]:
            error(f"notices.json lists Qt {version_of('qt')}, the build installs {qt_version[0]}")
        if qt["qt"] != qt_version[0]:
            error(f"qt-third-party.json is Qt {qt['qt']}'s, the build installs {qt_version[0]}: run "
                  "scripts/qt-attributions.py (docs/licences.md)")
        if version_of("libcxx") != f"NDK {ndk_version[0]}":
            error(f"notices.json lists libc++ of {version_of('libcxx')}, the build uses NDK {ndk_version[0]}")
    return notices, components


def check_mudlet_source(src, components):
    with open(os.path.join(src, "cmake", "BundledLuaModules.cmake"), encoding="utf-8") as f:
        modules = re.findall(r"^mudlet_bundled_lua_module\((\w+)", f.read(), re.MULTILINE)
    if not modules:
        error(f"no mudlet_bundled_lua_module() in {src}/cmake/BundledLuaModules.cmake: has Mudlet moved them?")
    with open(os.path.join(src, "src", "mudlet_fonts_common.qrc"), encoding="utf-8") as f:
        fonts = sorted(set(re.findall(r"<file>fonts/([^/<]+)/", f.read())))
    if not fonts:
        error(f"no fonts in {src}/src/mudlet_fonts_common.qrc: has Mudlet moved them?")
    wanted = {m: f"the Lua C module {m} (cmake/BundledLuaModules.cmake)" for m in modules}
    wanted.update({d: f"the fonts {d} (src/mudlet_fonts_common.qrc)" for d in fonts})
    for lua in sorted(os.listdir(os.path.join(src, "src", "mudlet-lua", "lua", "3rdparty"))):
        if lua not in MUDLET_LUA_FILES:
            error(f"Mudlet's src/mudlet-lua/lua/3rdparty/{lua} has no component: add one, and to MUDLET_LUA_FILES")
        else:
            wanted[MUDLET_LUA_FILES[lua]] = f"src/mudlet-lua/lua/3rdparty/{lua}"
    for directory, cid in MUDLET_3RDPARTY.items():
        if not os.path.isdir(os.path.join(src, "3rdparty", directory)):
            error(f"Mudlet has no 3rdparty/{directory} any more: drop {cid} from notices.json and MUDLET_3RDPARTY")
        wanted[cid] = f"3rdparty/{directory}"
    for cid, what in sorted(wanted.items()):
        if cid not in components:
            error(f"Mudlet builds {what} into libmudlet, but notices.json has no component '{cid}'")


def check_apk(apk, abi, notices):
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())

        def read(name):
            return z.read(f"assets/licences/{name}")

        for path in sorted(glob.glob(os.path.join(ASSETS, "**", "*"), recursive=True)):
            if os.path.isfile(path):
                rel = os.path.relpath(path, ASSETS).replace(os.sep, "/")
                with open(path, "rb") as f:
                    if f"assets/licences/{rel}" not in names or read(rel) != f.read():
                        error(f"{apk}: assets/licences/{rel} is missing or differs from the repository's")
        if "assets/licences/android.json" not in names:
            error(f"{apk} has no assets/licences/android.json: did :app's generateLicences task run?")
            android = []
        else:
            android = json.loads(read("android.json"))["components"]
        libs = sorted(n.rsplit("/", 1)[1] for n in names if n.startswith(f"lib/{abi}/") and n.endswith(".so"))
    texts = {n[len("assets/licences/texts/"):-4] for n in names if n.startswith("assets/licences/texts/")}
    modules = {c["name"] for c in android}
    # what every build of :app resolves, so an empty or partial list shows
    for module in ("androidx.core:core", "org.jetbrains.kotlin:kotlin-stdlib", "androidx.compose.ui:ui-android"):
        if module not in modules:
            error(f"{apk}: android.json lists no {module}, which :app depends on")
    for c in android:
        missing = sorted(texts_of(c) - texts)
        if missing:
            error(f"{apk}: {c['name']} is under {c['licence']}, whose texts are not in the APK: {missing}")
    if not libs:
        error(f"{apk} has no lib/{abi}/*.so")
    for lib in libs:
        if not any(fnmatch.fnmatchcase(lib, pattern) for pattern in notices["libraries"]):
            error(f"{apk}: lib/{abi}/{lib} is no component's: add it to notices.json's libraries, with a component for "
                  "its licence")
    if not errors:
        print(f"{os.path.basename(apk)}: {len(libs)} native libraries ({', '.join(libs)}) and "
              f"{len(android)} Gradle modules, each with its licence")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--mudlet-source")
    parser.add_argument("--apk")
    parser.add_argument("--abi")
    args = parser.parse_args()
    if args.apk and not args.abi:
        parser.error("--apk needs --abi")
    notices, components = check_repository()
    if args.mudlet_source:
        check_mudlet_source(args.mudlet_source, components)
    if args.apk and not errors:
        check_apk(args.apk, args.abi, notices)
    for e in errors:
        print(f"::error::{e}" if os.environ.get("GITHUB_ACTIONS") else f"error: {e}", file=sys.stderr)
    if errors:
        sys.exit(1)
    print("licences: OK")


if __name__ == "__main__":
    main()
