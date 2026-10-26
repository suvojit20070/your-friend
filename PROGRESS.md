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

## Phase 2.1 (this update)

- [x] Build fix: public dialog button style; resource checker now rejects private platform styles
- [x] Real Material 3 components: `MaterialSwitch` and `Slider` (Material Components 1.12.0), app theme on Material3
- [x] Liquid glass: rgba(25,30,40,0.65) glass with 1px specular rim; **real-time backdrop blur** (RenderEffect,
      Android 12+) on the nav bar, composers and toasts; cross-window blur behind menus, sheets and dialogs
- [x] Spring physics press effect on every tappable view (1.15× pop, damping 0.55, stiffness 400) + radial glow
- [x] Morphing popup menus (scale 0.35→1 from the tapped button, overshoot), frosted bottom sheets
      (decelerating slide-up, dim, drag to close), floating glass pill toasts, spring-in dialogs
- [x] Chat: "N tools" accordion with nested code boxes; "Add to conversation" sheet (Photos, Camera, File, Folder);
      conversation scrolls under the glass composer; empty-send pill
- [x] Text selection handles in the accent colour
- [x] 3D: full-body motion (weight shift, breathing, relaxed arms, finger curl, talking gestures, thinking pose,
      listening lean, happy bounce) and framing fixed (model fitted to the body, not the T-pose arm span)

## Phase 2.2 (this update)

- [x] Fixed: popup menu items did nothing (callback queued on a detached view)
- [x] Fixed: pressed buttons were cut off by their parents (parents stop clipping while they pop)
- [x] Chat: your messages and the AI's answers are selectable (links still open); "Select text" sheet
- [x] Recognizer "tok tok" sounds muted around listening (restored automatically, also after a crash)
- [x] Hands-free: normal recognizer first (the on-device one often lacks Bengali/Hindi and made hands-free
      stop after 8 errors), lower speech threshold, automatic fallback when a recognizer ignores piped audio,
      live status + mic level in Voice settings
- [x] Wake words (Settings → Voice): "tanu, hey tanu, hi alex" anywhere in the sentence; tolerant of small
      recognition slips; wake word alone opens an 8-second window for the request (unit-tested)
- [x] 3D is the default character: bundled `assets/Pink_Haired_Girl_Teen.vrm` (or any .vrm in assets), custom
      .vrm from Settings; 2D only as a silent fallback; 2D appearance editor removed
- [x] 3D life: weight shifts, idle actions (look around, touch hair, hands together, sway, glance, stretch),
      VRM spring-bone physics for hair/skirt with colliders, stronger look-at (also for taps on Home)
- [x] Real lip sync: vowel shapes a/i/u/e/o from formant analysis of the playing audio (unit-tested)
- [x] Floating: tall full-body window, hair trails when dragged, falls with gravity and lands — sits on the top
      edge of screen elements (via accessibility) or stands on the screen bottom
- [x] `tools/make-release-key.sh`: creates the signing key in Termux and the four GitHub secrets for the
      signed release job
- [x] DebugActivity also reports native crashes and ANRs (Android 11+)

## Phase 2.6 (this update)

- [x] Fixed: after pressing a top-bar or composer button the chat list could draw over the top bar (the press
      effect un-clipped parents up to the window root). It now un-clips at most two parents and never a
      container that holds a list / scroll view or a screen's root column
- [x] Toast capture: the accessibility service records toasts (system and custom), Material snackbars and
      status announcements (in memory only, 2 min, text capped; real notifications ignored). Screen tools
      (`screen_operation_*`, `screenshot`, `launch_app`, `open_uri`, `device_admin`) add "After this action the
      screen showed: toast …" to their result; a toast that comes late is reported with the next screen tool
- [x] Attachments: Photos opens the system photo picker with images **and videos** (multi-select); File allows
      multi-select. Attachments show as removable chips above the composer and go natively into the request:
      images as images, videos as 4 frames + note, PDFs as first 3 pages + note, text/code files inline
      (≤ 60k chars, marked as untrusted data), other files as an exact workspace-path note
      (`uploads/…`, "starts at your workspace root", Termux cannot see it); folders as `ext:<name>/` plus the
      real `/storage/emulated/0/...` path. The chat bubble shows only "📎 name"; memory extraction and titles
      never see file contents. New prompt rule 12 explains attachment notes and toasts
- [x] Floating 3D: when carried or falling the legs hang and arms/legs swing and trail the motion (two-stage
      damped oscillators per limb, unit-tested), then settle after letting go
- [x] Sitting styles chosen by mood and changed every 9–19 s with smooth blending: swinging legs, legs
      crossed, leaning back on the hands, hugging the knees (sad / shy / sleepy), chin in hand (thoughtful)
