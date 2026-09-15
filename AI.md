# AI.md

This file gives AI coding assistants durable context for working in the EQify repository. Explicit user instructions always take priority over this document.

## What this is

EQify is an Android equalizer that combines genre profiles, headphone-specific AutoEQ corrections, bass boost, and manual EQ adjustments. The Android client is written in Kotlin with Jetpack Compose. A small Node.js/Express backend supplies genre detection and converts the AutoEQ dataset into EQify's eight logical frequency bands.

The Android app supports API 26 and newer. `DynamicsProcessing.Limiter` is only available from API 28 (Android 9), so older or incompatible devices must use the Safe output-protection fallback.

## Repository layout

- `app/` - Android application.
- `app/src/main/java/com/example/eqify/` - state, audio engine, services, API clients, persistence, and view models.
- `app/src/main/java/com/example/eqify/screens/` - Jetpack Compose screens.
- `app/src/main/java/com/example/eqify/ui/theme/` - Compose theme tokens.
- `eqify-backend/` - Express backend and backend tests.
- `eqify-backend/autoeq-results/` - large external AutoEQ dataset; do not commit it.
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

## Backend

`eqify-backend/index.js` is the backend entry point. Its public routes are:

- `GET /api/headphones` - searches and deduplicates AutoEQ headphone entries.
- `GET /api/eq/:headphoneName` - parses a parametric AutoEQ file and returns the nearest eight logical bands.
- `POST /api/v1/genre` - detects genre using cache, Last.fm, iTunes metadata, keyword rules, and a default fallback.
- `GET /api/cache/stats` - reports the in-memory genre cache state.

AutoEQ parsing and band conversion are intentionally server-side. Do not copy the full dataset into the Android app or commit it to Git.

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
