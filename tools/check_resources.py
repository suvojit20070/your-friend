#!/usr/bin/env python3
"""
Static resource checks for Your Friend (runs in CI before Gradle, and locally without the SDK).

  * every R.<type>.<name> used in Java and every @<type>/<name> used in XML/manifest exists
  * translations only contain keys that exist in values/strings.xml and keep the same
    format specifiers (%1$s, %2$d, …) — a mismatch would crash at runtime
  * android:* attributes used in layouts/xml exist in the platform (when android.jar is found)
  * all XML files are well formed

Options:
  --emit-r DIR   also write a compile-only R.java (package com.suvo.agent) into DIR; used to
                 type-check the app with plain javac when the Android Gradle Plugin is unavailable.
  --android-jar  path to android.jar (default: $ANDROID_HOME/platforms/android-35/android.jar)

Your Friend — Original project by Suvo.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
JAVA = os.path.join(APP, "java")
PACKAGE = "com.suvo.agent"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def parse(path):
    try:
        return ET.parse(path)
    except ET.ParseError as e:
        err(f"{os.path.relpath(path, ROOT)}: malformed XML: {e}")
        return None


def collect_defined():
    """Returns {type: set(names)} for app resources."""
    d = {t: set() for t in ("string", "plurals", "color", "dimen", "style", "drawable", "mipmap",
                            "layout", "xml", "id", "bool", "integer", "array", "anim", "raw", "font", "menu")}
    for folder in sorted(os.listdir(RES)):
        full = os.path.join(RES, folder)
        if not os.path.isdir(full):
            continue
        base = folder.split("-")[0]
        for f in sorted(os.listdir(full)):
            path = os.path.join(full, f)
            name = f.split(".")[0]
            if base == "values":
                tree = parse(path)
                if tree is None:
                    continue
                for el in tree.getroot():
                    n = el.get("name")
                    if not n:
                        continue
                    tag = el.tag
                    if tag == "item" and el.get("type"):
                        tag = el.get("type")
                    if tag in ("string-array", "integer-array"):
                        tag = "array"
                    if tag == "style":
                        n = n.replace(".", "_")
                    if tag in d:
                        d[tag].add(n)
            elif base in d:
                d[base].add(name)
                if f.endswith(".xml"):
                    tree = parse(path)
                    if tree is None:
                        continue
                    for el in tree.iter():
                        for v in el.attrib.values():
                            for m in re.finditer(r"@\+id/([A-Za-z0-9_]+)", v):
                                d["id"].add(m.group(1))
    return d


def check_xml_refs(defined):
    files = [os.path.join(APP, "AndroidManifest.xml")]
    for folder in os.listdir(RES):
        full = os.path.join(RES, folder)
        if os.path.isdir(full):
            files += [os.path.join(full, f) for f in os.listdir(full) if f.endswith(".xml")]
    for path in files:
        tree = parse(path)
        if tree is None:
            continue
        rel = os.path.relpath(path, ROOT)
        for el in tree.iter():
            values = list(el.attrib.values())
            if el.text:
                values.append(el.text.strip())
            for v in values:
                for m in re.finditer(r"@(?!android:)([a-z]+)/([A-Za-z0-9_.]+)", v):
                    t, n = m.group(1), m.group(2)
                    if t == "+id":
                        continue
                    key = n.replace(".", "_") if t == "style" else n
                    if t in defined and key not in defined[t]:
                        err(f"{rel}: missing resource @{t}/{n}")
                for m in re.finditer(r"\?attr/([A-Za-z0-9_]+)", v):
                    warnings.append(f"{rel}: app attr ?attr/{m.group(1)} (no attrs.xml in this project)")
            parent = el.get("parent")
            if el.tag == "style" and parent and not parent.startswith("android:") and not parent.startswith("@android:"):
                p = parent.replace("@style/", "").replace(".", "_")
                if p not in defined["style"]:
                    err(f"{rel}: style {el.get('name')} has unknown parent {parent}")


def check_java_refs(defined):
    pat = re.compile(r"\bR\.([a-z]+)\.([A-Za-z0-9_]+)")
    for dirpath, _, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(dirpath, f)
            src = open(path, encoding="utf-8").read()
            src = re.sub(r"android\.R\.[a-z]+\.[A-Za-z0-9_]+", "", src)
            for m in pat.finditer(src):
                t, n = m.group(1), m.group(2)
                if t not in defined:
                    err(f"{os.path.relpath(path, ROOT)}: unknown resource type R.{t}")
                elif n not in defined[t]:
                    err(f"{os.path.relpath(path, ROOT)}: missing resource R.{t}.{n}")


FMT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]")


def strings_of(path):
    tree = parse(path)
    out = {}
    if tree is None:
        return out
    for el in tree.getroot():
        n = el.get("name")
        if el.tag == "string":
            out[n] = "".join(el.itertext())
        elif el.tag == "plurals":
            out[n] = {i.get("quantity"): "".join(i.itertext()) for i in el}
    return out


def check_translations():
    base = strings_of(os.path.join(RES, "values", "strings.xml"))
    for folder in sorted(os.listdir(RES)):
        if not folder.startswith("values-"):
            continue
        p = os.path.join(RES, folder, "strings.xml")
        if not os.path.isfile(p):
            continue
        tr = strings_of(p)
        missing = [k for k in base if k not in tr]
        if missing:
            warnings.append(f"{folder}: {len(missing)} untranslated strings (English fallback)")
        for k, v in tr.items():
            if k not in base:
                err(f"{folder}/strings.xml: '{k}' does not exist in values/strings.xml")
                continue
            b = base[k]
            if isinstance(v, dict) != isinstance(b, dict):
                err(f"{folder}/strings.xml: '{k}' string/plurals type differs from English")
                continue
            if isinstance(v, dict):
                ref = sorted(set(FMT.findall(b.get("other", ""))))
                for q, text in v.items():
                    if sorted(set(FMT.findall(text))) != ref:
                        err(f"{folder}/strings.xml: plurals '{k}' ({q}) format {FMT.findall(text)} != {ref}")
            elif sorted(FMT.findall(v)) != sorted(FMT.findall(b)):
                err(f"{folder}/strings.xml: '{k}' format {FMT.findall(v)} != English {FMT.findall(b)}")
            if isinstance(v, str) and re.search(r"(?<!\\)'", v):
                err(f"{folder}/strings.xml: '{k}' has an unescaped apostrophe")


def platform_attrs(android_jar):
    if not android_jar or not os.path.isfile(android_jar):
        return None
    try:
        out = subprocess.run(["javap", "-classpath", android_jar, "android.R$attr"],
                             capture_output=True, text=True, timeout=120).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    names = set(re.findall(r"public static final int (\w+);", out))
    return names or None


def check_android_attrs(attrs):
    if attrs is None:
        warnings.append("android.jar not found: android:* attribute check skipped")
        return
    files = [os.path.join(APP, "AndroidManifest.xml")]
    for folder in os.listdir(RES):
        full = os.path.join(RES, folder)
        if os.path.isdir(full):
            files += [os.path.join(full, f) for f in os.listdir(full) if f.endswith(".xml")]
    for path in files:
        tree = parse(path)
        if tree is None:
            continue
        for el in tree.iter():
            for k in el.attrib:
                if k.startswith(ANDROID_NS):
                    a = k[len(ANDROID_NS):]
                    if a not in attrs:
                        err(f"{os.path.relpath(path, ROOT)}: unknown attribute android:{a}")
            # <item name="android:foo"> inside styles
            if el.tag == "item" and (el.get("name") or "").startswith("android:"):
                a = el.get("name")[8:]
                if a not in attrs:
                    err(f"{os.path.relpath(path, ROOT)}: unknown style item android:{a}")


def emit_r(defined, out_dir):
    pkg_dir = os.path.join(out_dir, *PACKAGE.split("."))
    os.makedirs(pkg_dir, exist_ok=True)
    lines = [f"package {PACKAGE};", "", "/** Compile-only R generated by tools/check_resources.py (not used in real builds). */",
             "public final class R {"]
    counter = 0x7f000000
    for t in sorted(defined):
        if not defined[t]:
            continue
        lines.append(f"    public static final class {t} {{")
        for n in sorted(defined[t]):
            counter += 1
            lines.append(f"        public static final int {n} = 0x{counter:08x};")
        lines.append("    }")
    lines.append("}")
    with open(os.path.join(pkg_dir, "R.java"), "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit-r")
    ap.add_argument("--android-jar", default=os.path.join(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")),
                                                         "platforms", "android-35", "android.jar"))
    a = ap.parse_args()
    defined = collect_defined()
    check_xml_refs(defined)
    check_java_refs(defined)
    check_translations()
    check_android_attrs(platform_attrs(a.android_jar))
    if a.emit_r:
        emit_r(defined, a.emit_r)
    for w in warnings:
        print("warning:", w)
    for e in errors:
        print("error:", e)
    counts = ", ".join(f"{k}={len(v)}" for k, v in sorted(defined.items()) if v)
    print(f"resources: {counts}")
    if errors:
        print(f"{len(errors)} error(s)")
        sys.exit(1)
    print("resource check passed")


if __name__ == "__main__":
    main()
