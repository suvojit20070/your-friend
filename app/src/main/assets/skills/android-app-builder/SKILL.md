---
name: Android app builder
description: Build a real installable Android app (APK) on this phone in Termux, without Android Studio or Gradle, then install it.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [android app, apk, build app, make an app, app banao, app bana, chat bot app, chatbot app, android project]
required_tools: [shell, file_write]
---

# Building an Android app on the phone

You CAN build APKs here: Termux has the Android build tools as packages. Do not tell the user you
have no build environment — set it up. Run every command with the `shell` tool on the **termux**
backend and check each exit code before going on. Work in `~/apps/<name>` inside Termux.

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
- Write the files with `file_write` (or a heredoc in `shell`). Keep code small and complete.
- Network calls must run on a background `Thread`; update views with `runOnUiThread`.
- Never put the user's API keys in the source. A chat bot app should have a settings screen (or a
  first-run dialog) where the user types the endpoint, model and key; store them in SharedPreferences.

## 3. Build

```sh
cd ~/apps/chatbot
ANDROID_JAR=$PREFIX/share/aapt/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 24 --target-sdk-version 28 -o build/app-unsigned.apk build/res.zip
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes \
  $(find src build/gen -name "*.java")
d8 --lib "$ANDROID_JAR" --min-api 24 --output build/dex $(find build/classes -name "*.class")
cd build/dex && zip -q -u ../app-unsigned.apk classes.dex && cd ../..
```
If a step fails, read the error, fix the file it names, and run the build again from that step.

## 4. Sign and hand over

```sh
[ -f ~/apps/debug.jks ] || keytool -genkeypair -keystore ~/apps/debug.jks -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug"
apksigner sign --ks ~/apps/debug.jks --ks-pass pass:android --out build/app.apk build/app-unsigned.apk
apksigner verify build/app.apk && cp build/app.apk /sdcard/Download/chatbot.apk
```
Then install it with `py_install_apk` (the user approves the install) or tell the user it is in
Download. Report the real result: the APK path, its size, and what the app does.

## Notes
- Building a big Gradle project (Android Studio template) on the phone is not practical; generate a
  small framework-only project as above instead. For large projects, suggest GitHub Actions.
- If `pkg` cannot install a package, run `pkg update` once and retry, then explain what failed.
