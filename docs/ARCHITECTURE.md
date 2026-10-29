# Architecture

## Layers

```
┌──────────────────────────── ui/ ─────────────────────────────┐
│ Activities: Main, Chat, LiveAgent, ScreenControl, Permissions │
│ Settings, Providers, Memory, Characters, Tools, Terminal,     │
│ Files, Voice, About, Onboarding                               │
└───────────────┬───────────────────────────────┬──────────────┘
                │                               │
        agent/AgentRuntime              character/CharacterView
        agent/AndroidAgentHost          voice/SpeechController
                │                               │
┌───────────────▼──────────── core/ (pure Java) ▼──────────────┐
│ AgentLoop ─► LlmClient (OpenAiCompatClient, SSE)              │
│     │      ─► ToolExecutor ─► ToolRegistry ─► Tool            │
│     │                    └─► ApprovalPolicy, Approver, audit  │
│     ├─► PromptAssembler ◄─ MemoryRanker ◄─ MemoryStore        │
│     └─► ContextCompactor, HistorySanitizer                    │
│ CharacterAnimator ─► PuppetRenderer ─► Gfx                    │
│ LipSync, Wav, Pcm, SkillParser                                │
└───────────────┬──────────────────────────────────────────────┘
                │ implemented by
┌───────────────▼──────────────────────────────────────────────┐
│ data/ (SQLite, Keystore, Settings)   tools/ (30 tools)        │
│ device/ (Accessibility, MediaProjection, Shizuku, Camera)     │
│ terminal/ (sh, Termux, SSH)   files/ (Workspace, SAF)         │
│ voice/ (TTS engines, AudioPlayback)   nativebridge/ + cpp/    │
└──────────────────────────────────────────────────────────────┘
```

`core/` has no Android imports. It runs on a plain JVM, which is why its unit tests need no
emulator. Android pieces plug in through small interfaces: `AgentHost`, `MemoryStore`,
`CapabilityProvider`, `Approver`, `Gfx`, `LlmClient`.

`AppGraph` builds every singleton once (hand-written dependency injection) and is reached
through `App.graph(context)`.

## Agent loop (`core/agent/AgentLoop`)

```
start(task)
 └─ loop while state == RUNNING:
     1. messages = PromptAssembler.build(system, persona, memories, skills,
                                         HistorySanitizer.sanitize(history))
     2. response = llm.stream(messages, tools)        ── retries with backoff on 429/5xx/IO
          └─ context overflow? → ContextCompactor.compact() → retry once
     3. no tool calls → final answer → COMPLETED
     4. for each call: ToolExecutor.execute(call)      ── validate, capability, approve, run
     5. loop detection: same (name, args) → same result twice → stop with explanation
     6. limits: maxSteps, maxToolCalls, maxDuration → stop with explanation (each limit can be
        switched to "no limit" in Settings; loop detection stays on)
 pause() / resume() / cancel() are honoured between steps and inside long tools
 (CancellationToken).
```

`TaskState.Status`: `PENDING → RUNNING ⇄ PAUSED / WAITING_APPROVAL → COMPLETED | FAILED |
CANCELLED | LIMIT_REACHED`.

## Streaming

`SseParser` splits `data:` events. `StreamAccumulator` merges `delta.content` and
`delta.tool_calls[i]` fragments, where arguments arrive as string pieces keyed by index, into
complete `ToolCall`s. It also handles providers that send everything in a single chunk, and
ones that omit `id`.

## Memory

- Storage: SQLite table `memories` with an FTS4 mirror for keyword search (`SqlMemoryStore`).
- Ranking (`MemoryRanker`): keyword overlap + importance + recency decay + pinned boost. Each
  pick carries a human-readable "why".
- Extraction (`MemoryExtractor`): after a task, candidate facts are de-duplicated against
  existing ones (update instead of insert), and secrets are rejected.
- Compaction (`ContextCompactor`): plans a cut that never splits an assistant tool-call message
  from its tool results. The summary is saved and the summarized messages are flagged in one
  transaction, so a crash cannot lose history.

## Database (`data/Db`, version 3)

Tables: `conversations`, `messages`, `memories` (+ `memories_fts`, FTS4 kept in sync by
triggers), `tasks`, `audit`, `providers`, `custom_tools`, `ssh_hosts`. A corrupt database is
backed up before it is recreated. Migrations run step by step from any older
version.

## Native code

`libyourfriend.so` (CMake, `c++_shared`). Everything here has a Java fallback, so the app works
the same if the library cannot load (`NativeAudio.isLoaded()`, shown on the About screen):

- `native_audio.cpp`: lip-sync analysis (RMS + Goertzel bands), identical to `LipSync.java`.
- `dsp_core.cpp` / `native_dsp.cpp`: the other audio hot loops: mouth shapes (`Visemes`, Goertzel
  for many frequencies in one SIMD-friendly pass), resampling, peak normalising, microphone
  level (RMS). `dsp_core.*` is plain C++ without JNI, so it is tested on a PC against the Java
  reference (max difference 3e-8).

What stays in Java on purpose: the UI, network and database work (they wait on I/O, C++ would not
make them faster), and the 3D renderer and spring-bone physics (tied to OpenGL state and the model
classes; moving them is possible but was not done).

The on-device OmniVoice bridge (`omnivoice_bridge.cpp`, `:tts` process) no longer exists.

## Character rendering

`RigParams` (about 25 floats modelled on Live2D's standard parameters: head angle X/Y/Z,
body sway, breath, eye open L/R, eye smile, gaze X/Y, brow height/form, mouth open/form/width,
cheek blush, hair spring outputs, overlay levels) → `CharacterAnimator` updates them every frame from state (idle, listening,
thinking, speaking), expressions, touches and lip sync → `PuppetRenderer` draws layered shapes
(back hair, body, neck, head, eyes, mouth, front hair, brows, accessory) through `Gfx`.
`AndroidGfx` implements `Gfx` on `Canvas`. `CharacterView` drives it with `Choreographer`.

## Threads

- UI thread: views and dialogs only.
- Agent: one worker thread per task, inside `AgentForegroundService` so Android keeps it alive.
- Tools that need the UI or accessibility post to the main thread and wait, with timeouts.
- Audio: synthesis on a worker thread; `AudioTrack` writes on its own thread.
