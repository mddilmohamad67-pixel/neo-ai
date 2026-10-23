#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"

TOKEN="${NEO_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f .env ]; then
  TOKEN=$(grep -E '^NEO_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"' | xargs || true)
fi
if [ -z "$TOKEN" ] && [ -f .neo_token ]; then
  TOKEN=$(cat .neo_token)
fi
if [ -z "$TOKEN" ]; then
  echo "No token found (.env NEO_TOKEN or .neo_token). Start the server once."
  exit 1
fi

BASE="${NEO_URL:-http://127.0.0.1:8787}"
hit() {
  echo
  echo "→ $1"
  shift
  curl -s -m 15 "$@"
  echo
}

hit "health" "$BASE/health" -H "X-Neo-Token: $TOKEN"

hit "intent: torch" -X POST "$BASE/chat" \
  -H "X-Neo-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"torch on karo","language":"hi-IN"}'

hit "intent: call" -X POST "$BASE/chat" \
  -H "X-Neo-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"Sonia ko call karo","language":"hi-IN"}'

hit "intent: youtube search" -X POST "$BASE/chat" \
  -H "X-Neo-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"YouTube par lofi search karo","language":"hi-IN"}'

hit "weather" "$BASE/weather?lat=28.61&lon=77.21" -H "X-Neo-Token: $TOKEN"

hit "tts status (503 = Android TTS fallback, expected without key)" \
  -o /dev/null -w '%{http_code}' -X POST "$BASE/tts" \
  -H "X-Neo-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"Namaste, main NEO hoon."}'

echo
echo "Done. If OpenAI is configured, replies should be natural Hinglish;"
echo "otherwise device intents still parse via the built-in rule engine."
