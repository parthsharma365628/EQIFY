# AI.md

This file gives AI coding assistants durable context for working in the EQify repository. Explicit user instructions always take priority over this document.

## What this is

EQify is an Android equalizer that combines genre profiles, headphone-specific AutoEQ corrections, bass boost, and manual EQ adjustments. The Android client is written in Kotlin with Jetpack Compose. A small Node.js/Express backend supplies genre detection and converts the AutoEQ dataset into EQify's eight logical frequency bands.

The Android app supports API 26 and newer. `DynamicsProcessing.Limiter` is only available from API 28 (Android 9), so older or incompatible devices must use the Safe output-protection fallback.

## Repository layout

Representative tree (tracked files and important ignored directories; not every icon
or Gradle-generated file is shown):

```text
EQIFY/
├─ AI.md                         # Start here for architecture and guardrails
├─ GITHUB_PAGES_AUTOEQ.md        # Static export checkpoint and publishing steps
├─ CLOUDFLARE_AUTOEQ_MIGRATION.md # Deferred Worker + D1 migration plan
├─ README.md                     # Human-facing project overview
├─ settings.gradle.kts, build.gradle.kts, gradle/, gradlew.bat
├─ app/
│  ├─ build.gradle.kts            # Android SDK, Compose, dependencies, BASE_URL
│  └─ src/
│     ├─ main/AndroidManifest.xml # Activity, foreground service, tile, permissions
│     ├─ main/java/com/example/eqify/
│     │  ├─ MainActivity.kt, EqifyApplication.kt
│     │  ├─ EqEngine.kt, EqProcessingService.kt, EqState.kt
│     │  ├─ EqProcessingSnapshot.kt, EqQuickSettingsTileService.kt
│     │  ├─ OutputProtectionMode.kt, LimiterDiagnostics.kt
│     │  ├─ EqProfileManager.kt, UserPreferencesRepository.kt
│     │  ├─ EqifyApi.kt, HeadphoneEqDiskCache.kt
│     │  ├─ MediaListenerService.kt, NowPlayingState.kt
│     │  ├─ Bluetoothheadphonedetector.kt, Localgenredetector.kt
│     │  ├─ HomeViewModel.kt, SettingsViewModel.kt, EqTestTonePlayer.kt
│     │  ├─ screens/{HomeScreen,EqScreen,HeadphonesScreen,SettingsScreen}.kt
│     │  └─ ui/theme/{Color,Theme,Type}.kt
│     ├─ test/java/com/example/eqify/ # JVM unit tests
│     └─ androidTest/java/com/example/eqify/ # Device test
└─ eqify-backend/
   ├─ index.js                    # Express API and live dataset scanner
   ├─ lib/autoeq-converter.js     # Shared pure parser and 8-band formulas
   ├─ scripts/build-headphone-db.js # Offline static export generator
   ├─ test/{index,autoeq-converter}.test.js
   ├─ package.json, package-lock.json
   ├─ autoeq-results/             # Ignored, large raw data; often absent
   └─ generated/                  # Ignored static output; often absent
```

This is a file map, not an assertion that every ignored directory is present in
every clone. In particular, GitHub does not contain `autoeq-results/` or
`generated/`.

- `app/` - Android application.
- `app/src/main/java/com/example/eqify/` - state, audio engine, services, API clients, persistence, and view models.
- `app/src/main/java/com/example/eqify/screens/` - Jetpack Compose screens.
- `app/src/main/java/com/example/eqify/ui/theme/` - Compose theme tokens.
- `eqify-backend/` - Express backend and backend tests.
- `eqify-backend/autoeq-results/` - large external AutoEQ dataset; do not commit it.
- `CLOUDFLARE_AUTOEQ_MIGRATION.md` - deferred, step-by-step plan for converting AutoEQ data and moving the API to Cloudflare Workers + D1.
- `GITHUB_PAGES_AUTOEQ.md` - offline eight-band export format, local command, and manual static Pages publishing guide; Android does not yet consume it.
- `EQify_PRD.docx` - product requirements reference when present. Read it as requirements only and do not modify it.

## Running

Android, from the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

The app is normally launched from Android Studio on an emulator or physical device. The default backend URL is `http://10.0.2.2:3000/`, which reaches the host computer from the Android emulator. A different URL can be supplied with the `EQIFY_BASE_URL` Gradle property; keep the trailing slash required by Retrofit.

Backend:

```powershell
Set-Location eqify-backend
npm install
npm start
npm test
```

The backend listens on `PORT`, defaulting to `3000`. `LASTFM_API_KEY` is optional and must be supplied through the environment or a local untracked `.env` file. Never commit API keys or other secrets.

On Windows PowerShell, `npm.ps1` may be blocked by execution policy; use
`npm.cmd install`, `npm.cmd start`, and `npm.cmd test` instead. The offline
converter itself requires only Node built-ins and can run without `npm install`.

