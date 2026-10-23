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
- 82 JVM unit tests passing
- VRM loader checked on generated VRM 0.x and 1.0 test files (bones, expressions, skin, morph targets, textures)
- All 32 tool JSON schemas parse
- Native C++ syntax-checked with g++. Lip-sync output matches the Java version within 6e-8

## Not verified yet

- [ ] **Gradle/AGP build** (AAPT2 resource compile, D8/R8, lint): first CI run
- [ ] **Running on a phone**: every Android-facing feature
- [ ] audio.cpp Android cross-compile workflow and on-device OmniVoice speed and memory
- [ ] omnivoice-server against a real server
- [ ] Hugging Face Space engine against the real Space (input order of its `/tts` endpoint)
- [ ] 3D renderer on real phones and real VRM files (shaders were not compiled by a GPU driver here)
- [ ] Scheduled tasks firing with the screen off; employee delegation with real providers

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

## Phase 2 (this update)

- [x] New dark design system: grouped cards, Material 3 switch and slider drawn in code, pill navigation
      with a "More" sheet, centred top bars, fixed cut-off character and composer
- [x] Chat: model picker in the title, thinking on/off (`reasoning_effort`), full-width answers, new composer
- [x] Terminal: new window-style UI, quick keys, and **live view of every command the agent runs**
- [x] Floating character: drag fixed, looks toward every tap on screen, **hold to talk**
- [x] Voice: **Hugging Face Space engine** (e.g. `Suvo75/tanu-voice`) with HF token, Space API key,
      wake-up retries and fallback to the phone's voice. Online only; the token uses your ZeroGPU quota
      (not unlimited)
- [x] `record_mistake`: lessons from mistakes, shown in every later task (Self-active screen to review/remove)
- [x] `schedule_task` + optional **self check-in**: the agent starts itself later (AlarmManager, re-armed
      after reboot). Creating a schedule needs approval
- [x] Every task runs in the foreground service with a time-limited wake lock
- [x] **Team**: user-created employees, each with its own provider, role, instructions, allowed tools and
      step limit. Boss delegates with `delegate_task` (offered only when employees exist), otherwise works
      alone. Employees get their own tool registry (only allowed tools can run). **Live team tree** in the
      Agent screen
- [x] "How to work well" playbook in the system prompt; `install_skill` keeps skills found on the web (approval)
- [x] **3D character (VRM 0.x / 1.0)**: Settings → Character style. OpenGL ES 3 renderer with GPU skinning,
      face morphs, blink, lip sync, look-at, breathing, expressions; full-body or upper-body camera. 2D stays
      the default; falls back to 2D with the reason if a model cannot be shown
- [x] Settings export now strips every encrypted secret and token

## Planned

- Spring-bone physics (hair/clothes) and MToon outlines for the 3D character
- Persistent task-tree history, mood memory, browser use

## Next steps

1. Push to GitHub and run the **Android CI** workflow. Fix anything AAPT2/lint reports.
2. Install `YourFriend-debug-apk` on a phone. Go through onboarding, add a provider, and run a
   simple tool task (`device_status`, `find_installed_apps` → `launch_app`).
3. Enable accessibility and try a read → tap → read loop in a simple app (Settings, Clock).
4. Install Termux, then test `execute_python` and a custom Python tool.
5. Test voice: system TTS first, then omnivoice-server, then audio.cpp.
6. Add instrumented tests (Espresso) for the main flows once the app runs.
7. Profile the character renderer on a low-end phone and tune the "low" animation quality.
