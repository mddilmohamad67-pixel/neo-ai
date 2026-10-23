# Cloud backend (secure mode)

The app never holds an AI provider key. It holds **one device access token**
and calls **your** backend over HTTPS. OpenAI/ElevenLabs keys stay in the
server environment; the WebView only renders UI and never sees them.

```
Android app ──HTTPS + Bearer token──►  NEO backend  ──►  OpenAI / ElevenLabs
 (token in Keystore)                    (keys in env)      (provider side)
```

## Security model

| Layer | What protects it |
|---|---|
| Transport | HTTPS required for every non-private host; HSTS, nosniff, DENY framing, no-store |
| Device auth | 64-hex-char token, constant-time compare, `Authorization: Bearer` or `X-Neo-Token` |
| Revocation | `NEO_TOKENS=a,b,c` — remove a token and restart to cut off one device |
| Abuse | per-token+IP minute limit (`NEO_RATE_PER_MIN`) and optional daily cap (`NEO_DAILY_REQUESTS`) |
| Body size | 12 MB global cap, 8 MB for audio; request id on every response |
| Production guard | `NEO_ENV=production` refuses short/default tokens |
| Secrets in logs | tokens/keys are never logged; chat logs only intent + ok flag |
| Storage | no message or audio history on the server; only rotating `logs/neo.log` |

What the server still sees (by design): your chat text, spoken transcripts, and
the device command intents. Audio is not stored.

## 1. Make the token

```bash
python3 -c "import secrets;print(secrets.token_hex(32))"   # 64 hex chars
```

Use the **same value** in the server env (`NEO_TOKEN`) and in the app
(Settings ▸ Device access token). Do not reuse it anywhere else.

## Option A — Render (easiest)

1. Push `neo-ai/` to a private GitHub repo (never commit `.env`).
2. Render ▸ **New ▸ Web Service** ▸ your repo ▸ Runtime **Docker**.
3. `backend/render.yaml` describes the service; or set manually:
   - Dockerfile path: `backend/Dockerfile`, health check: `/health`
   - Env: `NEO_ENV=production`, `NEO_TOKEN=<hex>`, `OPENAI_API_KEY=<key>`,
     optional `ELEVENLABS_API_KEY` + `ELEVENLABS_VOICE_ID`,
     `NEO_FORWARDED_IPS=*`
4. Deploy, then verify:

```bash
curl -s https://YOUR-APP.onrender.com/health
curl -s https://YOUR-APP.onrender.com/health/full -H "Authorization: Bearer YOUR_TOKEN"
```

Free instances sleep; first request after idle can take ~30 s. The app's
"Check server" will just retry next time.

## Option B — Fly.io

```bash
cd backend
fly launch --no-deploy            # keep the included fly.toml
fly secrets set NEO_TOKEN=... OPENAI_API_KEY=... \
  ELEVENLABS_API_KEY=... ELEVENLABS_VOICE_ID=...
fly deploy
curl -s https://YOUR-APP.fly.dev/health
```

`force_https = true` and the `/health` check are already configured.

## Option C — VPS + Docker + Caddy (full control)

```bash
# on the server, DNS A record → server IP
git clone <your-repo> && cd neo-ai/backend
cp .env.example .env && nano .env     # NEO_TOKEN, keys, NEO_ENV=production
docker compose up -d --build
```

Then put Caddy in front (automatic TLS):

```bash
sudo apt install caddy
sudo cp Caddyfile /etc/caddy/Caddyfile   # edit the domain first
sudo systemctl reload caddy
```

Compose publishes the API on `127.0.0.1:8787` only, so nothing is exposed
without TLS. Verify with the two `curl` commands from Option A.

## 4. Point the app at it

App ▸ Settings ▸ Connection:

1. Tap **Cloud (HTTPS)** and enter `https://your-host` (no trailing slash needed).
2. Paste the same token under **Device access token**.
3. **Check server** → badge turns **online** and the lock line turns teal.

`http://` cloud URLs are rejected by both the UI and the Kotlin client. Plain
HTTP is only accepted for `127.0.0.1`, `localhost`, `*.local` and RFC1918 LAN
addresses (development).

## 5. Rotate / revoke devices

```bash
# add per-device tokens
NEO_TOKENS=tokenForPhone,tokenForTablet
# revoke one: delete it from the list and restart the service
```

Changing `NEO_TOKEN` invalidates the old value everywhere; update the app.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App: "Cloud backend ke liye https:// zaroori hai" | Use the HTTPS URL; cleartext public hosts are blocked on purpose |
| Server `403 HTTPS required` | Proxy is not sending `X-Forwarded-Proto: https`; set `NEO_FORWARDED_IPS` to the proxy IP (or `*` only when the port is private) |
| `401 Unauthorized` | Token mismatch — copy again, no spaces; in production the token must be ≥ 24 chars |
| `429` | Minute/daily limit; raise `NEO_RATE_PER_MIN` / `NEO_DAILY_REQUESTS` |
| `413` | Request too large; audio cap is 8 MB |
| `502` on `/tts` or `/chat` | Provider key invalid/quota; check server logs (`docker logs`, `fly logs`) |
| Health OK but chat fails | `OPENAI_API_KEY` missing → offline rule engine still answers device commands |
| Render/Fly health check fails | Container must listen on `0.0.0.0:$PORT`; the Dockerfile already does |

## Costs and limits

- Backend hosting: free tiers work for one user (cold starts on free Render).
- OpenAI: `gpt-4o-mini` keeps cost low; your key, your quota.
- ElevenLabs: optional; without it the phone's built-in TTS is used, so the
  backend never needs that key.
