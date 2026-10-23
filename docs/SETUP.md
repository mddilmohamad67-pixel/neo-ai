# NEO AI — setup guide

## 0. What you need

- Android 8.0+ phone (API 26+)
- Termux (F-Droid build recommended) + storage access
- AndroidIDE (or Android Studio) with SDK 33 + JDK 17
- Internet for OpenAI / ElevenLabs / weather
- Optional: OpenAI API key, ElevenLabs API key + voice ID

---

## 1. Termux backend

```bash
pkg update
pkg install python

# copy the backend folder from the zip, e.g.
cd ~/neo-ai/backend

python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env
nano .env
```

Fill `.env`:

```dotenv
NEO_ENV=dev
NEO_TOKEN=<run: python3 -c "import secrets;print(secrets.token_hex(32))">
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o-mini
ELEVENLABS_API_KEY=
ELEVENLABS_VOICE_ID=
```

- `ELEVENLABS_VOICE_ID` is the id from elevenlabs.io → Voices → voice → ID.
  With an empty key/id the app automatically uses Android's built-in TTS.
- Leave `NEO_HOST=127.0.0.1`. Only the same phone can reach the server.

Run it:

```bash
./run.sh          # creates .venv if missing, then starts uvicorn
# or
python main.py
```

If `NEO_TOKEN` is empty (dev only) the server generates one, stores it in
`backend/.neo_token` and names the file in the log. Use that value in the app.

Health check (public probe + authenticated capabilities):

```bash
curl -s http://127.0.0.1:8787/health
# {"ok":true,"service":"neo"}
curl -s http://127.0.0.1:8787/health/full -H "Authorization: Bearer YOUR_TOKEN"
# {"ok":true,"openai":true,"elevenlabs":false,...}
```

**Online/cloud backend:** keys never go in the app. Deploy this same backend
with HTTPS and point the app at it — see [`DEPLOY.md`](DEPLOY.md).

Keep Termux alive: `termux-wake-lock` (notification stays). Do not swipe Termux
away while using NEO.

### Optional: autostart on boot

Termux:Boot app + `~/.termux/boot/neo.sh`:

```bash
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
cd ~/neo-ai/backend && ./run.sh >> logs/boot.log 2>&1
```

`chmod +x ~/.termux/boot/neo.sh`.

---

## 2. Build the Android app

### AndroidIDE (recommended on phone)

1. Copy/open the project: `/storage/emulated/0/AndroidIDEProjects/NeoAI`.
2. Let Gradle sync finish (first sync downloads AGP/Kotlin/deps).
3. If SDK 33 is missing: **Tools ▸ SDK Manager** → install Platform 33 and
   Build-Tools 33.0.x. Or change `compileSdk`/`targetSdk` in
   `android/app/build.gradle.kts` to an installed version (32+).
4. **Build ▸ Assemble debug**.
5. APK: `android/app/build/outputs/apk/debug/app-debug.apk` → install.

### Command line (PC)

```bash
cd android
./gradlew assembleDebug        # needs JDK 17 + ANDROID_HOME
adb install app/build/outputs/apk/debug/app-debug.apk
```

If `gradle-wrapper.jar` is missing, run `gradle wrapper` once or just build via
AndroidIDE (it injects its own Gradle).

Release build (optional): create a keystore, configure `signingConfigs`, then
`./gradlew assembleRelease`.

---

## 3. First run

1. Open **NEO AI** → setup wizard appears.
2. **Microphone** → allow (required).
3. **Contacts & phone** → allow for calls / message drafts.
4. **Notifications** → allow (background indicator).
5. **Accessibility (optional)** → opens Android Settings → *Installed apps /
   Downloaded services* → **NEO AI Assistant** → enable → confirm the warning.
   This is the only way Android grants accessibility; the app never grants it.
6. **Settings ▸ Connection**:
   - Local: **Local Termux** → `http://127.0.0.1:8787`
   - Cloud: **Cloud (HTTPS)** → `https://your-host` (see [`DEPLOY.md`](DEPLOY.md))
   - Token: copy from `backend/.env` (or `.neo_token`), paste, **Check server**.
   - Badge turns **online**; the lock line turns teal for HTTPS.
7. Say: *"Neo, torch on karo"* or type *"Aaj ka weather batao"*.

### Battery

**Settings ▸ Android access ▸ Battery settings** → set NEO to *Unrestricted*.
OEMs (Xiaomi/Oppo/Vivo/Samsung) also have "Autostart" and "Background activity"
toggles — enable NEO there if hands-free mode stops.

---

## 4. Voice setup

- Language dropdown controls speech recognition + TTS language.
  `Hindi/Hinglish` uses `hi-IN`; Hinglish recognition quality depends on the
  Google speech service installed on the phone.
- ElevenLabs is called only from the backend; the APK never sees the key.
- If ElevenLabs fails or is unconfigured, the app speaks with the phone TTS
  automatically (pitch/rate tuned to sound feminine, engine dependent).

---

## 5. LAN access (optional, advanced)

Only if you want another device to use the backend:

1. Set `NEO_HOST=0.0.0.0` in `.env`.
2. Use a long random `NEO_TOKEN`, restrict with `iptables`/router firewall, and
   never port-forward to the internet.
3. Put HTTPS (Caddy/nginx) in front if traffic leaves your home network.
4. Cleartext HTTP is blocked by default for non-localhost on Android 9+; the app
   enables cleartext for simplicity, so treat the network as trusted.

---

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| badge stays **offline** | Is uvicorn running? `curl` `/health` and `/health/full` with the same token. Check `http://127.0.0.1:8787` (not `localhost` if IPv6 only). Cloud: confirm HTTPS URL + token. |
| "https:// zaroori hai" in app | Cloud URLs must be HTTPS; only loopback/LAN may use http. |
| Server `403 HTTPS required` | Proxy missing `X-Forwarded-Proto: https`; set `NEO_FORWARDED_IPS` correctly. |
| `401 Unauthorized` | Token mismatch. Copy again from `.env` / `.neo_token` into app Settings. |
| `429` | Rate limit (60/min). Wait a minute or raise `NEO_RATE_PER_MIN`. |
| "OpenAI se jawab nahi mila" | Wrong key, quota, or network. Device commands still work without OpenAI. |
| No voice | ElevenLabs not configured → check log; phone TTS may need a voice pack installed in Android Settings ▸ TTS output. |
| "Kuch sunai nahi diya" | Grant mic, install/enable Google speech service, use quieter room; hi-IN needs network on many phones. |
| Accessibility actions fail | Service enabled? Automation ON? Screen unlocked? Some apps mark buttons as non-clickable or use secure windows. |
| APK build fails on `compileSdk 33` | Install SDK 33 or lower `compileSdk` / `targetSdk` consistently. |
| `Unsupported class file major version` | Use JDK 17 for Gradle (AndroidIDE ships it). |
| Service killed overnight | Battery unrestricted + OEM autostart; Android may still stop it — see LIMITATIONS. |
| Weather says connect failed | Open-Meteo needs internet; grant location for local weather, else default city from `.env`. |

Logs:

```bash
tail -f ~/neo-ai/backend/logs/neo.log
# WebView console (logcat)
adb logcat -s NEO-WEB
```
