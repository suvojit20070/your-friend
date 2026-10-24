#!/usr/bin/env bash
# Your Friend — Original project by Suvo.
#
# Creates the release signing key ONCE (in Termux or any Linux) and prints the four values for
# GitHub → Settings → Secrets and variables → Actions:
#   YF_KEYSTORE_BASE64, YF_KEYSTORE_PASSWORD, YF_KEY_ALIAS, YF_KEY_PASSWORD
# The key stays in ~/yf-keys (outside the repository). Back it up: every update of the app must be
# signed with the same key, and a lost key cannot be recovered.
set -euo pipefail

if ! command -v keytool >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo "Installing Java (keytool)…"
    pkg install -y openjdk-17
  else
    echo "keytool not found: install a JDK first." >&2
    exit 1
  fi
fi

DIR="$HOME/yf-keys"
KS="$DIR/release.jks"
mkdir -p "$DIR"
chmod 700 "$DIR"
if [ -f "$KS" ]; then
  echo "A key already exists at $KS — reusing it (delete it yourself if you really want a new one)."
else
  read -r -p "Key alias [yourfriend]: " ALIAS
  ALIAS=${ALIAS:-yourfriend}
  while true; do
    read -r -s -p "Password (at least 6 characters): " PASS; echo
    read -r -s -p "Repeat password: " PASS2; echo
    [ "$PASS" = "$PASS2" ] && [ ${#PASS} -ge 6 ] && break
    echo "Passwords differ or are too short, try again."
  done
  read -r -p "Your name for the certificate [Suvo]: " CN
  CN=${CN:-Suvo}
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=$CN, O=Your Friend, C=IN"
  chmod 600 "$KS"
  printf '%s' "$ALIAS" > "$DIR/alias.txt"
fi
ALIAS=$(cat "$DIR/alias.txt" 2>/dev/null || echo yourfriend)
base64 -w0 "$KS" > "$DIR/release.jks.base64"

echo
echo "Done. Key: $KS"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  read -r -p "Upload the four secrets to GitHub now with gh? [y/N] " UP
  if [ "${UP:-n}" = "y" ]; then
    [ -n "${PASS:-}" ] || { read -r -s -p "Key password: " PASS; echo; }
    gh secret set YF_KEYSTORE_BASE64 < "$DIR/release.jks.base64"
    printf '%s' "$PASS" | gh secret set YF_KEYSTORE_PASSWORD
    printf '%s' "$ALIAS" | gh secret set YF_KEY_ALIAS
    printf '%s' "$PASS" | gh secret set YF_KEY_PASSWORD
    echo "Secrets uploaded. The next push builds a signed release APK."
    exit 0
  fi
fi
echo "Add these in GitHub → your repo → Settings → Secrets and variables → Actions → New repository secret:"
echo "  YF_KEYSTORE_BASE64  = the whole content of $DIR/release.jks.base64"
echo "  YF_KEYSTORE_PASSWORD = your password"
echo "  YF_KEY_ALIAS         = $ALIAS"
echo "  YF_KEY_PASSWORD      = your password"
echo "To copy the base64 text: termux-clipboard-set < $DIR/release.jks.base64   (needs the Termux:API app)"
