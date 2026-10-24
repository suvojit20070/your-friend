---
name: Android app builder
description: Build a real installable Android app (APK) on this phone in Termux, without Android Studio or Gradle, then install it.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [android app, apk, build app, make an app, app banao, app bana, chat bot app, chatbot app, android project]
required_tools: [shell]
---

# Building an Android app on the phone

You CAN build APKs here: Termux has the Android build tools as packages. Do not tell the user you
have no build environment — set it up. Run every command with the `shell` tool on the **termux**
backend and check each exit code before going on.

## 0. Where the files live (most important)
- The project lives in ONE folder inside Termux: `~/apps/<name>` (absolute: `/data/data/com.termux/files/home/apps/<name>`).
- Write and edit every project file with the `shell` tool (termux) using heredocs, e.g.
  `mkdir -p ~/apps/chatbot/src/com/example/chatbot && cat > ~/apps/chatbot/src/com/example/chatbot/MainActivity.java <<'EOF' ... EOF`.
- Do NOT use `file_write`/`file_edit` for it: those write to Your Friend's private workspace, which
  Termux cannot see — the build would silently use old files.
- Keep ONE layout (the flat one below). Never start a second copy (e.g. `src/main/...`) next to it.
  If you change the layout, move the files and delete the old ones in the same step.

## 1. Tools (once)

```sh
pkg install -y openjdk-17 aapt aapt2 apksigner d8 zip
ls $PREFIX/share/aapt/android.jar    # the Android API jar used to compile against
```
If `android.jar` is missing, find it: `find $PREFIX -name android.jar | head`. Use that path as `ANDROID_JAR`.

## 2. Project layout

```
~/apps/chatbot/
  AndroidManifest.xml
  res/layout/activity_main.xml
  res/values/strings.xml
  src/com/example/chatbot/MainActivity.java
```
- Plain `android.app.Activity` and framework views only (no AndroidX, no Kotlin, no Gradle libraries).
- Manifest: `package="com.example.chatbot"`, `<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28"/>`
  (target 28 keeps the packaging simple), the activity with MAIN/LAUNCHER, and
  `<uses-permission android:name="android.permission.INTERNET"/>` if it talks to the network.
- Write the files with `shell` heredocs inside the project folder (see step 0). Keep code small and complete.
- Network calls must run on a background `Thread`; update views with `runOnUiThread`.
- Never put the user's API keys in the source. A chat bot app should have a settings screen (or a
  first-run dialog) where the user types the endpoint, model and key; store them in SharedPreferences.

## 3. Build script (write it once as ~/apps/<name>/build.sh)

```sh
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail                      # stop at the first error — never report a build that failed
cd "$(dirname "$0")"                   # always build THIS folder
echo "Building in: $(pwd)"
ANDROID_JAR="$PREFIX/share/aapt/android.jar"
[ -f "$ANDROID_JAR" ] || ANDROID_JAR=$(find "$PREFIX" -name android.jar | head -1)
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 24 --target-sdk-version 28 -o build/app-unsigned.apk build/res.zip
LIBS=$(ls libs/*.jar 2>/dev/null | tr '\n' ':' || true)
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "${LIBS:-.}" -d build/classes \
  $(find src build/gen -name "*.java")
# Library jars are INPUTS (packed into the APK). Passing them with --lib leaves them out → crash.
d8 --lib "$ANDROID_JAR" --min-api 24 --output build/dex \
  $(find build/classes -name "*.class") $(ls libs/*.jar 2>/dev/null || true)
(cd build/dex && zip -q -u ../app-unsigned.apk classes*.dex)
```
Run it with `bash ~/apps/<name>/build.sh`. If it fails, read the error, fix the file it names, run again.
- Prefer no libraries: `HttpURLConnection` + `org.json` are built in. OkHttp needs okio + Kotlin
  stdlib jars too; Gson is fine as a single jar in `libs/`.
- Before every rebuild that the user will install, raise `android:versionCode` (and versionName)
  in AndroidManifest.xml so Android installs it as an update.

## 4. Sign and hand over

```sh
[ -f ~/apps/debug.jks ] || keytool -genkeypair -keystore ~/apps/debug.jks -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug"
apksigner sign --ks ~/apps/debug.jks --ks-pass pass:android --out build/app.apk build/app-unsigned.apk
apksigner verify build/app.apk
aapt2 dump badging build/app.apk | head -2   # must show the new versionCode and your package
OUT=/sdcard/Download/chatbot_v$(grep -o 'versionCode="[0-9]*' AndroidManifest.xml | grep -o '[0-9]*$').apk
cp build/app.apk "$OUT" && ls -la "$OUT"
```
Check that `dump badging` shows the versionCode you set — if not, you built a different folder.
Then install it with `py_install_apk` (the user approves the install) or tell the user it is in
Download. Report the real result: the APK path, its size, and what the app does.

## Notes
- Building a big Gradle project (Android Studio template) on the phone is not practical; generate a
  small framework-only project as above instead. For large projects, suggest GitHub Actions.
- If `pkg` cannot install a package, run `pkg update` once and retry, then explain what failed.
