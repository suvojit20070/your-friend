#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (AAR, Apache-2.0) that offline speech recognition uses.
# Run by CI before the build. Pinned version; bump SHERPA_VERSION to update.
set -euo pipefail
SHERPA_VERSION="1.13.7"
DEST="$(dirname "$0")/../../app/libs/sherpa-onnx.aar"
mkdir -p "$(dirname "$DEST")"
if [ -s "$DEST" ]; then echo "sherpa-onnx.aar already present"; exit 0; fi
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
echo "Downloading $URL"
curl -fL --retry 3 -o "$DEST.part" "$URL"
mv "$DEST.part" "$DEST"
ls -la "$DEST"
