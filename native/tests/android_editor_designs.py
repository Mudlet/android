#!/usr/bin/env python3
"""Shows the three alternative script editors (scripts/build-editor-designs.sh) working on a running emulator or
device. For each design, under its own application id:
  1. the smoke test (android_smoke.py) of its APK, with the real engine: the app starts, a profile opens;
  2. its walk-through (EditorDesignWalkThroughTest), against the tests' fake engine: the editor opens from the game,
     an item opens, an edit is saved. The pictures it takes of each screen, light and dark, go to
     <out>/<letter>/screenshots.
A design fails the run if either fails, or if the walk left no pictures of both themes. The pictures need API 31.

usage: android_editor_designs.py --apks <dir of build-editor-designs.sh --tests> --out <dir> [--symbols <dir>]
                                 [--designs abc]
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_instrumented as instrumented  # noqa: E402
from android_smoke import Device, SmokeFailure, adb, install, shell  # noqa: E402

WALK = "org.mudlet.android.ui.EditorDesignWalkThroughTest"
# A phone 360 dp wide, the narrowest the layouts are made for, whatever the emulator's own density is
WIDTH_DP = 360


def narrow_phone():
    size = re.search(r"Physical size: (\d+)x(\d+)", shell("wm size"))
    if not size:
        return
    density = 160 * min(int(size.group(1)), int(size.group(2))) // WIDTH_DP
    shell(f"wm density {density}")
    print(f"the screen: {size.group(1)}x{size.group(2)} px at {density} dpi, {WIDTH_DP} dp wide", flush=True)


def walk(design, package, app, tests, out, symbols):
    """The design's walk-through, installed anew; returns the failures, as text."""
    instrumented.APP = package
    instrumented.LOG = os.path.join(out, "instrumented.log")
    open(instrumented.LOG, "w").close()
    dev = Device(out, os.path.abspath(app), symbols)
    dev.logcat_path = os.path.join(out, "logcat-instrumented.txt")
    for installed in (package + ".test", package):
        adb("uninstall", installed, check=False)
    # Compose tests need a screen
    for command in ("input keyevent KEYCODE_WAKEUP", "svc power stayon true", "wm dismiss-keyguard",
                    "settings put system screen_off_timeout 1800000"):
        shell(command, check=False)
    dev.start_logcat()
    try:
        narrow_phone()
        install(app)
        install(tests)
        listed = re.search(rf"instrumentation:(\S+)/\S+ \(target={re.escape(package)}\)", shell("pm list instrumentation"))
        if not listed:
            raise SmokeFailure(f"{os.path.basename(tests)} does not instrument {package}")
        failures = instrumented.instrument(dev, "walk-through", listed.group(1), package,
                                           f"design {design.upper()}: the walk through its editor", 720, "-e", "class", WALK)
        instrumented.pull_screenshots(dev)
        shots = os.path.join(out, "screenshots")
        names = os.listdir(shots) if os.path.isdir(shots) else []
        for theme in ("light", "dark"):
            if not any(name.startswith(f"design-{design}-{theme}-") for name in names):
                failures.append(f"design {design.upper()}: the walk left no picture of the {theme} theme")
    except (SmokeFailure, subprocess.TimeoutExpired) as e:
        failures = [str(e)]
    finally:
        shell("wm density reset", check=False)
        dev.stop_logcat()
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apks", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--symbols", help="directory with the unstripped engine library, for ndk-stack")
    parser.add_argument("--designs", default="abc")
    args = parser.parse_args()
    failed = []
    for design in args.designs:
        package = f"org.mudlet.mudlet.design{design}"
        out = os.path.join(args.out, design)
        os.makedirs(out, exist_ok=True)
        app = os.path.join(args.apks, design, f"mudlet-editor-design-{design}-debug.apk")
        tests = os.path.join(args.apks, design, f"mudlet-editor-design-{design}-tests.apk")
        print(f"=== design {design.upper()}: {package}", flush=True)
        smoke = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_smoke.py"),
                 "--apk", app, "--out", out, "--package", package]
        if args.symbols:
            smoke += ["--symbols", args.symbols]
        failures = []
        if subprocess.run(smoke).returncode != 0:
            failures.append(f"design {design.upper()}: the smoke test failed ({out}/smoke.log)")
        failures += walk(design, package, app, tests, out, args.symbols)
        # Three of them do not fit the emulator's disk at once
        for installed in (package + ".test", package):
            adb("uninstall", installed, check=False)
        if failures:
            instrumented.log("\n" + "\n\n".join(f"FAIL {f}" for f in failures))
            if os.environ.get("GITHUB_ACTIONS"):
                text = "\n\n".join(failures)[:4000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
                print(f"::error title=Editor design {design.upper()}::{text}", flush=True)
            failed.append(design.upper())
        else:
            instrumented.log(f"\ndesign {design.upper()}: all passed")
    if failed:
        sys.exit(f"failed: design {', '.join(failed)}")
    print("all three designs passed")


if __name__ == "__main__":
    main()
