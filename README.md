# EQify

Android equalizer combining automatic genre tone profiles, headphone-specific
AutoEQ correction, bass boost, manual eight-band control, and output protection.
The client uses Kotlin and Jetpack Compose; the companion backend uses
Node.js/Express.

## Beta

EQify is in public beta (0.1.0-beta). Install the APK from the GitHub Releases
page; Android will ask you to allow installs from your browser or file manager.
Send bugs and ideas from **Settings → Send feedback**. See
[PRIVACY.md](PRIVACY.md) for what data leaves the phone, and
[RELEASING.md](RELEASING.md) for building a signed release.

### Installing the beta

1. Uninstall any older EQify build first, so two equalizers don't compete.
2. Download the APK from Releases and open it. Allow "Install unknown apps"
   for your browser or file manager when Android asks.
3. **Play Protect warning.** EQify isn't on the Play Store yet, so Play
   Protect may say it's unsafe or ask you to scan it. Tap **More details**,
   then **Install anyway**. You don't need to turn Play Protect off.
4. **Notification access (optional).** It lets EQify detect the song you play.
   Android's dialog says the app could read, dismiss and reply to
   notifications, because that's how Android describes this one permission.
   EQify only reads the song title and artist from Spotify, YouTube Music,
   YouTube and Apple Music. It never reads, dismisses or replies to any
   other notification. You can skip it: presets, headphone correction and
   bass still work.
5. **If the notification access switch is greyed out** (Android 13 and
   later): open **Settings → Apps → EQify**, tap the **⋮** menu, choose
   **Allow restricted settings**, confirm with your PIN, then turn access
   on again.

## Development

From the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
Set-Location eqify-backend
npm.cmd install
npm.cmd start
```

The default Android backend URL, `http://10.0.2.2:3000/`, is for an emulator
and debug builds only; release builds use the backend only when
`EQIFY_BASE_URL` is an https URL.
A physical device needs a reachable server URL configured through the
`EQIFY_BASE_URL` Gradle property. The large AutoEQ source dataset, generated
exports, build output, IDE state, and secrets must remain uncommitted.
The static headphone source defaults to the deployed Pages site and can be
overridden with `EQIFY_HEADPHONE_DATA_BASE_URL`.

See [AI.md](AI.md) for architecture and repository guardrails. The headphone
database is published separately through GitHub Pages. Android caches its index,
searches it locally, and downloads only the selected profile's eight gains.
Cloudflare remains a future migration plan.

Genre corrections, recent tracks, and a seven-day genre mix are stored on-device
with Room. The mix counts detected track observations, not listening time.

Made by Parth Sharma and Shreyansh Mangal.

## License

Copyright (C) 2026 Parth Sharma and Shreyansh Mangal. All rights reserved.

EQify is proprietary software. The source code may not be copied, modified or
redistributed without written permission. See [LICENSE](LICENSE).

Third-party components keep their own licences (listed in the app under
Settings → Open-source licences). The AutoEq headphone data is MIT-licensed
and the Archivo font uses the SIL Open Font License 1.1.
