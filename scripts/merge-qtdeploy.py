#!/usr/bin/env python3
"""Merges engines staged for different ABIs into one directory for :engine-service, for a release of several ABIs.

Each input is a directory scripts/build-apk.sh staged with STAGE_ONLY=1: libs/<abi>/*.so, Qt's jars in libs/, and
res/values/libs.xml, whose arrays list the libraries Qt loads as "<abi>;<name>" items. The output has every input's
libs/<abi>, the jars and any other resources (which must be the same in every input), and one libs.xml whose arrays
hold every input's items, as androiddeployqt writes it for a build of several ABIs; Qt's loader picks the items of
the device's ABI. Anything else in an input is an error: :engine-service would not package it.

usage: merge-qtdeploy.py <out-dir> <staged-dir> [<staged-dir> ...]
"""
import filecmp
import os
import shutil
import sys
import xml.etree.ElementTree as ET


def fail(message):
    sys.exit(f"merge-qtdeploy: {message}")


def abis_of(staged):
    libs = os.path.join(staged, "libs")
    abis = sorted(d for d in os.listdir(libs) if os.path.isdir(os.path.join(libs, d)))
    if not abis:
        fail(f"{staged} has no libs/<abi>: was it staged by scripts/build-apk.sh?")
    return abis


def jars_of(staged):
    libs = os.path.join(staged, "libs")
    files = [f for f in os.listdir(libs) if not os.path.isdir(os.path.join(libs, f))]
    others = sorted(f for f in files if not f.endswith(".jar"))
    if others:
        fail(f"{staged}/libs has {others}, neither an ABI's directory nor a jar")
    return sorted(files)


def files_under(top):
    return sorted(os.path.relpath(os.path.join(d, f), top) for d, _, files in os.walk(top) for f in files)


def main():
    if len(sys.argv) < 3:
        fail(__doc__.strip().splitlines()[-1])
    out, inputs = sys.argv[1], sys.argv[2:]
    for staged in inputs:
        extra = sorted(set(os.listdir(staged)) - {"libs", "res"})
        if extra:
            fail(f"{staged} has {extra}, which :engine-service does not package: what staged it?")
    # Resources other than libs.xml are copied from the first input, and must be the same in the others
    libs_xml = os.path.join("values", "libs.xml")
    resources = files_under(os.path.join(inputs[0], "res"))
    if libs_xml not in resources:
        fail(f"{inputs[0]} has no res/values/libs.xml: was it staged by scripts/build-apk.sh?")
    for staged in inputs[1:]:
        theirs = files_under(os.path.join(staged, "res"))
        if theirs != resources:
            fail(f"{staged} has the resources {theirs}, {inputs[0]} has {resources}")
        for f in resources:
            if f != libs_xml and not filecmp.cmp(os.path.join(inputs[0], "res", f), os.path.join(staged, "res", f),
                                                 shallow=False):
                fail(f"res/{f} differs between {inputs[0]} and {staged}")
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "libs"))

    seen_abis = {}
    first_jars = jars_of(inputs[0])
    if not first_jars:
        fail(f"{inputs[0]} has no Qt jars in libs/")
    for staged in inputs:
        for abi in abis_of(staged):
            if abi in seen_abis:
                fail(f"{abi} is staged in both {seen_abis[abi]} and {staged}")
            seen_abis[abi] = staged
            shutil.copytree(os.path.join(staged, "libs", abi), os.path.join(out, "libs", abi))
        jars = jars_of(staged)
        if jars != first_jars:
            fail(f"{staged} has the jars {jars}, {inputs[0]} has {first_jars}")
        for jar in jars:
            if not filecmp.cmp(os.path.join(inputs[0], "libs", jar), os.path.join(staged, "libs", jar), shallow=False):
                fail(f"{jar} differs between {inputs[0]} and {staged}: were they staged with the same Qt?")
    for jar in first_jars:
        shutil.copy2(os.path.join(inputs[0], "libs", jar), os.path.join(out, "libs", jar))

    # libs.xml: the first input's file, with each array holding the items of every input in turn; its strings
    # (use_local_qt_libs and the like) must agree
    trees = [ET.parse(os.path.join(staged, "res", "values", "libs.xml")) for staged in inputs]
    merged = trees[0]
    root = merged.getroot()
    for staged, tree in zip(inputs[1:], trees[1:]):
        for element in tree.getroot():
            name = element.get("name")
            mine = root.find(f"./{element.tag}[@name='{name}']")
            if element.tag == "array":
                if mine is None:
                    fail(f"{staged}'s libs.xml has the array {name}, {inputs[0]}'s has not")
                for item in element:
                    mine.append(item)
            elif mine is None or (mine.text or "") != (element.text or ""):
                fail(f"libs.xml's {element.tag} {name} differs between {inputs[0]} and {staged}")
    for array in root.iter("array"):
        for item in array:
            abi = (item.text or "").split(";", 1)[0]
            if abi not in seen_abis:
                fail(f"libs.xml's {array.get('name')} lists '{item.text}', whose ABI is not staged")
    shutil.copytree(os.path.join(inputs[0], "res"), os.path.join(out, "res"))
    ET.indent(merged, space="    ")
    merged.write(os.path.join(out, "res", "values", "libs.xml"), encoding="utf-8", xml_declaration=True)
    print(f"merged {', '.join(sorted(seen_abis))} into {out}")


if __name__ == "__main__":
    main()
