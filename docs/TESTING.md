# Testing NEO

## A. Backend smoke test

```bash
cd ~/neo-ai/backend
./selftest.sh
```

`selftest.sh` reads `.env`/`.neo_token` and checks health, chat intent parsing,
weather and TTS configuration. Expected highlights:

- `/health` → `"ok": true`
- `torch on karo` → `"intent": "torch"` (works even without OpenAI)
- `Sonia ko call karo` → `"intent": "call"`, args.name = `sonia`
- `/weather` → text or a graceful failure message

Manual:

```bash
TOKEN=$(grep '^NEO_TOKEN=' .env | cut -d= -f2)
curl -s http://127.0.0.1:8787/chat \
  -H "X-Neo-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"YouTube par lofi search karo","language":"hi-IN"}'
```

## B. Build checks

- `./gradlew assembleDebug` (or AndroidIDE Assemble) finishes with 0 errors.
- Install APK on API 26+ device.
- App opens, avatar renders, badge shows current backend state.
- No crash when rotating the device or backgrounding during speech.

## C. Manual test matrix

| # | Say / type | Expected |
|---|---|---|
| 1 | "Neo, torch on karo" | Torch on instantly (no backend needed), confirmation not required |
| 2 | "Torch off karo" | off |
| 3 | "Volume badhao" / "volume 40%" | system volume changes (40% sets music stream) |
| 4 | "YouTube kholo" | YouTube launches, truthful message |
| 5 | "YouTube par lofi search karo" | YouTube search results open |
| 6 | "Instagram kholo" | Instagram launches; no fake "search done" claim |
| 7 | "<name> ko call karo" | contact resolved → dialog → call placed; ambiguous names ask for full name |
| 8 | "<name> ko WhatsApp message likho: kal milte hain" | dialog shows name + draft → WhatsApp opens with text; user presses send |
| 9 | "Sonia ko SMS likho: hello" | SMS composer with text |
| 10 | "Phone settings kholo" | Settings |
| 11 | "Aaj ka weather batao" | live weather; needs internet/backend |
| 12 | "Mujhe 15 minute baad pani peene ka reminder" | calendar insert screen (user saves) |
| 13 | "Mere sawal ka jawab do: …" | conversational reply from OpenAI (or offline notice) |
| 14 | Accessibility: "back karo" / "home" | only after enabling accessibility + automation + confirm; automation badge visible |
| 15 | "Stop" while speaking | audio stops immediately, state idle |
| 16 | Airplane mode, device command | local commands still work; chat shows offline message, no fake success |
| 17 | Wrong token | badge offline + clear error, no crash |
| 18 | Kill Termux, reopen app | offline state, local commands still work |

## D. Permission/consent checks

- First launch wizard appears; each permission opens the real Android dialog.
- Denying mic: pressing mic shows the permission request/error, never silently fails.
- Accessibility is only enabled through Android Settings; the app explains why.
- Turning automation OFF stops accessibility actions and hides the badge.
- Hands-free ON shows a notification; Stop in the notification/in-app ends it.

## E. UI checks

- Dark + light themes; text readable over avatar.
- Avatar: idle breathing, blink, hair sway, drag to look, tap = happy.
- While speaking: mouth moves with audio amplitude; stops when audio ends.
- With no ElevenLabs key: phone TTS speaks and mouth animates synthetically.
- Chat scrolls; only last 40 messages kept in DOM.
- Small screen (≤620px height): hero hidden, composer usable.

## F. Failure injection

- `401`: change one character in app token → error toast, recovery after fixing.
- `429`: set `NEO_RATE_PER_MIN=1`, send two requests → friendly rate-limit message.
- OpenAI invalid key → server falls back to rule parser (device commands still work).
- ElevenLabs invalid key → `/tts` 502 → app uses Android TTS.
- Revoke a permission in Android Settings while app is open → next action reports
  permission missing instead of pretending success.
