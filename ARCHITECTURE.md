# Personal AI Agent App — Architecture

Ek on-device personal AI agent jiska chehra ek live animated character (anime girl / koi bhi custom character) hai. 24/7 active, device control (screenshot, mic, camera, files, shell), tools/skills system, local OmniVoice se awaaz, aur OpenAI-compatible LLM. Codename yahan **Aura** rakha hai — tum apna naam de dena.

---

## 0. Guiding rules
- **User consent-first:** har powerful permission (screen, mic, camera, files, shell) user khud deta hai, aur ek master kill-switch hamesha rahega. Ye ek transparent personal assistant hai, chhupi hui cheez nahi: foreground service + hamesha dikhne wala notification/overlay badge.
- **Characters:** app sirf **original ya user-imported** models ship karegi. Koi copyrighted anime character bundle nahi. Model format open standards: **Live2D Cubism** (2D, lip-sync + physics) aur optional **VRM** (3D).
- **Open-source first:** har bhaari cheez (voice, LLM runtime, render) swappable module hai. User chaahe toh apna implementation daal sake.

---

## 1. High-level layers

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION                                            │
│  - Overlay layer (character as floating window)         │
│  - Main app UI (chat, characters, skills, settings)      │
│  - Live2D/VRM renderer + lip-sync + touch focus          │
├─────────────────────────────────────────────────────────┤
│  AGENT CORE (the "brain")                                │
│  - Conversation loop (LLM + tool calling)                │
│  - Persona / system-prompt builder                       │
│  - Skill registry + tool dispatcher                      │
│  - Memory (short-term context + long-term store)          │
├─────────────────────────────────────────────────────────┤
│  CAPABILITY / TOOL PROVIDERS                             │
│  screen · input · camera · mic/STT · TTS(OmniVoice) ·    │
│  files · shell · web search · apps · notifications       │
├─────────────────────────────────────────────────────────┤
│  PLATFORM ACCESS                                         │
│  Accessibility · MediaProjection · Shizuku · SAF ·       │
│  CameraX · AudioRecord · Foreground services             │
└─────────────────────────────────────────────────────────┘
```

Rule: upar wali layer neeche wali ko **interface** se call karti hai, seedhe Android API se nahi. Isse har capability test aur replace karna aasaan rehta hai.

---

## 2. Modules (Gradle)

| Module | Kya | Language |
|---|---|---|
| `:app` | UI, overlay, navigation | Kotlin + Compose (ya Java+XML, neeche note) |
| `:core-agent` | LLM loop, tools, memory, persona | Kotlin/Java, no Android deps |
| `:capabilities` | screen/mic/camera/files/shell providers | Kotlin |
| `:voice` | OmniVoice (TTS) + STT wrappers | Kotlin + native |
| `:character` | Live2D/VRM render, lip-sync, motion, touch | Kotlin + C++/NDK |
| `:llm` | OpenAI-compatible client (stream + tools) | Kotlin |
| `:data` | DB (Room), settings, model/skill storage | Kotlin |

> **Stack note:** tum normally Java + XML pasand karte ho. Chat/settings screens aur agent core Java mein ho sakte hain. Lekin Live2D render, overlay aur smooth animation ke liye Kotlin + ek GL `SurfaceView`/`GLSurfaceView` zyada practical hai. Suggestion: UI aur core Java, sirf `:character` aur `:voice` ke native-heavy parts Kotlin. Decision tumhara.

---

## 3. Agent core (the loop)

```
user input (text / voice / tap / proactive trigger)
        │
        ▼
 build messages  ──►  [system prompt + persona + memory + recent turns]
        │
        ▼
 LLM call (stream, tools=enabled skills)
        │
   ┌────┴─────────────┐
   │ tool_call?       │
   ▼                  ▼
 run tool          final text
 (capability)         │
   │                  ▼
   └──► result ──► speak (OmniVoice) + lip-sync + emote
                      │
                      ▼
                 save to memory
