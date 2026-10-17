# AI.md

This file gives AI coding assistants durable context for working in the EQify repository. Explicit user instructions always take priority over this document.

## What this is

EQify is an Android equalizer that combines genre profiles, headphone-specific AutoEQ corrections, bass boost, and manual EQ adjustments. The Android client is written in Kotlin with Jetpack Compose. The app downloads headphone data from GitHub Pages; a small Node.js/Express backend retains headphone routes for tooling and provides the last step of the client's genre-detection fallback chain. Shared backend tooling converts the AutoEQ dataset into EQify's eight logical frequency bands.

The Android app supports API 26 and newer. `DynamicsProcessing.Limiter` is only available from API 28 (Android 9), so older or incompatible devices must use the Safe output-protection fallback.

## Repository layout

Representative tree (tracked files and important ignored directories; not every icon
or Gradle-generated file is shown):

```text
EQIFY/
├─ AI.md                         # Start here for architecture and guardrails
├─ FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md # Deferred, source-checked feature plan
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
│     │  ├─ EqGainRamp.kt
│     │  ├─ EqProcessingSnapshot.kt, EqQuickSettingsTileService.kt
│     │  ├─ OutputProtectionMode.kt, LimiterDiagnostics.kt
│     │  ├─ EqProfileManager.kt, UserPreferencesRepository.kt
│     │  ├─ EqifyApi.kt, HeadphoneDataRepository.kt, HeadphoneEqDiskCache.kt
│     │  ├─ GraphicEqImporter.kt, CustomEqProfileRepository.kt, HeadphoneCorrectionSelection.kt
│     │  ├─ MediaListenerService.kt, NowPlayingState.kt
│     │  ├─ GenreCoordinator.kt, GenreHistoryRepository.kt
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
- `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md` - implemented local correction/history/mix semantics and device-validation checklist.
- `CLOUDFLARE_AUTOEQ_MIGRATION.md` - deferred, step-by-step plan for converting AutoEQ data and moving the API to Cloudflare Workers + D1.
- `GITHUB_PAGES_AUTOEQ.md` - offline eight-band export format, Pages deployment, and Android static-data integration.
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

The backend listens on `PORT`, defaulting to `3000`. `LASTFM_API_KEY` is optional. `index.js` loads `.env`, but the repository's current `.gitignore` does not exclude `.env`; prefer a process/session environment variable, or add an ignore rule in a separately reviewed configuration change before creating that file. Never commit API keys or other secrets.

On Windows PowerShell, `npm.ps1` may be blocked by execution policy; use
`npm.cmd install`, `npm.cmd start`, and `npm.cmd test` instead. The offline
converter itself requires only Node built-ins and can run without `npm install`.

The Gradle config uses `minSdk 26`, `targetSdk 36`, a configurable
`EQIFY_BASE_URL` with emulator default `http://10.0.2.2:3000/`, and
`EQIFY_HEADPHONE_DATA_BASE_URL` defaulting to the deployed GitHub Pages site.
A physical phone cannot use the emulator-only `10.0.2.2` address for the genre
fallback; it needs a reachable backend URL. Both Retrofit base URLs need a
trailing slash.

Run verification in proportion to the change and follow any current user instruction to skip builds or tests.

## Architecture

### Application lifetime and audio sessions

`EqifyApplication` owns application-lifetime receiver registration. `EqProcessingService` is the foreground service that keeps processing alive. Do not release `EqEngine` or unregister its global audio-session receiver merely because `MainActivity` is destroyed; doing that stops EQ when the UI closes.

`EqEngine` owns the Android `Equalizer` and `DynamicsProcessing` effects. It first tries the playback session announced by Android and falls back to session `0`. Both effects must remain attached to the same audio session. EQify has eight logical bands, but Android devices expose different hardware band counts, so `EqProfileManager.mapToDeviceBands` maps the logical curve to the device-reported center frequencies and level range.

### The two EQ update paths

`EqState` is the shared in-memory state. `EqProcessingService` deliberately uses two separate paths:

1. `observeAutoEqPipeline()` handles genre, selected headphone, presets, bass boost, enabled/bypass state, and persisted audio settings. It may download a selected static headphone profile from GitHub Pages.
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

