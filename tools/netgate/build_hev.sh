#!/usr/bin/env bash
# Builds hev-socks5-tunnel (MIT, https://github.com/heiher/hev-socks5-tunnel): the small native
# "tun2socks" engine Net Gate uses to send the allowed apps' traffic through the user's proxy.
# Run by CI before the Gradle build; the .so files land in app/src/main/jniLibs (not in the repo).
# Pinned version; bump HEV_VERSION to update. The JNI class is the default hev.htproxy.TProxyService.
set -euo pipefail
HEV_VERSION="2.18.0"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
if ls "$OUT"/*/libhev-socks5-tunnel.so >/dev/null 2>&1; then echo "hev-socks5-tunnel already built"; exit 0; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK="$SDK/ndk/27.2.12479018"
[ -x "$NDK/ndk-build" ] || { echo "ndk-build not found in $NDK"; exit 1; }
WORK="$(mktemp -d)"
# The README's layout: the sources must be in a folder named "jni".
git clone --depth 1 --branch "$HEV_VERSION" --recursive --shallow-submodules https://github.com/heiher/hev-socks5-tunnel "$WORK/jni"
cd "$WORK"
"$NDK/ndk-build" -j"$(nproc)" APP_ABI="arm64-v8a armeabi-v7a x86 x86_64" APP_PLATFORM=android-26
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  mkdir -p "$OUT/$abi"
  cp "libs/$abi/libhev-socks5-tunnel.so" "$OUT/$abi/"
done
ls -la "$OUT"/*/libhev-socks5-tunnel.so
