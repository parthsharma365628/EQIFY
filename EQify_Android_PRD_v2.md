# **EQify — Android**

Automatic Intelligent Equalizer

Product Requirements Document | v2.0 September 2026 | Android-only scope

> This document supersedes `EQify_PRD.docx` (v1.0, March 2026) **for the Android app only**. The original PRD's cross-platform vision (Windows, browser extension, cloud sync, ML genre model) is preserved and re-scoped under **Section 11 — Future Plans**, not discarded. This version reflects what has actually been built as of the `EQIFY` repository state on **2026-09-25**, cross-checked against `AI.md`, `GITHUB_PAGES_AUTOEQ.md`, `CLOUDFLARE_AUTOEQ_MIGRATION.md`, and `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md`. Where this document's architecture assumptions conflict with the live repository, the repository wins — treat this as a snapshot to re-verify, not a permanent spec.

---

## 1. Executive Summary

EQify is an automatic, intelligent equalizer for Android that detects the genre of the currently playing track and applies an EQ profile tuned to both genre and the user's specific headphone model, in real time and without manual intervention. It combines three previously separate ideas — genre-aware tone shaping, headphone-specific AutoEQ correction, and manual fine control as a fallback — into one zero-friction app.

Since v1.0 of the PRD, the product has narrowed from a three-platform bet (Android + Windows + browser) to a **deliberately Android-first, bootstrapped build**, and a meaningful share of the v1.0 roadmap is now implemented and running, not just planned:

- Genre detection is live, using on-device signals plus a small external-lookup chain — not the official Spotify/Apple Music APIs the original PRD assumed (see Section 5.3 for why that changed).
- The full AutoEQ headphone correction database (6,028 profiles) is converted and published through GitHub Pages. Client integration is implemented but still needs device validation; it caches the catalog and downloads only the selected profile, with no SQLite bundling or per-request Node backend hit.
- The core automatic EQ pipeline, manual override panel, bass boost, output protection (limiter-based and safe-scaling fallback), and Bluetooth/wired headphone detection are implemented. The Quick Settings tile is built but still needs explicit device validation.
- A fully designed (not yet built) local data layer for genre correction, per-track history, and weekly listening insights exists as a source-verified plan, ready to implement.

This document re-prioritizes what's left to build for Android, adds a researched set of new feature candidates (validated in part against what competitors — chiefly Wavelet — actually ship in 2026), and rates each by implementation effort for a two-person team.

---

## 2. Problem Statement

Unchanged from v1.0, and still the core thesis:

- **Headphones color sound.** The same file sounds different on a bass-heavy consumer IEM than on a neutral studio-tuned pair, and most listeners never correct for it.
- **Genres want different curves.** EDM wants punch; classical wants a flat, wide response; podcasts and vocal-heavy content want boosted mids and controlled dynamics. Nobody switches EQ presets by hand between songs.
- **Compared tools do not advertise this exact combination.** Wavelet solves headphone correction and volume-aware loudness compensation well and can load saved profiles by headphone, but its official material does not advertise automatic genre awareness. Poweramp combines deep manual control with AutoEQ and per-device/per-app presets, but likewise does not advertise genre inference. Typical built-in streaming-app EQs remain preset-oriented rather than combining headphone and genre logic.

EQify's differentiator — automatic genre detection **combined with** automatic headphone correction, requiring zero manual switching — is not advertised by the compared products in this research pass (Section 7). This is a competitive positioning claim, not proof that no other product offers it.

---

## 3. Target Users

Unchanged from v1.0; still accurate for the Android-only build.

