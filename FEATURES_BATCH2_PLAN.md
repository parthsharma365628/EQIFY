# Batch 2: notification controls, backup and restore, volume-aware bass

Chosen 2026-11-24: **#9**, **#11**, **#10**. Plan only; nothing is built yet.

## Order

| # | Version | Feature | Effort | Why here |
|---|---|---|---|---|
| 1 | 0.3.0-beta | **#10 Volume-aware bass** | S | Touches the gain chain only. #9's bass button then toggles the same effective bass. |
| 2 | 0.3.0-beta | **#9 Notification controls** | S | Small, reuses `EqProcessingService` actions and the existing notification. |
| 3 | 0.3.0-beta | **#11 Backup and restore** | S–M | Last: it must save every setting that #10 and #9 add, so those keys must exist first. |

Ship together as 0.3.0-beta (versionCode 4), as with 0.2.0.

---

## 1. #10 Volume-aware bass

**Problem:** bass boost at high volume distorts or trips the limiter, and the user must lower bass by hand.

**Behaviour**
- Setting "Reduce bass at high volume", default **off** (a silent change to the sound would surprise testers). Settings → Sound.
- Below a threshold (about 60% of media volume) nothing changes. Above it, the bass boost fades down linearly to **50%** of the chosen level at max volume.
- The bass slider is never changed; only the applied amount is scaled. Home shows a small note, "Bass eased at this volume", while scaling is active.

**Design**
- Pure `VolumeBass.kt`: `scale(volumeFraction): Float` (1.0 up to the threshold, linear down to 0.5) and `apply(level, fraction)`. Unit-tested at 0, threshold, max and out-of-range values.
- `EqState.mediaVolumeFraction: StateFlow<Float>`, fed by the service from `AudioManager.getStreamVolume(STREAM_MUSIC) / getStreamMaxVolume`.
  - Listen with a non-exported `BroadcastReceiver` on `android.media.VOLUME_CHANGED_ACTION`. It is unofficial but widely used. Fallback: a `ContentObserver` on `Settings.System`.
  - Bluetooth absolute volume: the fraction still follows the phone's media stream, so no special case.
- The service already combines `bassBoostLevel` in the gain chain (`EqProcessingService.kt:177`). Apply the scale after the effective bass and before protection, so the limiter sees the final value.
- Debounce to about 10 updates a second and ramp with `EqGainRamp`, so volume presses don't click.
- New pref `volume_aware_bass` (a `pref {}` flow). No per-device override in this batch, which keeps the device sheet simple.

**Edge cases:** max volume reads 0 (treat as fraction 0, no scaling); EQ paused or bypassed (no effect); the "Sound layers" graph should show the scaled bass, so `SoundLayers.build` takes the scaled level.

**Tests:** `VolumeBassTest` (the curve). Device check: bass differs audibly at low and high volume, with no clicks.

---

## 2. #9 Notification controls

**Today:** the notification has one action, Pause/Resume EQ (`ACTION_TOGGLE`).

**Behaviour**
- Two actions, to keep the notification uncluttered:
  1. **Pause / Resume EQ** (existing).
  2. **Bass: Off / Low / High**, cycling through three steps, with the current step in the label.
- No preset-switching button: it was judged unnecessary.
- The notification text already lists preset, bass and headphones; the actions use the same state.

**Design**
- New service actions `ACTION_CYCLE_BASS`, a `PendingIntent.getService` with its own request code and `FLAG_IMMUTABLE`.
- Bass goes through **`DeviceSoundCoordinator.setBass`**, never `EqState.setBassBoost`, so device precedence from AI.md keeps working.
- Pure `NotificationControls.kt`: `nextBass(level)` and its label. Unit-tested.
- Rebuild the notification when mode, bass or preset changes (`updateNotification` already runs on those paths).
- Setting "Notification buttons", default **on**, in Settings → Sound with a one-line explanation.

**Edge cases:** service restart (actions are rebuilt each time, nothing stored).

**Tests:** `NotificationControlsTest` (cycling and wrap-around). Device check: each button works, including from the lock screen, on Android 8 and on 14 or later.