```

- **Streaming:** pehla sentence aate hi bolna shuru (tumhare Tanu call jaisa) — awaaz ka wait nahi.
- **Tool calling:** OpenAI-compatible `tools` array. Har skill ek JSON schema deta hai.
- **Persona:** har character ke saath ek system-prompt template (naam, style, language, rules) + global agent rules.

### System prompt layers (build order)
1. **Agent base** — "tu is device pe chalne wala assistant hai, ye tools hain, safety rules…"
2. **Persona** — selected character ki personality, bolne ka style, language.
3. **Capabilities** — abhi kaunse tools ON hain (screen/mic/camera permission ke hisaab se live).
4. **Memory** — long-term facts + aaj ka context.
5. **Live data** — time, battery, foreground app, location (agar allowed).

---

## 4. Capabilities (tools) — Android ke liye kaise

Har capability ka ek `interface` + ek ya zyada implementations (permission ke hisaab se best available chun'ti hai).

| Capability | Best method | Fallback | Permission |
|---|---|---|---|
| **Screenshot / read screen** | Accessibility (view tree) | MediaProjection (pixel capture) | Accessibility service / cast consent |
| **Tap / swipe / type** | Accessibility `GestureDescription` + `performAction` | Shizuku `input` | Accessibility / Shizuku |
| **Open apps, buttons, back/home** | Accessibility global actions | — | Accessibility |
| **Camera (front/back)** | CameraX | — | CAMERA (runtime) |
| **Mic / listen** | AudioRecord → STT | — | RECORD_AUDIO |
| **Files (create/read/write)** | App storage + SAF (Storage Access Framework) | Shizuku / MANAGE_EXTERNAL_STORAGE | SAF grant |
| **Shell commands** | **Shizuku** (ADB-level, no root) | root if present | Shizuku bind |
| **Notifications read/reply** | NotificationListenerService | — | Notification access |
| **Web search** | HTTP to a search API | — | INTERNET |

**Shizuku** yahan key hai: bina root ke ADB-level shell deta hai (`pm`, `input`, `settings`, file ops wider scope). App ise bind karke ek privileged helper ke through commands chalati hai. User Shizuku ek baar start karta hai (wireless ADB ya root se), app uski permission maang leti hai.

**Tool contract (sabke liye same shape):**
```json
{
  "name": "take_screenshot",
  "description": "Capture the current screen and return what's on it",
  "danger": "low",
  "requires": ["screen"],
  "parameters": { "type": "object", "properties": {} }
}
```
`danger` level se UI decide karta hai ki pehli baar confirm maange ya auto chale.

---

## 5. Skills system (tools user khud add kar sake)

Do tarah ki skills:

1. **Built-in skills** — code mein, native capabilities ko wrap karti hain (screenshot, tap, file_write, shell, camera_capture…).
2. **User skills** — ek folder mein JSON/JS files. User "quick web search" jaisa tool khud bana le.

**User skill format (declarative, safe):**
```json
{
  "name": "web_search",
  "description": "Search the web and return top results",
  "language_hint": "any",
  "action": {
    "type": "http",
    "method": "GET",
    "url": "https://api.example.com/search?q={{query}}&key={{secret.SEARCH_KEY}}",
    "pick": "results[].title"
  },
  "parameters": {
    "type": "object",
    "properties": { "query": { "type": "string" } },
    "required": ["query"]
  }
}
```
- `action.type`: `http` | `shell` | `intent` (open app/url) | `script` (sandboxed JS).
- `{{secret.X}}` ko app ke secure store se bharti hai, LLM ko kabhi nahi dikhata.
- **Script skills** QuickJS/Rhino jaise sandbox mein chalein, bina device API ke — sirf input→output. Device cheezein sirf built-in capabilities se.

Skill registry startup pe folder scan karta hai, schemas banata hai, aur jo ON hain woh LLM ke `tools` mein jaati hain.

---

## 6. Character layer (live model)

```
CharacterController
 ├─ Renderer (Live2D Cubism SDK / VRM via filament or three-on-ndk)
 ├─ LipSync   (OmniVoice audio amplitude → mouth param)
 ├─ MotionEngine (idle, talk, emote, drag, react-to-touch)
 ├─ FocusTracker (screen tap/area → head/eyes turn that way)
 └─ Expression map (emotion tag from LLM → face/motion)
