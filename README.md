# NEO AI — personal Android voice assistant

NEO is a local-first assistant made of two parts:

| Part | What it is | Where it runs |
|---|---|---|
| `android/` | Native Android app (Kotlin) + WebView 3D interface | Your phone |
| `backend/` | FastAPI server (OpenAI brain, ElevenLabs voice, weather) | Termux on the same phone |

No API key is inside the APK. The app talks to `127.0.0.1:8787` with a
per-install token, and the token/keys stay in `backend/.env`.

**Highlights**

- Procedural 3D female avatar (three.js, bundled offline): blink, breathe, hair
  sway, head tracking by drag, happy reaction, and lip-sync while speaking.
- Voice in Hindi / Hinglish / English using Android's speech recognizer.
- Natural female voice via ElevenLabs, with Android TTS fallback.
- Device control: apps, deep links, calls, WhatsApp/SMS drafts, volume, torch,
  settings, reminders/alarms, weather.
- User-authorized Accessibility automation (tap text, type, scroll, back/home)
  with an explicit ON badge and Stop control.
- Confirmation dialogs for calls, messages, reminders and UI automation.
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
├── backend/                  main.py, requirements.txt, run.sh, .env.example
└── docs/                     setup, api, testing, limitations
```

## Honest status

This is a complete, buildable v1 source drop. It was authored on-device and
every file is included, but it has **not** been compiled in this environment
(no JDK/Android SDK here). Build it once in AndroidIDE and follow
`docs/TESTING.md`; anything that fails will be a build/environment issue rather
than missing code. Features that need credentials (OpenAI, ElevenLabs) or manual
permission grants are marked in the docs.
