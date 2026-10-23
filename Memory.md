# Memory — NEO AI

> Managed by opencode. This is your project's memory — edit it freely.

## Project
- **Goal:** Personal Android voice assistant (Kotlin app + Termux FastAPI backend)
  with 3D female avatar, Hindi/Hinglish voice, device control, accessibility help.
- **Last worked:** 2026-10-23

## Progress Log
- 2026-10-23 (later): Added secure cloud mode. Backend hardened: public
  `/health` + authed `/health/full`, Bearer/X-Neo-Token multi-device tokens,
  HTTPS enforcement, HSTS/security headers, request ids, body cap, daily quota,
  production token guard, no CORS. Added Dockerfile, compose, Fly/Render/Procfile/
  Caddy deploy files and `docs/DEPLOY.md`. App: Cloud/Local presets, URL
  validation (HTTPS required off-LAN) in JS + Kotlin, token field masked.
  Fixed Kotlin compile errors (battery settings constant, accessibility smart
  cast, companion access, TTS lambda returns). Built real debug APK on-device:
  installed OpenJDK 17 + aapt2/apksigner, fake SDK at `~/android-sdk`
  (platform 33 ext3 + build-tools 33.0.2 jars, aapt2 override), Gradle 8.1.1.
  APK: `/storage/emulated/0/NeoAI.apk` (7 MB, v2 signed). Backend deps: Termux
  python 3.14 needs pydantic 1.10.26 (pure python) → `requirements-termux.txt`,
  run.sh auto-selects; cloud uses requirements.txt. Verified backend live:
  auth 401, intents, weather, TTS 503 fallback, production 403 http / 200 https.
- 2026-10-23: Built v1 from `neo.h` prompt + phone zip. Android project
  (minSdk 26, compileSdk 33) with WebView UI and procedural three.js avatar,
  VoiceController (SpeechRecognizer + TTS), DeviceController (apps/call/WhatsApp/
  SMS/volume/torch/settings/reminder/alarm/accessibility), ContactResolver,
  ApiClient, Prefs (EncryptedSharedPreferences), AccessibilityService,
  ForegroundService. Backend: FastAPI with token auth, rate limit, OpenAI JSON
  intents, rule-based fallback, ElevenLabs TTS, Open-Meteo weather, optional
  Whisper. Docs: SETUP, API, TESTING, LIMITATIONS, THIRD_PARTY. Copied to
  /storage/emulated/0/AndroidIDEProjects/NeoAI and zipped.

## Decisions
- WebView + three.js (r149, bundled) instead of native 3D libs — no extra Gradle
  deps, offline, easy to iterate, works on mid-range phones.
- Procedural female avatar (no external GLB) — no licences/assets, guaranteed
  to load; swap GLB later if desired.
- Keys stay in Termux `.env`; APK only holds a per-install token.
- Device actions are truthful: drafts/confirmation/verified intents only; no
  Instagram search API claims, no silent permission grants.
- Backend AI optional: rule parser keeps device commands working without OpenAI.

## Next Steps
- Install `/storage/emulated/0/NeoAI.apk` and run docs/TESTING.md matrix.
- Put real OPENAI_API_KEY (and optional ElevenLabs voice) in backend `.env`.
- Optional cloud: deploy per docs/DEPLOY.md, then Settings → Cloud (HTTPS).
- Optional: rigged GLB avatar with blendshapes; Vosk/Porcupine wake word;
  contact/app picker for ambiguous names.

## Open Questions
- Which Android version/device will be the primary test target?
- Does the user have OpenAI and ElevenLabs keys ready?
- Prefer Hindi female voice id from ElevenLabs (choose a voice ID).

## 2026-10-23 — voice-only, WhatsApp auto-send, WebUI hot update
- Backend: added `/app/manifest` and `/app/file/{path}` (auth, SHA-256, traversal guard), `backend/webui/`, `scripts/sync_webui.sh`. `.env` now uses Gemini OpenAI-compatible endpoint (`gemini-3.5-flash`, JSON mode off).
- Android: on-device quick parse for WhatsApp/SMS/call (instant confirm), spoken "haan/nahi" confirmation, hands-free auto-listen after every reply, WhatsApp auto-send via Accessibility (`com.whatsapp:id/send` poll), WebView loader prefers downloaded `filesDir/web`, verifies manifest hashes, falls back to APK assets.
- Rebuilt debug APK with all native changes; API/README/LIMITATIONS/SETUP updated.
- Next: user installs this build once; future HTML/CSS/JS changes ship through `/app/manifest` (no reinstall). Rotate both chat-pasted keys.
