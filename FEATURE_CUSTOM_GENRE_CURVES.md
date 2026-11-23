# Feature Plan: Customise built-in genre curves

Status: planned and implemented in the same pass (2026-11-22). Results are in
the **Correction log**. Device check pending.

## Goal
Let the user change a built-in curve (e.g. Rock), so that **every song using it**
gets the user's version: auto-genre, manual selection, genre corrections, sharing
and app restart. **Reset to default** restores the built-in curve.

## Assumptions (stated, per the working guidelines)
1. **Built-in presets that can be customised:** every entry in `EqProfileManager.defaultProfiles` **except Flat**.
   Flat must stay flat: it's the neutral fallback for "no genre" and unknown
   genres, and a customised Flat would silently colour everything. Bass Boost is
   customisable, because it's a normal preset.
2. **Names don't change.** A customised Rock is still called "Rock". Built-in names
   stay reserved for user presets (unchanged `PresetNameRules`).
3. **Saving a curve equal to the default** removes the override, so there's no
   pointless "customised" badge.
4. **Overrides are local only,** stored like the presets. Clearing app data removes them.

## Where built-in curves are used today (verified in source) → change

| # | Use | File | Change |
|---|---|---|---|
| 1 | Auto-genre / genre correction → curve | `EqProcessingService` → `EqProfileManager.resolveGenreGains` | Resolver takes an overrides map. The pipeline adds an overrides flow, merged with the custom-preset flow into one input (every `combine` group is already at 5). |
| 2 | Keyword-mapped genres ("alternative rock" → Rock) | `resolveGenreGains` | The same override applies after mapping |
| 3 | Picking a built-in preset | `EqScreenViewModel.selectPreset` | custom → **override** → default → Flat |
| 4 | Editing a built-in's sliders | `updateBand` | **Keep the built-in name** with unsaved changes. Only Flat still becomes "Custom". |
| 5 | Save while on a built-in | `saveToCurrentPreset` | Stores the override (or removes it if equal to the default). Flat/"Custom" → New preset dialog, as before. |
| 6 | Reset (discard unsaved edits) | `resetCurrentPreset` / `resolveGains` | Back to the **effective** curve (the override if any) |
| 7 | Reset to default | new | Picker row menu and EQ overflow menu. Removes the override; re-applies the default if that preset is active. |
| 8 | App start, restoring the last preset | `EqifyApplication.loadPresetGains` | custom → **override** → default |
| 9 | Sharing a built-in | `openShare` | Shares the **effective** curve. The "everyone already has it" note only appears when it isn't customised. |
| 10 | Picker list | `PresetPickerDialog` / `PresetRow` | Built-in rows show "Customised" plus a menu (Share, Reset to default) |
| 11 | Reset all | Settings → Data & privacy | "Reset customised genre curves (N)" with a confirm dialog. Hidden when N = 0. |
| 12 | Names reserved | `PresetNameRules` | Unchanged |

## Cases covered
- An auto-detected Rock song uses the customised Rock, including while the
  Activity is closed (service path). Changes apply live through the overrides flow.
- A song corrected to Rock uses the customised Rock (same resolver).
- Editing while auto-genre shows "Rock" then Save customises Rock for all Rock
  songs. A track change before saving discards the edit, as today.
- Editing Flat still makes "Custom", and Save asks for a new preset name.
- Reset to default on the active preset snaps the sliders back. On an inactive
  one, it changes only storage.
- "Save as new preset" from a customised built-in creates a normal custom
  preset (the overflow menu, unchanged).
- Corrupt or unknown stored entries (a removed genre, Flat, wrong band count,
  NaN, outside ±12 dB) are ignored and the default is used.
- Importing a shared "Rock" still becomes a custom preset "Rock (2)"
  (name reserved, unchanged).

## Files
- `GenreCurveOverrides.kt` (new, pure codec + rules) + `GenreCurveOverridesTest`
- `EqProfileManager.kt`: `resolveGenreGains(genre, overrides)`, `effectiveGains(name, overrides)` + tests
- `UserPreferencesRepository.kt`: `genreOverrides` flow, `setGenreOverride`, `clearGenreOverrides`
- `EqProcessingService.kt`, `EqifyApplication.kt`, `screens/EqScreen.kt`, `SettingsViewModel.kt`, `screens/SettingsScreen.kt`
- `AI.md`

## Checks
- **JVM:**
  - the codec round-trips
  - Flat and unknown names are rejected
  - bad gains are rejected
  - equal-to-default is dropped
  - the resolver uses overrides for direct, case-insensitive and keyword-mapped genres
  - Flat is never overridden
- **Device:**
  - customise Rock, play a Rock song: the curve changes
  - reset to default
  - restart the app on a customised preset
  - reset all in Settings

## Correction log
| Date | By | Change | Why |
|---|---|---|---|
| 2026-11-22 | Claude | Plan written. | — |
| 2026-11-22 | Claude | Implemented as planned. Build passes; 120 JVM tests pass (10 new in `GenreCurveOverridesTest`). Device checks not yet run. | — |
