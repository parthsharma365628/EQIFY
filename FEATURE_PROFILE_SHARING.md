# Feature Plan: Shareable EQ Profiles (code, QR, file)

Status: **Phase 1 implemented in source (2026-11-20)**. `assembleDebug` and
`testDebugUnitTest` pass (33 new JVM tests). Not yet validated on a device.
Phases 2–3 are not started.

Phase 1 is implemented in these files:
- `EqProfileCodec.kt`, `PresetNameRules.kt`, `BoundedTextReader.kt` (new)
- `UserPreferencesRepository.saveNewCustomPreset` (atomic, never overwrites)
- `HeadphoneDataRepository.findExactName`
- `screens/EqScreen.kt`:
  - Share (header and preset rows), with copy code, the share sheet, and save file
  - Import shared profile (paste or open file), with the preview dialog
- `HeadphonesScreen.kt` now uses `BoundedTextReader`
- Tests: `EqProfileCodecTest.kt`, `PresetNameRulesTest.kt`

Decisions taken:
- Bass is opt-in on import. It is snapped to the Home buttons' levels (0/3/6/9).
- Save & use doesn't change Auto-Genre; a note is shown instead.
- Built-in presets can be shared.

Source read for this plan:
`UserPreferencesRepository.kt`, `CustomEqProfileRepository.kt`,
`HeadphoneCorrectionSelection.kt`, `GraphicEqImporter.kt`, `EqState.kt`,
`EqProfileManager.kt`, `NowPlayingState.kt`, `HeadphoneDataRepository.kt`,
`EqProcessingService.kt` (auto pipeline), `screens/EqScreen.kt`,
`screens/HeadphonesScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`. Re-check source before implementing.

This is the "Shareable EQ profile export/import" P2/S candidate in
`EQify_Android_PRD_v2.md` §6.3.

## Note for AI assistants
Re-check the source before coding. Append to the **Correction log** at the end
instead of silently rewriting sections. Tags: **[VERIFIED]** = seen in source;
**[DESIGN CHOICE]** = decision made by this plan, open to challenge.

## Goal
A user can turn an EQ setup into a short code, a QR code or a small file,
send it to someone, and the receiver can preview it and save it into EQify.
No backend, no account, no network needed (except optionally to look up a
dataset headphone name in the cached index).

Non-goals (v1): cloud sharing/hosting, a public profile gallery, sharing genre
history, full settings backup (separate feature #7, which can reuse this codec
later), per-genre mappings (do not exist yet).

## What exists today (verified)

| Area | Current behavior | Consequence |
|---|---|---|
| Tone presets | `UserPreferencesRepository` stores a name list (`custom_preset_names`, `CustomPresetNameCodec` "v2:" URL-encoded) and gains as CSV under `custom_gains_<name>`. **[VERIFIED]** | An imported tone becomes an ordinary user preset via `saveCustomPreset`. No new storage. |
| Preset name rules | Enforced in `EqScreenViewModel.createNewProfile` and `renameCustomPreset`: non-blank, not `Custom`, not a default profile name, case-insensitively unique. `saveCustomPreset` itself does **not** validate and **silently overwrites** an existing name's gains. **[VERIFIED]** | Import must validate names itself and resolve collisions (auto-suffix), never call `saveCustomPreset` with an existing name. Extract the rules into one shared function. |
| Applying a preset | `EqScreenViewModel.selectPreset` → `EqState.applyManualPreset` sets manual override; `NowPlayingState.updateSong` calls `clearManualOverride()` on every track change. **[VERIFIED]** | With Auto-Genre on, "use now" only lasts until the next track. The preview must say so. Import must go through the VM/`EqState` path, never `EqEngine`. |
| Imported corrections | `CustomEqProfileRepository`: one atomic JSON file, schema 1, gains validated `[-12,12]`, name ≤100. `read()` **rejects any `format` other than `graphic_eq`**, which makes the whole store unreadable. **[VERIFIED]** | Do **not** introduce a new `format` value in v1. Store shared corrections as `graphic_eq` with `sourceFileName = "Shared profile"`. |
| Correction selection | Tagged `Dataset(name)` / `Custom(id)` / `None` in DataStore; service resolves it on the automatic path. **[VERIFIED]** | Selecting an imported correction = `selectCustomCorrection(id)` or `setSelectedHeadphone(name)` + `NowPlayingState.updateSelectedHeadphone(name)`, exactly like `HeadphonesViewModel`. |
| Dataset lookup | `HeadphoneDataRepository.search` / `getProfileGains` match names case-insensitively against the cached index. **[VERIFIED]** | Import can check whether a shared dataset name exists; offline with no cached index it cannot. |
| Bass boost | Global DataStore float + `EqState.setBassBoost`. **[VERIFIED]** | Optional in payload; applying it changes a global setting, so make it opt-in. |
| File reading | `HeadphonesViewModel.importGraphicEq` has a bounded (1 MiB) strict-UTF-8 SAF reader inline. **[VERIFIED]** | Extract to a shared helper and reuse (with a much smaller limit). |
| JSON | `CustomEqProfileRepository` uses `org.json` (Android stub — throws in JVM unit tests); `HeadphoneDataRepository` uses Gson (on the classpath via `converter-gson`). **[VERIFIED]** | The codec must use **Gson** (or hand-rolled parsing), not `org.json`, so it is JVM-testable. |
| Platform APIs | `minSdk 26` → `java.util.Base64` and `java.util.zip.Deflater/Inflater` available on device and JVM. **[VERIFIED]** | No dependency needed for the code format. |
| Deep links / share intents | Manifest has only the launcher intent filter on `MainActivity`. **[VERIFIED]** | `eqify://` links and "Share to EQify" need new intent filters (later phase). |
| QR | No QR or camera dependency. **[VERIFIED]** | Generating needs `com.google.zxing:core`; scanning needs an extra library (see open questions). |

