#!/usr/bin/env python3
"""
Downloads the TextMate grammars the file editor (Sora Editor) colours code with, from the VS Code
repository (MIT licence), into app/src/main/assets/textmate/, and writes languages.json for the
grammars that arrived. Run by CI before the build. A grammar that can't be fetched is skipped:
the editor then shows that language as plain text, and the build never fails because of it.
"""
import json
import os
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/microsoft/vscode/1.95.0/extensions/"
# name, scopeName, path in the VS Code repo, file extensions
GRAMMARS = [
    ("java", "source.java", "java/syntaxes/java.tmLanguage.json", ["java"]),
    ("python", "source.python", "python/syntaxes/MagicPython.tmLanguage.json", ["py"]),
    ("javascript", "source.js", "javascript/syntaxes/JavaScript.tmLanguage.json", ["js", "mjs", "cjs"]),
    ("typescript", "source.ts", "typescript-basics/syntaxes/TypeScript.tmLanguage.json", ["ts"]),
    ("json", "source.json", "json/syntaxes/JSON.tmLanguage.json", ["json"]),
    ("xml", "text.xml", "xml/syntaxes/xml.tmLanguage.json", ["xml", "svg"]),
    ("html", "text.html.basic", "html/syntaxes/html.tmLanguage.json", ["html", "htm"]),
    ("css", "source.css", "css/syntaxes/css.tmLanguage.json", ["css"]),
    ("markdown", "text.html.markdown", "markdown-basics/syntaxes/markdown.tmLanguage.json", ["md"]),
    ("shell", "source.shell", "shellscript/syntaxes/shell-unix-bash.tmLanguage.json", ["sh", "bash"]),
    ("yaml", "source.yaml", "yaml/syntaxes/yaml.tmLanguage.json", ["yml", "yaml"]),
    ("c", "source.c", "cpp/syntaxes/c.tmLanguage.json", ["c", "h"]),
    ("cpp", "source.cpp", "cpp/syntaxes/cpp.tmLanguage.json", ["cpp", "cc", "hpp"]),
    ("ini", "source.ini", "ini/syntaxes/ini.tmLanguage.json", ["ini", "properties", "conf", "cfg"]),
]

root = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "textmate")
os.makedirs(root, exist_ok=True)
langs = []
for name, scope, path, exts in GRAMMARS:
    dest_rel = "textmate/grammars/" + name + ".tmLanguage.json"
    dest = os.path.join(root, "grammars", name + ".tmLanguage.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as r:
            data = r.read()
        json.loads(data.decode("utf-8"))  # must be valid JSON
        with open(dest, "wb") as f:
            f.write(data)
        langs.append({"name": name, "scopeName": scope, "grammar": dest_rel, "extensions": exts})
        print("ok   ", name)
    except Exception as e:  # noqa: BLE001 - any failure just skips the language
        print("skip ", name, "-", e, file=sys.stderr)
with open(os.path.join(root, "languages.json"), "w") as f:
    json.dump({"languages": langs}, f, indent=1)
print(len(langs), "grammars")
