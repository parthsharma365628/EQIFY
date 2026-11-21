# UI Redesign: Direction (Phase 1)

Status: **approved 2026-11-21.** All nine recommendations in §9 were accepted
as written.
- **Phases 2 and 2b (tokens, themes)** are committed, and the user checked them on a device.
- **Phase 3 (component kit) is done in source.** Build and 87 unit tests pass. The previews are in `ui/components/ComponentPreviews.kt` (not visually reviewed by Claude: they need Android Studio).
- **The redesign is complete in source (phases 2–5).** Build and 88 tests pass.
- **On a device:** the EQ screen was checked by the user. Home, Headphones, Settings and the phase 5 polish still need a device check.
- **Large fonts:** check them via the font-scale previews in Android Studio.

Source surveyed: `Theme.kt`, `ui/theme/{Color,Type}.kt`, `AppGlyph.kt`, and
`screens/{Home,Eq,Headphones,Settings}Screen.kt` (about 3,400 lines of UI).

## 1. Goal

Make EQify look like a **professional audio tool**: calm, precise and
trustworthy. Numbers and curves should be easy to read at a glance.
Automatic behaviour (genre and headphone detection) should be visible
without being noisy.

It should also be **user-customisable with themes** (§4.6).

Not goals:
- no behaviour changes
- no new features beyond theming
- no navigation restructure (same 4 screens, same bottom bar)

## 2. What's wrong today (verified in source)

| Problem | Evidence | Effect |
|---|---|---|
| No type scale | `Type.kt` is the unused Studio template. Screens set ~120 `fontSize` values by hand, from 8 to 24 sp (52 in `EqScreen` alone). | Headings, labels and body text don't follow a consistent rhythm. Text at 8–10 sp is hard to read. |
| Token names don't match the colours | `AccentPurple` is sage green and `NeonCyan` is not cyan. Error red `0xFFFF5252` is hard-coded 6 times. Template `Color.kt` is unused. | Easy to misuse colours. No error/warning/success roles. |
| Text symbols as icons | `✕ ▾ ⇩ + ★ ☆ ✓ ←` and the "A/B" text button. Six hand-drawn `AppGlyph`s. | They render differently per phone font and look unfinished. |
| Inconsistent shapes and spacing | Corner radii 8/10/12/16/20/50 dp. Padding 12/14/16/20 dp, picked per screen. | The screens feel like they come from different apps. |
| Re-implemented components | Each screen builds its own cards, rows, pills and dialogs (e.g. three "row with two small TextButtons" variants). | Visual drift, and every change has to be made four times. |
| Misleading EQ sliders | Slider fill always grows from the **bottom**, so a −6 dB band looks "on". | Wrong reading of the most important control. |
| Dialog overload | Share, import, preview, correction, rename, delete and paste are all `AlertDialog`s with long scrolling content. | Cramped on phones; actions are hard to find. |
| Dense, low-hierarchy rows | E.g. custom-profile rows are `name · GraphicEQ` + Rename + Delete + Share, all as text buttons. | Unclear what is the main action. |

## 3. Design principles

1. **One accent, used sparingly.** The accent means "active / interactive". Everything else is neutral.
2. **Numbers are first-class.** All dB, Hz and percentage values use tabular (fixed-width) figures, aligned and never jumping.
3. **Hierarchy comes from type and space, not colour or boxes.** Fewer borders, more consistent spacing.
4. **Every screen has one main action.** Secondary actions go to icon buttons or an overflow menu.
5. **The status of automatic features is always visible:** which genre, from which source, with what confidence, and whether the correction is downloaded, cached or failed.
6. **Accessible by default:**
   - text at least 11 sp
   - touch targets at least 48 dp
   - WCAG AA contrast
   - respects the system font size
   - every icon button has a content description

## 4. Design tokens (proposed)

### 4.1 Colour roles: dark theme only, refined sage identity

