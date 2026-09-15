#!/usr/bin/env bash
# Build, verify, and publish a release APK (docs/PLAN-2026-09.md §6).
#
#   scripts/release.sh            # build + verify + rename, no publish
#   scripts/release.sh --publish  # also `gh release create v<version>`
#   NOTES_FILE=notes.md scripts/release.sh --publish   # release notes body
#
# Needs: JAVA_HOME (Android Studio's JBR works), the Android SDK's
# build-tools on disk (for apksigner/zipalign), release signing keys in
# local.properties (see app/build.gradle.kts), and `gh auth login` for
# --publish. Refuses to publish an unsigned APK.

set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)"
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts | head -1)"
[ -n "$VERSION" ] && [ -n "$CODE" ] || { echo "could not read versionName/versionCode"; exit 1; }
echo "[release] version $VERSION (code $CODE)"

echo "[release] unit tests"
./gradlew :app:testDebugUnitTest --console=plain -q

echo "[release] assembleRelease"
./gradlew :app:assembleRelease --console=plain -q

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
    echo "[release] no signed APK at $APK (found: $(ls app/build/outputs/apk/release/*.apk 2>/dev/null))"
    echo "[release] add release.* keys to local.properties — refusing to ship unsigned."
    exit 1
fi

echo "[release] verifying signature + alignment"
"$BT/apksigner" verify --print-certs "$APK" | head -3
"$BT/zipalign" -c -P 16 4 "$APK" && echo "[release] 16 KB page alignment OK"

OUT_DIR="app/build/outputs/release"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/transcriber-${VERSION}-arm64.apk"
cp "$APK" "$OUT"
shasum -a 256 "$OUT" | tee "$OUT.sha256"
echo "[release] $OUT"

if [ "${1:-}" = "--publish" ]; then
    TAG="v${VERSION}"
    NOTES="${NOTES_FILE:-}"
    if [ -n "$NOTES" ]; then
        gh release create "$TAG" "$OUT" "$OUT.sha256" --title "Transcriber for Android $VERSION" --notes-file "$NOTES"
    else
        gh release create "$TAG" "$OUT" "$OUT.sha256" --title "Transcriber for Android $VERSION" --generate-notes
    fi
    echo "[release] published $TAG"
fi
