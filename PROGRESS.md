# Progress

Your Friend — Original project by Suvo.

## Done

- [x] Pure-Java core: OpenAI-compatible streaming client, agent loop, tool lifecycle and
      approvals, memory ranking/extraction/secret filter, context compaction, history repair,
      skill parser, audio + lip sync, character rig/animator/renderer
- [x] Android layer: SQLite (v3, FTS4), Keystore secrets, settings, repositories, audit log
- [x] 30 built-in tools (apps, screen, files, code, SSH, web, memory, skills, custom tools)
- [x] Accessibility service, MediaProjection capture, Shizuku UserService, camera, speech input,
      hands-free foreground service
- [x] Termux RUN_COMMAND backend, local shell, SSH with host-key pinning, runtime detection
- [x] Voice: OmniVoice via audio.cpp (JNI + dlopen), omnivoice-server client, system TTS,
      reference voice record/import, AudioTrack playback with lip sync
- [x] Characters Aiko and Haru (original procedural 2D rig), custom characters with
      import/export, floating companion overlay
- [x] All UI sections, glass theme, Material You colors on Android 12+, onboarding,
      permissions dashboard
- [x] 5 languages × 680 strings + 6 plurals
- [x] GitHub Actions: resource check, assembleDebug, unit tests, lint, artifacts, optional
      signed release; experimental audio.cpp Android build
- [x] Docs, LICENSE (Apache-2.0), NOTICE with credit, examples

## Verified so far (in a sandbox without Android build tools)

- `javac` compile of all app sources against the Android 35 platform jar: 0 errors
- `tools/check_resources.py`: all R / XML references, translations and format specifiers valid
- 75 JVM unit tests passing
- Native C++ syntax-checked with g++. Lip-sync output matches the Java version within 6e-8

## Not verified yet

- [ ] **Gradle/AGP build** (AAPT2 resource compile, D8/R8, lint): first CI run
- [ ] **Running on a phone**: every Android-facing feature
- [ ] audio.cpp Android cross-compile workflow and on-device OmniVoice speed and memory
- [ ] omnivoice-server against a real server

## Phase 1 (this update)

- [x] Gemini: tool calls keep `extra_content.google.thought_signature` (streamed, stored, sent back);
      old history gets Google's documented placeholder. Unit-tested.
- [x] Floating companion appears when you leave the app and hides inside it (Settings toggle, on by default)
- [x] Tap cursor: a pointer glides to every tap/swipe before it happens (accessibility overlay, no extra permission)
- [x] Chat: Markdown (headings, bold/italic, code blocks, lists, quotes, links, tables) and selectable text
- [x] Live log: detail boxes use the full width (no more one-word-per-line)
- [x] Hands-free: own microphone listener that never takes audio focus, so music keeps playing
- [x] Termux: exact reason when it cannot connect (old Play Store build, permission, allow-external-apps)
- [x] Shizuku `device_admin` tool: silent APK install, uninstall, permissions, appops, settings, Wi-Fi/BT/data,
      brightness, volume, force-stop, notifications, dumpsys
- [x] Screen reading: long text cut (inputs 60, text 120 chars) with `get_text`; last 5 screens + actions as history
- [x] A broken tool can no longer crash the app at start

## Planned

- Phase 2: new UI in the style of the reference screenshots, Hugging Face Space voice engine (Tanu voice)
  with System TTS fallback, thinking on/off
- Phase 3: multi-agent (user-created child agents, each with its own provider; boss agent delegates or
  works alone; live task tree in the app), persistent task tree, mood memory, self-active check-ins,
  browser use, skill discovery from the web
- Phase 4: optional 3D VRM character (2D stays the default; 3D applied from Settings)

## Next steps

1. Push to GitHub and run the **Android CI** workflow. Fix anything AAPT2/lint reports.
2. Install `YourFriend-debug-apk` on a phone. Go through onboarding, add a provider, and run a
   simple tool task (`device_status`, `find_installed_apps` → `launch_app`).
3. Enable accessibility and try a read → tap → read loop in a simple app (Settings, Clock).
4. Install Termux, then test `execute_python` and a custom Python tool.
5. Test voice: system TTS first, then omnivoice-server, then audio.cpp.
6. Add instrumented tests (Espresso) for the main flows once the app runs.
7. Profile the character renderer on a low-end phone and tune the "low" animation quality.
