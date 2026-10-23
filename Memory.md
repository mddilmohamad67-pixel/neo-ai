# Memory — NEO AI

> Managed by opencode. This is your project's memory — edit it freely.

## Project
- **Goal:** Personal Android voice assistant (Kotlin app + Termux FastAPI backend)
  with 3D female avatar, Hindi/Hinglish voice, device control, accessibility help.
- **Last worked:** 2026-10-23

## Progress Log
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
- Build APK in AndroidIDE (SDK 33 + JDK 17) and run docs/TESTING.md matrix.
- Fix any compile issues surfaced by the first Gradle sync.
- Optional: swap avatar for a rigged GLB with blend shapes; add wake word via
  Vosk/Porcupine; add app picker for contact ambiguity.

## Open Questions
- Which Android version/device will be the primary test target?
- Does the user have OpenAI and ElevenLabs keys ready?
- Prefer Hindi female voice id from ElevenLabs (choose a voice ID).
