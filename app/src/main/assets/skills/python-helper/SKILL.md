---
name: Python helper
description: Write and run small Python programs in Termux for calculations, data processing or automation.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [python, script, calculate, csv, parse, convert, automate]
required_tools: [execute_python, file_write, file_read]
---

# Python helper

1. Check that Python works: if `execute_python` is unavailable, tell the user to install Termux (F-Droid) and Python (`pkg install python`), and set `allow-external-apps=true`.
2. Write the smallest program that solves the task. Prefer the standard library; if a package is needed, say which one and install it only after the user agrees (Terminal > Runtimes, or `pip install name`).
3. Run it with `execute_python`. Print clear results; for big outputs write a file to the workspace with `file_write` and report the path.
4. If it fails, read the error, fix the code and try again (at most 3 attempts), then explain the problem.
5. Show the final code to the user when it is useful to them.

Rules:
- Do not run code that deletes files outside the workspace, sends messages or accesses the network unless the user asked for it.
- Never put secrets or API keys into code you show.
