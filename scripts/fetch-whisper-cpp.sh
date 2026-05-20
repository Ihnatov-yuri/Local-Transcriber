#!/usr/bin/env bash
# Pull whisper.cpp into app/src/main/cpp/whisper.cpp/.
#
# We don't vendor whisper.cpp — it changes too often and is ~30 MB of source.
# Run this once after cloning Transcriber-Android. CI should run it before
# `./gradlew assembleDebug`.

set -euo pipefail

PIN="v1.7.4"   # last tag verified against the JNI shim's signatures
REPO_URL="https://github.com/ggml-org/whisper.cpp.git"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/whisper.cpp"

if [ -d "$DEST_DIR/.git" ]; then
    echo "[fetch] whisper.cpp already present at $DEST_DIR"
    echo "[fetch] checking out $PIN..."
    git -C "$DEST_DIR" fetch --tags --depth 1 origin "$PIN" || true
    git -C "$DEST_DIR" checkout --detach "$PIN"
    exit 0
fi

echo "[fetch] cloning whisper.cpp@$PIN -> $DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
git clone --depth 1 --branch "$PIN" "$REPO_URL" "$DEST_DIR"
echo "[fetch] done."
