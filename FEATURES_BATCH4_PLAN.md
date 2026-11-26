# Batch 4: plan

Chosen 2026-11-27. Target: **0.5.0-beta** (versionCode 6). The user asked to plan and build in one go.

| # | Feature | Effort |
|---|---|---|
| S1 | A/B level-matched comparison | S |
| S2 | Stereo balance | S |
| S3 | Quick-tile extras (Bass tile, Sleep tile) | S |
| S4 | Preset favourites and reorder | S |
| S5 | Haptic ticks on EQ sliders | S |
| S6 | "What changed" history | S |
| S7 | Per-device volume memory | S |
| D | Dialogue preset | S |
| P | AutoEQ parametric import | M |
| L | Safe listening alerts | M |
| A | Spectrum analyser | M |
| W | Wear OS app | M |

---

## Safe listening: research

**Sources**
- WHO–ITU *Safe listening devices and systems* (H.870), read 2026-11-27 at who.int:
  - A dose function tracks level and duration as a percentage of a weekly "sound allowance".
  - **Mode 1 (adults): 80 dB for 40 hours a week.** **Mode 2 (children/sensitive): 75 dB for 40 hours a week.**
  - It recommends personalised information, optional automatic volume reduction, and parental volume limits.
- Android 14 *Sound dose* (source.android.com, read the same day):
  - The platform computes calculated sound dose (CSD) per IEC 62368-1 / EN 50332-3 over a **rolling 7 days**. It warns at each 100%, and gives momentary warnings above RS2 (100 dBA by default).
  - It runs inside system_server/audioserver, from HAL-measured output levels, and **has no public app API**. EQify can't read it, and on certified phones Android already warns at the momentary level.

**What EQify can and can't know.** An app can't measure the sound pressure at the ear: it doesn't know the headphone's sensitivity or the true output. So EQify **estimates**, and says so in the UI.

**Estimate**
- `level dBA ≈ 100 + volumeCurve(volume fraction) + EQ boost`
  - **100 dBA** is the EN 50332 maximum for personal players, used as the ceiling at full volume.
  - **volumeCurve** is Android's default media volume curve: 1% → −58 dB, 20% → −40 dB, 60% → −17 dB, 100% → 0 dB, linear between points.
  - **EQ boost** is the mean positive output gain, capped at +6 dB.
- Counted once a second, only while music is playing on a **headphone-type output**. The speaker and "Not headphones" devices are skipped, because the standard is about personal listening.
- `dose += 10^((L − ref)/10) / (40 h)`, where ref is 80 dB (adults) or 75 dB (sensitive). This is the 3 dB exchange rate both standards use.
- Rolling 7 days, stored as day buckets (`listening_dose`). Saved at most once a minute.

**Alerts**
- A notification (its own channel, "Safe listening") at **100%, 200%, 300%…** of the weekly allowance, as IEC 62368-1 does. Each threshold fires once per window.
- The alert offers **Lower volume** (to 50%) and **Turn on volume limit** (the existing cap, at 70%).
- **Settings → Safe listening:**
  - On/off (**default on**).
  - Mode: Adults (80 dB) or Sensitive (75 dB).
  - This week's percentage, with "estimated" wording and a short explanation.
- **Home** shows a one-line note from 80% of the allowance upward.
- Not done: momentary-level warnings. Android does these on certified phones, and our estimate is too rough for a "too loud right now" alarm.

---

## Shared paths and how they're kept apart

1. **Per-channel input gain on the `DynamicsProcessing` effect** (S2 balance, and debug Orbit).
   - One writer in `EqEngine` sets `channel gain = balance + orbit`.
   - Orbit's reset returns to the balance, not to 0.
   - Balance needs the effect (Balanced protection on Android 9+). Otherwise the row says so, and links to Android's own audio-balance setting.
2. **Gain chain:** tone → correction → applied bass → loudness → **level-match offset (S1)** → protection.
   - Both EQ paths use the same helper.
   - The offset is only non-zero while the comparison is on. It resets when leaving the EQ screen, so normal listening is never quieter.
   - `requiresSnap` includes it.
3. **Media volume** (bass easing, loudness, cap, sleep fade, **S7**, **L**).
   - The single monitor registers when any of these is on.
   - S7 doesn't save while the sleep fade runs or while the cap is clamping. A restored volume passes through the cap. Restore waits 1 s after a device change, so Android's own per-device volume settles first.
   - L reads the same fraction and never sets the volume, except through the user's tap on an alert action.
4. **Status hub.** `ExternalStatus.refresh()` updates the widget, the new tiles and the watch from one place. The old direct `EqifyWidget.refresh` calls move there.
5. **Presets (D, S4).**
   - "Dialogue" becomes a built-in, kept out of the genre-correction list and genre keywords.
   - A user preset already called "Dialogue" is renamed by the existing startup migration ("Dialogue (Custom)").
   - **Backups:** a preset with a reserved name is renamed with a suffix instead of failing the whole restore.
   - Favourites follow renames and deletes, and are carried in backups.