The Gradle config uses `minSdk 26`, `targetSdk 36`, and a configurable
`EQIFY_BASE_URL` with emulator default `http://10.0.2.2:3000/`. A physical phone
cannot use the emulator-only `10.0.2.2` address; it needs a reachable backend
URL. The `BASE_URL` applies to all three Retrofit API calls, including genre.

Run verification in proportion to the change and follow any current user instruction to skip builds or tests.

## Architecture

### Application lifetime and audio sessions

`EqifyApplication` owns application-lifetime receiver registration. `EqProcessingService` is the foreground service that keeps processing alive. Do not release `EqEngine` or unregister its global audio-session receiver merely because `MainActivity` is destroyed; doing that stops EQ when the UI closes.

`EqEngine` owns the Android `Equalizer` and `DynamicsProcessing` effects. It first tries the playback session announced by Android and falls back to session `0`. Both effects must remain attached to the same audio session. EQify has eight logical bands, but Android devices expose different hardware band counts, so `EqProfileManager.mapToDeviceBands` maps the logical curve to the device-reported center frequencies and level range.

### The two EQ update paths

`EqState` is the shared in-memory state. `EqProcessingService` deliberately uses two separate paths:

1. `observeAutoEqPipeline()` handles genre, selected headphone, presets, bass boost, enabled/bypass state, and persisted audio settings. It may perform a backend request for headphone correction.
2. `observeManualAdjustments()` handles slider movement immediately using the correction already cached in `EqState`; it must not wait for the network.

This separation is load-bearing. A slider movement must not update `baseToneGains` in a way that retriggers the automatic pipeline and overwrites the user's new value. Preserve the stale-emission checks around manual override state when editing either path.

### Gain composition

The normal logical curve is composed in this order through `EqProfileManager.combineGains`:

- tone or genre curve;
- headphone correction;
- bass boost.

Keep the eight-band ordering defined by `EqProfileManager.BAND_CENTERS_HZ`. Clamp and hardware mapping belong at the existing boundaries rather than in individual UI controls.

### Output protection

`OutputProtectionMode` has three user-facing modes:

- `BALANCED` - uses the Android 9+ linked-stereo `DynamicsProcessing.Limiter`, attached to the same audio session as the equalizer. Current parameters are threshold `-1 dB`, ratio `20:1`, attack `3 ms`, release `100 ms`, and post-gain `0 dB`.
- `SAFE` - uses EQify's compatible static gain scaling when the strongest band boost exceeds `+6 dB`.
- `OFF` - applies no output protection.

Balanced is the default on Android 9+ when the limiter initializes successfully. Safe is the default on Android 8/8.1 and the automatic fallback when limiter attachment or enablement fails. `LimiterDiagnosticStatus` is the source for availability, attachment, enabled, unsupported, and failure state. Keep the Settings explanation consistent with actual processing behavior.

Do not change limiter parameters based only on whether the difference is obvious at normal listening levels. An intentionally aggressive threshold was used temporarily to validate that the effect processed real audio, then restored to `-1 dB`.

### Persistence

`UserPreferencesRepository` is the DataStore boundary. Screens should write settings through their view models rather than directly mutating processing objects. `EqProcessingService` observes persisted settings and mirrors values needed by the immediate manual path into `EqState`.

`EqProcessingSnapshot` is the compact persisted status for external controls. The processing service derives it from the master toggle, bypass state, active display profile, and `EqEngine.isEqualizerAttached`. The Quick Settings tile combines that snapshot with the process-local service-running flag so it does not claim that EQ is active after the service or app process has stopped.

The former `limit_output_gain` Boolean is a migration-only preference. Existing `false` values migrate to Off; existing `true` or missing values migrate to Balanced on API 28+ and Safe on older Android versions. Do not remove migration handling without considering installed users.

### Media and headphone detection

`MediaListenerService` reads active media notifications and updates `NowPlayingState`. Notification-listener permission is user-controlled and must be handled gracefully when absent.

`Bluetoothheadphonedetector` updates the selected device for Bluetooth and wired connections. Headphone corrections are fetched through `EqifyApi`, cached on disk by `HeadphoneEqDiskCache`, and cached in memory by the processing service. A backend failure must leave the app usable with a flat headphone correction and allow a later retry.

### UI composition

`MainActivity` hosts the Compose navigation and starts the foreground processing service. The principal screens are:

- `HomeScreen` - current processing and playback overview.
- `EqScreen` - presets, manual sliders, bass, comparison bypass, profile saving/renaming, and test tone controls.
- `HeadphonesScreen` - headphone search and selection.
- `SettingsScreen` - persisted behavior, integrations, output protection, cache, and privacy information.

Reuse the colors and typography in `ui/theme` and match the existing card-based dark interface. Keep explanations close to unfamiliar audio controls, especially where a mode has compatibility limits or clipping risk.

### Where to make a change

