#!/usr/bin/env bash
# Keeps the NEO backend alive and restarts it if it ever crashes.
cd "$(dirname "$0")" || exit 1
export PATH="$PREFIX/bin:$PATH"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock 2>/dev/null
mkdir -p logs
while true; do
  echo "--- $(date '+%F %T') starting backend ---" >> logs/server.out
  .venv/bin/python main.py >> logs/server.out 2>&1
  echo "--- $(date '+%F %T') backend exited, restarting in 5s ---" >> logs/server.out
  sleep 5
done
