#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
MODEL="$ASSETS/gesture_recognizer.task"
URL="https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
mkdir -p "$ASSETS"
if [[ -s "$MODEL" ]] && [[ "$(wc -c < "$MODEL")" -gt 1000000 ]]; then
  echo "Gesture model already present: $MODEL"
  exit 0
fi
curl --fail --location --retry 3 --connect-timeout 30 --max-time 180 "$URL" -o "$MODEL"
test "$(wc -c < "$MODEL")" -gt 1000000
echo "Gesture model downloaded."
