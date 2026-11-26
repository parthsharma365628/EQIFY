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
2. Build and check: `.\scripts\build.ps1 all`
   - Runs the tests, builds both release APKs, checks they share app ID, version and signing key.
   - Copies them to `EQify-<version>.apk` and `EQify-Wear-<version>.apk` in each module's `build/outputs/apk/release/`.
3. Bump `versionCode`/`versionName` in `wear/build.gradle.kts` too when the watch app changed. The script warns when the versions differ.
4. **Install the release APK on a real phone and smoke-test it.** Release builds are shrunk by R8 (no renaming), so check:
   - headphone search and download, and song detection after the update
   - genre detection through iTunes
   - sharing and importing a profile, including the QR code
   - the crash-report switch
   - backup, then restore
   - the volume features together (bass easing, loudness, volume limit) while pressing volume up and down
   - a sleep-timer fade
   - the widget, both new tiles and the app-icon shortcuts
   - rotating with a sheet open
   - the watch app, if it changed

   Record what you checked in `STATUS.md` (mark features **Device-validated**).
5. Publish on [eqify-releases](https://github.com/parthsharma365628/eqify-releases):
   - Create a tag such as `v0.5.0-beta`, marked as a pre-release.
   - Attach `EQify-<version>.apk` (a renamed `app-release.apk`) and, when it changed, `EQify-Wear-<version>.apk`.
   - Paste the notes from `release-notes/v<version>.md` in that repository.
   - Obtainium users and the in-app update check pick it up from there.

## Release vs debug

| | Debug | Release |
|---|---|---|
| Plain HTTP | Allowed (local backend) | Blocked |
| Genre backend | `EQIFY_BASE_URL` or the emulator's localhost | Only an https `EQIFY_BASE_URL` |
| R8 shrinking | Off | On (`-dontobfuscate`, so Sentry traces stay readable) |
| Debug menu in Settings | Shown | Hidden |

The app ID `com.liteplane.eqify` is published and now permanent: changing it would make every update a separate app. The debug build uses the same ID but a different signing key, so it can't be installed over a release build (or the other way round): uninstall first, which deletes the app's data unless you back it up. Two manifest components keep `com.example.eqify` class names on purpose; see "Package name" in `AI.md`.