`MediaListenerService` reads supported apps' media notifications and updates process-local `NowPlayingState`. It currently requires both title and artist, and deduplicates an unchanged pair. Notification-listener permission is user-controlled and must be handled gracefully when absent.

`GenreCoordinator`, not `EqProcessingService` or `HomeViewModel`, owns genre resolution at application scope. It checks the local Room correction first, then publishes an immediate `LocalGenreDetector` result before optional Last.fm, iTunes, and `POST /api/v1/genre`. `EqProcessingService` only consumes `NowPlayingState.currentGenre`. The coordinator logs one final observation per accepted event while Auto-Genre is enabled; it cannot recover tracks missed when the process, notification listener, or Auto-Genre is inactive. See `FEATURE_GENRE_INSIGHTS_AND_CORRECTION.md` for semantics and limits.

`Bluetoothheadphonedetector` updates the selected device for Bluetooth and wired connections. `HeadphoneDataRepository` caches the GitHub Pages `index.json`, searches it locally, validates selected profile files, and returns eight gains. `HeadphoneEqDiskCache` caches selected gains on disk; the processing service also keeps them in memory. A download failure leaves the app usable with flat correction and exposes an error message. GraphicEQ file or pasted ten-row imports use `GraphicEqImporter` and a separate local `CustomEqProfileRepository`; selection is tagged (Dataset/Custom/None) in preferences and resolved by the service, never by writing gains directly from UI. Bluetooth auto-selection switches to Dataset. ParametricEQ import is not implemented.

### UI composition

`MainActivity` hosts the Compose navigation and starts the foreground processing service. The principal screens are:

- `HomeScreen` - current processing and playback overview.
- `EqScreen` - presets, manual sliders, bass, comparison bypass, profile saving/renaming, and test tone controls.
- `HeadphonesScreen` - headphone search and selection.
- `SettingsScreen` - persisted behavior, integrations, output protection, cache, and privacy information.

Reuse the colors and typography in `ui/theme` and match the existing card-based dark interface. Keep explanations close to unfamiliar audio controls, especially where a mode has compatibility limits or clipping risk.

### Kotlin file reference

Paths are relative to `app/src/main/java/com/example/eqify/`. “Owns” means logic/state in that file, not necessarily durable storage.

