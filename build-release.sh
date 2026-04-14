#!/usr/bin/env bash
# Build a release APK, read version from app/build.gradle, copy + rename output.
#
# Usage: ./build-release.sh
#        ./build-release.sh --serve   # Also start/restart the local HTTP server on :8222

set -e

cd "$(dirname "$0")"

# Extract versionName from app/build.gradle
VERSION=$(grep 'versionName' app/build.gradle | head -1 | sed -E 's/.*versionName[[:space:]]+"([^"]+)".*/\1/')

if [ -z "$VERSION" ]; then
    echo "Error: could not extract versionName from app/build.gradle"
    exit 1
fi

echo "Building nstrfy $VERSION..."

nix develop /home/vcavallo/src/NarChives --command bash -c "./gradlew clean assembleRelease"

APK_SRC="app/build/outputs/apk/release/app-release.apk"
APK_DST="nstrfy-${VERSION}.apk"

if [ ! -f "$APK_SRC" ]; then
    echo "Error: build output not found at $APK_SRC"
    exit 1
fi

cp "$APK_SRC" "$APK_DST"
SIZE=$(ls -lh "$APK_DST" | awk '{print $5}')
SHA=$(sha256sum "$APK_DST" | awk '{print $1}')

echo ""
echo "=================================================="
echo "  Built: $APK_DST ($SIZE)"
echo "  SHA-256: $SHA"
echo "=================================================="

if [ "$1" = "--serve" ]; then
    echo ""
    echo "Restarting HTTP server on :8222..."
    pkill -f "python3 -m http.server 8222" 2>/dev/null || true
    sleep 1
    nohup nix-shell -p python3 --run "python3 -m http.server 8222" > /dev/null 2>&1 &
    sleep 2
    echo "Available at: http://$(hostname -I | awk '{print $1}'):8222/$APK_DST"
fi