6. **Imports (P).** It plugs into the existing import: a parametric file produces a normal imported correction. Its preamp is ignored, because protection and the limiter handle headroom.
7. **Spectrum (A).**
   - It uses `Visualizer` on session 0, which needs `RECORD_AUDIO`. That permission is asked **only** when the user opens the analyser, and the sheet explains it reads EQify's output, not the microphone.
   - It is released when the sheet closes, and touches no EQ effect.
8. **History (S6)** is in memory only, recorded where the service already decides changes. It doesn't feed back into any decision.
9. **Wear (W).**
   - The watch sends commands. The phone maps them to the same service actions, only while the service is running, since Android 12 limits background starts.
   - The phone publishes status through the hub.
   - The watch app is a separate APK with the same application ID and signing key. It is sideloaded onto the watch, because GitHub is our only store.
10. **Backup:** adds `favorite_presets`, `balance`, `safe_listening_enabled`, `safe_listening_mode` and `haptics`.
    - Never backed up: the listening dose, device volumes, history and level-match state.

---

## Feature notes

- **S1 Level match:** a "Match loudness" switch beside EQ/Original on the EQ screen.
  - While on, EQ output is lowered by its mean boost (0–12 dB), so switching to Original compares at similar loudness.
  - It is a session-only setting, off when leaving the screen.
- **S2 Balance:** −100 (left) to +100 (right) in Settings → Sound. It turns the other side down by up to 12 dB.
- **S3 Tiles:**
  - **Bass tile:** cycles the bass steps and shows the level.
  - **Sleep tile:** starts 30 min, or cancels. It shows the time left.
- **S4 Favourites:** a star on each row in the preset picker. Starred presets appear first, in a "Favourites" section, with move-up and move-down.
- **S5 Haptics:** a tick at every 1 dB step and a stronger one at 0 dB, on drag. Settings → Sound, on by default. Compose haptics follow the system haptics setting.
- **S6 History:** Home → "What changed" sheet. The last 30 automatic changes, with times:
  - Genre tone switched.
  - Headphones connected or disconnected, and correction changed.
  - Protection fell back to Safe.
  - Volume capped.
  - Sleep timer events.
  - It shows genre and device names, but no song titles.
- **S7 Device volume:** Settings → Sound, off by default. It remembers each output's last media volume and restores it on connect.
- **D Dialogue:** −1 at 170 Hz, presence +3/+5/+3 dB around 1–6 kHz. It's for films and videos, distinct from Podcast.
- **P Parametric import:**
  - It accepts AutoEQ/Equalizer APO "ParametricEQ.txt" lines (`Filter n: ON PK|LSC|HSC Fc … Hz Gain … dB Q …`, with optional `Preamp:`).
  - It builds RBJ biquads and computes the magnitude response.
  - Each band's gain is the **average response over that band's log-frequency span**, not one sample, so narrow filters aren't missed or exaggerated.
  - It flags when gains were limited to ±12 dB.
- **A Spectrum:** Home's live curve gets a "Live spectrum" sheet with 32 log-spaced bars and peak hold, at about 30 fps from `Visualizer.getFft`.
- **W Wear:**
  - A `:wear` module (Compose for Wear OS) showing status, Pause/Resume, Bass and the sleep timer.
  - The phone side is a `WearableListenerService` plus a status `DataItem`.

## Order

1. Shared engine pieces.
2. S1, S2, S5, D, S4, S6, S7, S3.
3. P.
4. L.
5. A.
6. W, last: it adds a module and dependencies, so if downloads fail the rest still ships.
7. Then tests, builds, docs and the version.

---

## Correction log

| Date | Who | Change | Why |
|---|---|---|---|
| 2026-11-27 | Claude | Built all twelve as **0.5.0-beta** (versionCode 6), plus a separate Wear OS APK with the same ID and signing key (checked). 226 JVM tests pass (23 new); debug and release builds of both modules pass, including lint. Not yet checked on a phone or watch. | — |
| 2026-11-27 | Claude | Web search was unavailable, so the research was read directly from who.int (H.870 summary) and source.android.com (Sound dose). | Tool error. |
| 2026-11-27 | Claude | S1: "Match loudness" is a switch under EQ/Original, on only while the EQ screen is open. | — |
| 2026-11-27 | Claude | S5: uses `LongPress` haptics at 0 dB and `TextHandleMove` for steps. These are the portable Compose types. | Newer haptic types aren't on every device. |
| 2026-11-27 | Claude | §5: an earlier test expected backups with a reserved preset name to fail. It now expects the rename. | Planned behaviour change. |
| 2026-11-27 | Claude | Balance on the processor now also re-applies when control comes back. The control listener used to be debug-only, for Orbit; now it is always on. | Balance must survive another app taking the effect. |
| 2026-11-27 | Claude | A: the spectrum and "What changed" open from rows on Home, not from tapping the live curve. | Easier to find. |