| File (lifecycle) | Purpose / responsibilities | Dependencies and invariants | Modify when |
| --- | --- | --- | --- |
| `EqifyApplication.kt` (Application) | Registers global receivers, starts genre coordinator, restores persisted EQ/preset/bass/headphone/listener state. | Engine, detector, preferences, genre history/coordinator; receivers and resolver outlive Activity. | Changing process startup or state restoration. |
| `MainActivity.kt` (Activity / Compose host) | Starts processing service; permissions and navigation; creates shared `HomeViewModel`. | Service, screens; preserve single-top/save-restore navigation; genre resolver is application-owned. | Changing entry, permissions, navigation. |
| `EqProcessingService.kt` (foreground Service) | Combines tone/genre, correction, bass, protection; owns auto/manual collectors, service-lifetime EQ ramp job, correction memory cache, notification and tile snapshot writes. | Runtime states, repositories, `EqProfileManager`, `EqEngine`; manual updates cancel ramp before instant write and must not lose to stale auto work. | Changing audio orchestration, transition classification, correction fallback, mono, notification. |
| `EqEngine.kt` (process singleton / audio effects) | Attaches/releases Equalizer and limiter; session selection, hardware gain application, final-target/applied-level tracking and attachment epoch. | `EqProfileManager`, limiter status, ramp controller; interim ramp writes must not replace restorable final target; do not release on Activity teardown. | Changing effect/session or device-band behavior. |
| `EqGainRamp.kt` (pure interpolation + service-owned controller) | Interpolates mapped millibel device levels over 250 ms and cancels/retargets one service-lifetime job. | Service supplies clock/scope and engine writer; delayed writes must never override manual input or cross attachment epochs. | Changing EQ transition timing/cancellation. |
| `EqState.kt` (process singleton / StateFlows) | Live EQ request/bypass, base tone, manual override/update, output, headphone correction/status, bass, service flag, protection. | UI/view models and service; tone-only and composed gains differ; copy arrays and preserve immediate manual path. | Changing shared processing state or override transitions. |
| `NowPlayingState.kt` (process singleton / StateFlows) | Current track/artist/genre, accepted observation token, selected headphone and listener state. | Media listener, genre coordinator, detector, service; track changes clear stale genre/manual override; not durable. | Changing live media/headphone propagation. |
| `EqProfileManager.kt` (pure singleton) | Eight bands, genre presets, genre labels, gain composition and device-band mapping. | Service, engine, VMs; preserve `[60,170,310,600,1000,3000,6000,12000]` Hz order. | Changing curves, genre mappings, arithmetic or interpolation. |
| `OutputProtectionMode.kt` (enum) | Balanced/Safe/Off values and parser. | Preferences, service, settings; preserve stored-value migration. | Changing mode identity/parsing. |
| `LimiterDiagnostics.kt` (status model) | Availability, attachment, enabled/unsupported/failure details. | Engine publishes, Settings reads; report actual effect state, not just intent. | Changing limiter diagnostics. |
| `EqProcessingSnapshot.kt` (persisted status model) | Processing status and compact tile snapshot. | Service writes through preferences, tile reads; requested enable is not actual activity. | Changing external status contract. |
| `EqQuickSettingsTileService.kt` (TileService) | Renders/toggles tile from persisted snapshot and live service flag. | Preferences, `EqState`, service; stale ACTIVE snapshot cannot prove service runs. | Changing tile action, label or lifecycle. |
| `UserPreferencesRepository.kt` (DataStore repository) | Durable settings, selected/favorite headphones, global custom tone presets, key and snapshot; migrations. | Application, service, VMs, tile; preserve legacy limiter and preset-name migrations; not downloaded-gain cache. | Changing preferences, preset CRUD or migration. |
| `EqifyApi.kt` (Retrofit contracts/clients) | Pages index/profile, Last.fm/iTunes and Node genre DTOs/endpoints. | Headphone repo, genre coordinator; keep JSON/base-URL contract; Android does not call Node headphone routes. | Changing network contracts/configuration. |
| `HeadphoneDataRepository.kt` (process singleton / Pages repository) | Caches Pages index, searches locally, validates/fetches one selected eight-gain profile. | Retrofit client, index file cache, screens/detector/service; do not download all profiles or accept malformed gains. | Changing catalog search, index cache or profile retrieval. |
| `HeadphoneEqDiskCache.kt` (process singleton / app-private cache) | Stores selected downloaded eight-gain corrections by name; load/save/count/clear. | Service and Settings; separate from index and favorites; clear must not erase choices. | Changing offline correction cache. |
| `GraphicEqImporter.kt` (pure converter) | Validates standalone GraphicEQ text or pasted ten-row table, log-frequency resamples to eight bands, reports ±12 dB limiting. | `EqProfileManager.BAND_CENTERS_HZ`; reject extra directives and malformed points rather than partially importing. | Changing file/table parsing or conversion math. |
| `CustomEqProfileRepository.kt` (app-private repository) | Atomically stores imported eight-band profiles, exposes list and CRUD. | Application, headphone/settings VMs, service; distinct from Pages cache and global tone presets. | Changing imported-profile schema or persistence. |
| `HeadphoneCorrectionSelection.kt` (selection model) | Tags active source as Dataset, Custom, or None. | DataStore and processing service; never treat a custom UUID as a Pages name. | Changing source-selection contract. |
| `Bluetoothheadphonedetector.kt` (application-registered receiver) | Detects Bluetooth/wired devices, matches catalog names, updates selection/banner. | Headphone repo, preferences, `NowPlayingState`; respect auto-detect setting/manual choice. | Changing device discovery or auto-selection. |
| `MediaListenerService.kt` (NotificationListenerService) | Extracts/deduplicates supported players' track and artist. | `NowPlayingState`; user permission required; ignore incomplete notifications. | Changing supported players or metadata extraction. |
| `Localgenredetector.kt` (pure singleton) | Immediate ordered keyword/artist genre heuristic. | Genre coordinator; first match wins, Pop fallback; align with supported profiles. | Changing local genre rules. |
| `GenreCoordinator.kt` (application-scoped coordinator) | Resolves corrected/automatic genre, guards stale async work, logs one final observation per accepted track. | `NowPlayingState`, history, APIs, preferences; never query Room from the EQ service or log provisional results. | Changing genre priority, background lifecycle or observation logging. |
| `GenreHistoryRepository.kt` (Room repository + pure aggregation) | Stores observations/corrections, prunes at 16 days, exposes 14-day recent and 7-day mix with read-time corrections. | Coordinator, Home/Settings VMs; composite normalized track identity, local-only data, DataStore remains separate. | Changing history schema, retention, correction semantics or aggregation. |
| `HomeViewModel.kt` (Activity-scoped AndroidViewModel) | Exposes coordinator status and Room recent/mix flows; Home EQ/bass/test and correction actions. | Coordinator, history, preferences, test player; it no longer owns genre lookup. | Changing Home actions or presentation state. |
| `SettingsViewModel.kt` (screen AndroidViewModel) | Adapts persisted settings; syncs immediate state; separately clears downloaded corrections and genre history. | Preferences, runtime states, disk cache, coordinator; persist choices via repository. | Adding settings action or clear operation. |
| `EqTestTonePlayer.kt` (singleton / short-lived AudioTrack) | Generates test tones with overlap guard and cleanup. | Home VM; playback off main thread, always release audio resources. | Changing test playback. |
| `screens/HomeScreen.kt` (Compose screen) | Displays playback/genre, weekly observation mix, recent tracks/correction picker, EQ/headphone/bass/test controls. | Home VM and runtime states; no second genre resolver; mix is track counts, not listening time. | Changing Home UI. |
| `screens/EqScreen.kt` (Compose screen + screen AndroidViewModel) | Preset picker/CRUD/rename, eight sliders, EQ controls. | `EqState`, profile manager, preferences; preserve immediate manual path; global tone presets differ from headphone corrections. | Changing EQ UI, presets or sliders. |
| `screens/HeadphonesScreen.kt` (Compose screen + screen AndroidViewModel) | Catalog search, favorites, selected headphone, GraphicEQ file/paste import and imported-profile selection/rename/delete. | Headphone repo, custom store, preferences, runtime states; typed custom headphone names differ from imported gain profiles. | Changing headphone picker or import UI. |
| `screens/SettingsScreen.kt` (Compose screen) | Settings, protection explanation/diagnostics, permissions, cache/privacy controls. | Settings VM, limiter diagnostics; describe Safe fallback accurately. | Changing Settings UI/copy. |
| `AppGlyph.kt`, `ui/theme/{Color,Theme,Type}.kt` (Compose helpers) | App glyph and shared visual tokens; no processing state. | Screens; keep behavior out of theme files. | Changing icons or visual system. |

