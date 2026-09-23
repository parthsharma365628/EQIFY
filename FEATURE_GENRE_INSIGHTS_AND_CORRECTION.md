# Feature Plan: Genre Correction, Recent Tracks, and Weekly Insights

Status: planning only. Do not implement this feature until explicitly requested.

This document supersedes the deleted `FEATURE_GENRE_LISTENING_INSIGHTS.md`.
It was checked against repository source at commit `8fd1092` on 2026-09-21.
Re-check the code and Git state before implementation because later commits may
change these findings.

## Scope

Three user-facing capabilities share one local data layer:

1. Correct the genre assigned to an exact title/artist pair.
2. Show a short recent-tracks list from which older assignments can be fixed.
3. Show a trailing seven-day genre mix that reflects corrections.

Non-goals:

- no backend route, cloud sync, or cross-device history;
- no duration-accurate listening time;
- no full listening-history browser;
- no fuzzy matching by artist, album, spelling, or remaster;
- no unrelated EQ, audio-session, or screen redesign.

## Verified current architecture

| Component | Current behavior | Consequence for this feature |
| --- | --- | --- |
| `MediaListenerService` | Accepts Spotify, YouTube Music, YouTube, and Apple Music notifications. Requires nonblank title and artist. Ignores a repeated identical pair until another track is accepted or the tracked notification is removed. | History can only represent metadata changes EQify observes. Blank metadata needs no new fallback unless listener behavior is deliberately expanded. |
| `NowPlayingState` | Holds process-local `track`, `artist`, and `currentGenre` flows. A song change clears genre and manual EQ override. | This is the bridge, not durable history. |
| `HomeViewModel` | Owns genre resolution. It publishes an immediate local result, then tries optional Last.fm, iTunes, and finally the EQify backend. | Correction lookup and final event logging belong around this resolver, not in the audio service. |
| `EqProcessingService` | Observes `NowPlayingState.currentGenre` and applies the matching tone curve through the existing automatic path. It does not detect genre. | Do not add database or correction queries to either EQ processing path. Feed corrected genre through the existing `currentGenre` flow. |
| `HomeScreen` | `NowPlayingCard` already displays title, artist, genre, source, and confidence. | Current-track correction belongs here. `EqScreen` does not display current-track metadata. |
| `EqProfileManager` | Contains fixed profile names and maps external genre strings to them. `Flat` and `Bass Boost` are utility profiles, not genres. | Picker choices should come from one genre-only source derived from this manager, excluding utility profiles. |
| `UserPreferencesRepository` | Uses Preferences DataStore for settings and named preset values. | No Room/SQLDelight dependency exists. Preferences DataStore is not suitable for a queryable append-only event log. |
| Tests | JVM tests live in `app/src/test/java/com/example/eqify/`. | Put pure normalization/aggregation tests there; persistence tests may need Android/Room test support. |

### Important lifecycle limitation

Genre detection currently lives in `HomeViewModel`. It runs while the Home
navigation entry and activity are alive; it is not owned by the foreground
processing service. `MediaListenerService` may continue to receive metadata
after the activity is destroyed, but no `HomeViewModel` then resolves a genre.

Before implementing weekly insights, choose and document one of these scopes:

- **Recommended for a real weekly feature:** move genre resolution into an
  application/service-owned coordinator, with `HomeViewModel` observing its UI
  state. Preserve the resolver order and keep database work outside
  `EqProcessingService`'s audio paths. This is a prerequisite refactor, not a
  hidden part of a small UI task.
- **Smaller but limited:** collect only while the Home view model is alive and
  label the result as app-session observations. Do not call that full weekly
  listening history.

Genre correction for the currently visible track can be implemented without
solving background collection, but the limitation must remain explicit.

## Product semantics

The event log cannot measure minutes listened. Notification metadata does not
provide reliable play duration, pause/resume boundaries, or a stable track ID.
Percentages therefore mean:

> share of track observations grouped by effective genre

Use UI copy such as **Genre mix from detected tracks** or **Tracks observed this
week**. Do not show "listening time," "minutes," or another duration claim.

The same track can be counted again after another track or a cleared
notification. A pause/resume that repeats the same notification may not produce
another event. This is acceptable for v1 if disclosed in the plan and tests.

## Recommended data layer

Use Room for this feature if implementation is requested. It requires a new
dependency and schema, but it fits two related tables, retention queries,
distinct recent-track queries, and aggregation better than encoding a growing
list in Preferences DataStore. Keep existing preferences in
`UserPreferencesRepository`; do not migrate unrelated settings to Room.

