# Voice

## Engines

| Engine | Where it runs | Cloning | Status |
|---|---|---|---|
| OmniVoice (audio.cpp) | On the phone, through a native library you import | ✅ ref.wav + ref.txt | Code complete, not tested on a device |
| OmniVoice server | Your PC or LAN server | ✅ | Code complete, not tested on a device |
| Android system TTS | On the phone | ❌ | Standard Android API |

Pick one in **Settings → Voice**. The default is OmniVoice on device. The status line shows
"ready" or exactly what is missing, and the app does not switch engines by itself. Choose
System TTS if you just want a voice right away.

## Reference voice (cloning)

Settings → Voice → Reference voice:

- `ref.wav`: 3–10 seconds of one speaker with little background noise. **Record** it in the app
  or **Import** a WAV file. It is converted to mono 24 kHz (OmniVoice's rate) and can be played
  back.
- `ref.txt`: the exact words spoken in the clip. Type it or import a `.txt` file. A wrong
  transcript is the most common cause of bad cloning.
- Optional: language, a style instruction, speed, and generation steps (quality vs. speed).

Only clone voices you have the right to use.

## Option A — on device with audio.cpp

[audio.cpp](https://github.com/0xShug0/audio.cpp) (Apache-2.0) is a C++ inference library with a
C API (`include/audiocpp.h`, ABI 0.2) that can run OmniVoice from a GGUF file.

Your Friend does **not** bundle it. The JNI bridge (`app/src/main/cpp/omnivoice_bridge.cpp`)
loads it at runtime with `dlopen` and checks the ABI version before calling anything.

1. Build `libaudiocpp.so` for your phone's ABI (almost always `arm64-v8a`):
   - GitHub → Actions → **Build audio.cpp for Android (experimental)** → Run workflow. It cross-compiles
     audio.cpp with the NDK and uploads the `.so` files as an artifact (tested: builds in ~15 min).
     Pick **cpu_arch** `armv8.2-a+dotprod+fp16` for almost every phone from 2018 on — the plain
     `armv8-a` build has no int8 dot product / fp16 math and speaks several times slower. Use
     `armv8.6-a+dotprod+fp16+i8mm` only on the newest chips; a phone without a feature crashes with
     SIGILL (the app then says so).
   - Or build it yourself with the NDK's CMake toolchain:
     `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DAUDIOCPP_BUILD_C_API=ON -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`.
   - The engine runs in its own process: if it crashes or runs out of memory the app keeps running
     and Voice settings show the reason. On a phone, 16 quality steps and a 3–6 s reference clip
     keep speaking reasonably fast.
2. Get an OmniVoice GGUF model (convert it with audio.cpp's tools, or download one that is
   already converted). **The OmniVoice weights are CC BY-NC 4.0, non-commercial only.**
3. Settings → Voice → OmniVoice on device → **Import libraries** (`libaudiocpp.so` plus any
   `.so` files it depends on) and **Model** (`.gguf`). The files are copied into app-private
   storage. You can set the thread count and remove the libraries again.
4. Voice → Test.

What to expect: OmniVoice is a large model. On mid-range phones generation may be slower than
real time, and it needs several GB of free RAM. None of this has been measured yet.

## Option B — omnivoice-server

[omnivoice-server](https://github.com/maemreyo/omnivoice-server) (MIT) wraps OmniVoice in an
OpenAI-style HTTP API.

1. On your PC (NVIDIA GPU recommended), follow its README to install and start it. Make it
   listen on your LAN, for example `--host 0.0.0.0 --port 8880`.
2. Settings → Voice → Server URL: `http://<pc-lan-ip>:<port>`. **Check** tests the connection.
3. Mode:
   - **Clone** (`clone_multipart`): `POST /v1/audio/speech/clone`, multipart with `text`,
     `ref_audio` = your ref.wav and `ref_text` = your transcript.
   - **OpenAI speech** (`openai_speech`): `POST /v1/audio/speech` with JSON `model`, `input`,
     `voice` (a voice name the server knows), `response_format: wav`.
4. Voice → Test.

Text you speak is sent to that server. Use one you control.

## Lip sync

Mouth movement comes from the audio actually being played:

- Each 1/60 s window gives an RMS amplitude (mouth open) and Goertzel band energies in the
  low, mid and high bands (mouth shape: round "o/u" vs wide "e/i").
- The analysis runs in native code (`native_audio.cpp`), with a Java fallback (`LipSync.java`).
  The Java version is unit-tested; the native version was checked against it on a desktop
  build and matched within 6e-8.
- The character reads the frame at the `AudioTrack` playback head position, so the mouth stays
  in sync even when playback stalls.
- System TTS is rendered to a WAV file first (`synthesizeToFile`) and then played the same way,
  so lip sync works with it too.

## Speech input

The microphone button uses Android's `SpeechRecognizer`. Hands-free mode (Settings → Voice, asks for consent first)
keeps listening while a foreground notification is shown, and stops when you turn it off. The mic is never opened without that notification.
