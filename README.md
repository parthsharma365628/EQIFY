# EQify

Android equalizer combining automatic genre tone profiles, headphone-specific
AutoEQ correction, bass boost, manual eight-band control, and output protection,
plus per-device sound, loudness compensation, safe-listening reminders, a sleep
timer, a widget and a Wear OS remote. The client uses Kotlin and Jetpack Compose
(`app/`, and `wear/` for the watch); the companion backend uses Node.js/Express.

For what's built, see [STATUS.md](STATUS.md); for goals and what's next, the PRD,
[EQify_Android_PRD_v2.md](EQify_Android_PRD_v2.md).

## Beta

EQify is in public beta (latest: 0.5.0-beta). Builds are published on the
public [eqify-releases](https://github.com/parthsharma365628/eqify-releases)
repository. Install with [Obtainium](https://github.com/ImranR98/Obtainium) for
automatic updates, or download the APK; Android will ask you to allow installs
from your browser or file manager.
Send bugs and ideas from **Settings → Send feedback**. See
[PRIVACY.md](PRIVACY.md) for what data leaves the phone, and
[RELEASING.md](RELEASING.md) for building a signed release.

### Installing the beta

1. Uninstall any older EQify build first, so two equalizers don't compete.
2. Download `EQify-<version>.apk` from Releases and open it. Allow "Install
   unknown apps" for your browser or file manager when Android asks. The
   optional watch app, `EQify-Wear-<version>.apk`, is installed on the watch
   with adb (see the releases README).
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
.\scripts\build.ps1 test      # unit tests (also: debug, release, verify, all)
Set-Location eqify-backend
npm.cmd install
npm.cmd start
```

The default Android backend URL, `http://10.0.2.2:3000/`, is for an emulator
and debug builds only; release builds use the backend only when
`EQIFY_BASE_URL` is an https URL. No such URL is configured for the published
betas, so their genre lookup is on-device, Last.fm (optional) and iTunes.
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

### Documents

- [CLAUDE.md](CLAUDE.md): the short rules for AI assistants.
- [AI.md](AI.md): architecture, guardrails and the rules that keep features from
  breaking each other.
- [STATUS.md](STATUS.md): what's built and what's been checked on a device.
- [EQify_Android_PRD_v2.md](EQify_Android_PRD_v2.md): product requirements and roadmap.
- [PLAN_TEMPLATE.md](PLAN_TEMPLATE.md): the template for new feature plans.
- Feature plans by release: [FEATURES_NEXT_PLAN.md](FEATURES_NEXT_PLAN.md)
  (0.2), [FEATURES_BATCH2_PLAN.md](FEATURES_BATCH2_PLAN.md) (0.3),
  [FEATURES_BATCH3_PLAN.md](FEATURES_BATCH3_PLAN.md) (0.4),
  [FEATURES_BATCH4_PLAN.md](FEATURES_BATCH4_PLAN.md) (0.5), plus the
  `FEATURE_*.md` files for earlier features.
- [RELEASING.md](RELEASING.md) and [PRIVACY.md](PRIVACY.md).

### Try the Orbit prototype

On an Android 9+ device, install the debug build, connect headphones, turn on EQ,
and select **Balanced** output protection. Leave **Force mono** off and switch
out of **Original** bypass. Turn on **Settings → Debug → Orbit prototype** and
play music for at least 30 seconds. The stereo balance sweeps gently right and
left, then repeats. The effect depends on the playback app and audio route, so
check for audible motion and confirm that turning Orbit off restores the balance.

Made by Parth Sharma and Shreyansh Mangal.

## License

Copyright (C) 2026 Parth Sharma and Shreyansh Mangal. All rights reserved.

EQify is proprietary software. The source code may not be copied, modified or
redistributed without written permission. See [LICENSE](LICENSE).

Third-party components keep their own licences (listed in the app under
Settings → Open-source licences). The AutoEq headphone data is MIT-licensed
and the Archivo font uses the SIL Open Font License 1.1.
