# Your Friend — Personal AI Agent (Android)

`com.suvo.agent` · built by Suvo (@suvojit20070)

An on-device personal AI agent with a live animated character, device control, a
skills system, long-term memory, native (C++) audio processing, and any
OpenAI-compatible LLM. See `ARCHITECTURE.md` for the full design.

## What's in this build

**Brain**
- OpenAI-compatible LLM client (streaming + tool-calling), full tool loop, sentence
  streaming (so the voice layer can speak early).
- Persona = the user's own system prompt. The app adds only agent-behaviour, live
  capabilities and memory layers. No fixed personality, no fixed language — the model
  replies in whatever language the user uses.

**Long-term memory (compressed)**
- Running history is kept short. When it grows, the oldest turns are **compressed by
  the model into durable fact-lines** and saved before being dropped, so even the
  oldest things are remembered (condensed). That memory is injected into every prompt
  and re-compressed when it gets large. A `remember` tool lets the model save facts explicitly.

**Device control** (opt-in, via Accessibility + Shizuku)
- `read_screen` (text + tappable coords), `tap` (xy or by text), `swipe`, `type_text`,
  `device_action` (back/home/recents/notifications), `screenshot` (API 30+),
  `shell` (ADB-level via Shizuku, no root — high-risk, user confirms), `file` (workspace).

**Skills** — user-added tools from JSON (`http` / `intent`), secrets kept out of the
model. Examples bundled: `web_search`, `open_url`.

**Native (C++/NDK)** — `libagentnative`: RMS/peak, energy VAD, lip-sync mouth smoothing,
fast PCM resample. Heavy per-frame audio work runs in C++; Java fallback if the lib can't load.

**Character** — two built-in animated bodies drawn in Canvas (original anime-style
**girl** and **boy**): idle breathing, blinking, lip-sync mouth (hooked to voice amplitude).
Switch in Settings. Imported Live2D/VRM models can replace this later.

**UI** — glassmorphism dark theme, streaming chat, in-app Settings (provider, keys,
character, permissions), Material You dynamic color, 5 app languages (English, Hinglish,
Hindi, Bangla, Japanese).

**License** — source-available, non-removable attribution (`LICENSE`, `NOTICE`).

## Build

### GitHub Actions (set up)
Push to `main` → `.github/workflows/build.yml` builds a debug APK and uploads it as an
artifact ("your-friend-debug"). Also runs on manual dispatch. It installs JDK 17, Android
SDK, NDK 26 + CMake, generates the Gradle wrapper, and runs `assembleDebug`.

### Local (Android Studio)
Open the project; it generates the Gradle wrapper and downloads dependencies.
`minSdk 26`, `targetSdk 35`, Java 17, NDK 26. Then open Settings in the app and enter your
LLM provider (base URL, API key, model).

## Roadmap (next)
- Voice: OmniVoice TTS (local + remote) + STT, speak streamed sentences, real lip-sync amplitude.
- Character overlay window (floating, draggable, touch-focus, move-around-screen) + Live2D/VRM import.
- Multimodal screenshot → vision, more capabilities (camera capture tool, notifications).
- Characters/Skills screens from the mockup, add-skill editor.

## Notes
- No copyrighted characters are bundled; the two built-in bodies are original, and users
  can import their own models.
- All device-control permissions are opt-in, shown transparently, with a kill-switch
  (the persistent notification's Stop action).
