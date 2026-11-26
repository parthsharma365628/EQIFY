# **EQify — Android**

Automatic Intelligent Equalizer

Product Requirements Document | v3.1, November 2026 | Android-only scope

> This document supersedes `EQify_PRD.docx` (v1.0, March 2026) **for the Android app only**; that file is kept unchanged. v3.1 reflects the repository at **0.5.0-beta** (versionCode 6, 2026-11-27). Feature status lives in `STATUS.md`, and design detail in the feature documents listed in Section 13. Where this document and the repository disagree, the repository wins. The file name stays `…_v2.md` so links keep working; v2.1 is in git history.

---

## 1. Executive Summary

EQify is an automatic, intelligent equalizer for Android. It detects the genre of the song playing and applies a matching tone, plus a correction for the user's exact headphones, in real time and without manual switching. Manual control is always available as a fallback.

Between v2.1 (October) and v3.0, EQify went from a pre-release app to a **public beta distributed outside the Play Store**:

- **Releases:** five beta versions (0.1.0 → 0.5.0). Signed release builds, R8 shrinking, a privacy policy and an open-source licences screen.
- **Distribution:** GitHub Releases plus Obtainium, and an in-app update check. The Play Store was deliberately not used (Section 9).
- **App ID and licence:** `com.liteplane.eqify`, closed source (all rights reserved).
- **Features:** about 30 since v2.1, including per-device sound, loudness compensation, backup, sleep timer, safe-listening reminders, widget, Wear OS remote and ParametricEQ import (Section 4).

**The main gap is no longer features. It is validation.** Most of 0.2.0–0.5.0 has passed unit tests and release builds but has never run on a phone or watch. The next milestone is device testing and a small closed group of testers, not more building (Section 10).

---

## 2. Problem Statement

Unchanged from v1.0:

- **Headphones colour sound.** The same song sounds different on a bass-heavy earbud than on a neutral pair, and most listeners never correct for it.
- **Genres want different curves.** Nobody switches EQ presets by hand between songs.
- **No compared product combines both.** Wavelet and Poweramp offer strong headphone correction and per-device presets, but their official material doesn't advertise automatic genre switching (Section 7).

EQify's differentiator is **automatic genre detection combined with automatic headphone correction**, with no manual switching. That is a positioning claim from the products compared here, not proof that no product anywhere does it.

---

## 3. Target Users

| | |
|---|---|
| **Age range** | 13–40 |
| **Tech savviness** | Slightly tech-savvy to audiophile |
| **Platform** | Android 8.0+ (phone), Wear OS 3+ (optional remote) |
| **Music apps** | Spotify, YouTube Music, YouTube, Apple Music (the four supported for song detection) |
| **Headphones** | Bluetooth, USB-C and wired, all price points |
| **Geography** | Global, English first |

**Persona A, the casual streamer (16–28).** Listens 2–4 hours a day on mid-range headphones, has never touched an EQ, wants it to "just sound good". Served by: automatic genre EQ, headphone detection, the tour, sensible defaults.

**Persona B, the audiophile (22–40).** Owns premium headphones, has used Wavelet or AutoEQ by hand, wants automation with full control. Served by: AutoEQ import, sound layers, per-device sound, the live spectrum, Match loudness, favourites.

Because the age range includes teenagers, safe-listening reminders (with a sensitive mode) and the volume limit matter for this audience.

---

## 4. Product Principles and Non-goals

**Principles**
1. **Automatic first.** The default experience needs no setup beyond permissions. Every manual control is a fallback.
2. **Don't overwhelm.** New options start at "Same as default" or off. Advanced controls live in sheets and Settings, not on Home.
3. **Local first.** No account. Settings, history and listening data stay on the phone. Backups are files the user controls.
4. **No ads, no analytics.** Crash reports (Sentry) are scrubbed of listening data and can be turned off.
5. **Honest copy.** Estimates are labelled as estimates (safe listening, genre confidence). Stats count detected songs, not listening time.
6. **Never break the sound.** Two EQ paths, automatic and manual, stay independent. Each layer has one owner (`AI.md`).

