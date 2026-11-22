# Design Review: EQify after the redesign

Date: 2026-11-21. Reviewer: Claude, using the `frontend-design` skill. Scope:
`ui/theme`, `ui/components`, and the Home / EQ / Headphones / Settings screens as
committed after redesign phases 2–5. The review itself changed no code; items 2–4 were then implemented (see the Progress section).

The method is a source read plus counts (below). Claude has not seen the app
rendered, so everything about visual weight is inferred from code. Check it
against the phone before acting on it.

## Verdict in one paragraph

The redesign fixed the *engineering* of the UI: one token system, one kit,
contrast-tested themes, 48 dp targets, no hand-set sizes. What it didn't do is
give EQify a *point of view*. Today it reads as a well-built Material 3 app that
could be any settings-heavy utility. It uses the stock system font, rounded cards
for every section, uppercase eyebrow labels and `A · B` meta strings, and its
most distinctive asset, the EQ curve, never appears on the screen people see
first. The fixes are mostly subtractive and cheap. One addition, the live curve
on Home, would do most of the work of making EQify recognisable.

## Who it is for, and what the app's job is

- **Subject:** an automatic equalizer. It listens to what you're playing and
  shapes the sound for that genre and your headphones.
- **Audience:** Persona A (casual streamer, never touched an EQ) and Persona B
  (audiophile who knows AutoEQ curves).
- **Primary job of the UI:** make an invisible thing visible. *"Your sound
  just changed, and here's how."* Everything else (settings, imports) is
  supporting.
- **Vernacular to borrow from:** hi-fi and studio hardware. Engraved panel
  labels, condensed lettering, meters and response graphs, one illuminated
  indicator showing what's live. Not a phone settings page.

## Findings

Severity: **High** = undermines the app's identity or clarity · **Medium** =
reads as template · **Low** = polish.

### High

**H1 · The signature visual is hidden.** The EQ curve (`ToneCurve`) appears 4
times, all on the EQ screen or in share/import sheets. **Home shows no curve.**
Yet Home is where the automatic magic happens: genre detected → curve changes.
The one moment unique to EQify, *the curve reshaping when the song changes*,
is never shown to a casual user. This is the single biggest opportunity.

**H2 · Everything is a card.** Home stacks 5 `EqCard`s (now 3 sections + sheets),
EQ uses 4, and Settings wraps every group in a card. All have the same 16 dp
radius on the same `surfaceRaised`. Hierarchy comes only from order, so the Now
Playing hero has the same visual weight as "Genre mix". This is the "SaaS-card
kit" pattern the skill warns about.

**H3 · Typography has no personality.** System Roboto at standard Material
roles. It's legible, but type is a neutral delivery vehicle here. For an audio
tool, numerals and short panel labels *are* the interface: dB values, Hz bands,
percentages. They deserve a face chosen for that job. The narrow EQ columns
also need a **condensed** width at large font sizes. Phase 5 worked around this
by dropping decimals, which a condensed width would fix properly.

### Medium

**M1 · Uppercase eyebrow labels everywhere.** `SectionHeader` uppercases every
section title. There are 8 call sites plus 7 Settings groups, plus hard-coded
`"CURRENTLY SELECTED"` and `"PREVIEW"`. Many add nothing: "Now playing" above a
track title, "Currently selected" above the selected headphone. Uppercase
tracked labels are a generic default. Here they also flatten hierarchy, because
every group gets the same shout.

**M2 · Middle-dot meta strings.** 12 user-facing strings join fragments with
` · `. Examples: "Active · Hip-Hop", "AutoEQ database · 6,028 models", "Past 7 days ·
3 detected tracks", "In use · imported GraphicEQ", "Safe fallback · Balanced
unavailable", "artist · genre". They read as machine output rather than speech,
and some hide meaning (*what* is "Safe fallback · Balanced unavailable"?).

**M3 · The accent means too many things.** The accent colours: links and text
actions, switch tracks, the selected segment, the selected preset chip, the nav
indicator, slider fill, curve, star, section headers (when `accent = true`) and
the bass level text. Because it's everywhere, it can't say the one thing it
should: **"this is acting on your sound right now."**

**M4 · Vocabulary drifts between screens.** The skill's rule is that an action
or thing keeps one name everywhere. Today:
- **Same feature, two names:** Home says *"Song detection is off"*, and Settings
  and the Genre mix say *"Media listener"*.
