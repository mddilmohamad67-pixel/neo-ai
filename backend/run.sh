#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PY=""
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "Python nahi mila. Termux me: pkg install python"
  exit 1
fi

# Android/Termux ships its own Python ABI: pure-Python deps only.
REQ=requirements.txt
if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *com.termux* ]]; then
  REQ=requirements-termux.txt
fi

if [ ! -d .venv ]; then
  echo "→ virtualenv bana raha hoon…"
  "$PY" -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

STAMP=".venv/.deps_ok_${REQ}"
if [ ! -f "$STAMP" ] || [ "$REQ" -nt "$STAMP" ] || [ requirements.txt -nt "$STAMP" ]; then
  echo "→ dependencies install/update ($REQ)…"
  pip install --upgrade pip >/dev/null
  pip install -r "$REQ"
  touch "$STAMP"
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "→ .env banaya gaya. NEO_TOKEN aur API keys bhar dein, phir dobara run karein."
  echo "  nano .env"
  exit 0
fi

echo "→ NEO backend start: http://127.0.0.1:${NEO_PORT:-8787}"
echo "→ Token: .env ke NEO_TOKEN se match hona chahiye (ya backend/.neo_token)."
exec "$PY" main.py