### `genre_observation`

Append-only except for retention deletion:

| Field | Type | Notes |
| --- | --- | --- |
| `id` | auto-generated long | Stable row identity. |
| `observedAtEpochMillis` | long | Time EQify accepted this track observation. |
| `title` | string | Original notification value for display. |
| `artist` | string | Original notification value for display. |
| `normalizedTitle` | string | Identity column; indexed with normalized artist. |
| `normalizedArtist` | string | Identity column; indexed with normalized title. |
| `loggedGenre` | string | Final genre used for this observation at write time. |
| `genreOrigin` | string | Stable internal value such as `automatic` or `manual`; not user-facing copy. |

### `genre_correction`

One row per normalized title/artist pair:

| Field | Type | Notes |
| --- | --- | --- |
| `normalizedTitle` | string | Composite primary-key column. |
| `normalizedArtist` | string | Composite primary-key column. |
| `title` | string | Latest display form. |
| `artist` | string | Latest display form. |
| `correctedGenre` | string | Must be a supported automatic genre profile. |
| `correctedAtEpochMillis` | long | Useful for ordering/debugging. |

Avoid a delimiter-built key such as `title::artist`; notification text can
contain arbitrary punctuation. Use the two normalized columns as a composite
identity in Room and a small `TrackIdentity` value object in Kotlin.

Normalization should trim, collapse consecutive whitespace, and lowercase with
`Locale.ROOT`. Do not strip terms such as "remaster" or "feat." in v1. The
current listener already rejects blank title or artist, so logging and
correction should keep that invariant rather than inventing placeholders.

This identity remains approximate: different apps can format the same track
differently, while two recordings with identical title and artist can collide.
That is accepted for v1.

### Effective genre and history

Never rewrite old observation rows when a correction changes. Resolve on read:

```text
effectiveGenre(observation) =
    correction[normalizedTitle, normalizedArtist] ?: observation.loggedGenre
```

This makes correction, retroactive display, and undo consistent. Deleting a
correction restores the genres originally logged.

Retention decision for v1:

- weekly aggregation window: trailing 7 days;
- recent-track query window: trailing 14 days, at most 15 distinct tracks;
- observation retention: 16 days, pruned after a successful insert and at app
  startup;
- corrections: retained until the user removes them or clears feature data.

All feature data must be clearable in one Settings action. Clearing downloaded
AutoEQ headphone corrections is a different action and must remain separate.

## Genre choices

The current practical genre set is `EqProfileManager.defaultProfiles.keys`
minus `Flat` and `Bass Boost`. `Acoustic` is valid even though not every
detection path produces it. Do not create another hand-maintained copy in a
screen; expose a genre-only list from the profile manager or an adjacent domain
source when implementing.

Constrain manual corrections to this set. Historical automatic values that no
longer map to a current profile must not crash aggregation; display their stored
label or group low-frequency/unknown values under `Other`.

## Required flows

### 1. Automatic genre resolution and one observation

1. Receive a new valid title/artist observation.
2. Normalize its identity.
3. Query the correction repository before calling `resolveGenre`.
4. If corrected, publish that genre with UI source `Manual correction` and skip
   local, Last.fm, iTunes, and backend detection.
5. Otherwise preserve the current order: immediate local result, optional
   Last.fm, iTunes, then backend fallback.
6. Log exactly one row after the final result for that observation is known.
   Do not log each intermediate local/remote update, or one track can be counted
   several times under different genres.
7. Before publishing or logging an asynchronous result, verify that its
   observation/identity is still current. `collectLatest` cancels normal stale
   work, but the guard should make this invariant explicit.
8. Perform Room work on the data/IO layer. A failed history write must not break
   genre detection or audio processing.

If background collection is chosen, preserve the same flow in the new
application/service-owned coordinator. Do not move it into the EQ engine.

### 2. Add, change, or remove a correction

1. User opens correction from the current `NowPlayingCard` or a recent-track
   row.
2. Picker shows supported genres and a **Use automatic detection** option.
3. Choosing a genre upserts the correction; choosing automatic deletes it.
4. Reads of insights and recent tracks update through a repository `Flow`, so
   history reflects the change immediately without rewriting observations.
5. If this is the current track, correction changes must also cancel/invalidate
   any in-flight automatic lookup and republish the current genre through
   `NowPlayingState.updateCurrentGenre`. A stale network response must not
   overwrite the user's choice.

