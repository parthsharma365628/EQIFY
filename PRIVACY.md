# EQify privacy policy

_Last updated: 27 November 2026. Applies to EQify for Android, version 0.1.0-beta and later, and the optional Wear OS app._

EQify is an equalizer. It works on your phone and has no accounts. This page says what leaves your phone, and why.

## What EQify reads on your phone

- **Now playing (only if you turn on Song detection and grant Notification Access).** EQify reads the song title and artist from the media notifications of supported music apps (Spotify, YouTube Music, YouTube, Apple Music). It ignores all other notifications.
- **Connected audio device.** The name and type of your Bluetooth, USB or wired output are used to pick a headphone correction. They stay on your phone.

## What is sent, and to whom

| Data | Sent to | When |
|---|---|---|
| Song title and artist | Apple iTunes Search API | To look up the genre of a song you play. |
| Artist name | Last.fm | Only if you add your own Last.fm API key. |
| Song title and artist | The EQify genre service | Only in builds where one is set up. The beta build does not use it. |
| A headphone model name (as part of a file path) | GitHub Pages (EQify headphone data) | Only after you choose a headphone, to download its correction. |
| A request for the latest version (no personal data; GitHub sees your IP address) | GitHub API | At most once a day while **Automatic update checks** is on in Settings, or when you tap **Check for updates**. |
| Crash and error reports | Sentry | Only while **Send crash reports** is on in Settings. You can turn it off at any time. |

Crash reports contain the device model, Android and app version, and technical details of the error. Before sending, EQify removes song titles, artists, headphone names and API keys from them. Screenshots and screen layouts are never attached.

These services have their own privacy policies: Apple, Last.fm, GitHub and Sentry.

## What is stored on your phone only

Your presets, favourites and customised genre curves, settings, device settings and volumes, genre corrections, recent tracks, the weekly genre mix and your safe-listening estimate. EQify also keeps imported GraphicEQ and ParametricEQ corrections and downloaded headphone corrections there. The "What changed" list is kept in memory only and is gone when EQify stops. You can clear these in **Settings → Data & privacy**, or remove all of them by uninstalling the app.

**Live spectrum.** When you open Home → Live spectrum, EQify asks for the "Record audio" permission. Android requires it to read an app's own audio output. EQify reads only what is playing, only while that screen is open. It never uses the microphone, and never saves or sends audio.

**Safe listening.** EQify estimates your weekly listening from your volume level, on the phone only. The estimate is not included in backups and never leaves the phone.

**Wear OS.** The optional watch app talks only to EQify on your paired phone, through Google Play services on your devices.

**Automation.** If you turn on Settings → Automation, other apps on your phone (like Tasker) can turn EQ on or off, set bass or a preset, or start the sleep timer. They can't read anything from EQify. It is off by default.

**Backups.** Settings → Backup & restore saves your presets, genre curves, device settings and other settings to a file you choose. It includes your headphone and device names, but no listening history. EQify never uploads it; it goes only where you save or share it.

## What EQify does not do

There are no ads or analytics, no account, and no selling or sharing of data for marketing. EQify never reads your messages or other notifications.

## Contact

Questions or deletion requests: use **Settings → Send feedback** in the app, or open an issue at <https://github.com/parthsharma365628/eqify-releases/issues>.