| | |
|---|---|
| **Age Range** | 13–40 |
| **Tech Savviness** | Slightly tech-savvy to audiophile |
| **Primary Platform** | Android (only platform for this document's scope) |
| **Streaming Services** | Spotify, Apple Music, YouTube Music, YouTube, local files |
| **Headphone Usage** | Personal wired & wireless headphones, all price points |
| **Geography** | Global, English-speaking markets first |

**Persona A — The Casual Streamer (16–28).** Listens 2–4 hrs/day, mid-range headphones, has never touched an EQ slider, wants it to "just sound good."

**Persona B — The Audiophile Enthusiast (22–40).** Owns premium headphones, has used Wavelet/AutoEQ manually, finds genre-switching by hand tedious, wants automation with a manual fallback and will give detailed feedback.

---

## 4. Current Implementation Status (as of 2026-09-25)

This is the section the v1.0 PRD didn't have, because nothing was built yet. Status values: **Implemented and device-validated**, **Built, not yet validated on device** (code exists, needs field testing), **Planned — documented** (a written, checked design exists but no code), **Not started**. Because EQify is pre-launch, “implemented” does not mean publicly shipped.

| Capability | Status | Where it lives | Notes |
|---|---|---|---|
| Automatic genre detection | **Implemented and device-validated** | `HomeViewModel.kt`, `Localgenredetector.kt` | Chain: local detector → optional Last.fm → iTunes → backend `POST /api/v1/genre` (cache → Last.fm → iTunes → keyword → default). It currently runs only while `HomeViewModel` is alive. |
| Genre source/confidence display | **Implemented** | `HomeViewModel.kt`, `HomeScreen.kt` | The Now Playing card already shows source plus Low/Medium/High confidence and marks fallback results. Confidence is heuristic, not a calibrated probability. |
| Headphone correction database | **Built, not yet validated on device** | GitHub Pages (`eqify-data`), `HeadphoneDataRepository.kt` | The 6,028-profile Pages deployment is live and HTTP-verified. Android catalog caching, local search, and on-demand profile download are implemented but still need a device test. |
| Automatic EQ application | **Implemented and device-validated** | `EqProcessingService.kt`, `EqProfileManager.kt` | Combines genre curve → headphone correction → bass boost into 8 logical bands, mapped to device band count. The new Pages input path remains part of the pending validation above. |
| Manual EQ override | **Implemented and device-validated** | `EqScreen.kt` | Sliders, built-in and global user-named presets, bass, A/B bypass comparison, save, and rename. Custom presets are not currently scoped per headphone or genre. |
| Output protection | **Implemented and device-validated** | `OutputProtectionMode.kt`, `EqEngine.kt` | Balanced (API 28+ limiter) / Safe (static scaling, all API levels) / Off. Migrates old `limit_output_gain` boolean automatically. |
| Bluetooth/wired headphone auto-detect | **Implemented and device-validated** | `Bluetoothheadphonedetector.kt` | Selects the matching headphone database entry; wired devices require user confirmation because Android exposes a generic name. |
| Quick Settings tile | **Built, not yet validated on device** | `EqQuickSettingsTileService.kt`, `EqProcessingSnapshot.kt` | Designed to reflect service state rather than only a stored preference; device behavior still needs confirmation. |
| Offline headphone-gain reuse | **Built, not yet validated with Pages client** | `HeadphoneEqDiskCache.kt` | Selected gains are cached on disk; the existing cache path is proven, but reuse after a Pages download still needs an end-to-end device check. |
| Settings, privacy copy, cache controls | **Implemented** | `SettingsScreen.kt` | The AutoEQ database subtitle still says “EQify backend” and must be updated to GitHub Pages before release. “Clear downloaded corrections” clears selected gain curves, not the cached catalog index. |
| Genre correction (user overrides a detected genre) | **Planned — documented** | `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md` | Full Room schema, flows, and edge cases specified and checked against source. Not implemented. |
| Per-track listening history (supporting data only) | **Planned — documented** | same doc | Feeds the correction picker and weekly insights; not a browsable history screen by design. |
| Weekly genre listening insights | **Planned — documented**, blocked | same doc | **Blocker**: genre resolution is currently scoped to `HomeViewModel`/activity lifetime, so it can't see background listening yet. Needs the coordinator-lifecycle fix described in that doc before the insight becomes trustworthy. |
| Built-in genre presets (EDM, Classical, Hip-Hop, Jazz, Pop, Rock, etc.) | **Implemented** | `EqProfileManager.kt` | Canonical genre set = `defaultProfiles.keys` minus `Flat`/`Bass Boost`. |
| Custom genre profiles (user-defined tags) | **Not started** | — | Re-scoped in Section 6 below given what's now known about the canonical-genre-list constraint. |
| Streaming-app deep API integration (official Spotify/Apple Music APIs) | **Not started, re-scoped** | — | See Section 5.3 — this was replaced by notification-metadata detection, not merely deferred. |
| Cloud account sync | **Not started** | — | Moved to Future Plans (Section 11); no backend/account system exists. |
| Crash reporting | **Not started** | — | v1.0 PRD assumed Crashlytics; not present in the current dependency list per `AI.md`/`app/build.gradle.kts`. |
| Play Store listing / public release | **Not started** | — | No evidence in the repo of a release build, signing config, or store listing work. |

---

## 5. Technical Architecture (as-built)

### 5.1 Stack

| Layer | v1.0 PRD assumption | What's actually running |
|---|---|---|
| Android app | Kotlin + Jetpack Compose, AudioEffect/Oboe | Kotlin + Jetpack Compose, Android `Equalizer` + `DynamicsProcessing` effects (not a native Oboe layer). `minSdk 26`, `targetSdk 36`. |
| Genre detection | Spotify/YouTube Music API metadata, later on-device ML | Notification-metadata via `MediaListenerService` → local heuristic detector → Last.fm → iTunes → backend keyword/default fallback. No streaming-service API integration. |
| Headphone database | AutoEQ dataset in local SQLite, periodic sync | AutoEQ dataset converted offline, published as static JSON on GitHub Pages, cached client-side. No SQLite; no periodic backend sync — a manual re-export/re-publish step. |
| Backend | Node.js/FastAPI for account sync, headphone DB updates, analytics | Node/Express, now scoped to exactly one job: the last-resort step of genre detection (`POST /api/v1/genre`). Headphone routes still exist for backend tooling/tests but the app doesn't call them. |
| Cloud infrastructure | Firebase (auth, Firestore, Crashlytics) | None implemented. Everything is local-only (DataStore + disk cache). |
| Local persistence | SQLite | Jetpack DataStore (`UserPreferencesRepository`) for settings/presets/snapshot. No Room yet — the genre-correction plan would introduce it. |

### 5.2 Data flow (as-built)

```
Notification metadata (MediaListenerService)
        │
        ▼
NowPlayingState (title, artist)
        │
        ▼
HomeViewModel: local detector → Last.fm → iTunes → backend (POST /api/v1/genre)
        │
        ▼
NowPlayingState.currentGenre
        │
        ▼
EqProcessingService (automatic path) ──┐
        │                              ├─► EqProfileManager.combineGains
Bluetoothheadphonedetector ──► HeadphoneDataRepository (GitHub Pages index + profile) ─┘
        │
        ▼
EqEngine (Equalizer + DynamicsProcessing) ──► audio session
```

Manual slider input goes through a **separate, always-fast path** in `EqProcessingService` that never waits on network calls — this separation is explicitly load-bearing and every future feature must preserve it.

### 5.3 Why genre detection isn't "Spotify API metadata" anymore

The v1.0 PRD assumed genre would come from official streaming-service APIs. That approach was abandoned, not merely delayed:

- Official streaming APIs increasingly restrict or require partner approval for the kind of always-on, cross-app metadata reading this product needs (the exact risk the v1.0 risk register flagged as "High").
- Notification-listener metadata can support multiple apps without official service APIs, but the current implementation deliberately whitelists only Spotify, YouTube Music, YouTube, and Apple Music for Android. Local players and other media apps are not currently processed even if they post standard media notifications; broader support requires an explicit compatibility and metadata-quality change.
- The trade-off: metadata is limited to title/artist text, so genre still has to be *inferred* (Last.fm/iTunes lookup, keyword fallback) rather than read directly off a `genre` field an API would provide. This is why genre confidence and user correction (Section 6) matter more here than they would in the original design.

### 5.4 Known architectural constraint to resolve before insights ship

Genre resolution lives in `HomeViewModel`, which is scoped to the Home screen/activity, not the always-alive `EqProcessingService`. Any feature promising "what you listened to today/this week" is only as honest as this constraint allows — right now it would really mean "what you listened to while the app was open." This is documented as the #1 open item in `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md` and should be treated as a prerequisite, not a nice-to-have, before that feature ships with unqualified "weekly insights" language.

---

## 6. Feature Requirements — Updated & New

Effort scale (two-person team, existing codebase, no new hires assumed):

| Rating | Meaning |
|---|---|
| **XS** | Hours. Config/UI-only change, no new logic. |
| **S** | 1–3 days. Self-contained, existing infra, no new dependency. |
| **M** | ~1–2 weeks. New UI + logic, still no new persistence/infra. |
| **L** | ~2–4 weeks. New persistence layer, cross-cutting change, or first-time integration of a new system (e.g. Room, mic access). |
| **XL** | 1+ month, ongoing, or genuine R&D risk (on-device ML, cloud accounts, new platform). |

### 6.1 Carried over from v1.0, re-prioritized for Android-only scope

| Feature | Priority | Status | Effort (remaining) | Notes |
|---|---|---|---|---|
| Genre Detection | P0 | **Implemented, lifecycle limitation remains** | — | Notification-based for four whitelisted apps; resolution currently depends on `HomeViewModel` being alive (Sections 5.3–5.4). |
| Headphone Database | P0 | **Deployment done; client validation pending** | **S** testing | GitHub Pages is live; validate first download, local search, selected-profile fetch, failure messaging, and offline cache reuse on device. |
| Auto Equalizer | P0 | **Done** | — | |
| Built-in EQ Presets | P1 | **Done** | — | |
| Headphone Auto-Detection | P1 | **Done** | — | Bluetooth + wired; no USB-descriptor parsing beyond what Android exposes. |
| User EQ Control | P1 | **Done** | — | |
| Custom Genre Profiles | P2 | Re-scoped | **M** | Existing user-named EQ presets are global and are not custom genre mappings. This feature would map a user-defined genre label to a chosen curve. It must remain separate from automatically detecting that label from metadata, which is a larger problem. |
| Streaming App Integration (official APIs) | P2 | **Dropped** | — | Superseded by notification-metadata approach; re-adding an official API would be a parallel detection path, not a replacement, and isn't worth the partner-approval risk for the accuracy gain. |
| User Profile Sync (cloud) | P2 | Deferred | **XL** | Needs an account system and backend that doesn't exist yet. Moved to Future Plans. |

### 6.2 Already fully specified, ready to build

| Feature | Priority | Effort | Notes |
|---|---|---|---|
| Genre correction (fix a wrongly detected genre) | **P0** for trust in auto-detection | **L** | Full design in `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md`. Requires adding Room. Should ship *before or alongside* the lifecycle fix in 5.4, since correction has value even without insights. |
| Recent-tracks list (supports correction UI) | P1 | **S**, once above lands | Bundled in the same doc; small once the data layer exists. |
| Weekly genre listening insights | P2 | **M**, but **blocked** on 5.4 | Don't ship with "weekly" framing until the lifecycle question is resolved — ship as "this session" insights first if the coordinator refactor is deferred. |

### 6.3 New feature candidates (this pass's research + prior brainstorm)

Researched against Wavelet's current official feature documentation and Play listing, Poweramp's official product page, Apple's Headphone Accommodations documentation, and Nissan's description of Personalized Sound. These sources validate equal-loudness compensation, custom AutoEQ import, headphone presets, and preference/hearing-profile personalization as established product patterns; they do not validate every implementation or release-date claim from the earlier draft.

| Feature | Description | Priority suggestion | Effort | Why |
|---|---|---|---|---|
| **Loudness-compensated EQ (ISO 226 equal-loudness)** | Adjust the applied curve based on system volume, boosting bass/treble more at low volumes to match how human hearing perception changes with loudness. | **P1** | **M** | Confirmed as a real, current Wavelet feature ("ISO 226-based equal loudness compensation"). Fits directly into the existing gain-composition pipeline as one more stage. Strong "why doesn't ours do this yet" gap versus the closest competitor. |
| **Genre-correction affordance beside confidence** | Keep the existing source/confidence display, but add a correction action when confidence is low or a fallback was used. | **P1** | **S**, after genre correction exists | The indicator itself is already implemented. The remaining product work is connecting it to the planned correction flow and defining better-calibrated confidence semantics. |
| **Per-app profile pinning** | Let a user lock a specific profile to a specific app (e.g. always "Podcast" in a podcast app), overriding genre detection for that app. | **P1** | **M** | Solves cases where genre inference is structurally weak. `MediaListenerService` sees the source package today, but that package is not carried into `NowPlayingState`; the data flow and persistence model must be extended. |
| **Shareable EQ profile export/import (string/QR/file, no cloud)** | Export a tuned headphone+genre combo as a shareable string or small file; import someone else's. | **P2** | **S** | Zero backend cost, fits the "zero external budget" constraint far better than the v1.0 cloud-sync plan, and is a community-building hook (power users share tunings on Reddit/Discord, per the original feedback-channel plan). |
| **Custom AutoEQ import (autoeq.app / squig.link style)** | Let a user paste in their own parametric EQ correction rather than only picking from the bundled 6,028 profiles. | **P2** | **M** | This is a confirmed, marketed Wavelet feature ("Have your own data? Easily import..."). Matters for the audiophile persona whose exact headphone/measurement isn't in the AutoEQ dataset. |
| **Comfort / long-session mode** | Optional, gentle time-based treble taper after N continuous hours of listening in one session. | **P2** | **M**, blocked on reliable session tracking | Pure audio-engineering framing, not a health claim. It reuses the gain pipeline but first needs playback-session timing that survives UI lifecycle changes and handles pauses and route changes correctly. |
| **Local settings backup/restore** | Export `UserPreferencesRepository` state to a file before uninstall/device swap; restore on a new install. | **P2** | **S** | Cheap, no backend, complements the existing disk-cache patterns. |
| **Crowdsourced headphone-correction requests** | If a user's headphone model isn't in the AutoEQ dataset, let them flag it; aggregate requests to prioritize future data work. | **P2** | **M** | Aggregation requires a write-capable endpoint, storage, abuse controls, and privacy copy; GitHub Pages cannot receive requests. A local-only log would not provide useful crowd prioritization. |
| **Track-transition EQ crossfade** | Short (100–300 ms) gain interpolation when the curve changes on a track/genre switch, instead of an instant snap. | **P2** | **M** | Requires cancellable ramping, coordination with immediate manual updates, and device testing to avoid fighting the audio-effect implementation. This is transition smoothness, not the same metric as processing latency. |
| **In-app hearing check → personalized compensation curve** | A short in-app listening check producing a personal EQ layer on top of headphone correction. | **P2** | **L–XL** | Apple documents preference- and audiogram-based Headphone Accommodations, and Nissan advertises an audio test for personalized sound. EQify would still need safety limits, calibration strategy, supported-output rules, careful non-medical copy, and accessibility review. |
| **BLE hearing-aid detection** | Research whether Android exposes enough stable route information to recognize supported BLE hearing devices and whether EQify can process those sessions safely. | **P3 / research** | **L–XL** | The earlier claim that this shipped in Wavelet 26.03+ could not be verified from Wavelet's official feature documentation or Play listing. Treat this as accessibility research, not competitor-parity scope. |
| **Channel balance / basic virtualizer** | Left/right balance compensation; simple stereo-widening effect. | **P3** | **M** (balance) / **L** (virtualizer, needs a real spatial-audio approach to sound good) | Competitor-parity features (Wavelet ships both); lower priority than anything genre/headphone-specific since they're not part of EQify's actual differentiator. |
| **Ambient-aware EQ (mic-based environment detection)** | Use the phone mic to detect a noisy environment and boost mids slightly for intelligibility. | **P3** | **XL** | Real differentiator, no direct competitor does this at the app level — but meaningful lift: mic access, battery cost, and privacy copy all need real design work, not a quick add. |
| **Home-screen widget** | One-tap bypass and current-genre display without opening the app. | **P2** | **S–M** | No home-screen widget exists. A lock-screen toggle is partly covered already by the foreground service notification, so it should not be presented as wholly unbuilt. |
| **Android Auto / car-audio profile** | Auto-switch to a "car" profile when connected to car Bluetooth audio. | **P3** | **M** | Reuses existing Bluetooth-device-detection logic; car audio is a genuinely different acoustic environment from headphones. |
| **Onboarding/first-run tutorial** | Brief guided setup: grant notification access, pick a headphone, explain automatic vs. manual mode. | **P1** (pre-launch) | **S** | Not glamorous, but the v1.0 PRD's own KPIs (Day-7/Day-30 retention) depend heavily on a casual user (Persona A) not bouncing off a confusing first run. |
| **Crash reporting (Crashlytics or equivalent)** | Wire up basic crash reporting before any public beta. | **P0** (pre-launch) | **S** | Assumed already in place by v1.0 PRD; verified absent. Blocking item for the ">99% crash-free sessions" KPI to even be measurable. |

---

## 7. Competitive Analysis (updated)

| Product | Strengths | Weaknesses | Auto-Genre EQ | Headphone Correction | Platform |
|---|---|---|---|---|---|
| **Wavelet** | Mature AutoEQ integration (5,000+ profiles), ISO 226 loudness compensation, automatic saved-profile loading by headphone, virtualizer/reverb, and custom-data import | No automatic genre feature advertised in the official material reviewed | No advertised auto-genre feature | Yes | Android |
| **Poweramp / Poweramp Equalizer** | Deep graphic/parametric EQ, per-device and per-app presets, preset import/export, and built-in AutoEQ presets | Dense controls aimed at advanced users; no advertised automatic genre switching | No advertised auto-genre feature | Yes | Android |
| **EQify (Ours)** | Automatic genre detection **and** automatic headphone correction together, zero manual switching, published open dataset via GitHub Pages | New entrant, no brand recognition, genre inference is metadata-based (not a true audio classifier) so occasionally wrong — mitigated by the planned correction feature | **Yes** | Yes | Android |

**Read on the gap:** Wavelet advertises loudness compensation and automatic loading by headphone, while Poweramp advertises AutoEQ and per-device/per-app presets. Neither product's official material reviewed here advertises automatic genre switching. Among these three products, EQify is the only one documented here as combining automatic genre inference with automatic headphone correction; avoid generalizing that claim to the entire market without broader research.

---

## 8. Success Metrics

Unchanged targets from v1.0 — they were never invalidated, just not yet reachable because there's no public release yet:

| Metric | Target |
|---|---|
| Total Downloads | 5,000 in 90 days of launch; 10,000 in 6 months |
| DAU/MAU | 30% |
| Play Store Rating | 4.0+ stars, reviewed weekly |
| EQ Latency | <10ms |
| Crash-Free Sessions | >99% (currently unmeasurable — no crash reporting wired up, Section 6.3) |
| Day-7 Retention | 40% |
| Day-30 Retention | 20% |

**Current stage relative to these targets: pre-launch.** No evidence of a Play Store listing, signed release build, or crash reporting in the repository. The realistic next milestone is closed beta, not public KPI tracking.

---

## 9. Timeline — where the original phasing actually landed

| Original phase (v1.0) | Status |
|---|---|
| Month 1–2: Foundation, headphone DB, manual EQ | **Mostly done**; the static Pages deployment is live, while the new Android Pages client still needs device validation |
| Month 2–3: Genre metadata integration, auto EQ switching | **Done**, via notification metadata instead of the assumed streaming APIs |
| Month 3–4: Android beta launch, crash reporting, closed beta | **Not started** — this is the actual next milestone |
| Month 4–5: Browser extension | Deferred to Future Plans (Section 11) |
| Month 5–6: Windows app | Deferred to Future Plans (Section 11) |
| Month 6+: ML genre model, custom genres, cloud sync | Custom genres re-scoped smaller (6.1); ML and cloud sync in Future Plans |

Recommended near-term order given everything above: **(1)** validate the GitHub Pages client and Quick Settings tile on device, **(2)** genre correction since it directly protects trust in the app's core promise, **(3)** pre-launch crash reporting and onboarding, **(4)** loudness compensation plus a correction affordance beside the already-existing confidence display, and **(5)** the `HomeViewModel` lifecycle fix before promising “weekly insights.”

---

## 10. Risks & Mitigations (updated)

| Risk | Severity | Mitigation |
|---|---|---|
| Genre inference (metadata-based, not a true classifier) stays visibly wrong for some tracks | **High** | Ship genre correction (6.2), connect it to the existing confidence display, and improve the current heuristic confidence semantics. |
| GitHub Pages dependency for headphone data (single external host, no SLA) | **Medium** | `HeadphoneEqDiskCache` already provides offline continuity; consider a documented manual mirror step if Pages has an extended outage. |
| AutoEQ measurement-source redistribution rights | **Medium** | Already flagged in `GITHUB_PAGES_AUTOEQ.md` as ongoing review work — keep attribution current with every re-export, don't treat the MIT software license as covering the underlying measurement data. |
| Notification-listener permission is sensitive and Play Store-scrutinized | **Medium** | Be explicit and narrow in the Play Store permission declaration; the privacy copy in `SettingsScreen` already exists — keep it current as new data (per-track history, Section 6.2) is added. |
| Foreground service killed by aggressive OEM battery management | **Medium** | Test across major OEM skins (Samsung, Xiaomi, OnePlus) before beta; document any required user-facing battery-exemption guidance. |
| Backend (Node) is a single point of failure for the last-resort genre-detection step | **Low–Medium** | If it is unreachable, Android retains the initial local heuristic result. Keep that client fallback tested; the backend's own keyword/default path helps only when the request reaches the server. |
| Small team, growing feature surface | **High** (unchanged from v1.0) | Same mitigation as v1.0: ruthless prioritization, Android-first, don't start Future Plans items (Section 11) until the pre-launch gate (Section 9) is cleared. |

---

## 11. Future Plans (beyond this document's Android-only scope)

These are the v1.0 PRD items that remain valid product direction but are explicitly **not** part of the current Android-only roadmap. Listed here so they aren't lost, not because any are imminent.

- **Windows desktop app** (Electron + WASAPI/EqualizerAPO) — same rationale as v1.0; still gated on Android reaching stability first, per the v1.0 risk register's own "small team cannot maintain all 3 platforms" mitigation.
- **Browser extension** (Manifest V3 + Web Audio API) — same as above; Offscreen Documents API remains the likely technical path for MV3 audio interception.
- **On-device ML genre classifier** — would remove dependence on title/artist metadata entirely (works for local files, obscure players, and closes the "wrong genre" gap at the source rather than patching it with corrections). Meaningful R&D and model-size/battery cost; realistically post-launch.
- **Cloud account sync** — cross-device profile sync via a real backend + auth system (Firebase or otherwise). Only worth building once there's a second platform (Windows/browser) or genuine multi-device demand from real users, not preemptively.
- **Community-sourced headphone correction platform** — grows the crowdsourced-request feature (6.3) into an actual submission/review pipeline once there's enough user volume to justify the moderation overhead.
- **Custom genre auto-detection** (as opposed to custom genre *profiles*, which is now in scope per 6.1) — teaching the detection chain to recognize a user-defined genre from metadata, not just offering it as a manual bucket. Meaningfully harder than the scoped version and not worth starting until the ML classifier direction above is decided.

---

## 12. Document Notes

- This document was produced by reviewing `AI.md`, `GITHUB_PAGES_AUTOEQ.md`, `CLOUDFLARE_AUTOEQ_MIGRATION.md`, `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md`, and `EQify_PRD.docx` (v1.0), plus external research into current competitor and personalized-audio features.
- External claims were checked against [Wavelet's official feature documentation](https://pittvandewitt.github.io/Wavelet/Features/), [Wavelet's import documentation](https://pittvandewitt.github.io/Wavelet/Import/), [Wavelet's Google Play listing](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet), [Poweramp's official product page](https://powerampapp.com/), [Apple's Headphone Accommodations documentation](https://support.apple.com/en-us/102663), and [Nissan's Personalized Sound description](https://www.nissanusa.com/owners/owner-experience.html). Re-check time-sensitive claims before publication.
- Effort ratings (Section 6) are estimates for a two-person team working in the existing codebase; they are not commitments and should be re-checked against actual velocity once a few of these ship.
- Per `AI.md`'s own guardrail, `EQify_PRD.docx` itself should not be modified — this is a new, separate document.
