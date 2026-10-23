# Tools reference

Every tool has a JSON Schema for its arguments, a **base risk**, the **capabilities** it needs,
and often a **per-call risk** (`riskFor(args)`) that depends on what the call actually does.
The approval policy uses the per-call risk. The model can call `list_tools` to see what is
available right now and, for anything unavailable, exactly what you need to enable.

## Built-in tools

### Discovery and apps

| Tool | Base risk | Needs | What it does |
|---|---|---|---|
| `list_tools` | read-only | — | Lists usable tools, what is missing for the others, device capabilities and installed runtimes. `name` returns one tool's full schema. |
| `device_status` | read-only | — | Time, timezone, battery, network, storage, screen size, Android version, foreground app. |
| `find_installed_apps` | read-only | app list | Searches launchable apps by name or package fragment. |
| `launch_app` | low | app list | Opens an app by package name or visible name. |
| `open_uri` | low | — | Opens https/http, `geo:`, `market:`, `mailto:`, `sms:`, `tel:`. Mail, SMS and phone links are **medium**. They only open a draft or the dialer, and nothing is sent or called automatically. |
| `notify` | low | notifications | Shows an Android notification. |

### Screen

| Tool | Base risk | Needs | What it does |
|---|---|---|---|
| `screen_operation_accessibility` | medium | accessibility | `read` lists visible elements with ids (`e1`, `e2`…); `find`, `tap`, `long_press`, `type`, `scroll`, `swipe`, `back`, `home`, `recents`, `notifications`, `quick_settings`, `wait_for`. `read`, `find` and `wait_for` are **read-only**. Typing into password fields is refused. |
| `screen_operation_shell` | medium | Shizuku | `tap`, `long_press`, `swipe`, `text`, `key`, `ui_dump`, `current_app` with ADB-shell identity. `ui_dump` and `current_app` are **read-only**. |
| `screenshot` | read-only | screen capture | Captures the screen, saves it to the workspace, and attaches it for vision models. |
| `view_image` | read-only | workspace | Loads an image from the workspace or a granted folder. |
| `camera_capture` | medium | camera | Takes a photo (front/rear) while the app is on screen. |

### Files

Paths are relative to the private workspace. Folders you granted through SAF are written
`ext:FolderName/...`.

| Tool | Base risk | Per-call risk | What it does |
|---|---|---|---|
| `file_list` | read-only | — | Lists a folder and the granted external folders. |
| `file_read` | read-only | — | Reads a text file, optionally a line range. |
| `file_search` | read-only | — | Recursive search by glob and/or contained text. |
| `file_info` | read-only | — | Size, type, modified time, SHA-256 (up to 50 MB). |
| `file_write` | low | overwrite → medium; external → medium; external overwrite → **high** | Create, overwrite or append. |
| `file_edit` | low | external → medium | Exact find/replace with an expected match count. |
| `file_manage` | medium | delete → medium (workspace, goes to trash) / **high** (external, permanent); mkdir → low; overwrite → **high** | rename, copy, move, delete, mkdir. |

### Code and terminal

| Tool | Base risk | Needs | What it does |
|---|---|---|---|
| `shell` | medium | local shell | `backend=local` (Android `sh`, in the workspace), `termux` (bash, pkg, python, clang, node, git), `shizuku` (ADB identity, **high**). Commands matching a dangerous pattern (`rm -rf`, `mkfs`, `dd … of=/dev`, `reboot`, `pm uninstall/clear/disable`, `settings put`, `curl … \| sh`, fork bombs…) are **high**. |
| `execute_python` | medium | Termux | Runs Python code with optional args and a timeout (≤ 900 s). |
| `execute_cpp` | medium | Termux | Compiles and runs C++ with clang. |
| `execute_java` | medium | Termux | Compiles and runs Java with OpenJDK. |
| `ssh` | **high** | network | Runs a command on a host you saved in Terminal → SSH hosts. Arbitrary hosts are not allowed, and the host key is pinned on first use. |

### Web

| Tool | Base risk | Needs | What it does |
|---|---|---|---|
| `py_web_search` | read-only | network | DuckDuckGo (no key), SearXNG (your instance) or Brave Search (API key). |
| `py_web_read` | read-only | network | Readable text, title and main links of a page. The content is marked untrusted. |
| `py_download_file` | low | network, workspace | Downloads into `downloads/` (max 300 MB). |
| `py_install_apk` | **high** | install packages | Installs an APK from a URL or the workspace. Android's own confirmation is always shown. |

### Memory, skills, custom tools

| Tool | Base risk | Per-call risk | What it does |
|---|---|---|---|
| `memory` | low | search/list → read-only; delete → medium | Long-term memory: search, list, add, update, delete. Secrets are refused. |
| `load_skill` | read-only | — | Reads an installed skill's instructions or resource files. |
| `py_meta_tools` | **high** | list/inspect/export → read-only; enable/disable → low; test/delete → medium; create/update → **high** | Manages custom tools. |

## Custom tool contract

### Python

```python
# Arguments arrive as JSON on stdin, already validated against your schema.
import json, sys
args = json.load(sys.stdin)
result = args["text"].upper()
print(json.dumps({"status": "ok", "message": result, "data": {"length": len(result)}}))
```

- Runs in Termux with the timeout you set.
- Output: one JSON object `{"status": "ok"|"error", "message": "...", "data": {...}}`, or plain
  text, which becomes the message.
- A non-zero exit code means failure.
- Its risk can never be read-only (it is raised to at least low).

### HTTP

- Method, URL, headers, body template, timeout.
- `{{arg}}` in the URL is URL-encoded.
- In the body, placeholders go where a JSON value belongs, without quotes:
  `{"q": {{query}}, "n": {{count}}}`. Each one is replaced by the JSON-encoded argument.
- Optional secret header (for example `Authorization` or `X-Api-Key`). The value is encrypted
  with the Keystore, never shown to the model, and left out of exports.

Example definitions are in [`examples/custom-tools.example.json`](../examples/custom-tools.example.json).

## Skill format

```markdown
---
name: File organizer
description: Tidy a folder — group by type/date, rename consistently, remove duplicates.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [organize, clean up, sort files, duplicates]
required_tools: [file_list, file_manage, file_info]
---

# File organizer
Step-by-step instructions the model may follow…
```

- Front matter: `name`, `description` (required); `version`, `author`, `license`, `triggers`,
  `required_tools` (optional).
- Installed skills are listed by name and description in the system prompt. The model loads the
  full text with `load_skill` when it needs it.
- On install, the app warns about `required_tools` that don't exist.
- Skills are guidance only. They cannot grant permissions or change approvals.
