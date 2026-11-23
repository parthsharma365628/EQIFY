# Next features: order and plans

Five features chosen on 2026-11-24:
- **#16** In-app update check
- **#4** Genre intensity
- **#15** Correction graph
- **#3** Per-device sound settings
- **#13** Listening stats

This document is the plan only. Each feature gets its own `FEATURE_*.md` and commit when built.

## Order

| # | Version | Feature | Effort | Why this position |
|---|---|---|---|---|
| 1 | 0.1.1-beta | **#16 Update check** | S | Every later feature reaches testers through updates. It must ship before the beta grows, or early testers never hear about new versions. |
| 2 | 0.2.0-beta | **#4 Genre intensity** | S | Small change to the gain chain, and fixes the likeliest complaint ("genre EQ is too strong"). #3 reuses it as a per-device setting. |
| 3 | 0.2.0-beta | **#15 Correction graph** | S–M | Builds a layered curve component that #3's device screen reuses. |
| 4 | 0.3.0-beta | **#3 Per-device sound** | M–L | The biggest one. It depends on #4 (intensity per device) and #15 (graph in the device screen). It gets its own release so feedback on it stays clear. |
| 5 | 0.4.0-beta | **#13 Listening stats** | M | Independent of the rest, using data the app already stores. Last because it's growth and marketing rather than sound quality. |

Each step is built, tested, documented and committed before the next one starts. #13 could run in parallel with #3, since they touch different files, if we want to save time.

---

## 1. #16 In-app update check (0.1.1-beta)

**Goal:** testers who installed the APK directly (not via Obtainium) learn when a new version is out.

**How it works**
- At most once every 24 hours, when the app opens, it calls `GET https://api.github.com/repos/parthsharma365628/eqify-releases/releases?per_page=5`.
  - This needs no auth. GitHub allows 60 unauthenticated requests per hour per IP, and we make at most one a day.
  - We use the list rather than `/releases/latest`, because `/latest` skips pre-releases, and every beta is a pre-release.
- It takes the newest non-draft release, reads its tag (`v0.2.0-beta`), and compares it with `BuildConfig.VERSION_NAME` using pure semantic-version comparison (`0.1.0-beta` < `0.1.1-beta` < `0.2.0-beta` < `0.2.0`).
- If newer, it shows a dismissible banner on Home: "EQify 0.2.0-beta is available. [What's new] [Dismiss]". Both buttons open the release page.
  - "Dismiss" hides that version only; the next version shows again.
- Settings → About → **Check for updates** checks immediately and shows "You're up to date" or the new version, then links to the page.
- Settings → About gets an **Automatic update checks** switch, on by default.

**Cases**
- Offline, rate-limited, or malformed responses fail silently; there's no banner, and a manual check says "Couldn't check right now".
- A tag that doesn't parse is ignored.
- A build newer than the latest release, such as your dev build, never shows a banner.
- Debug builds don't check automatically, only manually, to keep your test devices quiet.

**Privacy:** this is a new request to GitHub, which sees your IP address and a user agent. Add it to `PRIVACY.md` and the in-app "About your data" sheet.

**Files:**
- new `UpdateChecker.kt` (version parsing and comparison are pure; the network call is separate)
- `UserPreferencesRepository` (last check time, dismissed version, auto-check switch)
- `HomeScreen`, `SettingsScreen`
- `PRIVACY.md`

**Tests:** version comparison (pre-release ordering, `v` prefix, bad tags), newest-release selection (drafts skipped), the 24-hour throttle, and dismissed-version handling.

---

## 2. #4 Genre intensity (0.2.0-beta)

**Goal:** one control for "how strongly should automatic genre EQ shape the sound?"

**How it works**
- New setting **Genre strength**, from 0% to 125% in 5% steps, default 100%.
- It scales only the **automatic genre tone**, including customised genre curves, before correction and bass are added. It doesn't change:
  - manual presets: what you see on the sliders is what you get
  - headphone correction: that's accuracy, not taste
  - bass boost: it has its own control
- In `EqProcessingService`, the auto `tonePart` is multiplied by the factor in the auto-genre branches only. Output protection still applies afterwards, so 125% can't clip.
- Changes glide using the existing smooth-transition ramp.