**Non-goals for now**
- A local music file player (see Section 12).
- Cloud sync or accounts.
- Play Store distribution (revisit after the beta; Section 9).
- Song detection in apps other than the four supported ones.

---

## 5. Feature Status

Feature-by-feature status lives in **[STATUS.md](STATUS.md)**, updated with every release.

At 0.5.0-beta, a handful of 0.1 features are device-validated: AutoEQ correction, manual EQ, bass, output protection and themes. Everything added in 0.2–0.5 is **built but not yet checked on a phone**. That is the main risk (Section 11) and the first roadmap item (Section 10).

---

## 6. Technical Architecture (as built)

### 6.1 Stack

| Layer | What's running |
|---|---|
| Phone app | Kotlin + Jetpack Compose. `minSdk 26`, `targetSdk 36`. Package and app ID `com.liteplane.eqify`. Two shipped components keep `com.example.eqify` class names so existing users keep their permissions (`AI.md`). |
| Watch app | `:wear` module, Compose for Wear OS, `minSdk 30`. Talks to the phone through the Wearable Data Layer. |
| Audio | Android `Equalizer` (8 logical bands mapped to the device's bands) and `DynamicsProcessing` (Balanced limiter, stereo balance, Orbit). |
| Song detection | `MediaListenerService` reading media notifications from four apps. |
| Headphone data | AutoEQ, converted offline, published as static JSON on GitHub Pages (`eqify-data`). |
| Genre lookup | On device, Last.fm (optional key) and iTunes. The Node backend exists but release builds don't call it. |
| Storage | DataStore (settings, presets, device profiles, listening dose), Room (genre history and corrections), small JSON files (imported corrections). |
| Services used | GitHub Pages and the GitHub API (data, update check), iTunes and Last.fm (genre), Sentry (crashes), Google Play services (QR scanning, Wear). |

### 6.2 Sound path

```
Media notification ─► NowPlayingState ─► GenreCoordinator (correction → local → Last.fm → iTunes)
                                                  │
Output device ─► HeadphoneDetector ─► DeviceSoundCoordinator (per-device mode, preset, bass, strength)
                                                  │
Media volume ─► one volume monitor ─► bass easing, loudness, volume limit, device volume, safe listening
                                                  │
                                                  ▼
EqProcessingService gain chain (both paths):
  tone (× genre strength) → headphone correction → bass (after easing) → loudness → level match → protection
                                                  │
                                                  ▼
EqEngine: Equalizer bands + DynamicsProcessing (limiter; per-channel gain = balance + Orbit)
```

**Rules that keep features from breaking each other.** These are recorded in the batch plans and `AI.md`:
- The manual slider path never waits on the network.
- There is one media-volume receiver.
- One writer sets per-channel gain.
- Bass changes always go through `DeviceSoundCoordinator`.
- Every outside surface (widget, tiles, watch) updates from `ExternalStatus`.
- Every new user setting is added to the backup allowlist.

### 6.3 Why genre isn't read from Spotify's API
Unchanged from v2.1. Official streaming APIs restrict always-on, cross-app reading, so EQify infers genre from title and artist in media notifications. That is why genre correction and confidence display matter.

---

## 7. Competitive Analysis

| Product | Strengths | Auto-genre EQ | Headphone correction |
|---|---|---|---|
| **Wavelet** | Mature AutoEQ, ISO 226 loudness compensation, saved profiles per headphone, virtualizer and reverb, custom import | Not advertised | Yes |
| **Poweramp Equalizer** | Deep graphic and parametric EQ, per-device and per-app presets, AutoEQ presets | Not advertised | Yes |
| **EQify** | Automatic genre plus automatic correction; loudness compensation, GraphicEQ and ParametricEQ import, per-device sound, safe listening, Wear remote; no ads or account | **Yes** | Yes |

**Where EQify still trails:**
- A true parametric EQ.
- A virtualizer, spatial audio or crossfeed.
- Per-app profiles.
- A store presence (no Play listing, and Play Protect warns at install).

Re-check competitor facts before quoting them publicly.

---

## 8. Success Metrics

v2.1's targets (Play rating, DAU/MAU, Day-7 and Day-30 retention) **can't be measured**. EQify has no analytics by design and isn't on the Play Store. For the beta, use what can be measured:

| Metric | Source | Target for the beta |
|---|---|---|
| Installs | GitHub release asset download counts | 100 in the first month of public beta |
| Crash-free sessions | Sentry (users who keep reporting on) | > 99% |
| Testers on distinct phone brands | Feedback reports | At least 4 brands (Samsung, Pixel, Xiaomi, OnePlus or similar) |
| Feedback volume and themes | Feedback email, GitHub issues | A ranked list of the top 5 requests and top 5 problems |
| Tester survey | A short form at 2 weeks | Majority say auto-EQ sounds better than off |

If retention data is wanted later, it needs an **opt-in**, anonymous metric and a privacy policy change. It must not happen silently.

"EQ latency < 10 ms" is dropped. EQify uses Android's own effects, and there's no app-level measurement to report.

---

## 9. Distribution and Release

- **Channel:** GitHub Releases on the public `eqify-releases` repo (closed-source binaries, release notes, privacy policy, issue tracker). Obtainium gives automatic updates.
- **Why not Play (yet):** it avoids the $25 developer fee for now, and gives faster iteration during beta. Revisit once testers confirm the app works across phones.
- **Install friction to explain in every release:**
  - Play Protect's warning: tap "Install anyway".
  - Android 13+ "restricted settings" before notification access can be granted.
  - The Wear APK needs adb over Wi-Fi.
- **Each release:**
  - Bump the version in both modules.
  - Run the tests, then release builds.
  - Check both APKs share the signing key.
  - Smoke-test on a phone.
  - Write the release notes.
- **Licence:** closed source, all rights reserved. AutoEQ data is attributed (MIT software; measurement-source rights stay under review, `GITHUB_PAGES_AUTOEQ.md`).

---

## 10. Roadmap

**Now: validate and release**
1. Device-test 0.2.0–0.5.0 on a phone, and the watch app on a watch.
   - Volume features together, sleep fade, widget, tiles and shortcuts.
   - Rotation, backup round trip, spectrum, balance.
   - Song detection after an update.
2. Fix what breaks, then publish **v0.5.0-beta** (phone and watch APKs).
3. Recruit 5–20 testers across phone brands. Collect feedback through Settings → Send feedback.

**Next: chosen from feedback.** Candidates, in suggested order:

| Candidate | Effort | Why |
|---|---|---|
| Per-app profiles | M | Fits the automatic design; covers apps where genre inference is weak (podcasts, video) |
| Night mode / dynamic-range compression | M | Practical; reuses the `DynamicsProcessing` effect already attached |
| Parametric EQ | L | The enthusiast request; ParametricEQ import already has the maths |
| Orbit → spatial / 8D mode | L | Depends on the prototype's results on real phones |
| Smarter genre detection (per-artist learning, more apps) | M | Fewer wrong genres |
| Hearing test → personal curve | L–XL | Strong Pro feature; needs calibration and careful non-medical copy |
| Translations | M | Bigger markets |

**Monetisation, when there's demand.** These are options, not commitments:
- An optional **EQify Pro** with parametric EQ, a hearing test or spatial audio.
- Donations (GitHub Sponsors, Ko-fi).

Keep the core automatic EQ free, and keep the app free of ads.

---

## 11. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Four releases of features have never run on a device | **High** | Device validation before publishing (Section 10). |
| Feature surface grew fast (about 30 features in a week) for a two-person team | **High** | Freeze new features until beta feedback; keep the conflict rules in `AI.md`. |
| Genre inference is sometimes wrong | Medium (was High) | Genre correction, confidence display and genre strength now ship. |
| Sideloading friction (Play Protect, restricted settings, adb for the watch) loses installs | Medium | Clear install steps in the README and release notes; reconsider Play after the beta. |
| Safe-listening numbers taken as medical advice | Medium | Always labelled "estimated"; WHO guidance cited; non-medical wording; sensitive mode. |
| "Record audio" permission alarms users | Medium | Asked only when the spectrum opens, with an explanation; never the microphone. |
| Foreground service killed by OEM battery management | Medium | Test on Samsung, Xiaomi and OnePlus; document battery exemptions. |
| GitHub Pages outage (headphone data) | Medium | On-device cache keeps selected corrections working offline. |
| AutoEQ measurement-data rights | Medium | Keep attribution current; ongoing review. |
| Phones without Google Play services lose QR scanning and Wear | Low–Medium | The rest of the app works; say so in the README. |
| Exported automation receiver | Low | Off by default, allowlisted commands, no data returned. |
| Notification-listener permission is sensitive | Medium | Narrow use (four apps, title and artist only), explained in-app and in the privacy policy. |

---

## 12. Future Plans (beyond the current Android roadmap)

- **Local music file player.** Decided **not now**. It would allow real parametric filters, crossfeed and exact safe-listening levels. But it doubles the audio paths to test, needs a media library and player, and most target users stream. Reconsider only if testers ask. A one-file reference player for demos and hearing tests is the cheaper middle step.
- **Spatial audio / 8D** (from Orbit). Needs timing and head-related filtering beyond left/right gain; feasibility depends on the phone.
- **Stereo widening and crossfeed.** `DynamicsProcessing` can't mix channels; needs another approach.
- **On-device ML genre classifier.** Removes the dependence on metadata; post-launch R&D.
- **Community presets and headphone requests.** Need a backend, moderation and running costs.
- **Noise-aware EQ** (microphone). Needs microphone permission, battery testing and a privacy design.
- **Cloud sync.** Only with a second platform or real multi-device demand.
- **Windows app and browser extension.** Unchanged from v1.0; gated on Android stability.
- **Play Store listing.** After the beta proves out.

---

## 13. Document Notes

- **Sources for v3.0:** the repository at 0.5.0-beta, `AI.md`, `README.md`, `PRIVACY.md`, `RELEASING.md`, and the feature documents:
  - `FEATURES_NEXT_PLAN.md` (0.2), `FEATURES_BATCH2_PLAN.md` (0.3), `FEATURES_BATCH3_PLAN.md` (0.4), `FEATURES_BATCH4_PLAN.md` (0.5).
  - `FEATURE_HEADPHONE_DETECTION.md`, `FEATURE_PROFILE_SHARING.md`, `FEATURE_CUSTOM_GENRE_CURVES.md`, `FEATURE_CUSTOM_AUTOEQ_IMPORT.md`, `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md`, `FEATURE_EQ_TRANSITION_SMOOTHING.md`, `FEATURE_CRASH_REPORTING.md`, `UI_REDESIGN.md`, `DESIGN_REVIEW.md`.
- **Safe-listening research:** the WHO–ITU H.870 summary (who.int) and Android's Sound dose documentation (source.android.com), read 2026-11-27.
- **Competitor claims:** from v2.1's sources (Wavelet docs and Play listing, Poweramp's product page). Re-check before publishing.
- **Effort ratings:** S = days, M = 1–2 weeks, L = 2–4 weeks, XL = a month or more, for two people. Estimates, not commitments.
- **Keeping this current:** feature status moved to `STATUS.md` (2026-11-27). Update this document when goals, principles, metrics or the roadmap change.
- **The original:** `EQify_PRD.docx` stays unmodified (`AI.md`).

### Change log

| Version | Date | Changes |
|---|---|---|
| v2.0 | 2026-09-25 | Android-only rewrite of the v1.0 PRD. |
| v2.1 | 2026-10-11 | Pages headphone path validated; genre correction, history and weekly mix built. |
| v3.1 | 2026-11-27 | Feature status moved to `STATUS.md`; Section 5 now points there. |
| v3.0 | 2026-11-27 | Rewritten for 0.5.0-beta. Status table covers 0.1–0.5, with every feature marked validated or built. New sections: principles and non-goals, distribution and release, monetisation options. Metrics replaced with measurable beta metrics (no analytics by design). Architecture updated: gain chain, volume monitor, per-device sound, Wear module, backend off in release. Risks re-rated, with the untested backlog and feature growth as top risks. Local-player decision recorded. Roadmap: validate, publish, testers, then feedback-driven. |