| Concern | Source of truth / edit location | Important dependency |
| --- | --- | --- |
| Audio effect/session attachment | `EqEngine.kt` | Application-lifetime audio-session receiver; limiter must share session |
| Composed EQ output | `EqProfileManager.kt`, `EqProcessingService.kt` | Preserve separate automatic and immediate manual paths |
| Runtime UI state | `EqState.kt`, `NowPlayingState.kt` | In-memory state is not durable across process death |
| Durable preferences/presets | `UserPreferencesRepository.kt` | Preferences DataStore, plus legacy output-protection migration |
| Headphone lookup/correction | `HeadphonesScreen.kt`, `EqifyApi.kt`, `EqProcessingService.kt`, `HeadphoneEqDiskCache.kt` | Network failure uses favorites/flat correction and cached gains where available |
| Quick Settings control | `EqQuickSettingsTileService.kt`, `EqProcessingSnapshot.kt` | Snapshot in DataStore plus live service-running flag; no home-screen widget exists |
| Settings and output protection | `SettingsScreen.kt`, `SettingsViewModel.kt`, `OutputProtectionMode.kt` | Balanced falls back to Safe on unsupported/failed limiter |
| Backend API | `eqify-backend/index.js` | Keep JSON responses compatible with Retrofit |
| Offline static export | `eqify-backend/lib/autoeq-converter.js`, `scripts/build-headphone-db.js` | Does not by itself change Android networking |

## Backend

`eqify-backend/index.js` is the backend entry point. Its public routes are:

- `GET /api/headphones` - searches and deduplicates AutoEQ headphone entries.
- `GET /api/eq/:headphoneName` - parses a parametric AutoEQ file and returns the nearest eight logical bands.
- `POST /api/v1/genre` - detects genre using cache, Last.fm, iTunes metadata, keyword rules, and a default fallback.
- `GET /api/cache/stats` - reports the in-memory genre cache state.

AutoEQ parsing and band conversion are intentionally server-side. Do not copy the full dataset into the Android app or commit it to Git.

Backend input layout is `autoeq-results/<source>/<type>/<model>/<model>
ParametricEQ.txt`. The server sorts candidates by model, source, type and keeps
the first case-insensitive model match. The same shared converter is used by
`index.js` and the offline exporter. Target frequencies, in order, are
`[60, 170, 310, 600, 1000, 3000, 6000, 12000]` Hz; output is rounded to
0.1 dB and clamped to `[-12, 12]` dB.

`GET /api/headphones?search=` returns at most 50 `{name,type}` objects; lookup
is case-insensitive substring matching. `GET /api/eq/:headphoneName` returns
`{headphone,bands:[{frequencyHz,gainDb},...]}` or 404. `POST /api/v1/genre`
accepts `{track,artist}` and returns `{genre,source}`. `GET /api/cache/stats`
is a diagnostic endpoint. `EqifyApi.kt` defines these Android-side models.

### Data-hosting status (do not confuse plans with deployed infrastructure)

- The original EQIFY code repository is
  `https://github.com/parthsharma365628/EQIFY`. The converter/tooling commit
  `fe3310f` is on `main` as of 2026-09-16; inspect Git before relying on this
  historical checkpoint.
- `https://github.com/parthsharma365628/eqify-data` is the separate intended
  static-data repository. The publishing instructions and the last known export
  report are in `GITHUB_PAGES_AUTOEQ.md`; **do not infer that Pages is deployed**.
- One local export at `eqify-backend/generated/pages-v2/` had 6,028 selected
  profiles and checksum
  `ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc`.
  It is ignored and may not exist in another clone. The local input was
  `D:\eqify\eqify-backend\autoeq-results`, outside this working clone; its
  exact upstream revision was not recorded.
- No GitHub Pages client integration, Cloudflare Worker, D1 database, import,
  or production endpoint switch has been implemented by this work. The app
  still calls the configured Node API. Future static hosting and Cloudflare
  migration are alternative/sequence plans, not current runtime dependencies.

## Tests and diagnostics

- Android unit tests are in `app/src/test/`.
- Android instrumentation tests are in `app/src/androidTest/`.
- Backend tests are in `eqify-backend/test/` and run with Node's built-in test runner.
- Audio processing diagnostics use the `EqEngine` and `EqProcessingService` Logcat tags.
- Limiter diagnostics report whether it is available, attached, enabled, unsupported, or failed, plus the audio session ID.

Audio effects vary by Android version, manufacturer, playback app, and session routing. A successful build is not proof of correct audio behavior; session attachment and audible A/B testing on the target device remain important.

## Conventions and guardrails

- Inspect the branch, remote, working tree, and relevant files before changing code.
- Preserve existing behavior and implement only what the user requests; do not add unrelated PRD features.
- Treat existing uncommitted changes as user work unless their origin is known.
- Keep manual slider updates independent from slow network work.
- Preserve application-lifetime audio receiver and foreground-service behavior.
- Do not modify `EQify_PRD.docx`.
- Do not commit `.idea` state, `local.properties`, build outputs, `.env` files, secrets, `node_modules`, or the large AutoEQ dataset.
- Keep backend URLs and secrets configurable rather than embedding a developer's LAN address or credentials.
- Update this file when a structural invariant or major workflow changes; avoid filling it with transient task status.
