# Your Friend

**Your Friend — Original project by Suvo.**

Your Friend is an Android personal AI agent. You bring your own model provider (any
OpenAI-compatible endpoint: OpenAI, OpenRouter, Groq, Together, a local llama.cpp / Ollama /
LM Studio server, and so on). The app runs a real tool-calling agent loop on the phone: it can
read and operate the screen, open apps, work with files, run code through Termux, search the
web, remember things across conversations, speak with a cloned voice, and show an animated
companion character that lip-syncs while it talks.

Package: `com.suvo.agent` · minSdk 26 (Android 8.0) · targetSdk / compileSdk 35 (Android 15)

> **Status in one line:** the code is complete, compiles, and the core logic is unit-tested.
> It has **not yet been built with Gradle or run on a device** — the first GitHub Actions run
> is the first real build. Read [Feature status](#feature-status) before relying on anything.

---

## Contents

1. [Feature status](#feature-status)
2. [Architecture](#architecture)
3. [Building](#building)
4. [First run and provider setup](#first-run-and-provider-setup)
5. [Device control setup](#device-control-setup)
6. [Voice (OmniVoice) setup](#voice-omnivoice-setup)
7. [Characters](#characters)
8. [Terminal and runtimes](#terminal-and-runtimes)
9. [Tools, custom tools and skills](#tools-custom-tools-and-skills)
10. [Approval modes and permission limits](#approval-modes-and-permission-limits)
11. [Privacy and security](#privacy-and-security)
12. [Languages](#languages)
13. [Known issues and limitations](#known-issues-and-limitations)
14. [Troubleshooting](#troubleshooting)
15. [License and credits](#license-and-credits)

More detail lives in [`docs/`](docs): [ARCHITECTURE](docs/ARCHITECTURE.md),
[SETUP](docs/SETUP.md), [TOOLS](docs/TOOLS.md), [VOICE](docs/VOICE.md),
[SECURITY](docs/SECURITY.md). Work remaining is tracked in [PROGRESS.md](PROGRESS.md).

---

## Feature status

What each column means:

- **Implemented** — the code exists and is wired into the app.
- **Compiles** — compiled with `javac` against the Android 35 platform jar (all 325 app classes,
  zero errors) in an earlier session. The latest changes (chat rendering, limits, native audio)
  were checked piece by piece (see [PROGRESS.md](PROGRESS.md)); the Gradle/AGP build itself has
  not run yet.
- **Unit-tested** — covered by JVM unit tests in `app/src/test` (116 test methods; the newest ones
  were written but not run in the environment that made them).
- **On device** — verified on a real phone. **Nothing is in this column yet.**

| Area | Implemented | Compiles | Unit-tested | On device |
|---|---|---|---|---|
| OpenAI-compatible client (streaming SSE, tool calls, retries) | ✅ | ✅ | ✅ (incl. local HTTP server test) | ⏳ |
| Agent loop (limits, loop detection, pause/resume/cancel, compaction on overflow) | ✅ | ✅ | ✅ | ⏳ |
| Tool lifecycle, schema validation, approval policy, audit log | ✅ | ✅ | ✅ | ⏳ |
| Persistent memory (SQLite + FTS), ranking, secret filter, extraction | ✅ | ✅ | ✅ (core logic) | ⏳ |
| Context compaction and history repair | ✅ | ✅ | ✅ | ⏳ |
| 35 built-in tools | ✅ | ✅ | partly (web parsing, URL safety) | ⏳ |
| Accessibility screen reading / control | ✅ | ✅ | — | ⏳ |
| Screenshots (accessibility API 30+, MediaProjection fallback) | ✅ | ✅ | — | ⏳ |
| Shizuku shell control | ✅ | ✅ | — | ⏳ |
| Camera capture, microphone speech input, hands-free mode | ✅ | ✅ | — | ⏳ |
| Files: private workspace, trash, SAF folders | ✅ | ✅ | — | ⏳ |
| Terminal via Termux `RUN_COMMAND`, SSH (JSch, host-key pinning) | ✅ | ✅ | — | ⏳ |
| Custom tools (Python / HTTP), SKILL.md skills | ✅ | ✅ | ✅ (skill parser) | ⏳ |
| OmniVoice via omnivoice-server, Hugging Face Space, Android system TTS | ✅ | ✅ | — | ⏳ |
| Native C++ audio (lip sync, mouth shapes, resample, level) with Java fallback | ✅ | ✅ (C++ built and compared with Java on a PC) | ✅ (Java) | ⏳ |
| Chat: Markdown (lists, tables, code blocks with copy), batched streaming | ✅ | ✅ (renderer compiled against stubs) | — | ⏳ |
| Agent limits with "no limit" switches (steps, tool calls, time) | ✅ | ✅ | ✅ | ⏳ |
| Animated characters Aiko & Haru, custom characters, floating companion | ✅ | ✅ | ✅ (animator) | ⏳ |
| Team: user-created employees (own provider each), `delegate_task`, live team tree | ✅ | ✅ | prompt layering | ⏳ |
| Self-active: `schedule_task`, self check-in, `record_mistake` lessons, `install_skill` | ✅ | ✅ | prompt layering | ⏳ |
| Hugging Face Space voice engine (e.g. Suvo75/tanu-voice) with fallback | ✅ | ✅ | — | ⏳ |
| Optional 3D VRM character (OpenGL ES 3) | ✅ | ✅ | loader (generated VRM 0.x / 1.0 files) | ⏳ |
| Live terminal view of the agent's commands | ✅ | ✅ | — | ⏳ |
| Dark Material-style UI, 15 sections, onboarding, permissions dashboard | ✅ | ✅ | resource check | ⏳ |
| 5 languages (English, Hinglish, Bengali, Hindi, Japanese) | ✅ | ✅ | resource check | ⏳ |
| GitHub Actions build, tests, lint, signed release | ✅ | — | — | ⏳ first run pending |

---

## Architecture

```
app/src/main/java/com/suvo/agent/
├── core/            Pure Java, no Android imports, unit-tested on the JVM
│   ├── llm/         OpenAI-compatible protocol, SSE parser, streaming tool-call accumulator
│   ├── agent/       AgentLoop, prompt assembly, history sanitizer, task state
│   ├── tools/       ToolSpec, schema validator, registry, executor, approval policy
│   ├── memory/      Memory store API, ranker, secret filter, compactor, extractor
│   ├── skills/      SKILL.md parser
│   ├── audio/       PCM / WAV helpers, lip-sync and mouth-shape (viseme) analysis
│   └── character/   Rig parameters, animator, renderer over a tiny Gfx abstraction
├── data/            SQLite (Db), Keystore SecretStore, Settings, repositories, audit log
├── agent/           AgentRuntime, Android host, foreground service, approval notifications
├── tools/           The 35 built-in tools
├── device/          Accessibility service, screen capture, Shizuku, camera, capability registry
├── files/           Workspace (private files + trash), SAF folders, FileProvider
├── terminal/        Local shell, Termux backend, runtime manager
├── plugins/         Custom tools
├── skills/          Skill storage
├── voice/           TTS engines, playback with lip sync, speech input, hands-free service
├── nativebridge/    JNI wrappers for libyourfriend (C++ audio, Java fallback)
├── character/       Character specs, Android renderer, CharacterView, floating companion
└── ui/              Activities and small view helpers
app/src/main/cpp/    native_audio.cpp (lip sync), dsp_core.cpp + native_dsp.cpp (visemes, resample, level)
```

The agent loop, step by step:

1. Assemble context: system prompt, character persona, ranked memories (with the reason each
   was picked), enabled skills, sanitized history.
2. Stream a chat completion. Tool-call fragments are stitched together as they arrive.
3. For each tool call: validate arguments against the JSON schema → check the capability is
   granted → apply the approval policy (outside the model) → execute → record the result.
   Every call moves through `REQUESTED → VALIDATED → AUTHORIZED → EXECUTING →
   SUCCEEDED | FAILED | CANCELLED | DENIED`, and each transition is written to the audit log.
4. Feed results back and repeat until the model answers without tool calls or a limit is hit
   (steps, tool calls, wall time; each can be switched to "no limit" in Settings → AI). Identical calls returning identical results are detected as
   a loop and stopped.
5. If the provider reports a context overflow, the oldest turns are summarized (tool call and
   result groups are never split), the summary is saved, and the request is retried.

No third-party HTTP or DI libraries: `HttpURLConnection` and a hand-written `AppGraph`.
The only runtime dependencies are Shizuku API 13.1.5 and JSch 2.28.8.
Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Building

### On GitHub (recommended)

Push the project to a GitHub repository. `.github/workflows/build.yml` runs on every push and
pull request:

1. JDK 17, Android SDK platform 35, build-tools, NDK 27.2.12479018, CMake 3.22.1
2. `python3 tools/check_resources.py` — validates every `R.*` and `@…/…` reference,
   translations and format specifiers
3. `./gradlew assembleDebug testDebugUnitTest lintDebug`
4. Uploads **`YourFriend-debug-apk`** (Actions → the run → Artifacts), plus test and lint reports

Signed release builds run only when these repository secrets exist (nothing is hardcoded):
`YF_KEYSTORE_BASE64`, `YF_KEYSTORE_PASSWORD`, `YF_KEY_ALIAS`, `YF_KEY_PASSWORD`.

### Locally

Requirements: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests
python3 tools/check_resources.py # resource sanity check
```

Gradle 8.14.3 (wrapper), Android Gradle Plugin 8.7.3, Java 17 bytecode.

---

## First run and provider setup

Onboarding walks through: welcome and language → provider → character → approval mode →
permissions. Everything except the provider can be skipped.

**Settings → Providers → Add**:

| Field | Example |
|---|---|
| Name | OpenRouter |
| Base URL | `https://openrouter.ai/api/v1` (the app appends `/chat/completions`) |
| API key | stored encrypted with Android Keystore (AES-GCM), never logged or exported |
| Model | `openai/gpt-4o-mini` |
| Vision / tools | switch on what the model supports; **Test** checks the connection and **Fetch models** lists models |

Examples without secrets are in [`examples/providers.example.json`](examples/providers.example.json).
The model must support OpenAI-style `tools` / `tool_calls`. Models without tool calling still
chat but cannot use tools.

---

## Device control setup

All of these are optional. The **Permissions** screen shows each one's live state, why the
agent might need it, and a button that opens the right system page.

| Capability | How to grant | Used by |
|---|---|---|
| Accessibility | Settings → Accessibility → Your Friend → On | `screen_operation_accessibility`, screenshots on Android 11+, floating-companion pointing |
| Screen capture | Approve the Android capture dialog each session | `screenshot` fallback (MediaProjection) |
| Shizuku | Install Shizuku, start it (wireless debugging or root), grant Your Friend | `screen_operation_shell`, privileged shell |
| Overlay | "Display over other apps" | Floating companion |
| Camera / Microphone | Runtime prompt | `camera_capture`, voice input, hands-free |
| Notifications | Runtime prompt (Android 13+) | Approval requests, task progress |
| Install unknown apps | Per-app system setting | `py_install_apk` (Android still shows its own confirm dialog) |
| Termux | Install Termux (F-Droid/GitHub), set `allow-external-apps=true`, grant "Run commands in Termux" | `shell`, `execute_*`, Python tools |

The app never works around Android's own consent screens. Step-by-step instructions:
[docs/SETUP.md](docs/SETUP.md).

---

## Voice (OmniVoice) setup

Your Friend speaks through one of three engines (**Settings → Voice**; the default is the phone's own voice):

1. **Hugging Face Space (online)** — your own Gradio Space, for example `Suvo75/tanu-voice`
   (OmniVoice with your reference voice on ZeroGPU). Enter the Space, optionally a Hugging Face
   token (needed for private Spaces; usage then counts against *your* ZeroGPU quota, which is larger
   when logged in and larger again with PRO — it is **not** unlimited) and the Space's `API_KEY` if you
   set one. A sleeping Space takes 1–2 minutes to wake; the app retries. With "Use the phone's voice
   if this fails" on, replies are still spoken when you are offline or out of quota.

2. **OmniVoice server** — point the app at an
   [omnivoice-server](https://github.com/maemreyo/omnivoice-server) running on your PC or LAN.
   Voice cloning uses `/v1/audio/speech/clone`; plain speech uses `/v1/audio/speech`.
3. **Android system TTS** — always available, no cloning.

**Voice cloning:** set a reference clip `ref.wav` (3–10 s of clean speech; record it in the app
or import it) and its exact transcript `ref.txt` (type it or import it) in **Settings → Voice →
Reference voice**. Use only voices you have the right to use.

**Licensing:** the OmniVoice model weights are **CC BY-NC 4.0 (non-commercial)** and are not
shipped with this app. omnivoice-server is MIT.

The on-device OmniVoice engine (audio.cpp) was removed because it was heavy and slow on phones.
Neither the server nor the Space path has been tested on a phone yet.
Full guide: [docs/VOICE.md](docs/VOICE.md).

---

## Characters

Two original characters ship with the app: **Aiko** (girl) and **Haru** (boy). They are drawn
at runtime by a procedural 2D rig. This is not Live2D, and no external art is used.

Animation: breathing, blinking, eye saccades, head turns, hair springs, expressions (neutral,
happy, sad, surprised, angry, shy, sleepy), a thinking overlay, touch reactions, look-at, and lip sync driven by
the audio actually playing (amplitude plus band energy, synced to the `AudioTrack` playback
position).

**Custom characters:** Characters → duplicate a built-in one, or import a file, then edit
it. You can also export them. A character is a JSON file (`yourfriend-character/1`):

```json
{
  "format": "yourfriend-character/1",
  "id": "mika",
  "name": "Mika",
  "description": "You are Mika, the user's curious companion...",
  "personality": "cheerful, curious, explains things simply",
  "systemPrompt": "Keep everyday replies short.",
  "greeting": "Hi! What are we doing today?",
  "rig": { "base": "girl", "hairStyle": "long", "eyeShape": "round", "outfit": "sailor",
           "accessory": "ribbon", "hairColor": "#2B2140", "eyeColor": "#6A4BD8",
           "skinColor": "#FCE3D3", "outfitColor": "#3A6FF7", "accentColor": "#E5486B" },
  "animation": { "defaultExpression": "happy", "idleIntensity": 1.0, "blinkRate": 1.0,
                 "expressiveness": 1.0, "wander": false },
  "touchReactions": ["Hehe!", "Yes?"]
}
```

See `app/src/main/assets/characters/aiko.json` and `haru.json` for complete examples.

**3D (optional):** Settings → Appearance → Character style → *3D model (VRM)*, then pick a `.vrm`
file (VRoid Studio / VRoid Hub / Booth exports, VRM 0.x or 1.0). The app copies it into its private
storage and draws it with OpenGL ES 3: skinning, face morphs, blink, lip sync, look toward taps,
breathing and expressions, with a full-body or upper-body camera. 2D stays the default; if a model
cannot be shown the app falls back to 2D and Settings shows why. Hair and cloth use VRM spring-bone physics. The
floating companion (Settings → Floating companion) sits on top of other apps, can be dragged
and snaps to edges.

---

## Agent limits

**Settings → AI** has three limits for one autonomous task: steps, tool calls and minutes. Each
one has its own **No limit** switch; while it is on, the slider is hidden and the task runs until it
is finished or you stop it. Repeated identical failing calls are still stopped, and a task with no
time limit holds its wake lock in 12-hour blocks. Team employees have a step setting of their own
("No limit" there means "follow the boss").

---

## Terminal and runtimes

- **Local shell** — Android's `/system/bin/sh` inside the app sandbox. Limited but always there.
- **Termux** — the app sends commands with Termux's `RUN_COMMAND` intent and gets the result
  back through a `PendingIntent`. Python, Node, clang, Java etc. come from `pkg install` inside
  Termux. **Terminal → Runtimes** shows what is detected.
- **Shizuku** — runs commands as the shell user (ADB-level), via a Shizuku UserService.
- **SSH** — saved hosts. Host keys are pinned on first use (TOFU) and changes are refused.

Termux one-time setup:

```bash
pkg install python
mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
termux-reload-settings
```

Then grant "Run commands in Termux environment" to Your Friend (App info → Permissions →
Additional permissions).

---

## Tools, custom tools and skills

### Built-in tools (35)

| Risk | Tools |
|---|---|
| Read-only | `list_tools`, `device_status`, `find_installed_apps`, `file_list`, `file_read`, `file_search`, `file_info`, `screenshot`, `view_image`, `load_skill`, `py_web_search`, `py_web_read` |
| Low | `launch_app`, `open_uri`, `notify`, `file_write`, `file_edit`, `memory`, `py_download_file`, `record_mistake`, `delegate_task` |
| Medium | `shell`, `execute_python`, `execute_cpp`, `execute_java`, `file_manage`, `screen_operation_accessibility`, `screen_operation_shell`, `camera_capture`, `schedule_task`, `install_skill` |
| High | `ssh`, `py_meta_tools`, `py_install_apk`, `device_admin` (most actions) |

**Team (More → Team):** create employees — name, role, instructions, their own AI provider, allowed
tools and a step limit. For each task the boss agent checks the team: it hands matching work to an
employee with `delegate_task` and reviews the report, or does it itself when nobody fits (with no
employees the tool is not even offered). An employee only gets the tools you allowed (enforced by its
own tool registry, not by the prompt) and the same approvals as the boss; it cannot hire others or
schedule tasks. The Agent screen shows a live tree of who is doing what.

**Self-active (More → Self-active):** `schedule_task` lets the agent start itself later (reminders,
follow-ups, daily checks; you approve each one), and an optional self check-in every few hours
(never 22:00–08:00). `record_mistake` keeps short lessons that are shown in every later task; you can
review or delete them there. Allow *exact alarms* for on-time starts with the screen off.

Risk can change per call. For example, `file_manage` delete in an external folder, a `shell`
command like `rm -rf`, or anything through Shizuku counts as high, while `screen_operation_accessibility`
`read` is read-only. Typing into password fields is always refused. Full reference: [docs/TOOLS.md](docs/TOOLS.md).

### Custom tools

**Tools → New custom tool**. Two kinds:

- **Python** — runs in Termux. Arguments arrive as **JSON on stdin**, already validated against
  your schema. Print plain text, or JSON `{"status": "ok"|"error", "message": "...", "data": {...}}`.
- **HTTP** — method, URL, headers and a body template. Placeholders such as `{{query}}` become
  the JSON encoding of the argument. Secrets in headers come from the Keystore, not the
  definition. An optional secret (for example an API key) is sent in a header you name; it is
  stored encrypted and never shown to the model.

You pick the risk level. A Python tool can never be rated read-only (it is raised to at
least low), and the usual approval rules apply.

### Skills

A skill is a `SKILL.md` file: YAML front matter plus Markdown instructions.

```markdown
---
name: Web research
description: Answer questions that need current information, with sources.
version: 1.0
triggers: [search, latest, news, compare]
required_tools: [py_web_search, py_web_read]
---
# Web research
1. Search first, then open the 2–3 best results...
```

Six skills ship in `assets/skills/`. Imported skills are treated as **untrusted text**: they
cannot grant permissions, cannot change approval rules, and are shown as instructions the model
*may* follow.

---

## Approval modes and permission limits

| Mode | Read-only | Low | Medium | High |
|---|---|---|---|---|
| Assist | auto | blocked | blocked | blocked |
| Confirm (default) | auto | auto | ask | ask |
| Autonomous | auto | auto | auto | **ask** |

In Assist mode the agent can only look (read-only tools) and tells you what it would do.
High-risk actions always ask, in every mode where they are allowed. Approvals are enforced in `ToolExecutor`, outside
the model, so a prompt cannot change them. You can also set any tool to *always ask* or
*disabled*. Requests show as an in-app dialog and as a notification with Allow / Deny.

What the app **cannot** do, by Android design: approve its own screen-capture dialog, install
an APK without the system confirmation, read other apps' private data, or act on secure screens
such as the lock screen and some banking apps.

---

## Privacy and security

- API keys, SSH passwords and HTTP-tool secrets are encrypted with Android Keystore. They are
  never written to memory, logs, audit entries or exports.
- The memory extractor runs a secret filter (API keys, tokens, card-like numbers, passwords,
  private keys) and refuses to store matches.
- Tool results, web pages, files and skills are wrapped as untrusted content in the prompt.
- Web tools block private, loopback, link-local and CGNAT addresses (SSRF protection) unless
  you turn on LAN access in Settings.
- Everything stays on the phone except the requests you send to your chosen provider and voice
  server. There is no analytics or telemetry.
- Memory screen: view, edit, pin, delete or clear all memories. Settings → Export config writes
  your settings to a file, and secrets are always left out.

Details: [docs/SECURITY.md](docs/SECURITY.md).

---

## Languages

English, Hinglish (`b+hi+Latn`), Bengali, Hindi and Japanese, with 872 strings and 7 plural
sets each. On Android 13+ you can pick a per-app language in system settings; on older versions
use the in-app picker. To edit text, change `tools/i18n/*.py` and run
`python3 tools/i18n/generate.py`.

---

## Known issues and limitations

- **Not built with Gradle yet, and not run on a device yet.** Expect first-build fixes (lint,
  AAPT) and first-run fixes.
- Code runtimes need Termux. Without it only the limited local `sh` is available.
- Accessibility control cannot see inside some apps (games, Flutter or WebView canvases without
  accessibility nodes) or on secure screens.
- Screenshots through MediaProjection need your consent every session (Android rule).
- Plain `http://` is allowed (needed for LAN servers). Use `https://` for public providers so
  your API key is not sent unencrypted.
- The built-in characters are a procedural 2D rig, not Live2D (Live2D models cannot be loaded). VRM 3D models can be loaded.
- Speech input uses Android's `SpeechRecognizer`. Quality and offline support depend on the
  phone's recognition service.
- Android may kill background agents on aggressive OEM ROMs. Exempt the app from battery
  optimization if long tasks stop.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Provider returned 401" | Check the API key. Re-enter it, since keys are encrypted per device. |
| Model chats but never uses tools | The model or endpoint doesn't support `tools`. Try a tool-capable model. |
| "Context too long" loops | Lower the *context trigger* in Settings → AI, set the right context window on the provider, or start a new chat. |
| Termux commands time out | `allow-external-apps=true` missing, the permission isn't granted, or Termux was killed. Open Termux once. |
| Accessibility turns itself off | The OEM battery saver killed it. Disable battery optimization for Your Friend. |
| Shizuku "not running" | Restart Shizuku after every reboot (non-root). |
| No voice from OmniVoice | Check Voice → Test and the engine status line. For the server, check that the URL is reachable from the phone (same Wi-Fi, no `localhost`), or switch the engine to System TTS. |
| Character mouth not moving | Lip sync follows the audio the app plays. Check that *Speak replies* is on and the voice engine shows "ready". |
| CI fails at `check_resources.py` | A string or drawable reference is missing. The log names the file and line. |

---

## License and credits

Licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Redistributions must keep the NOTICE credit: **"Your Friend — Original project by Suvo."**

Third-party components and their licenses are listed in
`app/src/main/assets/THIRD_PARTY_NOTICES.txt` and on the in-app About screen: Shizuku API
(Apache-2.0), JSch (BSD-3-Clause), omnivoice-server (MIT,
not bundled), OmniVoice weights (CC BY-NC 4.0, not bundled).