- **Two spellings:** *favorites* (view model messages: "Offline favorites",
  "favorite" type label) and *favourites* (new UI: "Favourites & catalog",
  star descriptions).
- **"Correction" stretched:** the *Add correction* menu includes *"Name a
  headphone (flat)"*, which adds no correction.
- **Several names for imported corrections:** *Imported EQ profiles* (Settings),
  *My custom profiles* (Headphones), *Imported GraphicEQ* (row subtitle). These
  are the same thing.

### Low

**L1 · Appearance is the first Settings group.** For an audio app, Sound and
Detection matter more. The theme picker is large, so it pushes the important
switches below the fold.

**L2 · Status chip pile-up on Now Playing:** genre chip + confidence chip +
"via iTunes · fallback result" line + correction button. That's four
treatments for one fact ("we think this is Pop, not sure").

**L3 · Two-tab bottom bar.** Only Home and EQ are tabs. Headphones, a core
input to the sound, is buried behind a row tap. Worth considering a third tab.
This is a structural change, so it's out of scope for this review and listed only.

**L4 · Infinite pulse on "Detecting genre…".** Unprompted looping motion. It's
short-lived, so acceptable, but it should respect the system's reduce-motion
setting.

## Proposed direction: "Signal path"

A compact plan in the skill's format. It keeps the approved theme system and
palettes (they were user-chosen and contrast-tested) and changes **how they're
used**.

**Concept:** EQify shows the signal path: *song → genre → headphone →
curve → your ears*. The live curve is the one memorable thing. Everything
around it stays quiet.

**Colour rule (no new colours):**
- `accent` is reserved for **what is currently shaping the sound**: the live
  curve, the active preset name, the bass level when on, and the selected band.
- Links and text actions move to `textPrimary` (underline-free, medium weight).
  Switches keep Material's own primary role, which is acceptable because they
  are state.
- Section titles use `textMuted`, never accent.

**Type:**
- **One family with a width axis** for everything, so numerals and labels feel
  engineered. Candidates to verify (licence OFL, tabular figures, Cyrillic not
  needed):
  - **Archivo** (variable `wght` + `wdth`), or
  - **Barlow / Barlow Semi Condensed** (DIN-like, close to panel lettering)
- **Use condensed width for** band labels, dB readouts and chips. That fixes
  the narrow-column problem properly, instead of dropping decimals.
- **Normal width for** titles and body. No second family and no monospace
  (except the share code, where monospace is functional).
- **Cost:** about 150–300 KB of font files. This reverses UI decision 2 (system
  font), so it needs your OK.

**Layout:**

Home today:
```
┌─ card ─ NOW PLAYING ───────────┐
│ Title / Artist / chips / btn   │
└────────────────────────────────┘
┌─ card ─ Sound ─────────────────┐
┌─ card ─ Genre mix ─────────────┐
```

Home proposed (no hero card; the curve is the hero):
```
EQify                              ⚙
Blinding Lights                          ← titleLarge, left-aligned
The Weeknd
● Pop, not sure          Fix genre       ← one line; dot colour = confidence
╭──────────── live curve ──────────────╮ ← full-width, no card, morphs on change
│      ╱‾‾╲__        __╱‾‾              │
╰──────────────────────────────────────╯
 60  170  310  600  1k  3k  6k  12k
────────────────────────────────────────
Auto EQ                        [on]      ← rows directly on background,
Sony WH-1000XM5        Saved   ›         ← hairline dividers, no card
Bass   Off  +3  [+6]  +9
────────────────────────────────────────
Genre mix this week   ▇▇▇▇▅▅▃▂  Pop 46%  ← one compact line; opens sheet
```
- Text is left-aligned throughout; centring is kept only for onboarding.
- Cards remain where grouping is real and bounded: sheets, the EQ slider panel,
  and selected-item cards.

**Principles:**
1. **The curve is the brand.** It is shown wherever the sound is described, and it
   moves only when the sound changes.
2. **Accent = live.** If it isn't affecting what you hear right now, it isn't accent.
3. **Say it in a sentence.** No eyebrows, no `·` strings, sentence case,
   one name per thing.
4. **Cards only for real containers.** Sections are separated by space and hairlines.

### Checking the plan against generic defaults (skill step 2)

