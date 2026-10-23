#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python >/dev/null 2>&1; then
  echo "Python nahi mila. Termux me: pkg install python"
  exit 1
fi

if [ ! -d .venv ]; then
  echo "→ virtualenv bana raha hoon…"
  python -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

if [ ! -f .venv/.deps_ok ] || [ requirements.txt -nt .venv/.deps_ok ]; then
  echo "→ dependencies install/update…"
  pip install --upgrade pip >/dev/null
  pip install -r requirements.txt
  touch .venv/.deps_ok
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "→ .env banaya gaya. NEO_TOKEN aur API keys bhar dein, phir dobara run karein."
  echo "  nano .env"
  exit 0
fi

echo "→ NEO backend start: http://127.0.0.1:${NEO_PORT:-8787}"
echo "→ Token: .env ke NEO_TOKEN se match hona chahiye (ya backend/.neo_token)."
exec python main.py