The current sage palette is kept (it's distinctive and calm), with slightly
more contrast and proper role names.

| Role | Hex | Use |
|---|---|---|
| `background` | `#0E1110` | Screen background |
| `surface` | `#151918` | Cards |
| `surfaceRaised` | `#1C2120` | Sheets, dialogs, raised rows |
| `surfaceHighest` | `#252B29` | Inputs, slider tracks, pressed states |
| `outline` | `#3A4440` | Dividers, input borders |
| `outlineStrong` | `#73807A` | Focused or emphasized outlines |
| `textPrimary` | `#E8ECEA` | Main text |
| `textMuted` | `#A3ADA8` | Secondary text |
| `textFaint` | `#6F7A75` | Hints and disabled text only (not for essential text) |
| `accent` | `#6FA393` | Active controls, selected state, slider fill, links |
| `onAccent` | `#0B1310` | Text/icons on accent |
| `accentContainer` | `#22352F` | Selected chips, nav indicator, highlight backgrounds |
| `onAccentContainer` | `#CFE5DC` | Text on accentContainer |
| `success` | `#7FC8A9` | "Correction downloaded", "limiter active" |
| `warning` | `#E0B45C` | Low confidence, fallback in use |
| `error` / `errorContainer` | `#F2726F` / `#3A1F1E` | Failures, destructive actions |

Approximate contrast (to be checked with a tool in phase 2):
- `textPrimary` on `surface` ≈ 15:1
- `textMuted` on `surface` ≈ 7.8:1
- `accent` on `surface` ≈ 6:1
- `onAccent` on `accent` ≈ 6.5:1
- `textFaint` ≈ 3.9:1, which is why it's for hints only

**Genre-mix chart:** four tints of the accent, plus `textFaint` for "Other". It's a single-hue scale, because genres don't need rainbow colours.

### 4.2 Typography (Material 3 roles; replaces all hand-set sizes)

| Role | Size/line | Weight | Use |
|---|---|---|---|
| `headlineSmall` | 24/32 | SemiBold | Screen titles |
| `titleLarge` | 20/28 | SemiBold | Now Playing track title |
| `titleMedium` | 16/24 | SemiBold | Card titles, dialog/sheet titles |
| `titleSmall` | 14/20 | Medium | Row titles |
| `bodyLarge` | 16/24 | Regular | Inputs, main body |
| `bodyMedium` | 14/20 | Regular | Default body, row subtitles |
| `bodySmall` | 12/16 | Regular | Helper text, captions |
| `labelLarge` | 14/20 | Medium | Buttons |
| `labelMedium` | 12/16 | Medium | Chips, band labels, small values |
| `labelSmall` | 11/16 | Medium, +0.5 tracking, UPPERCASE | Section headers only (the "EQ", "AUDIO", "GENRE MIX" overlines) |

Plus a `numeric` style (tabular figures via `fontFeatureSettings = "tnum"`)
used for every dB/Hz/% readout. **Nothing below 11 sp.** Today's 8–10 sp band
labels and values move to `labelMedium`.

### 4.3 Spacing, shape, elevation

- **Spacing scale:** 4 · 8 · 12 · 16 · 24 · 32 dp.
  - Screen side padding 16 dp.
  - Gap between cards 12 dp.
  - Card padding 16 dp.
  - Row padding 16×12 dp.
- **Shapes:**
  - `small` 8 dp (chips, inputs)
  - `medium` 12 dp (rows, buttons)
  - `large` 16 dp (cards)
  - `extraLarge` 28 dp (sheet top corners)
  - full pill for toggles/segments
- **Elevation:** tonal only (surface → surfaceRaised → surfaceHighest). No drop shadows, which look muddy on near-black.

### 4.4 Iconography

Replace text symbols and `AppGlyph` with **Material Symbols (Rounded)** (implemented as vector drawables, see the correction log):
- **Basics from `material-icons-core`:** Close, Add, Check, Star/StarBorder, ArrowBack, Share, Search, Delete, Edit, MoreVert, KeyboardArrowDown, Info, Warning, Settings, PlayArrow.
- **A few copied as vector drawables:** Headphones, Equalizer/Tune, GraphicEq, QrCode/QrCodeScanner, FileOpen/Download, Bluetooth.

That way the app doesn't pull in the large `material-icons-extended` library; R8 is off, so its full size would land in the APK.

### 4.5 Motion (UI only; never on the audio path)

- 150–250 ms standard easing.
- Crossfade on genre or status changes.
- `animateContentSize` for expanding cards.
- Animated value readouts while dragging sliders.
- Nothing animates audio or delays EQ writes.

### 4.6 Themes (user-customisable)

**What the user can choose** (Settings → Appearance):

| Setting | Options | Default |
|---|---|---|
| Mode | Dark · Light · Follow system | Dark (today's look) |
| Accent | Sage · Amber · Ocean · Violet · Rose · Mono, plus **Dynamic** (wallpaper colours, Android 12+ only) | Sage |
| Pure black (AMOLED) | On/off, dark mode only: background `#000000`, surfaces slightly lifted | Off |

That's 6 accents × 2 modes = 12 curated looks, plus Dynamic. **No free colour
picker in v1:** curated palettes guarantee readable contrast, while arbitrary
colours can make text or sliders unreadable.

**Accent presets** (dark-mode `accent`; each also gets light-mode, container and on-colour variants):

| Accent | Dark `accent` | Character |
|---|---|---|
| Sage | `#6FA393` | Calm, current identity |
| Amber | `#E0A458` | Warm, analogue hi-fi |
| Ocean | `#6C9EFF` | Crisp, technical |
| Violet | `#A48CF0` | Expressive |
| Rose | `#E88AA0` | Soft, playful |
| Mono | `#C9CFCC` | Minimal grayscale; the accent is just brighter text |

**How it works:**
- **One source for theme data.** A theme is pure data: `EqifyPalette` = the colour roles from §4.1, for both modes. `EqifyTheme(mode, accent, pureBlack)` builds the Material `ColorScheme` plus an `EqifyExtraColors` CompositionLocal (success, warning, `textFaint`, chart tints) that Material doesn't have. Screens only use roles, never hex values, so a new theme needs no screen edits.
- **Neutrals stay constant and the accent varies.**
  - Background, surfaces and text are shared per mode.
  - Only the accent family changes, plus slightly tinted surfaces (about 4% accent mixed in) so each theme feels cohesive.
  - Success, warning and error stay fixed so their meaning never changes. For Amber, warning shifts slightly so it stays distinct.
- **Dynamic colour** uses `dynamicDarkColorScheme`/`dynamicLightColorScheme` on API 31+. Our extra roles are derived from it, and on older Android the option is hidden.
- **Saved setting.**
  - The choice is stored in `UserPreferencesRepository` (DataStore), set through `SettingsViewModel`, and applied live with no restart.
  - To avoid a wrong-colour flash at launch, the last theme is also mirrored to a tiny SharedPreferences value, read synchronously in `MainActivity` (the same pattern as the crash-reporting opt-out).
- **Fixed regardless of theme:**
  - QR codes stay black on white.
  - The EQ curve and slider fill use the accent.
  - The system bars and splash follow the theme.
- **Picker UI:**
  - an "Appearance" section with a mode `SegmentedToggle`
  - a row of accent swatches (circles with a check on the selected one)
  - the pure-black switch
  - a **live mini preview card** showing a slider and a chip
- **Quality gate:**
  - every palette is listed in one file
  - a JVM test checks the WCAG contrast of each key text/background pair (≥4.5:1 for text, ≥3:1 for large or non-text), for every theme and mode
  - previews render each theme

**Cost:** about +2 days on top of the redesign. Most of it is the light-mode
palette (every screen needs checking in light mode once) plus the picker and
contrast tests. Building the token system for themes from the start costs
almost nothing extra in phase 2.

## 5. Component kit (`ui/components/`, phase 3)

| Component | Replaces |
|---|---|
| `EqCard(title?, action?)` | Ad-hoc `Card` + padding combos on every screen |
| `SectionHeader(text)` | Hand-styled uppercase labels (`labelSmall`, accent or muted) |
| `SettingRow` / `SwitchRow` / `ValueRow` | `SimpleSettingsItem`, `ControlledSettingsItem` and similar rows elsewhere |
| `PillChip` / `StatusChip(kind)` | Genre/confidence chips, preset chip, correction status text |
| `SegmentedToggle` | Bass Off/+3/+6/+9 buttons, EQ ⇄ Original (A/B) |
| `PrimaryButton`, `TonalButton`, `TextAction`, `IconAction` | Mixed `TextButton`s with custom colours |
| `EqSheet` (ModalBottomSheet) | Long `AlertDialog`s: share, import, import preview, genre correction, recent tracks |
| `ConfirmDialog` | Delete/clear confirmations (short dialogs stay dialogs) |
| `StatusBanner(kind)` | Wired-headphone banner, share/import messages, errors |
| `ValueReadout(value, unit)` | All dB/Hz/% text |
| `ToneCurve` | The current `ToneCurvePreview`, reused in the EQ screen background and previews |
| `EmptyState(icon, title, body, action?)` | "Waiting for music…", "No matches", empty recent tracks |

## 6. Screen-by-screen plan

### Home (today: top bar → wired banner → Now Playing → genre mix → EQ status → headphone → bass)
- **Top bar:** "EQify" in `headlineSmall`, plus a settings `IconAction`.
- **Now Playing hero card:**
  - track title in `titleLarge`, artist in `bodyMedium`
  - a `StatusChip` row: genre · confidence dot (success/warning colour) · source
  - "Change genre" as a `TextAction`, emphasized when confidence is Low or a fallback was used (this is the PRD's planned affordance, at UI level only)
  - idle state uses `EmptyState` with a notification-access hint when access is missing
- **"Sound" card:** merges today's EQ status, headphone and bass cards into one card with three rows:
  - master switch with the active preset
  - headphone row with a correction `StatusChip`, tapping it opens Headphones
  - bass `SegmentedToggle`
  - test tone becomes a small secondary action

  **(Structural change: open decision 5.)**
- **Genre mix card** moves below:
  - one horizontal stacked bar with a legend and `ValueReadout` percentages
  - "Recent tracks" opens a sheet
  - keeps the "detected tracks, not listening time" wording

### EQ (the showcase screen)
- **Header:**
  - title
  - preset `PillChip` with a dropdown icon, which opens the picker sheet
  - **EQ ⇄ Original** `SegmentedToggle`, replacing the "A/B" text
  - overflow `MoreVert` menu: Save, Save as new, Reset, Share, Import
  - Save also appears as a tonal button only while there are unsaved changes
- **Sliders, redrawn:**
  - **bipolar fill from a visible 0 dB centre line**, so cuts and boosts read correctly
  - faint ±6/±12 dB grid lines
  - the `ToneCurve` drawn through the thumb positions behind the sliders
  - 48 dp-wide touch columns
  - values in `ValueReadout` (tabular) at `labelMedium`
  - band labels at `labelMedium`, not 9 sp
- **Preset picker:**
  - becomes an `EqSheet` with sections: Built-in / My profiles / actions (New, Import shared profile)
  - per-row overflow menu (Share, Rename, Delete) instead of 2–3 inline text buttons
- **Share and import:**
  - sheets instead of dialogs
  - the code shown in a monospaced box with a copy `IconAction`
  - the QR on a white rounded card
  - import preview uses the `ToneCurve` at full width

### Headphones
- **Top bar:** proper back `IconAction` and Done.
- **Search:** field with a leading search icon, a clear button, and a "6,028 models" caption.
- **Selected headphone card:** name, plus a `StatusChip`: Downloaded / Cached / Imported / Failed, with retry.
- **One "Add correction" button** with a menu: Import GraphicEQ file · Paste 10-band values · Name a headphone (flat). This replaces three separate text buttons.
- **Clear sections:** Favourites · My custom profiles · Catalog results.
  - Rows show the type (in-ear/over-ear) as a small chip, a star `IconAction`, and a check when selected.
  - Custom rows get an overflow menu: Use, Share, Rename, Delete.
- **Error and offline states** use `StatusBanner` / `EmptyState` with Retry.

### Settings
- **Rows:** `SectionHeader`s with consistent `SwitchRow`/`SettingRow`s, each with a leading icon.
- **Output protection:** a `ValueRow` showing the current mode, which opens a sheet with the three modes plus a diagnostics line.
- **Last.fm key:** in a row that opens a small sheet (masked input, Save/Clear).
- **Data group:** destructive "Clear …" rows use error-coloured text and a `ConfirmDialog`.
- **Privacy:** the long privacy subtitle becomes an "About your data" row that opens a sheet with short bullet points. The same content, made readable.
- **Debug group** (debug builds only) collected at the bottom.

### Shared
- **Bottom navigation:** Material icons, an `accentContainer` indicator, and labels in `labelMedium`.
- **Onboarding walkthrough:** restyled with the same tokens. Content unchanged.
- **App icon refresh:** optional, separate (open decision 6).

## 7. Rules for phases 2–5

- **UI only:**
  - View models, repositories, `EqState`/`NowPlayingState`, the automatic and manual EQ paths, and the sharing/crash logic don't change.
  - If a screen needs new UI state (e.g. sheet visibility), it stays in the composable or its existing view model and never touches processing.
- **Same strings and behaviour**, unless the change is listed above.
- **Each phase:** build plus all unit tests, Compose `@Preview`s for new components, and before/after screenshots if an emulator is available. One commit per phase.

## 8. Phases

| Phase | Scope | Estimate |
|---|---|---|
| 1 | This document (+ optional visual mock-up page) | done when approved |
| 2 | Tokens: colour roles **built as swappable palettes (§4.6)**, `Typography` + numeric style, spacing/shape constants, icon set; delete template `Color.kt`; rename tokens across screens (no layout changes yet) | ~1 day |
| 2b | Themes: all accent presets for dark + light, pure black, Dynamic colour, DataStore + startup mirror, Appearance picker with live preview, contrast tests | ~2 days |
| 3 | Component kit + previews | 1–2 days |
| 4 | Screens, one commit each: EQ → Home → Headphones → Settings | ~4 days |
| 5 | Polish: motion, 48 dp targets, contrast audit, large-font check, onboarding restyle | ~1 day |

## 9. Open decisions (needed before phase 2)

| # | Decision | Recommendation |
|---|---|---|
| 1 | Default accent (users can change it, §4.6) | Sage `#6FA393` |
| 2 | Font | System Roboto with tabular figures: no APK growth, and it renders well. Alternative: bundled Inter (~300 KB, more distinctive). |
| 3 | Icons | `material-icons-core` + ~8 copied vectors (not the full extended set). |
| 4 | Long dialogs → bottom sheets | Yes, for share, import, preview, correction, recent tracks and preset picker. |
| 5 | Home: merge EQ status + headphone + bass into one "Sound" card and move genre mix down | Yes. It reduces 5 cards to 3, and the controls you touch most go first. |
| 6 | App icon refresh in this pass | Later, as a separate small task. |
| 7 | Theme scope | 6 accents + Dynamic colour, Dark/Light/System, pure-black option. No free colour picker in v1. |
| 8 | Default mode | Dark. Alternative: Follow system. |
| 9 | Order: build themes (2b) before or after the screen redesign (4)? | Right after tokens (2 → 2b). Every redesigned screen is then checked in light mode as it's built, instead of all at the end. |

## Correction log
| Date | Corrected by | What changed | Why |
|---|---|---|---|
| 2026-11-21 | Claude | **Phase 5 · polish.**<br>(1) **Onboarding walkthrough** (`MainActivity.PermissionWalkthrough`) is restyled: step icon in an accent circle, `titleLarge`/`bodyMedium` text, animated step dots announced as "Step x of 4", `PrimaryButton` + `TextAction` Skip. Same steps, texts and actions.<br>(2) **Touch targets:** `SegmentedToggle` segments are 48 dp (were 40), and EQ value readouts are 48 dp tall (were 32). Known exception, unchanged: the eight band columns are about 40 dp wide on a 360 dp phone, because the screen is too narrow for eight 48 dp columns. Their vertical drag area is large.<br>(3) **Motion (UI only, never the audio path):** 180 ms colour fades on `SegmentedToggle` and `PillChip`; Headphones rows fade their selected colour; list rows and banners use `Modifier.animateItem()`; onboarding dots animate width.<br>(4) **Contrast audit:** new `ThemeContrastTest.componentPairsMeetWcagAa` covers the pairs components actually draw (selected-row text and icons on `accentContainer`, chip/banner/segment text on `surfaceHighest`, `onAccent` on `accent`, error on `surfaceHighest`, band fill on track, 0 dB tick). All 18 palettes passed without changes.<br>(5) **Large fonts:** `EqSheet` action rows, Home's genre chips and the Import sheet's source chips now wrap (`FlowRow`) instead of overflowing. Segment labels ellipsize. At font scale >1.3 the EQ readouts drop the decimal so "+12.0" fits a narrow column (exact values stay in the tap-to-edit dialog). New previews: kit at 1.5×/2×, and EQ bands at 1×/1.5×/2×. Claude did not render them; they need Android Studio. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 4 · Settings + navigation.**<br>`screens/SettingsScreen.kt` was rewritten on the kit. `SettingsViewModel` is unchanged, as are the notification-access check and the auto-enable on return.<br>Groups: Appearance · Sound (Auto EQ, smooth transitions, output protection `ValueRow`, force mono) · Detection (media listener, auto-genre, headphone auto-detect, Last.fm key `ValueRow`) · Data & privacy (About your data, imported-profile count, three destructive Clear rows) · Feedback (send feedback, crash reports) · About · Debug (debug builds only). Every row has a leading icon.<br>Output protection is a sheet with selectable option cards, the same wording, and a limiter diagnostics line. The Last.fm key is a sheet (masked field, Save/Clear). **The long privacy subtitle became an "About your data" sheet with five short points**, with the same content.<br>Confirmations use `ConfirmDialog` (destructive for Clear). Clear results show as banners instead of OK pop-ups.<br>Re-grouped: Last.fm moved into Detection, and the Clear actions moved from About into Data & privacy.<br>**Bottom navigation** uses `EqIcons` with an accent-container indicator. `AppGlyph.kt` is deleted, being unused. Unused `RoundedCornerShape` imports were removed from the EQ and Headphones screens.<br>The onboarding walkthrough in `MainActivity` is left for phase 5. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 4 · Headphones screen.**<br>The UI section of `screens/HeadphonesScreen.kt` was rewritten. `HeadphonesViewModel` is unchanged.<br>Top bar: back `IconAction` + Done `TextAction`, with the same confirm-on-Done / discard-on-Back behaviour.<br>Search is pinned (search icon, clear button, "6,028 models" caption), and everything else is **one `LazyColumn`**: selected-headphone card with a correction `StatusChip` + detail; **one "Add correction" menu** (Import GraphicEQ file · Paste 10-band values · Name a headphone (flat)), replacing two text buttons and the list-footer card; the import message as a `StatusBanner`; an offline WARNING banner with Retry; **My custom profiles** in one card (check when in use, overflow menu Share/Rename/Delete); then catalog/favourite rows (`HeadphoneListItem` restyled: selected = accent container + check, star `IconAction` with a description).<br>Before, custom profiles scrolled inside a 220 dp box separately from the results.<br>Loading, empty and error states use the kit. Paste is an `EqSheet`; Delete is `ConfirmDialog`; Rename and Name-a-headphone are short dialogs. List keys rely on the view model's case-insensitive de-duplication. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 4 · Home screen.**<br>`screens/HomeScreen.kt` was rewritten on the kit. `HomeViewModel`, `EqState` and `BluetoothHeadphoneDetector` are unchanged.<br>Layout (decision 5): top bar (`headlineSmall` + Settings `IconAction`) → banners (wired headphones as an ACTIVE `StatusBanner` with Change/dismiss; the genre-correction error, previously an AlertDialog, is now an ERROR banner) → **Now Playing** hero → **Sound** card (Auto EQ `SwitchRow`, headphone `SettingRow` + `StatusChip`, bass `SegmentedToggle`, "Play test sound" action) → **Genre mix** (one stacked bar in chart tints, "Other" = `textFaint`, legend with tabular %; same "detected tracks, not listening time" wording).<br>Now Playing: `titleLarge` track title, genre `PillChip` + confidence `StatusChip` (High = success, Medium = active, Low or fallback = warning, manual = "Your correction"), and the source line. **"Wrong genre? Fix it" becomes a tonal button when confidence is Low, a fallback was used, or detection failed** (the PRD §6.3 affordance, UI level). An idle state is added.<br>Behaviour difference: when the media listener is off, the card now shows "Song detection is off" with an Open settings action, instead of disappearing.<br>Recent tracks and genre correction are `EqSheet`s; the genres are a `FlowRow` of chips. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 4 · EQ screen.**<br>The UI section of `screens/EqScreen.kt` was rewritten. `EqScreenViewModel` and `EqState` are untouched, apart from comments.<br>Header: `headlineSmall` title, a tonal Save button shown only with unsaved changes, and an overflow menu (Save as new / Reset / Share / Import). There's a preset `PillChip` with a dropdown arrow, and **EQ ⇄ Original** `SegmentedToggle`, replacing the A/B text.<br>Sliders: `EqBandsPanel` uses three aligned rows of 8 columns (tabular `ValueReadout`s, which open an exact-gain dialog on tap; tracks; `labelMedium` band labels), with a live `ToneCurve` behind the tracks. **The fill now grows from a 0 dB centre tick**, so cuts read as cuts. The thumb has a ring, and the panel dims while bypassed. Drag behaviour, 0.5 dB steps and the `setProgress` accessibility action are kept, plus a new content/state description per band.<br>Sheets: the preset picker is an `EqSheet` (My profiles first, then Built-in; custom rows have an overflow menu, built-in rows a Share icon). Share, Import and Import preview are sheets too. **`ShareProfileDialog` keeps its signature**, so the Headphones screen's share also becomes a sheet.<br>Rename, New profile and exact gain stay short `AlertDialog`s; Delete uses `ConfirmDialog`. The share/import message is a `StatusBanner`.<br>Kit tweaks: `ToneCurve(height = null)` can fill its parent, and `EqSheet` adds `imePadding`.<br>Known limits: 8 band columns are about 40 dp wide on a 360 dp phone, below the 48 dp target width. The vertical drag area is large, and readouts get a 32 dp tap height. Messages use the neutral banner style, because the view model doesn't classify them as errors. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 3 implemented.**<br>New `ui/components/`:<br>- `EqIcons.kt` (`EqIcons`, `EqIcon`)<br>- `Containers.kt` (`EqCard`, `SectionHeader`, `SettingRow`, `SwitchRow`, `ValueRow`, `PillChip`, `StatusChip`/`StatusKind`)<br>- `Controls.kt` (`PrimaryButton`, `TonalButton`, `TextAction`, `IconAction`, `SegmentedToggle`, `ValueReadout` + pure `formatValue`, `StatusBanner`, `EmptyState`, `EqCrossfade`)<br>- `ToneCurve.kt` (curve/bars, grid, pure `dbToY`/`toneCurvePoints`)<br>- `Overlays.kt` (`EqSheet`, `ConfirmDialog`)<br>- `ComponentPreviews.kt` (Sage dark/light, Amber pure black, Ocean light, icon sheet)<br>- `ComponentMathTest` (7 tests)<br>Deviation from §4.4: **every icon is a Material Symbols Rounded vector drawable** (`res/drawable/ic_sym_*.xml`, 35 icons, Apache 2.0). They were fetched from google/material-design-icons and converted 1:1. `material-icons-core` isn't used, because its older filled style would mix two icon styles. Other decisions: `formatValue` uses `Locale.ROOT` and never prints `-0.0`. A clickable `PillChip` uses Material's clickable Surface, so the 48 dp touch area doesn't stretch the pill. `EqSheet` is always fully expanded, with pinned actions, and can be locked while work runs. `AppGlyph` and the text-symbol icons stay until phase 4 replaces their call sites. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 2b implemented.**<br>Palettes: `Palette.kt` builds all 6 accents × dark / pure black / light (18 palettes) from shared neutrals plus per-accent families. Dark surfaces get a 4% accent tint, except Mono and pure black. Amber uses a yellower warning.<br>Theme state: `ThemeSettings.kt` has `ThemeMode`, `ThemeSettings`, `ThemeStore` and `rememberEqifyColors`. Dynamic colour maps `dynamic{Dark,Light}ColorScheme` onto our roles; status colours stay ours.<br>`MainActivity` applies the theme live and sets the status/navigation bar icon appearance. `EqifyApplication` loads the theme synchronously.<br>New `screens/AppearanceSettings.kt` (top of Settings): mode segmented buttons, accent swatches + Dynamic (API 31+ only), pure black (shown only in dark), and a live preview card.<br>`ThemeContrastTest` now has 6 tests.<br>Deviations:<br>(1) **The theme is stored only in SharedPreferences**, not DataStore + mirror as planned. One source of truth, read synchronously before the first frame, with a StateFlow for live updates. Two copies could disagree.<br>(2) Light mode makes `surfaceRaised` white and `surface` `#FAFBFB` on a `#F1F4F2` background. Cards (currently `surfaceRaised`) would otherwise nearly disappear in light mode.<br>(3) Dynamic colour isn't contrast-tested, because it's generated at runtime; Material's tonal palettes guarantee its own pairs.<br>(4) The pre-first-frame launch window is still the static XML theme; only the window background after the first frame follows the theme.<br>Light mode on every screen still needs a visual check on a device. | Record the actual source state. |
| 2026-11-21 | Claude | **Phase 2 implemented.**<br>New files: `ui/theme/Palette.kt` (`EqifyColors` roles, `EqifyPalettes`, `LocalEqifyColors`), `Theme.kt` (`EqifyTheme.colors`, `EQifyTheme`, roles → Material `ColorScheme`), `Type.kt` (`EqifyTypography`, `tabular()`), `Dimens.kt` (`EqSpacing`, `EqifyShapes`, `PillShape`), `Contrast.kt`, plus `ThemeContrastTest`. Template `Color.kt` deleted.<br>All ~280 old token references migrated.<br>Deviations from this doc:<br>(1) `outlineStrong` raised from `#5C6863` to `#73807A`; the contrast test caught 2.5:1 on raised surfaces.<br>(2) Old `Surface2Dark` cards map to `surfaceRaised` for now, keeping today's card/background separation. Phase 4 reassigns them through the component kit.<br>(3) The slider thumb uses `onAccentContainer`, keeping today's lighter-than-fill look until the phase 4 slider redesign.<br>(4) Icons moved to phase 3, where they're first used, so no unused dependency is added now.<br>(5) Screens still set font sizes by hand; switching them to the type scale is phase 4, as planned.<br>Visible change in this phase: the refined palette (brighter accent, dark-on-accent switch thumbs and bass buttons). Layouts are unchanged. | Record the actual source state. |
| 2026-11-21 | Claude | Added user-selectable themes (§4.6, phase 2b, decisions 7–9) at the user's request. Light mode moved into scope. | User asked for customisable themes. |
| 2026-11-21 | Claude | Initial direction from a source survey. Note: the frontend/design plugin wasn't loaded in this session, so this doc follows standard Material 3 and audio-app practice. | — |
