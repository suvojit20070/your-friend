# Voice

## Engines

| Engine | Where it runs | Cloning | Status |
|---|---|---|---|
| Hugging Face Space | Your Gradio Space online (e.g. OmniVoice on ZeroGPU) | ✅ ref.wav + ref.txt | Code complete, not tested on a device |
| OmniVoice server | Your PC or LAN server | ✅ | Code complete, not tested on a device |
| Android system TTS | On the phone | ❌ | Standard Android API |

Pick one in **Settings → Voice**. The default is the phone's own voice (Android system TTS),
so the app speaks right away. The status line shows "ready" or exactly what is missing, and the
app does not switch engines by itself.

The on-device OmniVoice engine (audio.cpp, imported `.so` and GGUF model, `:tts` process) was
**removed**: it was heavy, fragile and slow on phones. If you had chosen it, the app now uses
the system voice, and the models and libraries it stored are deleted at the next start.

## Reference voice (cloning)

Settings → Voice → Reference voice:

- `ref.wav`: 3–10 seconds of one speaker with little background noise. **Record** it in the app
  or **Import** a WAV file. It is converted to mono 24 kHz (OmniVoice's rate) and can be played
  back.
- `ref.txt`: the exact words spoken in the clip. Type it or import a `.txt` file. A wrong
  transcript is the most common cause of bad cloning.
- Optional: language, a style instruction, speed, and generation steps (quality vs. speed).

Only clone voices you have the right to use.

## Option A — omnivoice-server

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
- Mouth shapes (a / i / u / e / o from formant estimates, `Visemes`) also run in C++
  (`dsp_core.cpp`), with `Visemes.java` as the fallback. On a desktop build the two agree within
  3e-8, and the C++ one is about 5x faster (a 3 s clip: ~14 ms versus ~70–90 ms). Phone timings
  have not been measured. Resampling, normalising and the microphone level use C++ as well.
- The character reads the frame at the `AudioTrack` playback head position, so the mouth stays
  in sync even when playback stalls.
- System TTS is rendered to a WAV file first (`synthesizeToFile`) and then played the same way,
  so lip sync works with it too.

## Speech input

The microphone button uses Android's `SpeechRecognizer`. Hands-free mode (Settings → Voice, asks for consent first)
keeps listening while a foreground notification is shown, and stops when you turn it off. The mic is never opened without that notification.