## Payload (schema v1) **[DESIGN CHOICE]**

```json
{
  "app": "eqify",
  "v": 1,
  "name": "Night Bass",
  "freqs": [60,170,310,600,1000,3000,6000,12000],
  "tone": [3.0,2.0,0.0,-1.0,0.0,1.0,2.0,1.5],
  "correction": {
    "kind": "dataset",
    "name": "Sennheiser HD 600",
    "gains": [-1.2,0.4,0.0,0.3,0.0,-2.1,1.0,3.2]
  },
  "bass": 2.0
}
```

- `tone` (required): the eight tone/slider gains, the same values a user preset stores.
  It never contains headphone correction or bass. This matches how `EqState.activeToneGains` is already kept separate.
- `correction` (optional):
  - `kind`: `"dataset"` or `"custom"`.
  - `name`: the display name.
  - `gains`: always embedded, even for a dataset profile, so the profile still imports offline or if the name is missing from the receiver's catalog.
- `bass` (optional): the sender's bass-boost level. It's only applied if the receiver opts in.
- `freqs` must equal `EqProfileManager.BAND_CENTERS_HZ` exactly. Gains are rounded to 0.1 dB.
- No `genre` field in v1, because there's no per-genre mapping to apply it to.
  Unknown fields are ignored, so a future v1-compatible field won't break older readers.
- Never included: track/artist data, device IDs, Bluetooth names, timestamps, the Last.fm key.

### Encoding
`EQIFY1:` + Base64-URL (no padding) of the raw-Deflate-compressed UTF-8 JSON.
The result is expected to be about 200–300 characters.

The same string is used everywhere:
- the copy/paste code
- the QR content
- the body of the `.eqify.json` file (or the plain JSON; the decoder accepts both)
- the `eqify://import?p=` link parameter

### Validation (decode rejects the whole input on any failure)
- Input:
  - Trim whitespace. Accept the code with or without surrounding text by finding the `EQIFY1:` token.
  - Raw input is capped at 8 KB.
  - The inflated output is capped at 8 KB, to protect against decompression bombs.
- Envelope: `app == "eqify"`.
  - `v == 1` is accepted.
  - `v > 1` is rejected with "Made with a newer EQify version".
  - A missing or non-integer `v` is rejected.
- Arrays:
  - `freqs` must exactly match the band centres.
  - `tone` and `correction.gains` must have exactly 8 finite values.
  - Each value must be in `[-12, 12]`. Out-of-range values are rejected, not clamped, because a shared code is made by EQify and is never legitimately out of range.
- Name:
  - Trim it, collapse whitespace and strip control characters.
  - 1–40 characters for the preset name, ≤100 for the correction name.
  - A blank name falls back to "Shared profile".
- `correction.kind` must be one of the two known values.
- `bass` must be within the UI's existing range (check the Home bass buttons' max when implementing).

Errors map to specific user messages:
- not an EQify code
- corrupted code
- newer version
- band N out of range
- too large

## Code layout