```

- **Overlay:** `TYPE_APPLICATION_OVERLAY` window (SYSTEM_ALERT_WINDOW permission). Transparent, draggable, screen pe kahin bhi.
- **Move around screen:** character khud bhi position change kar sake — MotionEngine ke paas "walk to x,y" command ho, aur LLM ise emote ki tarah trigger kare.
- **Touch focus:** jab user screen pe kahin tap/click kare, FocusTracker us direction mein sir/aankh ghuma de. (Tap event accessibility ya overlay touch se.)
- **Lip-sync:** OmniVoice PCM output ki amplitude har frame mouth-open param pe map — simple aur reliable.
- **Emotion:** LLM reply ke saath ek chhota tag deta hai (`<emote:happy>` / JSON field), MotionEngine use face + body motion pe map karta hai.
- **Multiple / custom characters:** har character ek folder — model files + `persona.json` + `voice.json` (OmniVoice ref) + `motion_map.json`. "New character" = naya folder + model import. Girl, boy, mascot, kuch bhi.

---

## 7. Voice

- **TTS = OmniVoice local** (jaise tumne banaya). On-device inference heavy hai; isliye `:voice` module ek interface deta hai:
  - `LocalOmniVoiceTts` — on-device (jahan device capable ho).
  - `RemoteOmniVoiceTts` — tumhari HF Space / server (fallback jab phone slow ho).
- **Voice output pehle:** reply ka pehla sentence banते hi play, lip-sync shuru.
- **STT (sunna):** on-device (Vosk/whisper.cpp) ya remote Whisper — interface se swappable.
- **Per-character voice:** `voice.json` mein ref clip + language default.

---

## 8. LLM

- **OpenAI-compatible client:** user `base_url`, `api_key`, `model` deta hai (multiple providers save kar sake, switch kare).
- Streaming + tool-calling support. Non-streaming providers ke liye fallback (Tanu wala pattern).
- Per-character model override optional.

---

## 9. Languages (app + agent)

- **App UI:** English, Hinglish, Bangla, Hindi, Japanese — standard Android `values-xx/strings.xml` (Hinglish = custom `values-b+en+IN` ya ek alag locale key).
- **Agent output:** language persona + user preference se decide; OmniVoice ko sahi language code jaata hai (tumhare Tanu wale detect logic jaisa).

---

## 10. Always-on (24/7)

- **Foreground service** (type `specialUse` / `microphone` jab sun raha ho) — OS kill na kare.
- Battery optimization exemption (user allow kare).
- Wake triggers: hotword (optional, on-device), overlay tap, notification, schedule, ya ongoing mic session.
- **Privacy:** mic/camera jab active ho, overlay pe clear indicator + Android ka apna dot. Ek tap kill-switch.

---

## 11. Data / storage

- **Room DB:** conversations, memory facts, characters meta, skills meta, audit log (kis tool ne kab kya kiya).
- **Files:** `/characters/<id>/…`, `/skills/<id>.json`, `/models/` (voice/stt), secure store (EncryptedSharedPreferences) for API keys & skill secrets.
- **Audit log zaroori:** har privileged action (shell, screenshot, file write) log ho — user dekh sake agent ne kya kiya.

---

## 12. Safety / guardrails (apne device par bhi)

- Har `danger: high` tool (shell, file delete, send money/app actions) pehli baar explicit confirm.
- "Autonomous mode" optional — tab bhi high-danger pe rok.
- Secrets LLM ko kabhi nahi.
- Master switch: sab capabilities ek jagah se off.

---

## 13. License / credit (tumhari requirement)

- Suggest: **custom source-available license** ya permissive (Apache-2.0) **+ ek NOTICE/attribution clause** jo kehti hai: koi bhi fork/redistribution mein original author (tum) ka credit hataya nahi jayega.
- Ek `LICENSE` + `NOTICE` file, aur app ke "About" screen mein hardcoded credit line.
- Dhyan: open-source dependencies (Live2D SDK ki apni licence hai, OmniVoice Apache-2.0, etc.) ki licences `NOTICE` mein rakhni hongi — ye tumhari apni licence se alag hai.

---

## 14. Build order (phases)

1. **Skeleton:** app + core-agent + LLM client. Text chat + tool loop working (no device control yet).
2. **Voice:** OmniVoice TTS + STT. Bolna/sunna.
3. **Character:** Live2D render in overlay + lip-sync + idle/talk motion. Draggable.
4. **Capabilities v1:** screenshot (accessibility), tap/type, open app.
5. **Shizuku + files + shell.** Camera + mic tools.
6. **Skills system:** registry + user HTTP/intent skills + web search example.
7. **Multi-character + custom import + per-character persona/voice.**
8. **Touch focus, move-around, emotions, polish, languages, licence.**

---

## Open questions (inpe decide karna hai)
1. **Render:** Live2D (2D, lighter, lip-sync easy) vs VRM/3D (bhaari). Dono support karein ya 2D se shuru?
2. **Stack:** core/UI Java rakhna hai, ya pura Kotlin+Compose (is scale pe zyada aasaan)?
3. **OmniVoice:** on-device target karna hai (kaafi powerful phone chahiye), ya pehle remote se, baad mein on-device?
4. **Distribution:** sirf apne liye, ya Play Store / APK public? (Store pe accessibility+shell wali app ki policy sakht hai — side-load zyada realistic.)
