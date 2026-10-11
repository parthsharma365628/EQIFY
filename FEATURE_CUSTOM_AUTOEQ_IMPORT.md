# Feature Plan: Custom AutoEQ Import (autoeq.app / Equalizer APO files)

Status: GraphicEQ Path 1 (file or pasted 10-band table) implemented in source
(2026-10-11); Android build and manual validation pending. ParametricEQ Path 2 remains unimplemented pending
its exact-vs-heuristic and offline-vs-backend decisions.

Implementation: `GraphicEqImporter.kt` parses/resamples; `CustomEqProfileRepository.kt`
stores local profiles; `HeadphoneCorrectionSelection.kt` and
`UserPreferencesRepository.kt` persist the tagged selection; `EqProcessingService.kt`
resolves it on the automatic path; `HeadphonesScreen.kt` imports files or pasted
values, then selects/renames/
deletes; `SettingsScreen.kt` clears imports separately. Bluetooth auto-selection
switches back to the matched dataset profile. Unmatched devices keep the current
selection. This section describes code state, not validation results.

## Note for AI assistants reading this file

This document was re-checked against the current Kotlin and Node source at
commit `eb033c7` (2026-10-11). Source-level claims below are marked
**[VERIFIED]** where confirmed. Re-check the source again before implementation
if the repository has moved.

1. Re-check the actual repository source at the current commit.
2. Update the **Assumptions and open questions** section if implementation
   decisions change.
3. Append a row to the **Correction log** when correcting this plan so a human
   or another AI can see what changed and why.

Confidence tags used throughout this doc:
- **[VERIFIED]** — confirmed against a primary source (cited) or the supplied
  architecture docs directly.
- **[ASSUMED]** — a reasonable inference from the supplied docs, not directly
  confirmed against source code.
- **[DESIGN CHOICE]** — a decision made while writing this plan, presented with
  its reasoning so it can be challenged, not a fact about the existing repo.

## Scope

Let a user take a standalone GraphicEQ or ParametricEQ export from autoeq.app
(or another Equalizer APO-compatible tool) and use it as a correction inside
EQify, without needing their exact headphone model to exist in the 6,028-entry
AutoEQ dataset EQify already ships.
Full Equalizer APO configurations with multiple processing directives, includes,
or both graphic and parametric sections are outside v1 scope; reject them rather
than silently applying only part of a file.

This is the "Custom AutoEQ import" line item named in `EQify_Android_PRD_v2.md`
(Section 6.3) — this document defines *how*, it does not add a new feature to
the roadmap. **[VERIFIED against the current workspace PRD.]**

In scope:
1. Import a `GraphicEQ.txt` file via the Android system file picker.
   Alternatively, paste a 10-row frequency/gain table copied from autoeq.app.
