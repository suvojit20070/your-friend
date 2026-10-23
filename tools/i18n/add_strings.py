#!/usr/bin/env python3
"""
Adds or replaces strings in all five language tables, then regenerates strings.xml.

Usage: python3 tools/i18n/add_strings.py new_strings.json
The JSON maps a key to its five translations:
  {"key": {"en": "...", "hinglish": "...", "bn": "...", "hi": "...", "ja": "..."}}
Every key needs all five languages, so translations never fall behind.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LANGS = ["en", "hinglish", "bn", "hi", "ja"]


def py_str(s):
    return json.dumps(s, ensure_ascii=False)


def apply(lang, entries):
    path = os.path.join(HERE, lang + ".py")
    src = open(path, encoding="utf-8").read()
    start = src.index("S = {")
    end = src.index("\n}\n", start)
    body = src[start:end]
    for key, text in entries.items():
        line = py_str(key) + ": " + py_str(text) + ","
        pat = re.compile(r'^' + re.escape(py_str(key)) + r': .*,$', re.M)
        if pat.search(body):
            body = pat.sub(lambda m: line, body)
        else:
            body = body + "\n" + line
    open(path, "w", encoding="utf-8").write(src[:start] + body + src[end:])


def main():
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    for key, tr in data.items():
        missing = [l for l in LANGS if not tr.get(l)]
        if missing:
            sys.exit("%s is missing %s" % (key, ", ".join(missing)))
    for lang in LANGS:
        apply(lang, {k: v[lang] for k, v in data.items()})
    subprocess.check_call([sys.executable, os.path.join(HERE, "generate.py")])


if __name__ == "__main__":
    main()
