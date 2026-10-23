#!/data/data/com.termux/files/usr/bin/bash
set -u
cd "$(dirname "$0")"
TOK=$(python3 -c 'import secrets;print(secrets.token_hex(32))')
LOG="${TMPDIR:-/data/data/com.termux/files/usr/tmp}/neo-test.log"
NEO_TOKEN="$TOK" NEO_ENV=dev .venv/bin/python -m uvicorn main:app \
  --host 127.0.0.1 --port 8787 --log-level warning >"$LOG" 2>&1 </dev/null &
PID=$!
trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null' EXIT
sleep 4
echo "--- public health"
curl -s -m 8 http://127.0.0.1:8787/health; echo
echo "--- no token (expect 401)"
curl -s -m 8 -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8787/health/full
echo "--- full"
curl -s -m 8 http://127.0.0.1:8787/health/full -H "Authorization: Bearer $TOK"; echo
echo "--- chat: torch"
curl -s -m 15 -X POST http://127.0.0.1:8787/chat -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' -d '{"message":"torch on karo","language":"hi-IN"}'; echo
echo "--- chat: instagram profile"
curl -s -m 15 -X POST http://127.0.0.1:8787/chat -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' -d '{"message":"Instagram par rehan ki profile kholo","language":"hi-IN"}'; echo
echo "--- weather"
curl -s -m 20 "http://127.0.0.1:8787/weather?lat=28.61&lon=77.21" -H "Authorization: Bearer $TOK"; echo
echo "--- tts (503 expected without key)"
curl -s -m 20 -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8787/tts \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"text":"Namaste"}'
echo "--- production HTTPS enforcement"
NEO_TOKEN="$TOK" NEO_ENV=production NEO_REQUIRE_HTTPS=1 .venv/bin/python -m uvicorn main:app \
  --host 127.0.0.1 --port 8788 --log-level warning >"$LOG.2" 2>&1 </dev/null &
PID2=$!
trap 'kill $PID $PID2 2>/dev/null; wait $PID $PID2 2>/dev/null' EXIT
sleep 4
echo -n "http:  "; curl -s -m 8 -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/health/full -H "Authorization: Bearer $TOK"
echo -n "https: "; curl -s -m 8 -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/health/full -H "Authorization: Bearer $TOK" -H 'X-Forwarded-Proto: https'
echo "--- log tail"
tail -4 "$LOG"