**UI (one place, no duplication)**
- On Home's "Sound" card, under the genre line: a compact **Genre strength** slider showing "Subtle" at 0–50%, "Balanced" at 55–100% and "Strong" above that, plus the percentage.
- It's only shown while auto-genre is on. When the user is on a manual preset, the slider is hidden, because it would do nothing.
- From step 4 on, if the connected device has its own strength, the slider edits that value and shows "for Sony WH-1000XM5" (see #3).

**Cases:**
- 0% means automatic genre is effectively flat, with correction and bass still applied.
- Changing strength while sliders are manually overridden does nothing until auto resumes. The slider is hidden then anyway.
- The stored value is clamped and validated, so bad stored data falls back to 100%.

**Files:**
- `UserPreferencesRepository` (`genreStrength`)
- `EqProcessingService`: merge into an existing combine input, since all groups are full
- `EqProfileManager` (a pure `scale(gains, factor)`)
- `HomeScreen`, `HomeViewModel`

**Tests:** scaling math (0%, 100%, 125%), and that it applies only to auto genre, never to manual presets, correction or bass.

---

## 3. #15 Correction graph (0.2.0-beta)

**Goal:** show what EQify is doing to the sound, layer by layer.

**Limit to be honest about:** AutoEq gives us the **correction** (eight band gains), not the headphone's raw measured response. The graph shows "what EQify changes", not "how your headphone measures", and its labels say so.

**How it works**
- A new reusable component, **`LayeredCurve`**, built from the existing `ToneCurve` drawing code. It draws up to four smooth lines over the eight bands:
  1. **Tone**: the genre or preset, after strength
  2. **Headphone correction**
  3. **Bass**
  4. **Total**: what's actually applied (`EqState.activeBandGains`), drawn thicker
- It has a 0 dB line, frequency labels (60 Hz…12 kHz) and a legend you can tap to show or hide each layer. Colours come from theme roles, so it works in all 18 palettes, with line style as well as colour for colour-blind users.

**Where it appears**
- **Headphones screen**, on the selected-headphone card: a small correction-only graph plus a one-line summary, such as "Mostly cuts 3–6 kHz by up to 4 dB, adds 2 dB below 100 Hz". The summary is generated from the gains and also serves as the screen-reader description.
- **EQ screen** overflow → **"Show sound layers"**: a sheet with the full four-layer graph for the current moment.
- The device screen in #3 reuses it.

**Cases:**
- With no correction (none selected, speaker, "Not headphones", or download failed), the correction line is hidden and a note explains why.
- Bypass ("Original"): the total is drawn as flat, with a note.
- Values are clamped to the ±12 dB range for drawing.

**Files:**
- new `ui/components/LayeredCurve.kt`
- a pure `CurveSummary.kt` (the text summary)
- `HeadphonesScreen`, `EqScreen`

**Tests:** summary wording for typical curves, flat curves and large cuts and boosts; layer data assembly.

---

## 4. #3 Per-device sound settings (0.3.0-beta)

**Goal:** each pair of headphones, the car and the phone speaker can sound the way you like, automatically, without the app becoming confusing.

### Design rules (to stop it getting overwhelming)
1. **Everything inherits by default.** A device starts as "Same as default" for every setting. Nothing changes until the user chooses to customise.
2. **At most four settings per device.** No per-device mono, protection or advanced options.
   1. Headphone correction (the existing remembered choice)
   2. Sound
   3. Bass
   4. Genre strength
3. **Show only what's different.** Each row shows its value. Inherited values are muted with "Default", and customised ones are accented with a **Reset** action. The device list shows a short summary like "Rock preset, bass +3".
4. **Controls edit what's playing now.** When the connected device has its own bass or strength, Home's controls edit that device's value and say so ("Bass, for Sony WH-1000XM5"). There's never a hidden second value.
5. **One home for it.** All device settings live in **Headphones → My devices**. Other places only link there.
6. **No new prompts on connect.** The existing connect banner gains a quiet "Using your settings for Sony WH-1000XM5" line plus an **Edit** link, only when the device has customised settings.

### Screens

**Headphones tab → "My devices" section (top of the tab)**
```
My devices
● Sony WH-1000XM5          Connected
  Rock preset · bass +3 · correction on
○ Car (Bluetooth)
  No correction · bass +6
○ Phone speaker
  Same as default
○ Wired headphones
  Same as default
```
- The connected device is first; the rest are ordered by last connected.
- The phone speaker and wired headphones are always listed. Wired headphones share one entry because Android can't tell them apart, and the subtitle says so.
- Tapping a device opens its sheet.

**Device sheet**
```
Sony WH-1000XM5                     ⋮ (Rename · Forget device)
[ correction + total graph (from #15) ]

Headphone correction   Sony WH-1000XM5 (AutoEQ)     >
Sound                  Default: follow genre        >
Bass                   +3 dB (for this device)  Reset
Genre strength         Default (100%)               >
```
- **Sound** opens a small picker with three options:
  - **Same as default**
  - **Follow genre automatically**
  - **Always use a preset**, which reveals the existing preset list, built-ins and your presets
- **Bass** and **Genre strength** open the same controls as elsewhere, with a "Use default" option at the top.

**EQ screen:** when the connected device forces a preset, the preset chip shows a small "Device: Sony WH-1000XM5" tag. If the user picks another preset manually, a snackbar asks "Use Jazz for Sony WH-1000XM5 from now on?" with the buttons [Always] and [Just now]. "Just now" is the default if it's ignored. This is how settings get learned without a setup step.

### Rules of precedence (one place, tested)
**Effective setting = manual change in this session → device setting → global default.**
- A manual slider edit or preset pick always wins for the current session, as it does today.
- When the device disconnects or a different one connects, the manual override is cleared and the new device's settings apply.
- "Not headphones" devices (car, speaker) can still have Sound, Bass and Strength. They just never get correction.
- Forgetting a device removes its settings and its remembered correction.
- Renaming only changes the label, not the stored key.

### Data and code
- New `DeviceProfile(label, kind, lastSeenMillis, sound: DeviceSound?, bassDb: Float?, genreStrength: Float?)`, where null means inherit, and `DeviceSound` is `FollowGenre` or `Preset(name)`.
- It's stored as a versioned codec like `DeviceHeadphoneMemory`, under a new key `device_profiles`. The existing `device_headphone_choices` stays as it is, so nothing has to migrate.
- New pure `DeviceSettingsResolver` that turns the global settings, the device profile and the manual state into effective settings, with a complete unit-test table.
- `EqProcessingService` gets the current device key from `HeadphoneDetector` and applies the resolver. Combine inputs are merged into one data class, as for genre curves.
- Preset rename and delete update device profiles, the same way genre corrections already follow presets. A deleted preset falls back to "Same as default".
- Customised built-in genre curves are honoured, since the preset path already uses `effectiveGains`.

**Cases:**
- **Unknown Bluetooth name:** the key is just `bluetooth` and the label is "Bluetooth device".
- **Two devices with the same name:** they share an entry, which is unavoidable, and their keys match.
- **Device connects while EQ is off:** settings are stored and apply when EQ turns on.
- **Bypass:** unaffected.
- **Rapid switching:** the latest output wins, using `collectLatest`.
- **Bad stored data:** that device is skipped and others still work.

**Tests:** resolver precedence table, codec round-trip and malformed input, preset rename and delete follow-through, and the forget and reset paths.

**Docs:** `FEATURE_PER_DEVICE_SOUND.md` with the precedence table and UI rules, and an `AI.md` update.

---

## 5. #13 Listening stats (0.4.0-beta)

**Goal:** a "your week in sound" screen and a shareable card, using data EQify already stores (`genre_observation`, kept for 16 days).

**Limit to be honest about:** observations are **detected songs**, not listening time. The labels say "songs detected", never minutes or hours.

**Screen** (opened from Home's "Genre mix this week" row, which becomes "Your week in sound")
- songs detected this week, and the change versus last week. Comparing the previous seven days needs 14 days of data, and we keep 16.
- genre mix bar, with the change per genre versus last week
- top 5 artists
- most active time of day: morning, afternoon, evening or night
- most-played genre each day, as a small 7-day strip
- the existing recent tracks, moved here

**Share card**
- A preview of the card: top genre, genre mix bar, number of songs, and the top 3 artists, with a **Show artists** switch on by default. It also carries the EQify name and a small "Get EQify" line with the releases link. That's free marketing.
- It's rendered to an image by drawing the card composable to a bitmap, saved in the app's cache, and shared through Android's share sheet using a `FileProvider`, which is a new manifest entry.
- Nothing is uploaded by EQify; the user chooses where to share.

**Cases:**
- **No data or under 3 songs:** a friendly empty state and no share button.
- **Only this week's data:** no "versus last week".
- **Corrected genres:** used, exactly as the genre mix uses them now.
- **"Other" genre:** shown last.
- **Very long artist names:** shortened with "…".
- **Clear genre history:** stats empty immediately.

**Files:**
- new pure `ListeningStats.kt` (the maths)
- new `screens/StatsScreen.kt`
- `HomeScreen`, `MainActivity` (route)
- manifest `FileProvider` and `res/xml/file_paths.xml`
- `PRIVACY.md` (a note that sharing is user-initiated)

**Tests:** week boundaries, change versus last week, top artist ties, time-of-day buckets, correction overrides, and empty or small data.

---

## Shared rules for all five
- Follow `AI.md`:
  - keep preference flows on `pref { }`
  - keep the two EQ paths independent
  - new reflection-mapped models (the GitHub release JSON) need a `proguard-rules.pro` keep rule
- Each feature:
  - Write its plan doc.
  - Build it, keeping the pure logic unit-tested.
  - Run `testDebugUnitTest assembleDebug assembleRelease`.
  - Smoke-test the **release** APK on a phone.
  - Bump `versionCode` and `versionName`.
  - Write the release notes and the commit message.
- Each release's GitHub notes list what's new, so update-check users see it.

## Correction log
| Date | By | Change | Why |
|---|---|---|---|
| 2026-11-24 | Claude | Plan written. | — |
| 2026-11-24 | Claude | All five built in one pass and shipped together as **0.2.0-beta** (versionCode 3) instead of four releases. 158 JVM tests pass (38 new); clean debug and release builds pass. Not yet checked on a phone. | The user asked for all five one by one in one session. |
| 2026-11-24 | Claude | §2: the EQ sliders show the genre curve itself, not the scaled one; a note on the EQ screen says strength is applied in automatic mode. | Saving a customised genre curve must never bake the strength in (it would be applied twice). |
| 2026-11-24 | Claude | §4: correction for a device is still chosen by connecting it and picking a model on the Headphones tab; the device sheet shows it, offers "Not headphones" and Reset. Bass and strength controls on Home edit the device's value when it has one. | Reuses the existing remembered-choice flow instead of a second picker. |
| 2026-11-24 | Claude | §5: "Your week in sound" is a sheet opened from Home (replacing the genre-mix sheet), not a separate screen. | Same content with less navigation. |