| File | Change |
|---|---|
| `EqProfileCodec.kt` (new, pure Kotlin) | `data class SharedEqProfile`, `encode(profile): String`, `encodeJson(profile): String`, `decode(text): Result<SharedEqProfile>` with a typed `ShareError`. Uses Gson + `java.util.Base64` + `Deflater/Inflater`. No Android imports. |
| `PresetNameRules.kt` (new, pure) | `validate(name, existing): String?` and `uniqueName(base, existing): String` → "Night Bass (2)". Moves the duplicated rule from `EqScreenViewModel.createNewProfile` / `renameCustomPreset` into one place, and both callers use it. |
| `BoundedTextReader.kt` (new, small) | Extracted SAF bounded strict-UTF-8 reader from `HeadphonesViewModel.importGraphicEq`. GraphicEQ import keeps its 1 MiB limit; profile import uses 64 KB. |
| `ProfileShareRepository.kt` (new) or methods on the VMs | Build a payload from current state. Resolve an import into concrete actions. Details below. |
| `screens/EqScreen.kt` | "Share" on preset rows and "Share current EQ". "Import profile" entry in `PresetPickerDialog`. Share sheet and import preview dialogs. |
| `screens/HeadphonesScreen.kt` | "Share" action on imported custom-correction rows (correction only, flat tone). |
| `UserPreferencesRepository.kt` | No schema change. Optional: an atomic `saveNewCustomPreset(name, gains)` that fails if the name exists, instead of the overwrite-prone `saveCustomPreset`. |
| `AndroidManifest.xml`, `MainActivity.kt` (phase 3) | `eqify://import` VIEW filter + `ACTION_SEND text/plain` filter → route to the import preview. |
| `app/build.gradle.kts` | Phase 2: `com.google.zxing:core` (pinned). Phase 3: the scanner library chosen in open question 2. |
| Tests | `EqProfileCodecTest`, `PresetNameRulesTest` in `app/src/test`. |
| Docs | `AI.md` file map and "Where to make a change" row. `EQify_Android_PRD_v2.md` status. This file's correction log. |

## Flows

### Export
1. Sources:
   - **A saved user preset:** its gains.
   - **Current EQ:** `EqState.baseToneGains` when manual override is on, else `activeToneGains`. These are the tone gains, never the combined output.
   - **A default preset:** allowed, but the share sheet notes it's built in.
2. Correction, taken from `headphoneCorrectionSelection`:
   - `Dataset(name)` → kind dataset, name, and `EqState.currentHeadphoneCorrection` gains, only if the correction status is Downloaded or Cached for that name. Otherwise omit the correction.
   - `Custom(id)` → kind custom, `displayName`, and the stored gains.
   - `None` → omit the correction.
3. A share-sheet toggle, "Include headphone correction", defaults to **on**. The sheet shows what's included.
4. Outputs:
   - **Copy code:** clipboard.
   - **Share:** `ACTION_SEND` with the code plus a one-line explanation.
   - **Show QR:** ZXing bitmap in a dialog, phase 2.
   - **Save file:** `CreateDocument("application/json")`, suggested name `<name>.eqify.json`, containing the readable pretty JSON. The decoder accepts plain JSON too, so files stay human-readable.

### Import
1. Entry points:
   - **Paste code:** dialog with a text field.
   - **Open file:** `OpenDocument`, filtered to `application/json` and `text/plain`, read through `BoundedTextReader`.
   - **Scan QR:** phase 3.
   - **Deep link / Share to EQify:** phase 3.
2. Decode (pure, on `Dispatchers.Default`). On error, show the specific message and stop. Nothing is written.
3. **Preview dialog.** Never auto-apply. It shows:
   - the name and a small 8-bar curve of the tone, drawn with Compose `Canvas` (no chart library)
   - the correction line:
     - "Uses Sennheiser HD 600 correction (found in catalog)"
     - "Uses Sennheiser HD 600 correction (not in your catalog, will be saved as an imported correction)"
     - "Includes imported correction 'X'"
   - checkboxes:
     - "Save tone as preset" (on)
     - "Use headphone correction" (on if present)
     - "Also set bass boost to +N dB" (off)
   - a note when Auto-Genre is on: "Auto-Genre will replace this tone on the next track."
4. Buttons: **Save** and **Save & use**.
5. Writes. On any failure, report it and leave things as they were:
   - **Tone:** `PresetNameRules.uniqueName`, then save as a new preset (never overwrite).
   - **Correction (dataset):**
     - Check the cached index for the name. When the index is unavailable offline, treat it as not found and use the embedded gains.
     - Found: only for "Save & use", call `setSelectedHeadphone(name)` + `NowPlayingState.updateSelectedHeadphone(name)`. The service downloads or uses its cache as normal.
     - Not found: `customEqProfiles.importProfile(name, "Shared profile", gains)` (format stays `graphic_eq`). Only for "Save & use", `selectCustomCorrection(id)`.
   - **Correction (custom):** always `importProfile`. Then, for "Save & use", select it.
   - **Bass:** only if ticked, through the same repository/`EqState` path the Home bass buttons use.
   - **"Use":** `EqScreenViewModel.selectPreset(newName)`, the existing path. It sets the manual override and is never a direct engine call.
   - **Dedup:** if an identical custom correction already exists (same 8 gains), reuse its id instead of adding a duplicate.
