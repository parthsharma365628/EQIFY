# EQify status

The one place that says what's built and what's been checked on a device. Update it
with every release: `CLAUDE.md` asks for this after each implementation.
Last updated: 2026-11-27, **0.5.0-beta** (versionCode 6). First emulator check on 2026-11-27: song detection, the tour and the genre-strength slider work.

Design detail is in the feature plans; product goals are in `EQify_Android_PRD_v2.md`.

**Status values**
- **Device-validated:** run and checked on a real phone.
- **Emulator-checked:** run and checked on the Android emulator. Screens, permissions and song detection behave as on a phone; audio effects don't, so sound still needs a phone.
- **Built:** in a release build and covered by unit tests, but not yet checked on a device.
- **Prototype:** debug builds only.
- **Not started.**

"Built" is most of the table: device testing is the first item on the roadmap (PRD Section 10).

## Core sound

| Feature | Version | Status | Notes |
|---|---|---|---|
| Automatic genre detection | 0.1 | Device-validated (earlier path); **app-scoped coordinator emulator-checked** (2026-11-27, 0.5.0-beta debug, after the package rename) | Chain: user correction → local detector → optional Last.fm → iTunes. The Node backend step is **off in release builds** (no `https` URL configured). |
| Genre source and confidence display | 0.1 | Built | Heuristic confidence, not a probability. |
| Genre correction, recent tracks | 0.1 | Built | Room-backed; corrections can point at the user's own presets. |
| Customisable genre curves | 0.1 | Built | Users can edit each built-in genre's curve. |
| Genre strength | 0.2 | **Emulator-checked** (slider, 2026-11-27) | Scales only automatic genre EQ, globally or per device. The audible effect still needs a phone. |
| AutoEQ headphone correction (6,000+ profiles) | 0.1 | Device-validated | Static JSON on GitHub Pages; catalog cached, profiles downloaded on demand. |
| Headphone detection (Bluetooth, USB-C, wired) | 0.1 | Built | Remembers the choice per device; no correction on the speaker or "Not headphones" devices. |
| Custom correction import: GraphicEQ | 0.1 | Built | File or pasted autoeq.app table. |
| Custom correction import: ParametricEQ | 0.5 | Built | AutoEQ `ParametricEQ.txt`; RBJ biquads; each band takes the mean response over its frequency span. Preamp ignored (protection handles headroom). |
| Manual 8-band EQ, presets, A/B bypass | 0.1 | Device-validated | |
| Match loudness (A/B) | 0.5 | Built | EQ lowered by its mean boost while comparing; off when leaving the EQ screen. |
| Dialogue preset | 0.5 | Built | Built-in, not offered as a genre. |
| Preset favourites and reorder | 0.5 | Built | Follow renames and deletes; in backups. |
| Bass boost | 0.1 | Device-validated | Off / +3 / +6 / +9 dB. |
| Ease bass at high volume | 0.3 | Built | Above 60% volume, bass fades to half at full volume. Off by default. |
| Loudness compensation | 0.4 | Built | Below 50% volume, adds bass and treble. Off by default. Never overlaps bass easing (tested). |
| Output protection (Balanced limiter / Safe / Off) | 0.1 | Device-validated | Balanced needs Android 9+. |
| Smooth EQ transitions | 0.1 | Built | 250 ms ramp on genre and correction changes. |
| Stereo balance | 0.5 | Built | Needs Balanced protection; otherwise links to Android's own setting. |
| Per-device sound ("My devices") | 0.2 | Built | Each output can follow genre or use a preset, with its own bass and strength. |
| Remember volume per device | 0.5 | Built | Off by default; never above the volume limit. |
| Sound layers graph | 0.2 | Built | Tone, correction, bass and total, with a plain-language summary. |
| Live spectrum | 0.5 | Built | `Visualizer`; asks for "Record audio" only when opened. Reads EQify's output, never the microphone. |
| Test sounds | 0.4 | Built | Low/mid/high, left only, right only, 20 Hz–20 kHz sweep. |
| Haptic slider ticks | 0.5 | Built | |

## Listening health

| Feature | Version | Status | Notes |
|---|---|---|---|
| Safe-listening reminders | 0.5 | Built | WHO–ITU H.870: 80 dB / 40 h a week (adults) or 75 dB (sensitive), 3 dB exchange rate, rolling 7 days, headphones only. **Estimated** from volume, not measured (Android's own sound-dose data has no app API). Reminders at each 100%. On by default. |
| Volume limit | 0.4 | Built | 50–90% of media volume. Off by default. |
| Sleep timer | 0.4 | Built | 15–60 min or end of track; 30 s fade, pause through audio focus, volume restored. |

## Controls outside the app

| Feature | Version | Status | Notes |
|---|---|---|---|
| Notification controls | 0.3 | Built | Pause/Resume, Bass cycle, Cancel sleep. |
| Quick-settings tiles | 0.1 / 0.5 | Built | EQ on/off (0.1), Bass and Sleep timer (0.5). |
| Home screen widget | 0.4 | Built | Status, Pause and Bass. |
| App-icon shortcuts | 0.4 | Built | Pause, Bass, Sleep in 30 min. |
| Tasker / automation | 0.4 | Built | Allowlisted broadcast commands. **Off by default.** |
| Wear OS remote | 0.5 | Built | Separate APK, same ID and signing key; sideloaded. |

## Insight, sharing and data

| Feature | Version | Status | Notes |
|---|---|---|---|
| Your week in sound | 0.2 | Built | Genres, top artists, time of day, shareable card. Counts detected songs. |
| "What changed" log | 0.5 | Built | In memory only; no song titles. |
| Profile sharing (code, file, QR, link) | 0.1 | Partly device-validated | QR scanning via Google's code scanner (no camera permission). |
| Backup and restore | 0.3 | Built | Replace or Merge, validated before applying, Undo. Never includes history, listening dose, device volumes or the automation switch. |

## App, release and quality

| Feature | Version | Status | Notes |
|---|---|---|---|
| Onboarding tour | 0.4 | **Emulator-checked** (2026-11-27) | Three pages, then the permission steps; replay from Settings. |
| Landscape and tablet layout | 0.4 | Built | Navigation rail at 600 dp+; side-by-side EQ in landscape. |
| Themes | 0.1 | Device-validated | |
| In-app update check | 0.2 | Built | Daily, against the public releases repo. |
| Crash reporting (Sentry) | 0.1 | Built | Opt-out, scrubbed. |
| Signed release builds, R8 | 0.1 | Done | `RELEASING.md`. |
| Orbit stereo-motion prototype | — | Prototype | Debug only (Shreyansh); a possible step toward spatial audio. |

## How to update

- **A new feature:** add a row with its version and status **Built**.
- **After testing on a phone:** change it to **Device-validated**, and add the device and date in Notes (for example, "Pixel 7, Android 15, 2026-12-02").
- **After testing on the emulator:** use **Emulator-checked** with the date. Audio features stay **Built** until heard on a phone.
- **Testing tip:** uninstall any old `com.example.eqify` build first. It keeps its own notification access and EQ, so two equalizers compete and the new app can't detect songs.
- **A problem found on a device:** keep it **Built**, note the problem, and link the issue.
