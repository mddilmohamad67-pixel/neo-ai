#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1
if curl -s -m 2 http://127.0.0.1:8787/health >/dev/null 2>&1; then
  echo "NEO backend already online: http://127.0.0.1:8787"
  exit 0
fi
mkdir -p logs
.venv/bin/python daemon.py
sleep 8
if curl -s -m 2 http://127.0.0.1:8787/health >/dev/null 2>&1; then
  echo "NEO backend online: http://127.0.0.1:8787"
else
  echo "Start fail hua. Logs: backend/logs/server.out"
  exit 1
fi
