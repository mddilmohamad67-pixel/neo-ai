# NEO API

Base URL: `http://127.0.0.1:8787` locally, or `https://your-host` in cloud mode.
Every route except `/health` requires a device token:

```
Authorization: Bearer <NEO_TOKEN>
# X-Neo-Token: <NEO_TOKEN>   (also accepted)
```

Responses are JSON except `/tts` (`audio/mpeg`). Rate limit: 60 requests/minute
per token fingerprint + client (`NEO_RATE_PER_MIN`), optional daily cap
(`NEO_DAILY_REQUESTS`). Production requires HTTPS and starts only with tokens
≥ 24 chars. No CORS headers are sent — only the native app is meant to call it.

Errors: `401` bad token, `403` HTTPS required, `413` body too large,
`429` rate limit, `503` provider not configured.

## GET /health

Public liveness probe, no token, no configuration details:

```json
{"ok": true, "service": "neo"}
```

## GET /health/full

Authenticated capability check (used by the app badge):

```json
{"ok": true, "openai": true, "elevenlabs": false, "transcribe": true,
 "model": "gpt-4o-mini", "secure": true, "time": "..."}
```

## POST /chat

Request:

```json
{
  "message": "Sonia ko call karo",
  "language": "hi-IN",
  "user_name": "Rehan",
  "device": {"accessibility": "on", "battery": "64%"}
}
```

Response:

```json
{
  "reply": "Sonia ko call kar rahi hoon.",
  "intent": "call",
  "args": {"name": "Sonia"}
}
```

Intent names and args:

| intent | args | app action |
|---|---|---|
| `none` / `chat` | – | just speak `reply` |
| `open_app` | `app` | resolve label → launcher intent |
| `open_url` | `url` | https only |
| `search` | `app`, `query` | YouTube deep link, Instagram app, Google fallback |
| `call` | `name`, optional `number` | contacts fuzzy match → confirm → ACTION_CALL |
| `whatsapp` | `name`, `message` | confirm → `wa.me` draft (user sends) |
| `sms` | `name`, `message` | confirm → `smsto:` draft (user sends) |
| `volume` | `direction` (`up/down/mute/set`), `percent` | AudioManager |
| `torch` | `on` | CameraManager torch |
| `settings` | `screen` | official Settings intents |
| `reminder` | `title`, `when` | confirm → calendar insert (user saves) |
| `alarm` | `hour`, `minute` | AlarmClock intent |
| `accessibility` | `action`, `text` | confirm + automation ON required |
| `time`, `battery` | – | answered locally, no backend needed |
| `weather` | – | converted to live weather text by the server |

If `OPENAI_API_KEY` is missing or fails, a deterministic Hinglish rule parser
returns the same intent shape for common commands.

## POST /chat/ack

```json
{"intent": "call", "ok": true, "detail": "Sonia ko call lag raha hai."}
```

Used only for logging/telemetry-free debugging. Response `{"received": true}`.

## POST /tts

```json
{"text": "Namaste, main NEO hoon.", "language": "hi-IN"}
```

- `200` → `audio/mpeg` bytes
- `503` → ElevenLabs not configured → app falls back to Android TTS
- `502` → provider failed

## GET /weather?lat=&lon=&lang=

Uses Open-Meteo (no key). Falls back to `NEO_DEFAULT_LAT/LON` when the phone has
no location permission.

```json
{"text": "Abhi 29°C hai, thodi badal. Feels like 31°C…"}
```

## POST /transcribe (optional)

Multipart `file=@voice.m4a`. Uses OpenAI Whisper. The app does **not** call this
by default — it uses on-device `SpeechRecognizer` for privacy and latency.

## Error format

```json
{"detail": "human readable message"}
```

`401` bad token · `422` validation · `429` rate limit · `5xx` provider failure.