---

## 3. #11 Backup and restore

**Goal:** one file holds everything a user would hate to lose when changing phones or reinstalling.

**Included**
- Custom EQ presets.
- Customised genre curves.
- Genre strength, bass level, output protection, and the new #10 and #9 settings.
- Device profiles (each device's own preset, bass and strength).
- Remembered headphone choices and imported custom correction profiles.

**Not included:** listening history and week stats (private and large), update-check state, crash-report preferences, dismissed banners.

**Format:** one JSON file named `EQify-backup-YYYY-MM-DD.eqifybackup`, with:
- `format: "eqify-backup"` and `version: 1`, so later versions can migrate.
- `appVersion` and `createdAt`.
- One section per area above, reusing the existing encoders (`DeviceProfiles.encode`, `GenreCurveOverrides.encode`, `EqProfileCodec`) so there is no second format to maintain.

**Design**
- Pure `BackupCodec.kt`: `build(snapshot): String` and `parse(text): ParseResult`.
  - It validates everything (gain ranges and band counts through the existing validators, names through `PresetNameRules`, a size cap through `BoundedTextReader`).
  - It ignores unknown keys, for forward compatibility.
  - A corrupt or unrecognised file never partly applies.
- Export: Settings → Backup & restore → **Back up**, using the Storage Access Framework `CreateDocument`, so no storage permission is needed.
- Restore: **Restore** uses `OpenDocument`, parses, then shows a confirmation sheet: "Restore 6 presets, 3 genre curves, 2 devices?"
  - **Replace** swaps current settings for the file's.
  - **Merge** keeps current items and adds the file's. On a name clash the file's copy gets a suffix, reusing the profile-sharing import rename logic.
- Apply in one place (`BackupRepository.apply`), writing through the same repository functions the UI uses, so invariants (device-profile preset cleanup, the custom-preset list) hold.
- Before replacing, save a **safety snapshot** of current settings in the cache and show an "Undo" snackbar for the session.

**Privacy:** the file stays on the device unless the user shares it. It contains device names such as Bluetooth headphone names, and the sheet says so. Update `PRIVACY.md` (a local file the user creates, never uploaded).

**Edge cases:** a backup from a newer version (refuse: "update EQify first"); a new phone without the old headphones (entries are kept and apply when that device connects); an empty backup; a huge file (cap about 2 MB). Android Auto Backup is **not** a substitute and stays off.

**Tests:** `BackupCodecTest`: round trip, unknown keys ignored, bad gains rejected, truncated JSON, newer version, merge name clashes, size cap.

---

## Correction log

| Date | Who | Change | Why |
|---|---|---|---|
| 2026-11-24 | Claude | Built all three as **0.3.0-beta** (versionCode 4). 178 JVM tests pass (20 new); debug and release builds pass. Not yet checked on a phone. | — |
| 2026-11-24 | Claude | §2: the notification has Pause and Bass only. Bass cycles Off, +3, +6, +9 dB (Home's steps), not Off/Low/High. | The user removed Next preset; Home's steps keep one bass scale. |
| 2026-11-24 | Claude | §1: no debounce or ramp; applied bass is rounded to 0.5 dB steps instead, and changes snap. | Each volume press is one small change; simpler than a second ramp. |
| 2026-11-24 | Claude | §3: the file is `.json` (`EQify-backup-DATE.json`), not `.eqifybackup`, so file pickers open it. Merge keeps the current item when both have one, except presets with different gains (suffix). Theme is not included. Undo lasts until the app process ends (in memory, no cache file). | Simpler, and same safety for the session. |

## Shared rules
Same as `FEATURES_NEXT_PLAN.md`: `pref {}` flows, every new key added to the backup, `testDebugUnitTest assembleDebug assembleRelease`, a release APK smoke test on a phone, a version bump, release notes, and a commit title and description at the end.

## Defaults I chose (say if you disagree)
1. Volume-aware bass defaults **off**, and eases to 50% above about 60% volume.
2. Notification buttons: Pause and Bass only.
3. Backup excludes listening history.
