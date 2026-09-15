#!/usr/bin/env bash
# Pull the official k2-fsa Android AAR into app/libs/.
#
# We don't vendor it in git — it's a ~50 MB binary. Run this once after
# cloning Transcriber-Android. CI should run it before
# `./gradlew assembleDebug`.

set -euo pipefail

PIN="1.13.8"   # last version verified against app/build.gradle.kts and the Kotlin call sites
ASSET="sherpa-onnx-${PIN}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${PIN}/${ASSET}"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
DEST="${DEST_DIR}/${ASSET}"

if [ -f "$DEST" ]; then
    echo "[fetch] $ASSET already present at $DEST"
    exit 0
fi

echo "[fetch] downloading $URL -> $DEST"
mkdir -p "$DEST_DIR"
curl -fL --progress-bar -o "${DEST}.partial" "$URL"
mv "${DEST}.partial" "$DEST"
echo "[fetch] done."
