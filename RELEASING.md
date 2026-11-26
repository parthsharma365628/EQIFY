# Releasing EQify

How to build a signed beta APK. Nothing here is committed: keys and passwords live only on your machine.

## One-time setup

1. **Create a signing key** (keep the file and passwords somewhere safe and backed up; without them you cannot ship updates):

   ```powershell
   & 'D:\ANDROID\jbr\bin\keytool.exe' -genkeypair -v -keystore "$HOME\eqify-release.jks" -alias eqify -keyalg RSA -keysize 4096 -validity 10000
   ```

2. **Add these to `%USERPROFILE%\.gradle\gradle.properties`** (next to `EQIFY_SENTRY_DSN`):

   ```properties
   EQIFY_KEYSTORE_FILE=C:/Users/<you>/eqify-release.jks
   EQIFY_KEYSTORE_PASSWORD=...
   EQIFY_KEY_ALIAS=eqify
   EQIFY_KEY_PASSWORD=...
   # Optional: where Settings > Send feedback sends mail
   EQIFY_FEEDBACK_EMAIL=...
   # Optional: a deployed https genre backend. Unset = release builds skip the backend.
   # EQIFY_BASE_URL=https://...
   ```

Without the signing properties, `assembleRelease` still works but produces `app-release-unsigned.apk`, which phones will not install.

## Each release

1. Increase `versionCode` (and update `versionName`) in `app/build.gradle.kts`.
2. Build: `.\gradlew.bat testDebugUnitTest assembleRelease`
3. Output: `app/build/outputs/apk/release/app-release.apk`, and the watch app at `wear/build/outputs/apk/release/wear-release.apk`.
   - Bump `versionCode`/`versionName` in `wear/build.gradle.kts` too.
   - Both must be signed with the same key: check with `apksigner verify --print-certs` on each.
4. **Install the release APK on a real phone and smoke-test it.** Release builds are shrunk by R8 (no renaming), so check:
   - headphone search and download
   - genre detection through iTunes
   - sharing and importing a profile, including the QR code
   - the crash-report switch
5. Upload the APK to a GitHub Release with short notes on what changed.

## Release vs debug

| | Debug | Release |
|---|---|---|
| Plain HTTP | Allowed (local backend) | Blocked |
| Genre backend | `EQIFY_BASE_URL` or the emulator's localhost | Only an https `EQIFY_BASE_URL` |
| R8 shrinking | Off | On (`-dontobfuscate`, so Sentry traces stay readable) |
| Debug menu in Settings | Shown | Hidden |

The app ID `com.liteplane.eqify` is permanent once published. Before the first public release, change it in `app/build.gradle.kts` if you want a different one. The debug build uses the same ID, so it installs as a new app next to any old `com.liteplane.eqify` install.
