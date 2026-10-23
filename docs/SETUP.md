# Setup guide

Everything below is optional except a provider. The app works as a plain chat assistant with
just a provider. Each extra capability unlocks more tools.

## 1. Install the APK

- From CI: GitHub → Actions → latest successful run → Artifacts → `YourFriend-debug-apk`.
  Unzip it and install `app-debug.apk`. Android asks you to allow installs from your browser or
  file manager.
- From a local build: `./gradlew installDebug` with the phone connected over ADB.

## 2. Provider

Settings → Providers → Add. Any OpenAI-compatible `/chat/completions` endpoint works.

| Service | Base URL |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| Together | `https://api.together.xyz/v1` |
| Ollama on your PC | `http://<pc-lan-ip>:11434/v1` |
| LM Studio on your PC | `http://<pc-lan-ip>:1234/v1` |
| llama.cpp server | `http://<pc-lan-ip>:8080/v1` |

On a phone, `localhost` means the phone itself. Use the PC's LAN IP and make sure the server
listens on `0.0.0.0`. Plain `http://` works (needed for LAN servers), but use `https://` for
anything on the internet.

Use **Test** in the editor to check the key and model, and **Fetch models** to list what the
endpoint offers. Set the context window and switch on tools/vision to match the model.

## 3. Accessibility (screen reading and control)

1. Permissions → Accessibility → Open settings.
2. Installed apps / Downloaded apps → **Your Friend** → turn it on and confirm.
3. On Android 13+, sideloaded apps are blocked from accessibility at first. If the switch is
   grey: App info → ⋮ → **Allow restricted settings**, then try again.
4. Some phones (Xiaomi, Oppo, Vivo, Samsung) stop accessibility services to save battery. Set
   Your Friend's battery usage to *Unrestricted*.

The service reads the screen only when a tool asks. It never records typed text or password
fields.

## 4. Screen capture (fallback screenshots)

On Android 11+ screenshots go through the accessibility service. On older versions, or if that
fails, the app asks for MediaProjection consent. Android shows its own dialog, and you must
approve it every session. The app cannot skip that dialog.

## 5. Shizuku (ADB-level control)

1. Install Shizuku (Play Store or GitHub release).
2. Start it: wireless debugging (Android 11+) or root. Without root, start it again after
   every reboot.
3. Permissions → Shizuku → Request. Approve in Shizuku's dialog.

Shizuku enables `screen_operation_shell` (input tap/swipe/text/keyevent through `input`,
`uiautomator dump`) and running privileged shell commands. These are medium risk or higher.

## 6. Termux (code runtimes)

1. Install Termux from F-Droid or GitHub. The Play Store build is outdated and does not work.
2. In Termux:
   ```bash
   pkg update && pkg install python
   mkdir -p ~/.termux
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   termux-reload-settings
   ```
3. Android Settings → Apps → Your Friend → Permissions → Additional permissions →
   **Run commands in Termux environment** → Allow.
4. Optional runtimes: `pkg install clang` (C/C++), `pkg install openjdk-17` (Java),
   `pkg install nodejs`.
5. Your Friend → Terminal → Runtimes shows what is detected, and can install missing
   runtimes through Termux.

## 7. Floating companion

Settings → Floating companion → allow **Display over other apps**. Drag the character
anywhere; it snaps to the nearest edge. With accessibility on, it can point at the screen
element the agent is about to touch.

## 8. Voice

See [VOICE.md](VOICE.md).

## 9. Notifications

On Android 13+, allow notifications so approval requests reach you while the app is in the
background. Long tasks run in a foreground service with a persistent notification.
