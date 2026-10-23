# Building the APK inside Termux (no Android Studio)

This is how the shipped `NeoAI.apk` was produced on-device. It is optional —
AndroidIDE with SDK 33 + JDK 17 works normally.

## 1. Toolchain (once)

```bash
pkg install openjdk-17 aapt2 apksigner unzip curl
```

`aapt2` from Termux is an ARM build; the Android SDK's copy is x86_64 and cannot
run on the phone, so AGP is pointed at the Termux binary.

## 2. Minimal SDK at `~/android-sdk`

Download and extract (URLs from Google's `repository2-3.xml`):

- `platform-33-ext3_r03.zip` → `platforms/android-33/` (needs `android.jar`)
- `build-tools_r33.0.2-linux.zip` → `build-tools/33.0.2/` (jars/scripts only)

Then:

```bash
ln -sf "$PREFIX/bin/aapt2" ~/android-sdk/build-tools/33.0.2/aapt2
# package.xml files tell AGP the components exist (see this repo's Memory/notes)
```

Create `android/local.properties` (never commit it):

```properties
sdk.dir=/data/data/com.termux/files/home/android-sdk
```

## 3. Build

```bash
cd android
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
```

The first run downloads Gradle 8.1.1 + AGP/Kotlin deps (several minutes, Wi-Fi
recommended). Output:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

## 4. Verify

```bash
~/android-sdk/build-tools/33.0.2/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk | head -3
```

## Notes

- `local.properties`, `.gradle/`, `build/` and the SDK are machine-specific and
  git-ignored.
- Only `aapt2` is swapped; D8/R8, apksig and zipalign used by AGP are JVM code
  or in-process, so x86_64 binaries are not required for a debug build.
- Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — it would
  break AndroidIDE/desktop builds. Pass it on the command line.
