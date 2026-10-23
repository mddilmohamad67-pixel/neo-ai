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
- **WhatsApp / SMS are drafts.** `wa.me` and `smsto:` intents prefill the message;
  only the user presses Send. Direct sending is not exposed by Android.
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
  OEM battery managers) may suspend the mic with the screen off. NEO does not
  promise 24/7 listening.
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

## Not included

- Cloud sync/accounts, analytics, ads, telemetry — deliberately absent.
- Multi-user profiles, child mode, payment actions.
- Automatic publishing/deleting content on social apps.
- Any bypass of lockscreen, biometrics, or app sandboxing.