### State ownership

| State | Owner / lifetime | Consumers |
| --- | --- | --- |
| Preferences, selected/favorite headphone, custom **tone** presets | `UserPreferencesRepository` / durable | View models, service, detector, tile |
| Live EQ request, override, computed gains/correction | `EqState` / process | Service, screens, tile |
| Track event, resolved genre, detected headphone | `NowPlayingState` / process | Coordinator, service, screens |
| Corrections and 16-day observation log | `GenreHistoryRepository` / local Room database | Coordinator, Home/Settings VMs |
| Genre lookup and final event logging | `GenreCoordinator` / application process | Home VM, `NowPlayingState` |
| Actual Equalizer/limiter attachment and diagnostics | `EqEngine` / audio session | Service, Settings |
| Processing collectors, correction memory cache, notification | `EqProcessingService` / service | Engine, runtime state, preferences |
| Pages index vs selected correction cache | `HeadphoneDataRepository` vs `HeadphoneEqDiskCache` / local files | Search/detector vs service/Settings |
| Imported profiles vs active source | `CustomEqProfileRepository` / local JSON; `UserPreferencesRepository` / DataStore | Headphone/Settings VMs and processing service |
| Screen interaction | Screen ViewModels / navigation or Activity | Compose screens |
| Last tile-visible processing snapshot | `EqProcessingSnapshot` in DataStore / durable | Tile; cross-check live service flag |

### Where to make a change

