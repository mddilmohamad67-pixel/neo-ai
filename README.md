# NEO AI — personal Android voice assistant

NEO is an assistant made of two parts:

| Part | What it is | Where it runs |
|---|---|---|
| `android/` | Native Android app (Kotlin) + WebView 3D interface | Your phone |
| `backend/` | FastAPI server (OpenAI brain, ElevenLabs voice, weather) | Termux **or** any HTTPS host |

No AI provider key is ever inside the APK or the WebView. The app only stores a
long random **device access token** (encrypted with Android Keystore) and sends
it to your backend over HTTPS. OpenAI/ElevenLabs keys live in the server env.

Two supported modes (switch in app ▸ Settings ▸ Connection):

- **Local** — `http://127.0.0.1:8787`, backend in Termux, nothing leaves the phone
  except the OpenAI/ElevenLabs calls made by the backend.
- **Cloud** — `https://your-neo.example.com` (Docker on Render/Fly/VPS). HTTPS is
  enforced; cleartext URLs are rejected for non-private hosts.

**Highlights**

- Procedural 3D female avatar (three.js, bundled offline): blink, breathe, hair
  sway, head tracking by drag, happy reaction, and lip-sync while speaking.
- Voice in Hindi / Hinglish / English using Android's speech recognizer.
- Natural female voice via ElevenLabs, with Android TTS fallback.
- Voice-only confirmation ("haan/nahi") plus hands-free auto-listen after replies.
- Device control: apps, deep links, calls, WhatsApp/SMS drafts, volume, torch,
  settings, reminders/alarms, weather.
- User-authorized Accessibility automation (tap text, type, scroll, back/home)
  with an explicit ON badge and Stop control.
- Confirmation prompts for calls, messages, reminders and UI automation.
- WhatsApp auto-send via Accessibility (tap Send after `wa.me` prefills the chat).
- WebUI hot updates: edit `assets/web`, run `scripts/sync_webui.sh`, and the app
  picks up HTML/CSS/JS from `/app/manifest` without reinstalling the APK.
- Light + dark themes, setup wizard, permission dashboard.

## Quick start

```bash
# 1) backend (inside Termux)
cd ~/neo-ai/backend
cp .env.example .env
nano .env                 # paste NEO_TOKEN + OPENAI_API_KEY (optional ElevenLabs)
chmod +x run.sh && ./run.sh

# 2) build the app
#    Open /storage/emulated/0/AndroidIDEProjects/NeoAI in AndroidIDE
#    Build ▸ Assemble debug  →  install app-debug.apk

# 3) connect
#    App ▸ Settings ▸ Connection ▸ paste token ▸ Check server
```

Full step-by-step: [`docs/SETUP.md`](docs/SETUP.md) ·
**Cloud deploy: [`docs/DEPLOY.md`](docs/DEPLOY.md)** ·
Build in Termux: [`docs/BUILD_TERMUX.md`](docs/BUILD_TERMUX.md) ·
API: [`docs/API.md`](docs/API.md) ·
Testing: [`docs/TESTING.md`](docs/TESTING.md) ·
Limits: [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)

## Project layout

```
neo-ai/
├── android/                  Gradle project (Kotlin, minSdk 26)
│   └── app/src/main/
│       ├── java/com/neoai/   MainActivity, DeviceController, VoiceController,
│       │                     ApiClient, Prefs, ContactResolver,
│       │                     NeoAccessibilityService, NeoForegroundService
│       ├── assets/web/       index.html, style.css, app.js, three.min.js
│       └── res/              theme, icon, accessibility config
├── backend/                  main.py, run.sh, requirements*.txt, .env.example,
│                             Dockerfile, docker-compose.yml, fly.toml, render.yaml
└── docs/                     setup, deploy, api, testing, limitations
```

## Honest status

This is a complete, buildable v1 source drop. It was authored on-device and
every file is included, but it has **not** been compiled in this environment
(no JDK/Android SDK here). Build it once in AndroidIDE and follow
`docs/TESTING.md`; anything that fails will be a build/environment issue rather
than missing code. Features that need credentials (OpenAI, ElevenLabs) or manual
permission grants are marked in the docs.