6. Confirm with a message, e.g. "Saved 'Night Bass (2)'. Headphone correction saved to My custom profiles."

## Invariants to preserve
- The manual slider path is untouched. An import never blocks on the network. The optional index check is a short local cached read; when it's unavailable, fall back to embedded gains.
- No UI code calls `EqEngine`. Corrections are applied only through the tagged selection on the automatic path.
- A custom UUID is never treated as a dataset name.
- Global presets stay global. Importing doesn't add per-headphone scoping.
- Payloads contain no listening history or device identifiers.

## Phases and effort (two-person team)

| Phase | Scope | Effort |
|---|---|---|
| 1 | Codec + name rules + tests. Copy/paste code and share-intent export. Paste import with preview. File export/import. EqScreen UI. | ~2.5 days |
| 2 | QR display (ZXing core). Share action on HeadphonesScreen custom rows. Dedup of identical corrections. | ~0.5–1 day |
| 3 | QR scanning. `eqify://import` deep link + "Share to EQify" intent filters routed through `MainActivity` to the preview. | ~1 day |
| — | Docs (`AI.md`, PRD), regression pass | ~0.5 day |

Total is about 4–5 days, slightly above the PRD's "S" rating, mainly because
of the correction handling and the name-collision fixes.

## Tests

**JVM (`app/src/test`):**
- Codec:
  - encode→decode gives the same profile back, with and without a correction and bass
  - the decoded profile is equal at 0.1 dB rounding
  - the code length stays under 400 characters for a typical profile
- Decode rejects:
  - a missing prefix
  - bad Base64
  - truncated Deflate data
  - inflated output over the cap
  - `app` ≠ eqify
  - `v` = 2, missing or a string
  - wrong `freqs`
  - 7 or 9 gains
  - NaN or ±∞
  - out-of-range gains
  - an unknown correction kind
  - oversized raw input
- Decode accepts:
  - plain JSON
  - a code surrounded by other text
  - extra unknown fields
- Name rules:
  - reserved names (`Custom`, `Flat`, `Hip-Hop` in any case)
  - duplicates → suffix chain "(2)", "(3)"
  - control characters stripped
  - length cap
  - blank → fallback

**Manual / device:**
- Export on one device, import on another: same sliders, correct correction selected, sound matches.
- Import while a slider drag or manual override is active: sliders stay responsive, and nothing written before the user confirms.
- Auto-Genre on → "Save & use" → change track: genre takes over, as the preview said it would.
- Dataset name missing, or offline with no cached index → saved as an imported correction from the embedded gains.
- Garbage text, a non-EQify QR code, or a 5 MB file → clear error, no crash.
- Existing GraphicEQ import still works after extracting `BoundedTextReader`.

## Open questions (decide before or during phase 1)

| # | Question | Recommendation |
|---|---|---|
| 1 | Include bass boost in the payload? | Include it, but opt-in on import (default off), because bass is a global setting. |
| 2 | QR scanning library | Google `play-services-code-scanner`: no camera permission and little code, but it needs Google Play services. The alternative is CameraX + ML Kit/ZXing (needs the camera permission and more code). If this is undecided, ship phases 1–2 without scanning. Scanning a QR with the phone's camera app also works once the phase 3 deep link exists. |
| 3 | "Save & use" with Auto-Genre on | v1: apply it and show the note. Don't change the Auto-Genre setting automatically. Per-app pinning or custom genre profiles are the real fix later. |
| 4 | Dataset correction found in catalog, but the embedded gains differ (dataset updated) | Prefer the receiver's catalog version. Note it in the preview if the difference is above 0.5 dB on any band. |
| 5 | Sharing built-in presets | Allow it (harmless). Low value, so no special UI. |

## Correction log

| Date | Corrected by | What changed | Why |
|---|---|---|---|
| 2026-11-20 | Claude | Initial plan from a source read of the files listed at the top. | — |
| 2026-11-20 | Claude | Implemented phase 1 and changed the plan in three places. (1) Existing `createNewProfile` now uses `PresetNameRules.validateNew` with the same messages. The new 40-character cap applies only to imported names, so existing flows are unchanged. (2) Imported bass is snapped to 0/3/6/9 dB, because Home only offers those levels. (3) A dataset name not found in the cached catalog (or a lookup that takes over 5 s) falls back to the embedded gains as an imported correction. A Gradle run needed `JAVA_HOME=D:\ANDROID\jbr` and `-Djdk.net.unixdomain.tmpdir=<short path>` because the user temp path contains a space. Open question 4 (catalog vs embedded gains differ) is not handled yet: the catalog version is used silently. | Record the actual source state and decisions. |