2. Import a `ParametricEQ.txt` file via the same picker (second path; can ship
   later than #1 — see **Path 2** below).
3. Store imported profiles locally, list them, select one as the active
   headphone correction, rename or delete them.
4. Surface all of this from `HeadphonesScreen` (selection) and `SettingsScreen`
   (management/clear-all), mirroring the existing headphone-data UI pattern.

Non-goals for v1:
- No cloud sync or cross-device sharing of imported profiles — local only,
  consistent with how downloaded AutoEQ corrections are cached today.
- No in-app curve editing/drawing. A custom profile becomes a selectable
  8-band correction. Existing `EqScreen` sliders add/edit the active tone layer;
  they do not rewrite the imported correction file unless a separate save-as
  workflow is designed.
- No support for CamillaDSP YAML, EasyEffects JSON, PipeWire `.conf`, or Roon
  presets — only the two Equalizer APO-family text formats autoeq.app itself
  exports for graphic/parametric use.
- No arbitrary pasted Equalizer APO configuration. Pasted input is limited to
  the ten-row frequency/gain table; standalone `GraphicEQ:` files use SAF.
- No change to the existing GitHub Pages dataset, its 6,028 profiles, or the
  fixed genre profiles.

## Verified current architecture (what this feature plugs into)

| Component | Current behavior | Consequence for this feature |
| --- | --- | --- |
| Logical bands | Eight fixed target frequencies `[60, 170, 310, 600, 1000, 3000, 6000, 12000]` Hz; published gains are rounded to 0.1 dB and clamped to `[-12, 12]` dB. **[VERIFIED — `EqProfileManager.kt`, `autoeq-converter.js`]** | A custom import should produce this exact eight-value representation before entering the existing pipeline. Input validation must reject implausible values before output clamping; clamping alone can hide a malformed file. |
| `EqProfileManager.mapToDeviceBands` | Maps the eight logical bands to whatever band count/range the device's hardware `Equalizer` actually exposes. **[VERIFIED]** | Custom profiles do not need their own device-mapping logic — they only need to produce a standard eight-gain array, then this existing step handles the rest. |
| `HeadphoneDataRepository` / `HeadphoneEqDiskCache` | Cache the GitHub Pages `index.json`, search locally, download only the selected profile, and cache selected gains on disk; a download failure falls back to flat correction. **[VERIFIED]** | Use this as a behavior pattern, not as the storage model. A custom profile has no Pages `profilePath`; it needs a local profile store. The current selected-headphone state is only a `String`, so custom identity must be added explicitly rather than passed as an ordinary Pages name. |
| `eqify-backend/lib/autoeq-converter.js` | Parses ParametricEQ-like text and produces eight approximate gains using heuristic peak/shelf shapes plus preamp. **[VERIFIED]** | It is **not** an exact biquad response evaluator. Do not describe it as exact math or silently reuse it for a high-fidelity import without deciding whether approximation parity or true filter-response evaluation is the requirement. |
| Two EQ update paths (`EqProcessingService`) | Automatic/network-driven changes vs. manual slider changes are deliberately kept independent so slider input is never blocked by network work. **[VERIFIED]** | Selecting or importing a custom profile is an *automatic*-path change (like picking a different headphone), never a manual-path one. It must not touch the manual slider code path. |
| Persistence boundary | `UserPreferencesRepository` (Preferences DataStore) is the settings boundary; screens write through view models, not directly to processing objects. **[VERIFIED]** | Use a repository behind the UI. Do not add custom profile keys directly to a screen or overload the existing global preset-name namespace. |
| Room / SQL | Room is present in `app/build.gradle.kts` and used by `GenreHistoryRepository.kt`; custom import has no store yet. **[VERIFIED]** | A small local profile store can remain separate from the genre database, but it must use atomic writes and recover from partial/corrupt state. |
| `HeadphonesScreen.saveCustomHeadphone` | Saves a typed headphone *name* as the selected headphone; it does not import or store EQ gains. **[VERIFIED]** | Keep name selection distinct from a custom correction profile and its stable ID. |

## Format research (primary sources)

Both formats below are things autoeq.app generates by running the same
AutoEq pipeline EQify's own dataset was built from, then exporting the result
in an Equalizer APO-compatible text format. **[VERIFIED against Equalizer
APO's [configuration reference](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/)
and AutoEq's [export code](https://github.com/jaakkopasanen/AutoEq/blob/master/autoeq/frequency_response.py).]**

### `GraphicEQ.txt`

A single line, semicolon-separated frequency/gain pairs:

```text
GraphicEQ: 20.00 0.00; 25.00 -1.75; 30.00 -3.20; ... ; 20000.00 -2.10
```

Confirmed syntax, straight from Equalizer APO's own configuration reference:
`GraphicEQ: <Frequency> <Gain (dB)>; <Frequency> <Gain (dB)>; ...`, and the
gain values are interpolated **linearly in the logarithmic frequency
spectrum** between specified points, with a **flat** response outside the
specified range.

AutoEq's current default export is dense (its [default step](https://github.com/jaakkopasanen/AutoEq/blob/master/autoeq/constants.py)
is a frequency ratio of `1.0563`, about 1/12.6 octave); point count and spacing
can vary by version/configuration. The interpolation rule is the same either way.

AutoEq's standalone `GraphicEQ.txt` export has no separate preamp line; its
generator accounts for headroom in the exported points. An arbitrary Equalizer
APO file may have a separate `Preamp:` directive. Path 1 must reject such a
file as outside scope, not ignore its preamp or invent another one.

### `ParametricEQ.txt`

A separate preamp line plus one line per biquad filter, in the same
Equalizer-APO-family syntax EQify's backend already parses:

```text
Preamp: -6.8 dB
Filter 1: ON PK Fc 21 Hz Gain 6.4 dB Q 0.70
Filter 2: ON PK Fc 105 Hz Gain -3.1 dB Q 1.41
...
```

Unlike `GraphicEQ.txt`, the preamp here is a **separate line that must be
applied** — it is not folded into the per-filter gains. AutoEq's own
documentation is explicit that the preamp value from the result must be set,
or the parametric filters alone can clip. The current converter adds parsed
preamp to its heuristic output, but this is
not an exact response calculation. **[VERIFIED against the current source;
still requires a Path 2 fidelity decision.]**

Filter types AutoEq emits are mostly `PK` (peaking); its optimizer has also
supported shelf filters since a 2022 rework, so a real-world export can
contain `LSC`/`HSC` (low/high shelf) lines too. A parser that only handles
`PK` must reject a file containing unsupported filters or report an explicit
partial-conversion warning; it must never silently under-convert a profile.

## Resampling design — Path 1 (GraphicEQ, client-side, ship first)

The paste path accepts the website's optional `Frequency (Hz) Gain (dB)`
header plus exactly ten whitespace-separated numeric rows. It validates each
row, then uses the same log-frequency interpolation, rounding, and clamping as
the file path. Pasting saves a profile but does not activate it automatically.

**[DESIGN CHOICE]** Recommended first because it needs no backend call or new
network dependency and has a directly specified interpolation rule.

1. Launch Android's Storage Access Framework document picker
   (`ActivityResultContracts.OpenDocument`). Prefer `text/plain`; provide a
   separate `*/*` retry if a provider mislabels `.txt` files. Validate content
   regardless of the provider's MIME type.
2. Read the file as UTF-8 text, accepting and removing a UTF-8 BOM. Reject
   empty files and anything without a `GraphicEQ:` line with a clear error.
   Enforce a reasonable size limit while streaming (for example, 1 MiB), even
   if the provider omits or misreports file size. Reject invalid UTF-8/binary
   content. Store the converted profile and metadata only; do not retain the URI
   grant or raw file.
   Reject extra processing directives (including `Preamp:`, `Filter`, and
   `Include`) rather than silently discarding part of a configuration.
3. Strip the `GraphicEQ:` prefix, split on `;`, trim each pair, split each on
   whitespace into `(frequencyHz: Double, gainDb: Double)`. Empty trailing
   tokens are allowed; malformed non-empty tokens must fail the import with a
   line/pair-specific error rather than being silently skipped.
4. Require finite, strictly positive frequencies, at least two distinct
   points, deterministic duplicate handling (prefer rejecting duplicate
   frequencies), and reasonable input gain limits. Sort points by frequency.
5. For each of the app's eight target frequencies, resample using **the same
   rule the format itself defines**: linear interpolation in log-frequency
   space between the two bracketing points; if the target frequency is below
   the file's lowest point or above its highest, use the nearest endpoint's
   gain (flat extrapolation), matching "flat outside the specified bands."
6. Round each resulting gain to 0.1 dB and clamp to `[-12, 12]` dB only after
   validation, matching the existing dataset convention. Report if output
   limiting occurred.
7. No separate preamp step — see **Format research** above.

This is a pure function (`List<Pair<Double, Double>> -> List<Double>` for the
eight gains) with no Android or network dependency, so it belongs in a plain
Kotlin file with JVM unit tests, not inside a ViewModel or repository class.

## Resampling design — Path 2 (ParametricEQ, decide sub-path before building)

**[DESIGN CHOICE — open decision, not resolved by this document]**

The desired math is evaluation of a chain of biquad peaking/shelf filters'
combined gain response at eight specific frequencies, after applying preamp.
The current `eqify-backend/lib/autoeq-converter.js` does not perform that exact
biquad evaluation: it uses heuristic peak/shelf shapes. Two implementation
paths are possible:

**Option A — new backend endpoint.** Add `POST /api/eq/convert` accepting raw
pasted/uploaded `ParametricEQ.txt` text and returning the same
`{bands:[{frequencyHz,gainDb},...]}` shape `GET /api/eq/:headphoneName`
already returns. Cheapest to build (the parser/math is untouched, only a new
route), but this is only acceptable after adding a strict request-size limit,
validation, rate limiting, and explicit privacy disclosure. **Cost:** custom parametric
import now requires network connectivity to `BuildConfig.BASE_URL`, which is
inconsistent with the rest of the headphone-correction flow — GraphicEQ
import (Path 1) and every *downloaded* correction work fully offline once
cached, but a parametric import would not.

**Option B — port the math to Kotlin.** Keeps every import path offline,
consistent with the app's existing "works without a live backend" posture for
headphone data. **Cost:** response math exists in two languages and can drift.
The current Node converter must first be treated as a heuristic converter, not
an exact biquad-response oracle; shared golden fixtures are required.

This plan does not pick one — make the decision explicitly before coding. Do
not default to Option A merely because it is cheaper: it sends user-provided
file contents to the configured backend and expands a currently genre-focused
endpoint. Option B is preferable if offline/private import is a requirement,
but only after exact-response versus dataset-parity behavior is specified.

Either way, Path 2 must define whether the product wants exact biquad response
sampling or parity with the current heuristic dataset converter. Do not claim
both automatically. Add shared golden fixtures before changing the converter
or promising that a parametric import matches an existing dataset profile.

## Storage

**[DESIGN CHOICE]** Keep this store separate from the existing Room-backed
genre history. Custom profiles are a short, user-curated list
(realistically single digits to low tens of entries), not an append-only
event log needing aggregation queries — Room's benefit for the genre feature
(retention windows, distinct-track queries, weekly aggregation) doesn't apply
here. This remains a design choice, not a claim that Room is absent.

Use a dedicated repository with one versioned, atomically-written JSON file:

```text
<app-private files dir>/custom_eq_profiles.json
```

The file contains a document such as:

```json
{
  "schemaVersion": 1,
  "profiles": [
    {
      "id": "uuid",
      "displayName": "My headphone",
      "sourceFileName": "GraphicEQ.txt",
      "format": "graphic_eq",
      "importedAtEpochMillis": 0,
      "frequenciesHz": [60, 170, 310, 600, 1000, 3000, 6000, 12000],
      "gains": [0, 0, 0, 0, 0, 0, 0, 0]
    }
  ]
}
```

One atomic document avoids orphaned profile files and index/profile update
ordering bugs. A temporary file plus rename (or Android `AtomicFile`) must be
used for writes. A corrupt document should be quarantined or reset with a
visible warning; it must not crash the audio service.

Profile fields:

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string (UUID) | Generated at import time; selection key within the single JSON document. |
| `displayName` | string | Defaults to the source filename minus extension; user-renamable. |
| `sourceFileName` | string | Original picked file name, kept for display/debugging. |
| `format` | string | `"graphic_eq"` or `"parametric_eq"`. |
| `importedAtEpochMillis` | long | For sorting the list newest-first. |
| `frequenciesHz` | number[8] | Must equal the app's fixed target list. |
| `gains` | number[8] | Finite, rounded to 0.1 dB, and within `[-12, 12]` after validated conversion. |

This is a custom-store schema, not the GitHub Pages schema. Do not claim that
it is the same shape as `HeadphoneEqDiskCache`; that cache currently stores a
single name-to-array JSON object without metadata or schema versioning.

## Data flow / active-source model

**[DESIGN CHOICE]** The current `NowPlayingState.selectedHeadphone` and
`UserPreferencesRepository.selectedHeadphone` are plain headphone-name
strings. A custom UUID cannot safely be disguised as a Pages name, because the
service would try to fetch it from GitHub Pages. Add a persisted, tagged
selection alongside the physical headphone name:

```text
HeadphoneCorrectionSelection
  = Dataset(name: String)
  = Custom(profileId: String)
  = None
```

**[DESIGN CHOICE — illustrative model, not a literal type to paste in.]**

Selection must feed `EqProcessingService`'s automatic pipeline. Today that
service observes `NowPlayingState.selectedHeadphone`, calls
`resolveHeadphoneCorrection(name)`, then writes
`EqState.currentHeadphoneCorrection`; a UI-only write to that array would be
overwritten on the next recompute. Extend the pipeline (or a resolver it calls)
to observe the tagged selection and resolve Dataset through Pages/cache,
Custom through the local store, and None to flat. Keep the manual slider path
unchanged and responsive; only the resolved eight gains are source-agnostic.

Persist the source and stable identifier, separately from the selected headphone
name. Do not persist raw gains in multiple competing state locations.
The current `HeadphoneCorrectionStatus` is name-based and uses
Loading/Downloaded/Cached/Failed; extend its identity and presentation so a
local import reports "using imported profile," not "downloaded." Update both
`HomeScreen` and `HeadphonesScreen` status rendering accordingly.

If the active custom profile is deleted, select `None` (flat correction) and
show an error/status message. Do not silently reselect an unrelated cached
headphone profile. Define Bluetooth auto-selection behavior before coding:
current detection updates/persists the selected headphone *name*. If auto-
selection also switches the tagged correction to Dataset, make that visible;
if a user-pinned Custom correction remains active, keep its identity and label
clear. Never treat a custom UUID as a Pages headphone name.

## Settings page structure

Add a **Custom EQ Profiles** section to `SettingsScreen`, placed near the
existing headphone-cache controls so both "clear downloaded corrections" and
"clear custom imports" are visually grouped but stay separate actions
(mirroring the precedent already set in the genre-correction plan, which
keeps its own clear-data action distinct from clearing AutoEQ headphone
cache):

- **Imported profiles: N** — simple count, tappable to jump to the full list
  (which may be better hosted on `HeadphonesScreen` itself, next to search —
  see below).
- **Clear all custom profiles** — destructive action, confirm dialog, removes
  the atomic custom-store document and, if a custom profile was active, selects
  `None`/flat correction with a visible status message. It must not silently
  reinterpret a custom UUID as a Pages headphone name.
- One line of privacy copy: *"Imported EQ files are read and converted on
  your device and stored locally. They are not uploaded."* — adjust this
  sentence if Path 2 ends up using Option A (backend reuse), since that path
  *does* send file contents to the configured backend URL and the copy needs
  to say so.

Add the actual **import and selection UI** to `HeadphonesScreen` rather than
Settings, next to the existing search. The current screen combines favorites
and Pages results in one list; it has no separate favorites-row component, so
custom profiles should get a clearly labeled section:

- An **"Import from file"** button/chip alongside the search bar, opening the
  SAF picker.
- A **"Paste 10-band values"** action opens a name/table dialog; accepts exactly
  ten numeric frequency/gain rows with an optional column header.
- A **"My custom profiles"** section (only shown when the list is non-empty),
  listed above or below search results, each row showing display name, a
  format badge (Graphic/Parametric), and select/rename/delete affordances.
- Inline error state directly under the import button when a picked file
  fails to parse, naming the reason (no `GraphicEQ:` line found, zero valid
  points parsed, unsupported filter type in a parametric file, etc.) rather
  than a generic failure toast.

## Required flows

### 1. Import a GraphicEQ.txt file

1. User taps **Import from file** on `HeadphonesScreen`.
2. SAF picker opens; user selects a `.txt` file.
3. Read and validate as described in **Path 1**. On failure, show the inline
   error and do not create a profile entry.
4. On success, resample to eight bands, generate a UUID, default
   `displayName` from the file name, and atomically save the updated custom
   profile document.
5. Refresh the custom-profiles list; do not automatically select the new
   profile as active — selection is a separate explicit action (consistent
   with how picking a downloaded headphone already works).

The pasted 10-band route follows flow 1 from conversion onward, using the
same local store and explicit-selection rule. It does not create a file or
contact a server.

### 2. Import a ParametricEQ.txt file

Same picker/UI entry point as flow 1; format is detected from file content
(`Preamp:`/`Filter` lines vs a `GraphicEQ:` line), not from a separate button.
If both signatures are present, reject the file as ambiguous rather than
silently choosing one parser. Parametric parsing must honor `ON`/`OFF`, reject
missing or malformed required fields, and reject unsupported filter types unless
the UI clearly reports a partial conversion.
Everything after parsing follows flow 1 from step 4 onward. Do not ship this
flow until the **Path 2** sub-decision (Option A vs B) has been made
explicitly.

### 3. Select a custom profile as the active correction

1. User taps a row in **My custom profiles**.
2. Persist the tagged selection; the automatic-path resolver reads the eight
   gains from the custom profile document. Do not write the array from the
   screen and expect it to survive a service recompute.
3. `EqProcessingService` resolves and applies the local gains, with a local
   status message; manual sliders remain independently responsive.

### 4. Manage custom profiles

- **Rename**: updates `displayName` in the custom profile document only; the
  profile ID and gains are untouched.
- **Delete**: removes the profile from the atomic custom-store document. If
  the deleted profile was active, select `None`/flat correction and show a
  visible status message. Do not silently activate an unrelated cached
  selection.
- **Clear all**: the `SettingsScreen` action described above.

## Privacy

- Path 1 (GraphicEQ file or pasted table): fully on-device. Contents never
  leave the phone.
- Path 2 (ParametricEQ) **if Option A is chosen**: the raw filter text is sent
  to the configured backend URL (same `BuildConfig.BASE_URL` genre detection
  already uses) for conversion. This is a new use of an existing network
  destination, not a new one, but must be disclosed in the settings privacy
  copy — the file's *content* (filter frequencies/gains), not personally
  identifying information, is what's transmitted.
- Path 2 **if Option B is chosen**: no network involved, same as Path 1.
- Imported file names and display names are stored locally only, same
  storage model as everything else in this feature.

## Verification plan for future implementation

Unit tests (pure functions, no Android dependency needed for most of these):

- GraphicEQ parser: well-formed file, trailing semicolon, extra whitespace,
  unsorted input points, single malformed pair among valid ones, empty file,
  file missing the `GraphicEQ:` prefix entirely.
- Pasted table: optional header, tabs/spaces, ten rows, Unicode minus, malformed
  row, duplicate frequency, and sample values from autoeq.app.
- GraphicEQ resampling: target frequency exactly on a source point, between
  two points (log-interpolation correctness against a hand-computed
  example), below the lowest source point, above the highest source point
  (flat extrapolation in both directions).
- Rounding/clamping: values that round at the 0.1 dB boundary, values outside
  `[-12, 12]` dB clamp correctly in both directions.
- ParametricEQ parser: `PK` filters, at least one `LSC`/`HSC` shelf filter,
  missing `Preamp:` line (prefer reject unless the selected format contract
  explicitly defines 0 dB), `ON` versus `OFF` filters, malformed numeric
  fields, unsupported filter type (prefer a visible reject or an explicit
  import warning; never silently produce a wrong curve), and ambiguous files
  containing both GraphicEQ and ParametricEQ signatures.
- Storage: import, list, rename, delete, clear-all against a temp directory;
  atomic-write interruption and corrupted/missing custom-store recovery without
  crashing the audio service.
- Selection integration: selecting a custom profile updates the automatic
  path only; manual slider state and the manual update path are provably
  untouched by a test that changes both in sequence. Recompute, service restart,
  and Bluetooth selection must not reinterpret a custom ID as a Pages name.

Manual checks:

- Import a real autoeq.app `GraphicEQ.txt` export and compare the resulting
  eight gains against manually reading the same file's nearby points — sanity
  check the interpolation, not just unit-test it.
- Selected custom profile survives app restart and killed-and-restarted
  foreground service, same as a downloaded profile does today.
- Local profile status says "imported" or equivalent, never "downloaded";
  Bluetooth auto-selection behavior matches the documented source policy.
- Deleting the active custom profile falls back cleanly, with audible/UI
  confirmation, not silent flat output.
- Malformed/garbage file produces a clear in-app error, not a crash.
- If Path 2 Option A is built: airplane mode produces a clear "needs
  connection" message for parametric import specifically, while GraphicEQ
  import and every already-downloaded correction keep working offline.

## Implementation order

1. Re-check current source (per the note at the top of this document) and
   confirm/replace every **[ASSUMED]** tag above.
2. Build and unit-test the pure GraphicEQ parser + resampler (Path 1), no UI.
3. Add the local atomic custom-profile store and its own tests, independent of
   the parser.
4. Wire Path 1 into `HeadphonesScreen` (import button, list, select, error
   states) and `SettingsScreen` (count, clear-all, privacy copy).
5. Regression-check: automatic EQ still applies correctly for downloaded
   profiles, manual sliders remain independent, app restart/service restart
   behavior matches existing downloaded-profile behavior.
6. Decide Path 2's Option A vs B explicitly (see **Path 2**), document the
   decision and reasoning in this file before writing code for it.
7. Build Path 2 following the same parser → storage → UI order.
8. Update `AI.md`'s "Where to make a change" table with the final source-of-
   truth file locations for this feature, and update the repository-layout
   tree if new top-level files were added.

## Acceptance checklist

- [ ] GraphicEQ resampling matches Equalizer APO's own defined interpolation
      rule (log-linear between points, flat outside range) — not an
      approximation of it.
- [ ] No extra preamp is applied when resampling a `GraphicEQ.txt` file.
- [ ] Eight-band rounding (0.1 dB) and clamping (`[-12, 12]` dB) matches the
      existing dataset convention exactly, so custom and downloaded profiles
      are indistinguishable downstream.
- [ ] Custom-profile storage remains independent of the existing Room-backed
      genre-history database, as specified by the storage design.
- [ ] Selecting/importing a custom profile only ever goes through the
      automatic EQ path; manual slider priority is untouched, and service
      recomputation cannot overwrite it with a dataset-name lookup.
- [ ] `HeadphonesScreen` and `SettingsScreen` each get the UI described above,
      not a single screen doing both jobs.
- [ ] Clearing custom profiles and clearing downloaded AutoEQ cache remain
      two separate actions.
- [ ] Every malformed-file case produces a specific, visible error rather
      than a silent failure or crash.
- [ ] Path 2's exact-response versus heuristic-parity decision, plus Option A
      versus B, is written down explicitly before any
      parametric-import code is merged.
- [ ] If Option A was chosen, settings privacy copy discloses the network
      destination for parametric import specifically.
- [ ] `AI.md` is updated with final file locations after implementation.

## Assumptions and open questions (fill in during implementation)

| # | Item | Status |
| --- | --- | --- |
| 1 | Whether Room exists in the app yet | **[VERIFIED present]** `app/build.gradle.kts` includes Room 2.8.5; `GenreHistoryRepository.kt` uses it. Custom-profile JSON remains a design choice. |
| 2 | Exact current shape of active correction state | **[VERIFIED]** Current state is a headphone-name string plus `EqState.currentHeadphoneCorrection`; custom import needs a tagged selection extension. |
| 3 | Whether `autoeq-converter.js` applies `Preamp:` | **[VERIFIED]** It parses `Preamp:` with a default of `0` and adds it to the heuristic eight-band output. This is not proof of exact biquad fidelity. |
| 4 | Whether AutoEq exports shelf filters | **[VERIFIED from upstream source]** AutoEq emits `LSC`/`HSC` mappings in its parametric writer; retain shelf test coverage. |
| 5 | Path 2 Option A vs B final decision | **Not yet decided — see Path 2 section** |
| 6 | Default behavior for a `ParametricEQ.txt` file missing a `Preamp:` line | **[DESIGN CHOICE pending]** Prefer rejecting the file unless the selected parser specification explicitly defines a 0 dB default. |
| 7 | Exact versus heuristic ParametricEQ conversion | **[DESIGN CHOICE pending]** Current Node code is heuristic. Choose exact biquad sampling or documented parity before Path 2. |
| 8 | Custom-store format | **[DESIGN CHOICE]** One versioned atomic JSON document is preferred over an index plus per-profile files because profile count is small and it avoids partial-update/orphan states. |
| 9 | Bluetooth auto-selection vs user-pinned Custom profile | **[DESIGN CHOICE pending]** Define whether auto-selection switches source to Dataset or preserves Custom; never leave the source ambiguous. |

## Correction log

Another AI model, a human reviewer, or a later pass by this same assistant
should append here rather than silently rewriting sections above.

| Date | Corrected by | What changed | Why |
| --- | --- | --- | --- |
| 2026-09-26 | Codex | Re-checked Kotlin/Node source. Corrected converter description from exact biquad math to heuristic approximation; added tagged custom-selection requirement; replaced split files with an atomic custom-store design; tightened malformed-input handling; corrected stale UI/storage assumptions; and clarified Path 2 security and parity decisions. | Prevent incorrect implementation and silent audio-profile selection failures. |
| 2026-10-11 | Codex | Re-checked current source; corrected Room presence, GraphicEQ spacing/preamp scope, JSON ID wording, bounded file reading, service-owned correction resolution/status, and Bluetooth selection ambiguity. | Avoid partial imports and a custom selection being overwritten by the current name-based service pipeline. |
| 2026-10-11 | Codex | Recorded Path 1 implementation locations and the chosen Bluetooth behavior; left Path 2 pending. | Keep this plan aligned with source without implying unrun Android validation. |
| 2026-10-11 | Codex | Added ten-row pasted GraphicEQ input as an alternative to file import; reused eight-band conversion and local storage. | autoeq.app may show copyable values without a downloadable file. |
