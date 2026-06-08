#!/bin/bash -e
#
# Linux / macOS / WSL equivalent of fetch-mpv-libdovi.ps1.
# Downloads the libdovi-enabled mpv-android-lib AAR into app/libs/.
#
#   cd android
#   ./scripts/fetch-mpv-libdovi.sh

TAG="${1:-mpv-libdovi-v1}"
URL="https://github.com/FortunasXP/mpv-android-libdovi/releases/download/${TAG}/mpv-android-lib-libdovi.aar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$PROJECT_ROOT/app/libs/mpv-android-lib-libdovi.aar"

if [ -f "$DEST" ] && [ "${FORCE:-0}" != "1" ]; then
    echo "AAR already present at $DEST ($(du -h "$DEST" | cut -f1)) — set FORCE=1 to re-download."
    exit 0
fi

mkdir -p "$PROJECT_ROOT/app/libs"
echo "Downloading from $URL"
echo "Destination: $DEST"
echo "Size: ~155 MB."

curl -fL --progress-bar -o "$DEST" "$URL"

echo "Done — $DEST ($(du -h "$DEST" | cut -f1))"
