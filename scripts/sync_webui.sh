#!/usr/bin/env bash
# Copy the WebView UI from the Android assets into backend/webui so it can be
# hot-published via /app/manifest. Run this after editing assets/web/*.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main/assets/web"
DST="$ROOT/backend/webui"

[ -d "$SRC" ] || { echo "missing $SRC"; exit 1; }
mkdir -p "$DST"
rm -rf "$DST"/*
cp -a "$SRC"/. "$DST"/

echo "published $(find "$DST" -type f | wc -l) files -> backend/webui"
find "$DST" -type f -printf '  %f  %s bytes\n' | sort