| Concern | Source of truth / edit location | Important dependency |
| --- | --- | --- |
| Audio effect/session attachment | `EqEngine.kt` | Application-lifetime audio-session receiver; limiter must share session |
| Composed EQ output | `EqProfileManager.kt`, `EqProcessingService.kt` | Preserve separate automatic and immediate manual paths |
| EQ transition smoothing | `EqGainRamp.kt`, `EqProcessingService.kt`, `EqEngine.kt`, `UserPreferencesRepository.kt`, `screens/SettingsScreen.kt` | Only genre/correction changes ramp; manual, bypass, protection and controls snap; device listening validation pending |
| Runtime UI state | `EqState.kt`, `NowPlayingState.kt` | In-memory state is not durable across process death |
| Genre resolution and correction | `GenreCoordinator.kt`, `GenreHistoryRepository.kt`, `Localgenredetector.kt` | Correction first, then local → optional Last.fm → iTunes → EQify backend; application-scoped |
| Recent tracks / weekly mix | `GenreHistoryRepository.kt`, `screens/HomeScreen.kt` | 14-day distinct recents, 7-day observation counts, 16-day retention; not listening duration |
| Durable preferences/presets | `UserPreferencesRepository.kt` | Preferences DataStore, plus legacy output-protection migration |
| Headphone lookup/correction | `HeadphonesScreen.kt`, `HeadphoneDataRepository.kt`, `EqifyApi.kt`, `EqProcessingService.kt`, `HeadphoneEqDiskCache.kt` | Pages index is cached; only selected gains are downloaded; failure uses favorites/flat correction or cached gains |
| GraphicEQ import and selection | `GraphicEqImporter.kt`, `CustomEqProfileRepository.kt`, `HeadphoneCorrectionSelection.kt`, `UserPreferencesRepository.kt`, `screens/HeadphonesScreen.kt`, `EqProcessingService.kt` | Imported gains stay local; service owns automatic correction resolution; ParametricEQ remains planned |
| Quick Settings control | `EqQuickSettingsTileService.kt`, `EqProcessingSnapshot.kt` | Snapshot in DataStore plus live service-running flag; no home-screen widget exists |
| Settings and output protection | `SettingsScreen.kt`, `SettingsViewModel.kt`, `OutputProtectionMode.kt` | Balanced falls back to Safe on unsupported/failed limiter |
| Backend API | `eqify-backend/index.js` | Keep JSON responses compatible with Retrofit |
| Offline static export | `eqify-backend/lib/autoeq-converter.js`, `scripts/build-headphone-db.js` | Does not by itself change Android networking |

## Backend

`eqify-backend/index.js` is the backend entry point. Its public routes are:

- `GET /api/headphones` - searches and deduplicates AutoEQ headphone entries.
- `GET /api/eq/:headphoneName` - parses a parametric AutoEQ file and returns the nearest eight logical bands.
- `POST /api/v1/genre` - detects genre using cache, Last.fm, iTunes metadata, keyword rules, and a default fallback.
- `GET /api/cache/stats` - reports the in-memory genre cache state, including cached artist names; treat it as a local diagnostic endpoint and do not expose it unchanged in a public deployment.

AutoEQ parsing and band conversion happen in backend/offline tooling. Android consumes only the converted static index and selected profile; do not copy the full dataset into the app or commit it to Git.

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
is a diagnostic endpoint. The headphone routes remain available to backend
tools but Android no longer calls them; `EqifyApi.kt` defines the active genre
and static Pages models.

### Data-hosting status (do not confuse plans with deployed infrastructure)

- The original EQIFY code repository is
  `https://github.com/parthsharma365628/EQIFY`. The converter/tooling commit
  `fe3310f` is on `main` as of 2026-09-16; inspect Git before relying on this
  historical checkpoint.
- `https://github.com/parthsharma365628/eqify-data` is the separate published
  static-data repository. GitHub Pages serves it from `main` and `/(root)` at
  `https://parthsharma365628.github.io/eqify-data/`; publication commit was
  `5e84681` on 2026-09-25.
- The published export was regenerated from AutoEq commit
  `7ae0f56d53074872b028649617a22bbb4232feb7`, contains 6,028 profiles, and has
  checksum
  `ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc`.
  Its ignored local build path is
  `eqify-backend/generated/pages-autoeq-7ae0f56d5307/` and may not exist in
  another clone.
- GitHub Pages Android client integration is implemented. No Cloudflare Worker,
  D1 database, or import has been implemented. The Node API remains configured
  for the final genre-resolution fallback, not headphone lookup.

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