Immediate live correction is compatible with the existing two-path design when
it enters through `currentGenre`: the automatic EQ path reacts normally, while
manual slider override still has priority in `EqProcessingService`. Do not call
`EqEngine` directly from UI code.

### 3. Weekly genre card

1. Read observations from `now - 7 days` through `now`.
2. Apply correction lookup to every row.
3. Count observations by effective genre.
4. Compute deterministic percentages. State the rounding rule and ensure the UI
   handles a rounded total that is not exactly 100.
5. Show the top 3-4 genres and combine the remainder as `Other`.
6. Show a neutral empty state when there are no observations.

Use a pure aggregation function that accepts `now`; do not read the clock inside
the calculation.

### 4. Recent tracks

Query the latest row for each normalized title/artist pair from the last 14
days, order by latest observation descending, and limit to 15. Show title,
artist, effective genre, and a correction affordance. No pagination or search.

## UI placement

- Put the current-track correction affordance inside or immediately beside
  `HomeScreen`'s existing `NowPlayingCard`.
- Put the compact weekly card on `HomeScreen`, below Now Playing and before less
  time-sensitive controls if layout remains readable.
- Open recent tracks from the weekly card in a dialog, bottom sheet, or small
  dedicated route. Prefer a sheet/dialog unless navigation complexity proves
  necessary.
- Put clear-data and local-history privacy copy in `SettingsScreen`.
- Reuse existing theme tokens. Do not add a charting dependency for simple bars.

## Privacy

- Observations and corrections remain on device.
- Existing automatic detection can already send title and artist directly to
  iTunes, optionally Last.fm, and the configured EQify backend. This feature
  should add no new network destination.
- Settings copy must say that title, artist, timestamp, and chosen/detected genre
  are stored locally for recent tracks and genre mix.
- One explicit action clears both Room tables. App uninstall/storage clear also
  removes them normally.

## Verification plan for future implementation

Unit tests:

- identity normalization: case, leading/trailing whitespace, repeated
  whitespace, and locale-independent casing;
- supported correction choices exclude `Flat` and `Bass Boost`;
- effective genre with correction, without correction, and after removal;
- aggregation: empty, single genre, top-N + Other, seven-day boundary, future
  timestamp exclusion, unknown historical genre, deterministic ties/rounding;
- final-result logging: intermediate local result is not separately logged;
- stale result guard: old network result cannot overwrite/log after track or
  correction changes;
- retention at the 16-day boundary and recent query at the 14-day boundary.

Manual checks:

- corrected current track changes the displayed/active automatic profile once;
- manual sliders remain responsive and retain priority;
- corrected replay skips all genre network lookups;
- correction added during an in-flight lookup wins;
- recent tracks and weekly card update without restart;
- clearing feature data removes observations and corrections only;
- missing notification access produces an empty/permission-aware state, not an
  error or fabricated history;
- killing the activity confirms the chosen lifecycle scope behaves as labeled.

## Implementation order

1. Re-check current source and decide background versus app-session scope.
2. Define genre-only choices and pure identity/aggregation functions.
3. Add Room schema, repository, retention, and tests.
4. Make genre resolution correction-aware and log only final observations.
5. Add current-track correction UI.
6. Add recent tracks and weekly card.
7. Add Settings privacy/clear controls.
8. Run regression checks for automatic EQ, manual sliders, service lifetime,
   notification permission, and offline behavior.
9. Update `AI.md` with final source-of-truth locations after implementation.

## Acceptance checklist

- [ ] Collection scope is explicit; weekly UI does not overstate coverage.
- [ ] Room is isolated to observation/correction data; DataStore remains the
      settings boundary.
- [ ] Correction lookup happens before every detection/network path.
- [ ] Exactly one final observation is logged per accepted metadata occurrence.
- [ ] Intermediate genre updates and stale async results are not logged.
- [ ] Current correction updates live and cannot be overwritten by stale work.
- [ ] EQ UI never calls the engine directly; existing automatic/manual path
      priorities remain intact.
- [ ] Picker excludes utility profiles and uses one canonical source.
- [ ] Weekly percentages are observation counts, not claimed listening time.
- [ ] 7-day aggregation, 14-day recent list, and 16-day retention agree.
- [ ] Corrections resolve at read time and can be removed.
- [ ] Local title/artist storage is disclosed and both tables are clearable.
- [ ] No backend or cloud storage was added for this feature.