- **Dark near-black + one accent** is a known cluster. It's kept because it was
  approved and device-tested, it suits listening in the dark, and users
  can pick light/other accents. The **accent-only-means-live** rule is what
  stops it reading as decoration.
- **A condensed DIN-like grotesk** could read as an "audio app default". It's
  justified by function (narrow band columns, numerals) and by hardware panel
  vernacular. *Revised:* the condensed width is limited to data, not headlines,
  so it doesn't become a theme costume.
- **Full-bleed graph as hero** could become the generic "big chart on a
  dashboard". *Revised:* the curve has **no axis box, no grid by default, no
  numbers above it.** It's a shape, with band labels as the only scale.
  It morphs only on genre or correction change (motion that answers a change in
  the sound, which the skill endorses), never on load.

## Recommendations, ranked

| # | Change | Fixes | Effort | Risk |
|---|---|---|---|---|
| 1 | **Live curve on Home:** compose tone + headphone + bass (the same `combineGains` output already in `EqState.activeBandGains`) and show it under the track. Animate the morph on change (respect reduce-motion). | H1 | S–M | Low: reads existing state |
| 2 | **Unify vocabulary:** "Song detection" everywhere; "favourites" or "favorites" (pick one); "Imported corrections" everywhere; move "Name a headphone" out of *Add correction* | M4 | S | Very low |
| 3 | **Drop eyebrows:** `SectionHeader` becomes sentence-case `titleSmall` muted; remove "Now playing", "Currently selected", "Preview" labels | M1 | XS | Very low |
| 4 | **Rewrite the 12 `·` strings as sentences**, e.g. "Active · Hip-Hop" → "Hip-Hop preset", "Safe fallback · Balanced unavailable" → "Using Safe: the limiter isn't available on this phone" | M2 | S | Very low |
| 5 | **Accent = live:** text actions → `textPrimary`; section titles muted; keep accent for curve, active preset, active bass, selected band | M3 | S | Low; re-check contrast test (it still covers all pairs) |
| 6 | **De-card Home and Settings** per the wireframe: rows on background with hairlines; cards kept for sheets/selected items | H2 | M | Medium: visible layout change, needs device check |
| 7 | **Panel typeface** with a condensed width for data | H3 | M | Medium: font licence/size; reverses decision 2 |
| 8 | Collapse Now Playing chips to one line (L2), move Appearance below Sound/Detection (L1), and make the detecting pulse respect reduce-motion (L4) | L1, L2, L4 | S | Low |

**Suggested order:** 2 → 3 → 4 (one quick "copy and labels" pass, about half a
day), then **1** (the signature), then 5 → 6 → 7 as one "visual identity" pass,
reviewed on the phone after each step.

## Evidence (counts from source)

- `ToneCurve(` used 4× in screens, **0× on Home**.
- `EqCard(` per screen: Home 5, EQ 4, Headphones 1, Settings 1 (wraps 7 groups).
- Uppercase labels: `SectionHeader` (uppercases all) ×8 call sites + 7 Settings
  groups, plus hard-coded "CURRENTLY SELECTED", "PREVIEW".
- 12 user-facing strings with ` · ` (preview names excluded).
- Vocabulary: "media listener" ×3, "Song detection" ×2; "favorite(s)" ×4,
  "favourite(s)" ×3.

## Progress

| Date | Items | Status |
|---|---|---|
| 2026-11-22 | 2, 3, 4 (copy and labels pass) | **Done in source**; build and 88 tests pass; device check pending |
| 2026-11-22 | 1 (live curve on Home) + L4 (reduce-motion pulse) | **Done in source**; build and 88 tests pass; device check pending |
| 2026-11-22 | 5, 6, 7 (visual identity) | **Done in source**; build and 88 tests pass; device check pending |

### Glossary (item 2)
Use these names in every user-facing string:

| Thing | Name | Not |
|---|---|---|
| A tone curve (built-in or yours) | **preset** · *My presets* · *New preset* | profile |
| A headphone curve | **correction** (from the catalog, or **imported**) · *Imported corrections* | custom profile, imported EQ profile, imported GraphicEQ |
| The bundle you share or import | **shared profile** | — |
| Reading the playing song | **song detection** | media listener |
| Starred headphones | **favorites** (US spelling, matching code, docs and stored keys) | favourites |

