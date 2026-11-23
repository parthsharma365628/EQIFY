# EQify privacy policy

_Last updated: 23 November 2026. Applies to EQify for Android, version 0.1.0-beta and later._

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

Your presets and customised genre curves, settings, genre corrections, recent tracks and the weekly genre mix. EQify also keeps imported GraphicEQ files and downloaded headphone corrections there. You can clear these in **Settings → Data & privacy**, or remove all of them by uninstalling the app.

## What EQify does not do

There are no ads or analytics, no account, and no selling or sharing of data for marketing. EQify never reads your messages or other notifications.

## Contact

Questions or deletion requests: use **Settings → Send feedback** in the app, or open an issue at <https://github.com/parthsharma365628/eqify-releases/issues>.