- [x] OmniVoice (audio.cpp) now runs in its own `:tts` process (`OmniVoiceService`): a native crash or an
      out-of-memory kill there no longer closes the app. Voice settings show why it died (stage: loading
      library / loading model / speaking, signal, abort lines from Android's crash record) with a tip
      (wrong GGUF format, missing CPU instructions, low memory). The imported libc++_shared.so is no
      longer loaded next to the app's own copy

## Phase 2.7 (this update) — "Zafiro" Liquid UI

- [x] One seed colour → whole tonal palette (`ui/Palette`): warm cream / deep warm grey pages, tonal
      surface containers, muted primary used at low alpha. 12 pastel seed presets + wallpaper colour
      (Android 12+) in Settings → Appearance (swatch sheet). Static colour tokens = the amber scheme
- [x] G2 squircle shapes everywhere (`ui/Squircle`, corner smoothing 0.6): cards 28dp, composer 36dp,
      dialogs 48dp, sheets 32dp top, popovers 20dp, menu highlights 16dp, capsules for buttons/chips
- [x] Liquid glass presets (`GlassDrawable.Preset`): BUTTON / POPOVER / COMPOSER / PILL / DIALOG / SHEET with
      the spec's blur radii (light/dark), surface tints, primary-container hue tint, thin specular rim.
      Live blur + 1.5x vibrancy (RenderEffect) on Android 12+, tonal fallback below
- [x] Screen shell (`ui/Shell`): content scrolls under a floating top bar; at the top only round glass
      buttons float; when content is under the bar a progressive blur + tint fades in (280 ms) with no
      hard edge. Corner buttons are live-blur glass that swell when pressed
- [x] Dialogs: 48dp glass panel with window background blur, 42% scrim, 24sp title, equal-width capsule
      buttons (one solid primary), fade 120 ms + scale 1.04 → 1 in 240 ms
- [x] Settings rows: Card-28 surfaceContainerLow groups, 18sp titles / 13sp summaries, 24dp icons and
      chevrons, 0.5dp inset dividers at 35%, press = tonal 8% fade (no ripple) + haptic
- [x] Chat: user bubble primary 18% with tail, assistant answers plain text 17sp/26sp, tonal tool blocks,
      24dp between turns; 72dp glass composer
- [x] Motion: squish 0.92 (0.985 large) springs, haptic on every press, menus condense out of the button
      in 240 ms FastOutSlowIn, direction-aware page slides (360 ms), reduced motion → fades

## Phase 2.8 (this update) — Suvo Liquid UI v2 (`docs/design/suvo-liquid-ui.md`)

- [x] Overlays live INSIDE the screen (overlay host, z4): `GlassDialog` (48dp glass, blurs the real page,
      42% scrim, max 2 equal 64dp liquid capsule buttons, a 3rd action as quiet text; fade 120 ms +
      scale 1.04 → 1, out 180 ms), `GlassPopover` (morphs out of its button: size + radius → 20dp in
      240 ms, rows fade in after 25%, BottomEnd / BottomStart / centre), `GlassSheet` (32dp top, handle,
      rubber-band drag, close below 30% or fling, 300 / 220 ms, scrim 32%). Back closes the newest one.
      `Ui.dialog()` keeps the AlertDialog.Builder calls; plain item lists open as a sheet
- [x] Liquid press (`LiquidPress`, a foreground drawable so it never steals touches): spring 0.5 / 300
      swell, lean toward the finger (tanh), stretch along the drag, wobble back, white wash + radial
      glow (PLUS blend), inner shadow on corner buttons. Per component: corner 24dp, capsules 4dp,
      composer / fields 3dp, popover 6dp; squish 0.92 / 0.95 / 0.88; rows 8% / 500 ms; haptics
- [x] Glass presets with the spec values incl. hue tint, rim light, soft shadows, +20% fallback with a
      top inner highlight, disabled glass; new TEXTFIELD, SCROLL, CAPSULE presets
- [x] Liquid toggle (64 x 28 track, 39 x 24 pill thumb that swells 1.5x and turns to glass with a
      magnified track, drag with overshoot + tick) and liquid slider (6dp track, 40 x 24 thumb, 1.35x
      glass while held, velocity squash / stretch, step ticks) replace the Material widgets
- [x] Settings: segmented selector for 2–4 short choices, value preview + glass option sheet for more,
      expandable slider rows with min / max labels, row tap toggles switches
- [x] Glass text fields (36dp, 52dp min, hue tint, eye toggle on secret fields); dropdowns open a sheet
- [x] Top bar: exactly two slots — a second action turns the right button into "more" (popover);
      progressive glass is solid inside the status bar and melts to nothing
- [x] Chat: glass attach / send action buttons (primary icons), glass scroll-to-bottom circle above the
      composer, copy / more icons under messages (no long-press needed), tertiary timestamps
- [ ] Not yet: iOS-style scrollbar and rigid rubber-band overscroll, swipe-to-delete rows, multi-select
      collections, direction-aware "push from left" for history, 96sp onboarding hero
- [x] 2.8.1 fixes: pressed buttons no longer stay shifted (the press ignored finger moves reported after
      release and now settles exactly back); liquid press un-clips its parents so swells are not cut;
      toggle / slider get room for their swelling thumbs and the toggle no longer flickers after a
      change; the seed palette now also reaches XML-built views and views added later (text colours,
      icon tints, tonal / glass backgrounds, press) on every layout pass (`ui/ThemeMap`)

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