### What changed
- **Vocabulary (item 2):** about 45 strings across the screens, view models and repositories were renamed to match the glossary. The preset-name errors and their tests now say "preset". *Name a headphone* moved out of the *Add correction* menu into a closing list row ("Can't find your headphones? Add them by name. They play without a headphone correction.").
- **Eyebrows (item 3):** `SectionHeader` is now sentence-case `titleSmall` (no uppercase). Removed: "Now playing", "Currently selected". "PREVIEW" became "Preview". `labelSmall` is documented as captions/units only.
- **Middle dots (item 4):** all 12 screen strings are now sentences. Examples: "On, using the Hip-Hop preset", "Paused. Audio plays unchanged.", "3 detected tracks in the past 7 days. This counts tracks, not listening time.", "Found via iTunes, as a fallback", "Using Safe because the limiter isn't available", "6,028 models from the AutoEQ database".
- **Not changed (deliberately):** the foreground notification text in `EqProcessingService` still uses ` · `. It's outside the screens and in the audio service file; change it separately if wanted.

### Item 1: live curve on Home
- **What it shows:** `LiveCurve` in `screens/HomeScreen.kt` draws `EqState.activeBandGains`, the final output the service actually applies (tone + headphone correction + bass, after Safe-mode scaling). The service publishes it from both the automatic path and the manual slider path, and publishes all zeros while paused or bypassed. Home only reads it.
- **Placement:** between Now Playing and the Sound card, with no card, grid, points or numbers above it. The band labels (`60 … 12k`, `textFaint`) are the only scale. It dims to 40% when EQ is off, bypassed, or the engine isn't running.
- **Motion:** each band animates with `animateFloatAsState` (450 ms, FastOutSlowIn). It starts at its current value, so nothing animates on load; it only morphs when the applied sound changes. It snaps instantly when the system *Remove animations* setting is on (`rememberReduceMotion()`, new `ui/components/Motion.kt`).
- **Accessibility:** a content description reads the curve ("Current sound: 60Hz +6.0 dB, …", or "flat", or "EQ is off").
- **Kit:** `ToneCurve(showPoints = false)` option added.
- **L4:** the "Detecting genre…" pulse now holds still when motion is reduced.
- The Now Playing card was later removed (item 6), so the curve now sits directly under the track text, as in the wireframe.

### Items 5–7: visual identity
- **Item 5, accent = live.**
  - `TextAction` now draws in `textPrimary`; `ValueRow` values use `textMuted`; the favorite star, privacy-sheet icons and the onboarding step dot no longer use accent.
  - Accent stays on: the live/EQ curves, band fill and thumb, the bass level when on, selected toggle segments (bass, EQ ⇄ Original), active-status dots, and progress indicators.
  - Filled/tonal buttons and switches keep Material's primary roles. These are state and calls to action, as the review allowed.
- **Item 6, no cards on Home or Settings.**
  - Home: title, banners, track and live curve sit in the 16 dp gutter. The Sound rows run full width between hairlines, with dividers inset to the text at 56 dp.
  - The genre mix is now one row: "Genre mix this week", "Mostly Pop, 46% of 12 detected tracks", plus a mini stacked bar. It opens a **Genre mix sheet** (full bar, legend, explanation, Recent tracks).
  - Settings: each group is a sentence-case title plus full-width rows, closed by a hairline.
  - Cards remain for sheets, the EQ band panel, the selected headphone and the theme preview.
- **Item 7, panel typeface (reverses UI decision 2).**
  - **Archivo** variable font (`res/font/archivo_variable.ttf`, Omnibus-Type, SIL OFL 1.1, licence in `res/raw/archivo_ofl.txt`).
  - Verified in the font's tables: `wght` 100–900, `wdth` 62–125, `tnum` present.
  - `ArchivoText` (width 100) sets the whole type scale. `ArchivoCondensed` (width 75) plus `TextStyle.condensed()` is used for `ValueReadout`, `PillChip`, `StatusChip`, `SegmentedToggle` labels, and EQ/Home band labels.
  - Because the data is condensed, the EQ readouts now drop their decimal only above 1.5× font scale (was 1.3×).
  - Cost: 658 KB file, **248 KB compressed in the APK**.
- **Follow-ups:**
  - Subset the font to Latin with `fontTools` (roughly halves it).
  - An in-app "Open-source licences" screen (Archivo OFL, Material Symbols Apache 2.0, ZXing, Sentry), before Play Store release.
  - Remaining review item 8 (Settings order L1, chip pile-up L2).
  - Not yet seen on a device.
