# Unsupported features & Android limitations

NEO is designed to stay inside Android's rules. These are intentional limits, not
bugs. Anything listed here will be reported to the user instead of faked.

## Platform / automation

- **No silent permission granting.** Accessibility must be enabled by the user in
  Android Settings. The app cannot toggle it, and the service can be revoked at
  any time.
- **Secure windows are off-limits.** Password fields, banking apps, OTP screens
  and other `FLAG_SECURE` windows are never read or typed into.
- **Instagram has no public in-app search API.** NEO can open the app, open a
  profile URL (`instagram.com/_u/<user>/`), or (with automation ON) try to tap a
  visible "Search" control. If the UI changes or the button is not exposed,
  NEO says so. It never claims a search succeeded.
- **WhatsApp auto-send needs Accessibility ON.** `wa.me` prefills the message.
  With automation ON, NEO watches for `com.whatsapp:id/send` for ~20 s and taps
  it once. If WhatsApp changes its UI, a contact is ambiguous, or automation is
  off, the draft stays open for the user to press Send. SMS remains draft-only.
- **WebUI hot update only covers HTML/CSS/JS.** The app downloads changed
  `assets/web` files from `/app/manifest`, verifies SHA-256 and reloads. Kotlin,
  manifest, permissions or Gradle changes still require reinstalling the APK.
- **Calls** use `ACTION_CALL` after confirmation. The app cannot prove the call
  connected — it reports "call placed", not "person talked".
- **Reminders / alarms** open the system Calendar/AlarmClock screen. NEO cannot
  silently write calendar events without the user saving them (and calendar
  permission).
- **App labels** are matched fuzzily from installed launchers. Apps without a
  launcher activity cannot be opened by intent.
- **Volume** controls the media stream (not ringtone/alarm) in v1.
- **Torch** needs a rear flash unit and Camera permission on Android 13+.

## Voice & AI

- **Hinglish quality** depends on the Google speech service; some phones only
  recognize English reliably. Text input always works.
- **Wake word**: v1 ships without an always-on "Neo" wake word. Reliable wake
  words require a dedicated engine (Porcupine/Vosk) and a foreground mic; a fake
  version would drain battery and fail unpredictably. Press-to-talk is the
  supported path.
- **Hands-free mode** keeps a foreground service + notification, but Android (and
  OEM battery managers) may suspend the mic with the screen off. NEO listens
  again after each reply while the app is in front; it does not promise 24/7
  listening or a wake word.
- **No call recording, no OTP/password reading, no private-chat scraping.**
- **Internet required** for OpenAI chat, ElevenLabs and weather. Rule-based
  device commands work offline.
- **Lip-sync** is amplitude-based (not phoneme/viseme accurate); it is smooth and
  expressive but not a studio-grade facial rig.
- **Avatar realism**: the bundled character is procedural three.js geometry
  (semi-realistic stylized, no external 3D asset/licence). You can swap in a GLB
  via `THREE.GLTFLoader` if you want a scanned model.

## Device / build

- **minSdk 26** (Android 8). Target/compile SDK 33. Newer Android versions may
  need `targetSdk` bumps for Play Store submission.
- **`QUERY_ALL_PACKAGES`** is used to resolve app labels; Google Play restricts
  this permission. This project is intended for personal sideloading. For Play,
  replace it with explicit `<queries>` entries.
- **Cleartext HTTP** is enabled for `127.0.0.1`. Do not expose the backend to the
  internet over plain HTTP.
- **Battery optimisation** must be disabled manually for reliable background
  behaviour; some OEMs still kill services.
- **Built/tested environment**: source was authored on-device; compile it in
  AndroidIDE/Android Studio. If Gradle complains about SDK 33, install it or
  lower `compileSdk`/`targetSdk` together.
- **Play Store policy** on accessibility: Google requires a declaration and
  restricts its use. This build is for the owner's own device, with explicit
  consent and visible indicator.

## Cloud mode

- **You host it.** There is no NEO-operated cloud; deploy the same backend to
  Render/Fly/VPS (see `docs/DEPLOY.md`) and use your own provider keys.
- **Shared-token auth, not accounts.** One long token (or a short `NEO_TOKENS`
  list) identifies devices. There is no signup, OAuth, or per-user isolation.
  Revoke by editing `NEO_TOKENS` and restarting.
- **TLS is mandatory** for public hosts. The app rejects `http://` cloud URLs;
  the server rejects cleartext when `NEO_REQUIRE_HTTPS=1` (default in production).
- **The server sees your text.** Chat messages and transcripts are processed in
  plaintext on the backend (that is what sends them to OpenAI). Audio and chat
  history are not stored, only a rotating log line with the intent name.
- **Free hosts cold-start.** Render free tier sleeps after idle; the first reply
  can take ~30 s. Fly scale-to-zero behaves similarly.
- **Not audited.** This is a personal project; do not use it for regulated data.

## Not included

- Cloud sync/accounts, analytics, ads, telemetry — deliberately absent.
- Multi-user profiles, child mode, payment actions.
- Automatic publishing/deleting content on social apps.
- Any bypass of lockscreen, biometrics, or app sandboxing.
